package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The change split, and the conservation assertion that guards it.
 *
 * <h2>Why this class exists as its own file</h2>
 * The split is production code with <b>no other test able to reach it</b>: every dry-eval test that
 * drives {@link LiquidateTransactionBuilder} end to end is currently red for an unrelated reason (it
 * pins third-deployment fixtures), so none of them can say anything about this hook. A hook whose
 * only coverage is a red test is uncovered. These tests call the seam directly and depend on no
 * deployment coordinate at all, so they stay honest across a redeploy.
 *
 * <h2>What is being protected</h2>
 * Measured 2026-08-25, after the first real liquidation ({@code 49743a1e…}): the bot's change came
 * back as ONE output holding 9,964,993,434 lovelace and 5,000,000 tFLDT. That output fails
 * {@code adaOnlyWalletUtxo()}'s single-asset test — correctly — so the wallet held 9,966 ADA and
 * could build nothing. <b>A successful liquidation disabled the bot.</b>
 */
class ChangeSplitTest {

    private static final String CHANGE =
            "addr_test1qqgern5qmhfqlztqfkk7wjfc8qvadlsjc2xhwm45nrdzudn3fakarjxlcrdwsee2wtcja4l3neq6dfxernah25938dsskl9d9z";
    /** Any address that is not the change address; the split must not read or touch it. */
    private static final String ELSEWHERE =
            "addr_test1wznjzsdxr09drt5w0hf9nn4yr7vyeq2cngyxvzhwsn9nfsq4mrsn7";

    private static final String TFLDT_POLICY = "1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c";
    private static final String TFLDT_NAME = "74464c4454";
    private static final String TFLDT_UNIT = TFLDT_POLICY + "." + TFLDT_NAME;

    /** The measured shape: the whole balance and the collateral in one output. */
    private static final BigInteger MEASURED_CHANGE = BigInteger.valueOf(9_964_993_434L);
    private static final BigInteger MEASURED_COLLATERAL = BigInteger.valueOf(5_000_000L);

    private static final BigInteger FEE = BigInteger.valueOf(1_133_033L);

    private static final ProtocolParams PARAMS = LoanFixtures.protocolParams().getProtocolParams();

    // ---- construction helpers ------------------------------------------------------------------

    private static MultiAsset tfldt(long quantity) {
        return MultiAsset.builder()
                .policyId(TFLDT_POLICY)
                .assets(new ArrayList<>(List.of(
                        Asset.builder().name(TFLDT_NAME).value(BigInteger.valueOf(quantity)).build())))
                .build();
    }

    private static TransactionOutput output(String address, BigInteger lovelace, MultiAsset... assets) {
        return TransactionOutput.builder()
                .address(address)
                .value(Value.builder().coin(lovelace)
                        .multiAssets(new ArrayList<>(List.of(assets))).build())
                .build();
    }

    private static Transaction txWith(TransactionOutput... outputs) {
        return Transaction.builder()
                .body(TransactionBody.builder()
                        .inputs(new ArrayList<>())
                        .outputs(new ArrayList<>(List.of(outputs)))
                        .fee(FEE)
                        .build())
                .build();
    }

    private static List<TransactionOutput> changeOutputs(Transaction txn) {
        return txn.getBody().getOutputs().stream()
                .filter(output -> CHANGE.equals(output.getAddress()))
                .toList();
    }

    private static void split(Transaction txn) {
        LiquidateTransactionBuilder.splitChangeSoAdaStaysSpendable(PARAMS, txn, CHANGE);
    }

    // ---- the case it exists for ----------------------------------------------------------------

    @Test
    void theMeasuredPostLiquidationChangeIsSplitIntoASpendableAdaOutputAndAMinAdaTokenOutput() {
        Transaction txn = txWith(output(CHANGE, MEASURED_CHANGE, tfldt(MEASURED_COLLATERAL.longValue())));

        split(txn);

        List<TransactionOutput> change = changeOutputs(txn);
        assertEquals(2, change.size(), "the single change output must become two");

        TransactionOutput tokens = change.stream()
                .filter(output -> !output.getValue().getMultiAssets().isEmpty())
                .findFirst().orElseThrow(() -> new AssertionError("the collateral vanished"));
        TransactionOutput ada = change.stream()
                .filter(output -> output.getValue().getMultiAssets().isEmpty())
                .findFirst().orElseThrow(() -> new AssertionError("no ada-only output was produced"));

        // THE POINT: this output is what adaOnlyWalletUtxo() will accept next cycle.
        assertTrue(ada.getValue().getCoin().compareTo(BigInteger.valueOf(9_000_000_000L)) > 0,
                "the ada-only output must carry the working capital, not a token of it: "
                        + ada.getValue().getCoin());

        // The token output sits at the min-ada MinAdaCalculator computes for it — never a constant.
        BigInteger minAda = new com.bloxbean.cardano.client.api.MinAdaCalculator(PARAMS)
                .calculateMinAda(output(CHANGE, BigInteger.ZERO, tfldt(MEASURED_COLLATERAL.longValue())));
        assertEquals(minAda, tokens.getValue().getCoin(),
                "the token output must hold exactly its min-ada, so nothing is stranded with it");
        assertEquals(MEASURED_COLLATERAL,
                tokens.getValue().getMultiAssets().getFirst().getAssets().getFirst().getValue());
    }

    @Test
    void everyUnitIsConservedAcrossTheSplitAndTheOnlyLovelaceLostIsTheFeeItAdded() {
        Transaction txn = txWith(output(CHANGE, MEASURED_CHANGE, tfldt(MEASURED_COLLATERAL.longValue())));
        Map<String, BigInteger> before =
                LiquidateTransactionBuilder.changeAddressCensus(txn.getBody().getOutputs(), CHANGE);

        split(txn);

        Map<String, BigInteger> after =
                LiquidateTransactionBuilder.changeAddressCensus(txn.getBody().getOutputs(), CHANGE);
        BigInteger feeDelta = txn.getBody().getFee().subtract(FEE);

        assertEquals(before.get("lovelace"), after.get("lovelace").add(feeDelta),
                "lovelace at the change address may only fall by what the split paid in fee");
        assertEquals(MEASURED_COLLATERAL, after.get(TFLDT_UNIT), "the collateral must survive intact");
        assertTrue(feeDelta.signum() > 0,
                "the extra output has bytes and balancing has already run, so a fee top-up is "
                        + "mandatory — a zero delta means FeeTooSmallUTxO at submit");
    }

    /**
     * Two assets, because a per-unit invariant asserted with one asset is indistinguishable from a
     * check that merely counts assets or looks at the first one.
     */
    @Test
    void aChangeOutputCarryingSeveralDistinctAssetsKeepsEveryOneOfThem() {
        String otherPolicy = "2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d";
        MultiAsset other = MultiAsset.builder()
                .policyId(otherPolicy)
                .assets(new ArrayList<>(List.of(
                        Asset.builder().name("6161").value(BigInteger.valueOf(7)).build(),
                        Asset.builder().name("6262").value(BigInteger.valueOf(9)).build())))
                .build();
        Transaction txn = txWith(output(CHANGE, MEASURED_CHANGE, tfldt(5_000_000L), other));

        split(txn);

        Map<String, BigInteger> after =
                LiquidateTransactionBuilder.changeAddressCensus(txn.getBody().getOutputs(), CHANGE);
        assertEquals(BigInteger.valueOf(5_000_000L), after.get(TFLDT_UNIT));
        assertEquals(BigInteger.valueOf(7), after.get(otherPolicy + ".6161"));
        assertEquals(BigInteger.valueOf(9), after.get(otherPolicy + ".6262"));
    }

    // ---- the shapes it must decline to touch ---------------------------------------------------

    @Test
    void anAdaOnlyChangeOutputIsLeftExactlyAsItWas() {
        Transaction txn = txWith(output(CHANGE, MEASURED_CHANGE));

        split(txn);

        assertEquals(1, txn.getBody().getOutputs().size(), "there was nothing to split");
        assertEquals(MEASURED_CHANGE, txn.getBody().getOutputs().getFirst().getValue().getCoin());
        assertEquals(FEE, txn.getBody().getFee(), "an untouched body must not be charged a fee");
    }

    /**
     * Two token-bearing change outputs is a shape this does not model, and guessing which to split
     * is exactly the kind of silent choice that produced the defect being fixed. It must decline.
     */
    @Test
    void twoTokenBearingChangeOutputsAreDeclinedRatherThanGuessedAt() {
        Transaction txn = txWith(
                output(CHANGE, MEASURED_CHANGE, tfldt(5_000_000L)),
                output(CHANGE, BigInteger.valueOf(3_000_000L), tfldt(1_000_000L)));

        split(txn);

        assertEquals(2, txn.getBody().getOutputs().size(), "an unmodelled shape must be left alone");
        assertEquals(FEE, txn.getBody().getFee());
    }

    @Test
    void aChangeOutputWithNoRoomAboveItsMinAdaIsLeftAlone() {
        // 1 lovelace: far below any min-ada for a token-bearing output, so there is nothing to carve.
        Transaction txn = txWith(output(CHANGE, BigInteger.ONE, tfldt(5_000_000L)));

        split(txn);

        assertEquals(1, txn.getBody().getOutputs().size());
        assertEquals(BigInteger.ONE, txn.getBody().getOutputs().getFirst().getValue().getCoin());
    }

    @Test
    void tokenBearingOutputsAtOtherAddressesAreNotTheBotsChangeAndAreIgnored() {
        Transaction txn = txWith(
                output(ELSEWHERE, BigInteger.valueOf(2_000_000L), tfldt(99_000_000L)),
                output(CHANGE, MEASURED_CHANGE));

        split(txn);

        assertEquals(2, txn.getBody().getOutputs().size(),
                "an asset-manager output is not change and must not be split");
        assertEquals(BigInteger.valueOf(99_000_000L),
                txn.getBody().getOutputs().getFirst().getValue()
                        .getMultiAssets().getFirst().getAssets().getFirst().getValue());
    }

    // ---- the assertion must be shown to FAIL, or it is not known to discriminate ----------------

    @Test
    void conservationHoldsWhenOnlyTheFeeMoved() {
        LiquidateTransactionBuilder.assertChangeConserved(
                Map.of("lovelace", BigInteger.valueOf(1_000), TFLDT_UNIT, BigInteger.valueOf(5)),
                Map.of("lovelace", BigInteger.valueOf(900), TFLDT_UNIT, BigInteger.valueOf(5)),
                BigInteger.valueOf(100));
    }

    @Test
    void aDroppedAssetIsCaughtAndNamed() {
        var thrown = assertThrows(RuntimeException.class, () ->
                LiquidateTransactionBuilder.assertChangeConserved(
                        Map.of("lovelace", BigInteger.valueOf(1_000), TFLDT_UNIT, BigInteger.valueOf(5)),
                        Map.of("lovelace", BigInteger.valueOf(1_000)),
                        BigInteger.ZERO));
        assertTrue(thrown.getMessage().contains(TFLDT_UNIT),
                "the failure must name the unit that did not balance, was: " + thrown.getMessage());
    }

    @Test
    void anAssetAppearingFromNowhereIsAlsoCaught() {
        assertThrows(RuntimeException.class, () ->
                LiquidateTransactionBuilder.assertChangeConserved(
                        Map.of("lovelace", BigInteger.valueOf(1_000)),
                        Map.of("lovelace", BigInteger.valueOf(1_000), TFLDT_UNIT, BigInteger.valueOf(5)),
                        BigInteger.ZERO));
    }

    @Test
    void lovelaceLeakingBeyondTheFeeIsCaught() {
        var thrown = assertThrows(RuntimeException.class, () ->
                LiquidateTransactionBuilder.assertChangeConserved(
                        Map.of("lovelace", BigInteger.valueOf(1_000)),
                        Map.of("lovelace", BigInteger.valueOf(800)),
                        BigInteger.valueOf(100)));
        assertTrue(thrown.getMessage().contains("lovelace"), thrown.getMessage());
    }

    /**
     * The check must not be satisfiable by lovelace alone. This pair balances lovelace exactly and
     * halves the collateral — the precise mistake a lovelace-only assertion would wave through.
     */
    @Test
    void aQuantityChangeIsCaughtEvenThoughLovelaceBalancesPerfectly() {
        var thrown = assertThrows(RuntimeException.class, () ->
                LiquidateTransactionBuilder.assertChangeConserved(
                        Map.of("lovelace", BigInteger.valueOf(1_000), TFLDT_UNIT, BigInteger.valueOf(5_000_000)),
                        Map.of("lovelace", BigInteger.valueOf(1_000), TFLDT_UNIT, BigInteger.valueOf(2_500_000)),
                        BigInteger.ZERO));
        assertTrue(thrown.getMessage().contains("5000000") && thrown.getMessage().contains("2500000"),
                "the failure must report both quantities so the slip is readable, was: "
                        + thrown.getMessage());
        assertFalse(thrown.getMessage().contains("conserve lovelace"),
                "lovelace balanced here; a lovelace complaint means the per-unit check never ran");
    }
}
