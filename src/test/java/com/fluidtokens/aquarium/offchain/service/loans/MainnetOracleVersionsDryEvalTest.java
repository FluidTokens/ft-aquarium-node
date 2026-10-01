package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.aiken.AikenTransactionEvaluator;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.common.model.SlotConfigs;
import com.bloxbean.cardano.client.plutus.blueprint.PlutusBlueprintUtil;
import com.bloxbean.cardano.client.plutus.blueprint.model.PlutusVersion;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.model.loans.OracleSignature;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>The REAL v1 and v2 mainnet oracle scripts accept the redeemer this node builds (FAB-114).</b>
 *
 * <p>FluidTokens split their oracles on 2026-09-30: v1 for Lending v3, v2 for Lending v4, permanently.
 * Every live v4 loan names v1; new v4 loans name v2. No v2 loan existed on 2026-10-01, so nothing had
 * ever evaluated this node's oracle redeemer against a v2 oracle script. The registry CLAIMS v2 is
 * signed by the same keys — but the oracle validator checks signatures against the keys APPLIED to the
 * script ({@code validators/oracle.ak}, multisig branch), which only the deployed script can answer.
 *
 * <h2>What makes it honest</h2>
 * <ul>
 *   <li><b>Real scripts.</b> The deployed applied code, fetched from Koios on 2026-10-01 and pinned
 *       ({@code mainnet-oracle-script-<hash8>.hex}); each is asserted to hash to the credential its
 *       registry entry withdraws from.</li>
 *   <li><b>Real entries, through production's path.</b> The 2026-10-01 mainnet registry payload is
 *       loaded by the real {@link FluidOracleClient} and read out of the real
 *       {@link LiquidationExecutor#oracleSnapshot()} — the map every builder resolves a loan's oracle
 *       from — by the NFT a loan would name. Never a map the test assembles (FAB-110 was exactly that
 *       map missing every v1 oracle).</li>
 *   <li><b>Production's encoder.</b> {@link LiquidationTxEncoder#oracleRedeemer} with the entry's parsed
 *       signatures and key positions, as {@code LiquidateTransactionBuilder} emits it.</li>
 *   <li><b>An evaluator wired into the build</b> with {@code ignoreScriptCostEvaluationError(false)}, so a
 *       refused redeemer fails the build rather than shipping placeholder ex-units (CCL trap 8).</li>
 *   <li><b>Negative controls.</b> A tampered signature and a wrong key position are both refused —
 *       otherwise a rig that evaluated nothing would read as green.</li>
 * </ul>
 *
 * <p>⚠ <b>Scope:</b> the oracle's own withdrawal, which is where a v1/v2 difference could live. The
 * multisig branch checks signatures only; the window and NFT checks are the loan side's
 * ({@code retrieve_oracle_data}), already exercised by the liquidation rigs.
 */
class MainnetOracleVersionsDryEvalTest {

    /** A synthetic operator wallet: it only pays the fee and backs the collateral, never evaluated. */
    private static final String WALLET = AddressProvider.getEntAddress(
            Credential.fromKey(HexUtil.decodeHexString("ab".repeat(28))), Networks.mainnet()).getAddress();
    private static final List<Utxo> UNIVERSE = List.of(
            LoanFixtures.adaUtxo("aa".repeat(32), 0, WALLET, 50_000_000L),
            LoanFixtures.adaUtxo("aa".repeat(32), 1, WALLET, 10_000_000L));

    private static PlutusScript script(String hash8) {
        try {
            return PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(
                    LoanFixtures.fixture("mainnet-oracle-script-" + hash8 + ".hex"), PlutusVersion.v3);
        } catch (Exception e) {
            throw new AssertionError("cannot load the deployed oracle script " + hash8, e);
        }
    }

    private static Map<String, OracleEntry> productionSnapshot() throws Exception {
        FluidOracleClient client = OracleClients.mainnetTwoVersions();
        var network = new AppConfig.Network();
        network.setNetworkForTest("mainnet");
        var configuration = new AppConfig.LiquidationConfiguration(
                AppConfig.LiquidationConfiguration.Mode.SHADOW, 60, 120, 30, BigInteger.ZERO, 200, 30);
        ObjectProvider<FluidOracleClient> provider = new ObjectProvider<>() {
            @Override public FluidOracleClient getObject() { return client; }
            @Override public FluidOracleClient getObject(Object... args) { return client; }
            @Override public FluidOracleClient getIfAvailable() { return client; }
            @Override public FluidOracleClient getIfUnique() { return client; }
        };
        return new LiquidationExecutor(configuration,
                (com.fluidtokens.aquarium.offchain.service.BlockEventListener) null,
                (com.fluidtokens.aquarium.offchain.service.AppUtxoService) null,
                (com.bloxbean.cardano.client.account.Account) null, (LiquidationCandidateScanner) null,
                (LiquidationUtxoResolver) null, (LiquidateTransactionBuilder) null,
                (PayInAdvanceLiquidationRouter) null, (ConvertLiquidationRouter) null,
                (com.fluidtokens.aquarium.offchain.service.LoansContractRegistry) null,
                (LiquidationDecisionLog) null, (MarketCoverageReporter) null, provider, network,
                (com.bloxbean.cardano.client.api.ProtocolParamsSupplier) null,
                (org.cardanofoundation.conversions.CardanoConverters) null,
                (LiquidationExecutor.TransactionSubmitter) null).oracleSnapshot();
    }

    /** The oracle entry a loan naming {@code policy + assetName} would be built with. */
    private static OracleEntry entryNamedBy(String policy, String oracleAssetName) throws Exception {
        OracleEntry entry = productionSnapshot().get(policy + oracleAssetName);
        assertNotNull(entry, "the executor's snapshot has no oracle " + policy + oracleAssetName);
        return entry;
    }

    /** The oracle's withdraw-0, carrying {@code redeemer}, built with the real evaluator wired in. */
    private static Transaction build(OracleEntry entry, PlutusScript script,
                                     com.bloxbean.cardano.client.plutus.spec.PlutusData redeemer) {
        var supplier = LoanFixtures.utxoSupplier(UNIVERSE);
        var evaluator = new AikenTransactionEvaluator(supplier, EvalFixtures.protocolParams(),
                hash -> Optional.of(script), SlotConfigs.mainnet());
        ScriptTx tx = new ScriptTx()
                .withdraw(entry.rewardAddress(), BigInteger.ZERO, redeemer, WALLET)
                .attachRewardValidator(script);
        return new QuickTxBuilder(supplier, EvalFixtures.protocolParams(),
                (com.bloxbean.cardano.client.api.ScriptSupplier) hash -> Optional.of(script),
                (com.bloxbean.cardano.client.api.TransactionProcessor) null)
                .compose(tx)
                .feePayer(WALLET)
                .collateralPayer(WALLET)
                .withTxEvaluator(evaluator)
                // ⛔ FALSE: a refused redeemer must fail the build, never ship placeholders (CCL trap 8).
                .ignoreScriptCostEvaluationError(false)
                .build();
    }

    /**
     * Each of NIGHT v1, NIGHT v2 (a DIFFERENT script), and FLDT v1/v2 (one SHARED script under two
     * NFTs), resolved by the NFT a loan would name and accepted by the real deployed oracle script.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "NIGHT v1, 93794f9b7f3dc632cb889c7aec7d334f016f532e64f16141b6895f5b, 6f7261636c654e69676874, a0bb657d",
            "NIGHT v2, 26e60b2083c14b849e622f8e05dd46ab01a7986fe5d72eeba8680d26, 6f7261636c654e69676874, 756897fd",
            "FLDT v1,  93794f9b7f3dc632cb889c7aec7d334f016f532e64f16141b6895f5b, 6f7261636c65464c44544333, d81a8bea",
            "FLDT v2,  26e60b2083c14b849e622f8e05dd46ab01a7986fe5d72eeba8680d26, 6f7261636c65464c44544333, d81a8bea",
    })
    void theDeployedOracleScriptAcceptsTheRedeemerThisNodeBuilds(String label, String policy,
                                                                 String oracleAssetName, String hash8) throws Exception {
        OracleEntry entry = entryNamedBy(policy.trim(), oracleAssetName.trim());
        PlutusScript script = script(hash8.trim());

        assertEquals(entry.withdrawCredentialHash(), HexUtil.encodeHexString(script.getScriptHash()),
                label + ": the pinned script must be the one this oracle entry withdraws from");
        assertTrue(entry.hasEnoughSignatures(), label + ": the registry entry must carry enough signatures");

        Transaction built = build(entry, script,
                LiquidationTxEncoder.oracleRedeemer(entry.feed(), entry.signatures()));

        var reward = built.getWitnessSet().getRedeemers().stream()
                .filter(r -> r.getTag() == RedeemerTag.Reward).findFirst().orElseThrow();
        assertTrue(reward.getExUnits().getMem().compareTo(BigInteger.valueOf(10_000)) > 0
                        && reward.getExUnits().getSteps().compareTo(BigInteger.valueOf(10_000)) > 0,
                label + ": ex-units are placeholders, so nothing was evaluated: " + reward.getExUnits());
    }

    /** NEGATIVE CONTROL: one flipped signature byte — the script must refuse it. */
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "NIGHT v2, 26e60b2083c14b849e622f8e05dd46ab01a7986fe5d72eeba8680d26, 6f7261636c654e69676874, 756897fd",
            "FLDT v2,  26e60b2083c14b849e622f8e05dd46ab01a7986fe5d72eeba8680d26, 6f7261636c65464c44544333, d81a8bea",
    })
    void aTamperedSignatureIsRefused(String label, String policy, String oracleAssetName, String hash8) throws Exception {
        OracleEntry entry = entryNamedBy(policy.trim(), oracleAssetName.trim());
        List<OracleSignature> tampered = new ArrayList<>(entry.signatures());
        OracleSignature first = tampered.getFirst();
        String hex = first.signatureHex();
        char flipped = hex.charAt(0) == '0' ? '1' : '0';
        tampered.set(0, new OracleSignature(first.keyPosition(), flipped + hex.substring(1)));

        Exception refused = assertThrows(Exception.class, () -> build(entry, script(hash8.trim()),
                        LiquidationTxEncoder.oracleRedeemer(entry.feed(), tampered)),
                label + ": a forged signature must be refused, or this rig evaluates nothing");
        assertScriptRefused(label, refused);
    }

    /**
     * NEGATIVE CONTROL: a key position the script has no key for. NIGHT v2 carries ONE key, so a shifted
     * position indexes past the end of the applied key list and the script refuses on that
     * ({@code Machine(EmptyList)}) — it proves positions are honoured, not a wrong-key signature check.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "NIGHT v2, 26e60b2083c14b849e622f8e05dd46ab01a7986fe5d72eeba8680d26, 6f7261636c654e69676874, 756897fd",
    })
    void anOutOfRangeKeyPositionIsRefusedByTheScript(String label, String policy, String oracleAssetName, String hash8) throws Exception {
        OracleEntry entry = entryNamedBy(policy.trim(), oracleAssetName.trim());
        List<OracleSignature> shifted = entry.signatures().stream()
                .map(s -> new OracleSignature(s.keyPosition() + 1, s.signatureHex()))
                .toList();

        Exception refused = assertThrows(Exception.class, () -> build(entry, script(hash8.trim()),
                        LiquidationTxEncoder.oracleRedeemer(entry.feed(), shifted)),
                label + ": a key position outside the applied key list must be refused");
        assertScriptRefused(label, refused);
    }

    /**
     * ⛔ FAB-114 invariant. FLDT's v1 and v2 oracles share ONE withdraw credential under TWO NFTs. A
     * transaction touching both needs one withdrawal (per credential) but BOTH NFT reference inputs
     * (per NFT) — retrieve_oracle_data requires the exact NFT each loan names. Reference inputs used to
     * be deduplicated by credential, which kept one NFT and refused the transaction.
     */
    @org.junit.jupiter.api.Test
    void oracleReferenceInputsAreOnePerNftEvenWhenTwoVersionsShareACredential() throws Exception {
        OracleEntry fldtV1 = entryNamedBy("93794f9b7f3dc632cb889c7aec7d334f016f532e64f16141b6895f5b",
                "6f7261636c65464c44544333");
        OracleEntry fldtV2 = entryNamedBy("26e60b2083c14b849e622f8e05dd46ab01a7986fe5d72eeba8680d26",
                "6f7261636c65464c44544333");
        assertEquals(fldtV1.withdrawCredentialHash(), fldtV2.withdrawCredentialHash(),
                "precondition: the two FLDT versions share one credential on mainnet");

        var distinct = LiquidateTransactionBuilder.distinctByOracleNft(List.of(fldtV1, fldtV2, fldtV1));

        assertEquals(2, distinct.size(), "one per NFT: both versions' reference inputs must travel");
        assertTrue(distinct.stream().anyMatch(e -> e.referenceInput().equals(fldtV1.referenceInput()))
                        && distinct.stream().anyMatch(e -> e.referenceInput().equals(fldtV2.referenceInput())),
                "each version's own NFT reference input");
    }

    /**
     * The refusal must come from the SCRIPT (a RedeemerError from the evaluator), not from anything
     * else in the build — otherwise a rig fault would pass the negative controls (audit finding 5).
     */
    private static void assertScriptRefused(String label, Throwable refused) {
        StringBuilder chain = new StringBuilder();
        for (Throwable t = refused; t != null; t = t.getCause()) {
            chain.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append(" | ");
        }
        assertTrue(chain.toString().contains("RedeemerError"),
                label + ": refused, but not by the oracle script — " + chain);
    }
}
