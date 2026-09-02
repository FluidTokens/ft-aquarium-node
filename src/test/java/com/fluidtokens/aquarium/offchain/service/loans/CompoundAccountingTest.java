package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.aiken.AikenTransactionEvaluator;
import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.common.model.SlotConfigs;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluidtokens.aquarium.offchain.model.loans.CompoundCandidate;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>Every lovelace of the built body, accounted.</b>
 *
 * <h2>The production refusal this exists to pin</h2>
 * On 2026-09-02 the first armed cycle refused a <b>correct</b> transaction:
 * <pre>
 *   BOT_NET_MISMATCH: bot nets 2325431 lovelace, expected -1479060 (fee earned 0 - tx fee 1479060)
 * </pre>
 * The 3,804,491 discrepancy was in the bot's favour, which looked like value sticking to the bot that
 * belonged to the pool or the lender. It was not. <b>Conservation was intact and the pool had its
 * full {@code addedLiquidity}</b>; cardano-client-lib's coin selection had simply added further
 * wallet inputs to cover the fee, the outputs' min-ada and the withdrawal dummy, and that extra was
 * the bot's OWN money returning as change. <b>The body was right and the expectation was wrong.</b>
 *
 * <h2>Why the rig had missed it</h2>
 * The offline wallet held one fat 20 ADA UTxO, so nothing extra was ever selected and the baseline's
 * assumption held by accident. <b>The fixture supplied what production has to earn</b> — this repo's
 * own recorded failure shape, arriving again in a new place. These tests therefore run the wallet
 * <em>thin</em>, which is what a real bot wallet looks like after it has been paying fees.
 */
@Slf4j
class CompoundAccountingTest {

    private static final LoansContractRegistry REGISTRY = LoanFixtures.shippedPreviewRegistry();
    private static final String LOAN_ID = "e833a769ea3a480343175e253eab799ec0b058c99de30cc17160dc37";
    private static final String POOL_ID = "00d3513725536642b6fe985ce9ec87d1ebb880497d92e0a8495bc6d0bf";
    private static final BigInteger ESCROW = BigInteger.valueOf(29_109_268L);
    private static final String BOT = LoanFixtures.botAddress();

    private static Utxo utxo(String key) {
        try (InputStream is = CompoundAccountingTest.class
                .getResourceAsStream("/loans-v4/compound-candidate-e833a769.json")) {
            JsonNode n = new ObjectMapper().readTree(is).get(key);
            List<Amount> amounts = new ArrayList<>();
            n.get("amount").forEach(a -> amounts.add(Amount.builder()
                    .unit(a.get("unit").asText())
                    .quantity(new BigInteger(a.get("quantity").asText())).build()));
            return Utxo.builder().txHash(n.get("txHash").asText())
                    .outputIndex(n.get("outputIndex").asInt())
                    .address(n.get("address").asText()).amount(amounts)
                    .inlineDatum(n.get("inlineDatum").isNull() ? null : n.get("inlineDatum").asText())
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Utxo walletUtxo(String seed, long lovelace) {
        return Utxo.builder().txHash(seed.repeat(32)).outputIndex(0).address(BOT)
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(lovelace)))).build();
    }

    private static CompoundCandidate candidate() {
        Utxo b = utxo("bond");
        return new CompoundCandidate(LOAN_ID, utxo("escrow"),
                new AssetManagerDatumConverter().deserialize(utxo("escrow").getInlineDatum()),
                ESCROW,
                new LenderBond(b.getTxHash(), b.getOutputIndex(), b.getAddress(), LOAN_ID,
                        b.getInlineDatum(),
                        new LenderManagerDatumConverter().deserialize(b.getInlineDatum())),
                POOL_ID, utxo("pool"), utxo("poolManager"), 0L, true, null, "recorded preview candidate");
    }

    private static Transaction build(List<Utxo> walletUtxos) {
        List<Utxo> universe = new ArrayList<>(List.of(utxo("escrow"), utxo("bond"), utxo("pool"),
                utxo("poolManager"), utxo("config"), utxo("lmConfig")));
        universe.addAll(walletUtxos);
        TransactionEvaluator evaluator = new AikenTransactionEvaluator(
                LoanFixtures.utxoSupplier(universe), EvalFixtures.protocolParams(),
                EvalFixtures.scriptSupplier(REGISTRY), SlotConfigs.preview());
        var builder = new CompoundTransactionBuilder(REGISTRY, Networks.preview(),
                LoanFixtures.utxoSupplier(universe), EvalFixtures.protocolParams(), evaluator);
        return builder.build(new CompoundTransactionBuilder.Request(candidate(), utxo("bond"),
                utxo("config"), utxo("lmConfig"), walletUtxos.getFirst(), BOT,
                BigInteger.ZERO, 70_000_000L, 70_000_300L));
    }

    /**
     * ⛔ THE PRODUCTION SHAPE. A thin wallet forces cardano-client-lib to select more than the
     * nominated input — which is exactly what refused a correct transaction on 2026-09-02.
     */
    @Test
    void aThinWalletForcesExtraInputsAndTheAccountingStillHolds() {
        List<Utxo> thin = List.of(walletUtxo("a1", 2_000_000L), walletUtxo("a2", 3_804_491L),
                walletUtxo("a3", 5_000_000L), walletUtxo("a4", 5_000_000L),
                walletUtxo("a5", 5_000_000L));

        Transaction tx = build(thin);

        long botInputs = tx.getBody().getInputs().stream()
                .filter(i -> thin.stream().anyMatch(u -> u.getTxHash().equals(i.getTransactionId())))
                .count();
        assertTrue(botInputs > 1,
                "this fixture must actually exercise multi-input selection, or it re-proves nothing "
                        + "— the rig that missed the production defect had exactly one wallet input");
        log.info("thin wallet: {} bot inputs selected, fee {}", botInputs, tx.getBody().getFee());
    }

    /** The identity, on the single-input shape, stated as arithmetic rather than as a passing build. */
    @Test
    void theBodyConservesEveryLovelace() {
        Utxo wallet = walletUtxo("9e", 20_000_000L);
        Transaction tx = build(List.of(wallet));

        BigInteger scriptInputs = lovelace(utxo("escrow")).add(lovelace(utxo("bond")))
                .add(lovelace(utxo("pool"))).add(lovelace(utxo("poolManager")));
        BigInteger inputs = scriptInputs.add(lovelace(wallet));
        BigInteger outputs = tx.getBody().getOutputs().stream()
                .map(o -> o.getValue().getCoin()).reduce(BigInteger.ZERO, BigInteger::add);

        assertEquals(inputs, outputs.add(tx.getBody().getFee()),
                "inputs must equal outputs plus fee — anything else is value appearing or vanishing");

        // And the pool received the whole escrow, since this pool publishes no compounding fee.
        TransactionOutput pool = tx.getBody().getOutputs().stream()
                .filter(o -> utxo("pool").getAddress().equals(o.getAddress()))
                .findFirst().orElseThrow();
        assertEquals(lovelace(utxo("pool")).add(ESCROW), pool.getValue().getCoin(),
                "at a zero fee rate the pool must receive the entire addedLiquidity");

        // ⇒ therefore the bot nets exactly minus the transaction fee, which is what the builder asserts.
        BigInteger toBot = tx.getBody().getOutputs().stream()
                .filter(o -> BOT.equals(o.getAddress()))
                .map(o -> o.getValue().getCoin()).reduce(BigInteger.ZERO, BigInteger::add);
        assertEquals(tx.getBody().getFee().negate(), toBot.subtract(lovelace(wallet)),
                "with the pool and the echoes correct, conservation FORCES net == -txFee");
    }

    /**
     * ⚠ And the withdrawal dummy is one of the bot's outputs, not an extra payment. CCL prepends
     * exactly one output at the change address when a transaction carries withdrawals (trap 1); an
     * accounting that looked at only the last change output would under-count what came back.
     */
    @Test
    void theWithdrawalDummyCountsTowardsWhatTheBotGetsBack() {
        Transaction tx = build(List.of(walletUtxo("9e", 20_000_000L)));

        List<TransactionOutput> botOutputs = tx.getBody().getOutputs().stream()
                .filter(o -> BOT.equals(o.getAddress())).toList();

        assertEquals(2, botOutputs.size(),
                "one withdrawal dummy plus one change output — both are the bot's money");
    }

    private static BigInteger lovelace(Utxo utxo) {
        return utxo.getAmount().stream().filter(a -> "lovelace".equals(a.getUnit()))
                .map(Amount::getQuantity).findFirst().orElse(BigInteger.ZERO);
    }
}
