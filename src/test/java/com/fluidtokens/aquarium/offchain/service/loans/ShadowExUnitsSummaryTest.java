package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>The one thing a shadow dump must never do: present placeholder budgets as a validated
 * rehearsal.</b>
 *
 * <p>The dump's whole claim is <i>"every script evaluated and returned a real budget"</i> — the phase-2
 * failure that forfeits collateral is exactly what it rules out. If the transaction was built with no
 * evaluator, cardano-client-lib leaves <b>10000 mem / 10000-or-1000 steps</b> on every redeemer and
 * <b>says nothing</b>, because {@code ignoreScriptCostEvaluationError} defaults true (CCL trap 8). A
 * dump of that is worse than no dump: it looks like proof.
 *
 * <p>This project has already paid for that once. The 2026-08-21 incident shipped precisely these
 * numbers, under-declaring by two to five orders of magnitude, and a suite of 307 tests missed it
 * because every ex-units assertion read the evaluator's <em>report</em> rather than the transaction.
 *
 * <p>Both branches are driven directly, so neither depends on a rig happening to be wired one way.
 */
class ShadowExUnitsSummaryTest {

    private static Transaction withRedeemers(List<Redeemer> redeemers) {
        return Transaction.builder()
                .witnessSet(TransactionWitnessSet.builder().redeemers(redeemers).build())
                .build();
    }

    private static Redeemer redeemer(RedeemerTag tag, int index, long mem, long steps) {
        return Redeemer.builder()
                .tag(tag)
                .index(BigInteger.valueOf(index))
                .data(BigIntPlutusData.of(0))
                .exUnits(ExUnits.builder()
                        .mem(BigInteger.valueOf(mem))
                        .steps(BigInteger.valueOf(steps))
                        .build())
                .build();
    }

    /** The exact signature CCL leaves behind: 10000 mem, and 10000 or 1000 steps by tag. */
    @Test
    void aTransactionCarryingOnlyPlaceholdersIsMarkedNotValidated() {
        String summary = LiquidationExecutor.exUnitsSummary(withRedeemers(List.of(
                redeemer(RedeemerTag.Spend, 0, 10_000L, 10_000L),
                redeemer(RedeemerTag.Reward, 0, 10_000L, 1_000L))));

        assertTrue(summary.startsWith(LiquidationExecutor.PLACEHOLDER_MARKER), summary);
        assertTrue(summary.contains("total=20000/11000"), summary);
    }

    /** Measured budgets carry no marker — or the warning would cry wolf and stop being read. */
    @Test
    void measuredBudgetsAreReportedWithoutTheMarker() {
        String summary = LiquidationExecutor.exUnitsSummary(withRedeemers(List.of(
                redeemer(RedeemerTag.Spend, 0, 2_255_013L, 777_825_970L),
                redeemer(RedeemerTag.Reward, 0, 1_170_386L, 440_472_759L))));

        assertFalse(summary.contains(LiquidationExecutor.PLACEHOLDER_MARKER), summary);
        assertTrue(summary.contains("total=3425399/1218298729"), summary);
    }

    /**
     * ⚠ ONE real redeemer among placeholders is NOT the placeholder state. A partial evaluation is a
     * different fault — trap 8's "validate the payload, not the envelope" — and calling it
     * placeholders would send an operator to the wrong cause.
     */
    @Test
    void aSingleMeasuredRedeemerIsEnoughToClearTheMarker() {
        String summary = LiquidationExecutor.exUnitsSummary(withRedeemers(List.of(
                redeemer(RedeemerTag.Spend, 0, 10_000L, 10_000L),
                redeemer(RedeemerTag.Reward, 0, 1_170_386L, 440_472_759L))));

        assertFalse(summary.contains(LiquidationExecutor.PLACEHOLDER_MARKER), summary);
    }

    @Test
    void aTransactionWithNoRedeemersSaysSoRatherThanReportingZero() {
        assertEquals("no redeemers", LiquidationExecutor.exUnitsSummary(withRedeemers(List.of())));
        assertEquals("no redeemers", LiquidationExecutor.exUnitsSummary(Transaction.builder().build()));
    }
}
