package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>The first real mainnet artefact the COMPOUND path has ever had — and what the bot would do
 * with it.</b>
 *
 * <h2>What changed, and it retires a claim in findings §54.5</h2>
 * §54.5 said the compound path was <b>structurally</b> empty on mainnet: <i>"one loan ever minted,
 * never burned, so nothing has ever been repaid and no escrow exists to compound."</i> That was true
 * when written and is <b>now false</b>. At <b>08:52:48 UTC on 2026-09-04</b> the borrower <b>repaid</b>
 * loan {@code d832b78e…#1} — redeemer {@code e4b067a6…}, {@code loanRepayActionScriptHash}, not a
 * liquidation; the bot was never armed and never acted — and output 0 of that transaction is a
 * <b>25,000,000 lovelace repayment escrow</b> sitting at the asset-manager credential.
 *
 * <p>⚠ <b>The structural claim flipped. The profitability claim did not</b>, and keeping those apart
 * is the point of this class: the only live mainnet pool manager still publishes
 * {@code compoudingFeePerMille = 0}, so the work pays nothing and is refused at the shipped margin.
 * <b>"There is finally something to compound" and "compounding it is worth doing" are different
 * questions</b>, and the first being answered does not answer the second.
 *
 * <p>Read-only; gated on {@code BLOCKFROST_KEY}. The recorded datum is checked against the live chain
 * rather than trusted, because a recording is only safe when something derived independently is
 * compared against it — this suite has now had two config datums age out from under it.
 */
@EnabledIfEnvironmentVariable(named = "BLOCKFROST_KEY", matches = ".+",
        disabledReason = "reads mainnet: run with `set -a; . ./.env.mainnet; set +a`")
class MainnetCompoundEscrowTest {

    private static final String CONFIG_POLICY_ID = "db2c498e1b93da91e6a79f58526a1e66591d97ace3f8e43d2619b416";
    private static final String LM_CONFIG_POLICY_ID = "a56b0ac2654663f395601601a7825649e5488905648747e912d870e4";
    private static final String CONFIG_ASSET_NAME = "706172616d6574657273";
    private static final String SMART_TOKENS_SPEND = "fca77bcce1e5e73c97a0bfa8c90f7cd2faff6fd6ed5b6fec1c04eefa";

    /** The repayment that created it. */
    private static final String REPAYMENT_TX =
            "42a0ca18aac5de654f17df3bdeac14ed9463bf4139a47ba3bd40c7c763dc9e09";
    private static final long ESCROW_LOVELACE = 25_000_000L;

    private static final String BF = "https://cardano-mainnet.blockfrost.io/api/v0";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20)).build();

    private static JsonNode get(String path) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create(BF + path))
                .header("project_id", System.getenv("BLOCKFROST_KEY"))
                .timeout(Duration.ofSeconds(30)).GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("blockfrost " + path + " -> " + response.statusCode());
        }
        return MAPPER.readTree(response.body());
    }

    private static String fixture(String name) throws IOException {
        try (InputStream is = MainnetCompoundEscrowTest.class.getResourceAsStream("/loans-v4/" + name)) {
            if (is == null) {
                throw new IllegalStateException("fixture not on the classpath: " + name);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    private static LoansContractRegistry registry() {
        return new LoansContractRegistry(CONFIG_POLICY_ID, LM_CONFIG_POLICY_ID, CONFIG_ASSET_NAME,
                SMART_TOKENS_SPEND);
    }

    /**
     * ⛔ The escrow is real, it is where the compound path looks, and the recorded datum still matches
     * the chain.
     */
    @Test
    void aRealRepaymentEscrowExistsAtTheAssetManagerCredential() throws Exception {
        JsonNode output = get("/txs/" + REPAYMENT_TX + "/utxos").get("outputs").get(0);

        assertEquals(registry().getAssetManagerSpendScriptHash(),
                new Address(output.get("address").asText()).getPaymentCredentialHash()
                        .map(HexUtil::encodeHexString).orElse(null),
                "the escrow must sit at the credential CompoundCandidateScanner reads; anywhere else "
                        + "and the bot would never see it");

        List<JsonNode> amounts = new java.util.ArrayList<>();
        output.get("amount").forEach(amounts::add);
        assertEquals(1, amounts.size(), "the escrow is ada-only");
        assertEquals(String.valueOf(ESCROW_LOVELACE), amounts.get(0).get("quantity").asText());

        assertEquals(fixture("mainnet-repayment-escrow-42a0ca18.hex"),
                output.get("inline_datum").asText(),
                "the recorded escrow datum no longer matches the chain. Re-capture it — and note that "
                        + "this is the THIRD recorded datum in this suite to be checked against live "
                        + "state, which is the only reason the previous two surfaced as reds rather "
                        + "than as silent agreement between two stale things");
    }

    /** ⚠ It names the LENDER BOND of the loan that was repaid — the owner a compound must pay. */
    @Test
    void theEscrowNamesTheLenderBondOfTheRepaidLoan() throws Exception {
        String datum = fixture("mainnet-repayment-escrow-42a0ca18.hex");
        String loanId = "1b6fda505ea9b739e42b5871d274344af37c196ddb70619541a7d06d";

        assertTrue(datum.contains(registry().getLenderBondPolicyId()),
                "the escrow's ownerAsset must be a lender bond, or no pool can be resolved for it");
        assertTrue(datum.contains(loanId),
                "and it must be THIS loan's bond — the one repaid at 08:52:48 UTC");
        assertTrue(datum.contains(HexUtil.encodeHexString("installment_repayment".getBytes(StandardCharsets.UTF_8))),
                "the action must be installment_repayment; a different action is a different escrow "
                        + "and not something lm_compound_action will collect");
    }

    /**
     * ⛔ <b>AND THE ANSWER THE OPERATOR ACTUALLY NEEDS: it would be REFUSED, and not for a fixable
     * reason.</b>
     *
     * <p>Driven through the production {@link CompoundEconomics}, at the shipped margin of zero, with
     * the fee the only live mainnet pool manager really publishes. <b>A zero fee means the work pays
     * nothing</b>, so the net is exactly minus the transaction fee — refused, correctly, and the lever
     * is the POOL OWNER's rather than the operator's.
     */
    @Test
    void atTheShippedMarginTheOnlyMainnetPoolWouldRefuseThisEscrow() {
        var network = new AppConfig.Network();
        ReflectionTestUtils.setField(network, "network", "mainnet");
        var configuration = new AppConfig.CompoundConfiguration();
        ReflectionTestUtils.setField(configuration, "enabled", true);
        ReflectionTestUtils.setField(configuration, "profitMarginLovelace", BigInteger.ZERO);

        var assessment = new CompoundEconomics(configuration, network).assess(
                true,                                   // the bond names a pool
                true,                                   // pool and manager are both live
                true,                                   // ada principal
                BigInteger.valueOf(ESCROW_LOVELACE),
                0L,                                     // compoudingFeePerMille, read from chain
                BigInteger.valueOf(300_000L));          // a representative tx fee

        assertFalse(assessment.approved(),
                "a zero-fee pool must be refused at the shipped margin: the compound pays nothing and "
                        + "costs a transaction fee. ⚠ If this ever passes, either the pool owner set a "
                        + "fee or the margin went negative — and the second is an operator's stated "
                        + "loss, not a bug");
    }

    /**
     * ⚑ The measurement that makes the refusal above a FACT about mainnet rather than a hypothesis:
     * the only live pool manager publishes a zero fee, read through the production decoder.
     */
    @Test
    void theOnlyLiveMainnetPoolManagerStillPublishesAZeroCompoundingFee() throws Exception {
        String poolManagerUnit = registry().getPoolManagerPolicyId()
                + "0046337bd27d65a63574039b6293da11701ed2da01bcfaf626c18cccbe";
        JsonNode addresses = get("/assets/" + poolManagerUnit + "/addresses");
        assertTrue(addresses.isArray() && !addresses.isEmpty(), "the pool manager NFT has vanished");

        for (JsonNode u : get("/addresses/" + addresses.get(0).get("address").asText() + "/utxos")) {
            boolean holdsIt = false;
            for (JsonNode a : u.get("amount")) {
                holdsIt |= poolManagerUnit.equals(a.get("unit").asText());
            }
            if (!holdsIt) {
                continue;
            }
            Utxo utxo = Utxo.builder().txHash(u.get("tx_hash").asText())
                    .outputIndex(u.get("output_index").asInt())
                    .amount(List.of(Amount.lovelace(BigInteger.ONE)))
                    .inlineDatum(u.get("inline_datum").asText()).build();
            assertEquals(0L, CompoundCandidateScanner.compoundingFeePerMille(utxo),
                    "the mainnet pool manager now publishes a NON-ZERO compounding fee — compounding "
                            + "may finally pay, and the refusal above needs re-reading rather than "
                            + "re-asserting");
            return;
        }
        throw new AssertionError("no UTxO at the pool-manager address carries the pool-manager NFT");
    }
}
