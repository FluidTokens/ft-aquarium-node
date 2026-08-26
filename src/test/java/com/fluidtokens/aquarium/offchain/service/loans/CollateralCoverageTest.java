package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The collateral-coverage refusal.
 *
 * <h2>⛔ What this stops — the only failure today that never became a transaction</h2>
 * Measured 2026-08-25. The bot's collateral input was the 1,000,000 lovelace ada-only UTxO; the
 * required collateral was <b>1,670,285</b>; cardano-client-lib subtracted and emitted a collateral
 * return of <b>−670,285</b>. A negative {@code MaryValue} has no encoding, so every era decoder
 * rejected the CBOR — {@code DeserialiseFailure 1227 "expected array or int, got TypeNInt"} — and it
 * was refused <b>before any validation ran</b>. Not phase 2, not phase 1: the node could not parse
 * it. Six decoder errors were reported and only the sixth named the real field.
 *
 * <p>{@code adaOnlyWalletUtxo()} accepted that UTxO, correctly: it was ada-only, datum-free and
 * script-free. It asks about SHAPE. Nothing asked whether it was <b>large enough</b>, and that is
 * what this guard adds.
 *
 * <p>⚠ These tests are deployment-independent by construction — no policy id, no coordinate, no
 * fixture datum — so they keep meaning the same thing after a redeploy.
 */
class CollateralCoverageTest {

    private static final ProtocolParams PARAMS = LoanFixtures.protocolParams().getProtocolParams();

    /**
     * The observed {@code total_collateral}. It pins the rounding: {@code 1,113,523 × 150 / 100} is
     * {@code 1,670,284.5} and this is {@code 1,670,285}, so the declared collateral is a CEILING and
     * integer division would understate it by one lovelace.
     *
     * <p>⚠ <b>This is cardano-client-lib's DECLARATION, not the ledger's minimum.</b> CIP-0040 gives
     * the requirement as {@code quot(txfee × collateralPercent, 100)} — truncating, so
     * {@code 1,670,284} — under a {@code ≥} rule, while
     * {@code CollateralBuilders.balanceCollateralOutputs():129} rounds with
     * {@link java.math.RoundingMode#CEILING}. Over-declaring by one lovelace is safe and is what the
     * chain saw; do not "correct" it downward, and do not cite it as the ledger's figure.
     */
    private static final BigInteger OBSERVED_TOTAL_COLLATERAL = BigInteger.valueOf(1_670_285L);
    private static final BigInteger OBSERVED_FEE = BigInteger.valueOf(1_113_523L);
    private static final BigInteger STARVED_CAPACITY = BigInteger.valueOf(1_000_000L);

    private static final String ADDRESS =
            "addr_test1qqgern5qmhfqlztqfkk7wjfc8qvadlsjc2xhwm45nrdzudn3fakarjxlcrdwsee2wtcja4l3neq6dfxernah25938dsskl9d9z";

    private static Utxo walletHolding(BigInteger lovelace) {
        Utxo utxo = new Utxo();
        utxo.setTxHash("49743a1e".repeat(8));
        utxo.setOutputIndex(0);
        utxo.setAddress(ADDRESS);
        utxo.setAmount(new ArrayList<>(List.of(Amount.builder()
                .unit("lovelace").quantity(lovelace).build())));
        return utxo;
    }

    private static Transaction txWith(BigInteger fee, BigInteger collateralReturnOrNull) {
        TransactionBody.TransactionBodyBuilder body = TransactionBody.builder()
                .inputs(new ArrayList<>())
                .outputs(new ArrayList<>())
                .fee(fee);
        if (collateralReturnOrNull != null) {
            body.collateralReturn(TransactionOutput.builder()
                            .address(ADDRESS)
                            .value(Value.builder().coin(collateralReturnOrNull)
                                    .multiAssets(new ArrayList<>()).build())
                            .build())
                    .totalCollateral(OBSERVED_TOTAL_COLLATERAL);
        }
        return Transaction.builder().body(body.build()).build();
    }

    private static void check(Transaction txn, BigInteger capacity) {
        LiquidateTransactionBuilder.assertCollateralIsCoverable(PARAMS, txn, walletHolding(capacity));
    }

    // ---- the measured artefact -----------------------------------------------------------------

    @Test
    void theNegativeCollateralReturnThatNoNodeCouldParseIsRefused() {
        Transaction txn = txWith(OBSERVED_FEE, STARVED_CAPACITY.subtract(OBSERVED_TOTAL_COLLATERAL));

        var thrown = assertThrows(LiquidateTransactionBuilder.RefusedException.class,
                () -> check(txn, STARVED_CAPACITY));

        assertEquals(LiquidateTransactionBuilder.Refusal.INSUFFICIENT_COLLATERAL, thrown.getReason());
        assertTrue(thrown.getMessage().contains("-670285"),
                "the refusal must quote the negative return so the operator can match it against the "
                        + "decoder's byte offset, was: " + thrown.getMessage());
    }

    /**
     * The same shortfall with no collateral return in the body at all — a builder that simply
     * declined to emit one. The arithmetic must catch it independently, because the artefact check
     * has nothing to look at.
     */
    @Test
    void theSameShortfallIsCaughtByArithmeticWhenNoReturnWasEmitted() {
        var thrown = assertThrows(LiquidateTransactionBuilder.RefusedException.class,
                () -> check(txWith(OBSERVED_FEE, null), STARVED_CAPACITY));

        assertEquals(LiquidateTransactionBuilder.Refusal.INSUFFICIENT_COLLATERAL, thrown.getReason());
        assertTrue(thrown.getMessage().contains("1000000")
                        && thrown.getMessage().contains(OBSERVED_TOTAL_COLLATERAL.toString()),
                "the refusal must state what it had and what it needed, was: " + thrown.getMessage());
    }

    // ---- the boundary, pinned against the ledger's own number -----------------------------------

    /**
     * ⚠ The ceiling, proven by a case integer division would get wrong. Anything below the observed
     * {@code total_collateral} must be refused and exactly that value must pass — if this drifts to
     * {@code floor}, a one-lovelace shortfall builds an unparseable transaction again.
     */
    @Test
    void exactlyTheLedgersRequiredCollateralIsEnoughAndOneLovelaceLessIsNot() {
        check(txWith(OBSERVED_FEE, null), OBSERVED_TOTAL_COLLATERAL);

        var thrown = assertThrows(LiquidateTransactionBuilder.RefusedException.class,
                () -> check(txWith(OBSERVED_FEE, null), OBSERVED_TOTAL_COLLATERAL.subtract(BigInteger.ONE)));
        assertEquals(LiquidateTransactionBuilder.Refusal.INSUFFICIENT_COLLATERAL, thrown.getReason());
    }

    @Test
    void aWalletWithRoomToSpareIsAccepted() {
        check(txWith(OBSERVED_FEE, BigInteger.valueOf(8_329_715L)), BigInteger.valueOf(10_000_000L));
    }

    /**
     * A zero return is the exact-fit case and is representable, so it must NOT be refused. This is
     * the boundary a clamp-to-zero fix would have made unreachable.
     */
    @Test
    void aZeroCollateralReturnIsExactFitNotAFailure() {
        check(txWith(OBSERVED_FEE, BigInteger.ZERO), OBSERVED_TOTAL_COLLATERAL);
    }

    /**
     * The guard must read {@code collateral_percent} from the parameters rather than assume 150.
     * At 200% the same wallet that was sufficient becomes insufficient, and nothing else changed.
     */
    @Test
    void theRequirementScalesWithCollateralPercentFromTheProtocolParameters() {
        ProtocolParams doubled = LoanFixtures.protocolParams().getProtocolParams();
        doubled.setCollateralPercent(new java.math.BigDecimal("200"));

        check(txWith(OBSERVED_FEE, null), BigInteger.valueOf(2_500_000L));   // 200% needs 2,227,046

        var thrown = assertThrows(LiquidateTransactionBuilder.RefusedException.class, () ->
                LiquidateTransactionBuilder.assertCollateralIsCoverable(
                        doubled, txWith(OBSERVED_FEE, null), walletHolding(BigInteger.valueOf(2_000_000L))));
        assertTrue(thrown.getMessage().contains("200"),
                "the refusal must quote the percent it applied, was: " + thrown.getMessage());
    }
}
