package com.fluidtokens.aquarium.offchain.service.loans;

import org.cardanofoundation.conversions.CardanoConverters;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The first live liquidation was rejected {@code OutsideValidityIntervalUTxO} — and <b>the builder
 * was correct.</b>
 *
 * <h2>What actually happened, measured on chain</h2>
 * <pre>
 *   decided_at      22:40:04.425Z
 *   invalidBefore   slot 121,127,975  =  decided_at − 29.4s  ⇐ EXACTLY the intended 30s backdate
 *   current slot    121,127,848       =  ONE SLOT AFTER THE LAST APPLIED BLOCK
 *   block 121,127,847 → next block 121,128,029           ⇐ A 182-SLOT GAP
 * </pre>
 * <b>The ledger's "current slot" is the node's position at the last block it applied, not wall
 * clock.</b> Preview's block gaps measured from two independent sources on 2026-08-26: mean ~37s,
 * max 182 in ~25 blocks. <b>A 30-second backdate cannot cover an ordinary gap.</b>
 *
 * <h2>⚠ Not a regression, and the provenance is the point</h2>
 * {@code validitySlots} is byte-identical to the previously deployed image, and the 30-second
 * constant dates from the commit titled <i>"run the liquidation loop in shadow, INCAPABLE OF
 * SUBMITTING"</i>. <b>A margin chosen in the commit that made submission impossible could never have
 * been tested by anything.</b> Arming did not introduce the fault; it was the first thing capable of
 * finding it.
 *
 * <h2>The fix this pins</h2>
 * Anchor the window's START to the last applied slot, so {@code invalidBefore} is at or behind the
 * node's position <b>by construction at any gap length</b> — rather than by a guess at how long a
 * gap might be. <b>Only the start moves:</b> {@code validTo} stays wall-clock-based, because the
 * transaction still needs its full landing time and because the oracle-window check reads
 * {@code validTo} and is a <b>separate constraint on the other end of the same interval</b> — a
 * point worth keeping, since "one displaced window explains both symptoms" was a plausible
 * unification that would have produced a fix clearing only half the problem.
 */
class ValidityWindowAnchorTest {

    private static final CardanoConverters CONVERTERS = LoanFixtures.converters();

    private static long millisOfSlot(long slot) {
        return CONVERTERS.slot().slotToTime(slot).toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    /** ⇒ The measured failure, reproduced: a lagging tip must pull the anchor back. */
    @Test
    void aLaggingChainTipPullsTheAnchorBackToTheLastAppliedBlock() {
        long lastBlockSlot = 121_127_847L;
        long tipMillis = millisOfSlot(lastBlockSlot);
        long now = tipMillis + 157_000L;   // wall clock 157s ahead, as it was that night

        long anchor = LiquidationExecutor.windowAnchorMillis(now, lastBlockSlot, CONVERTERS);

        assertEquals(tipMillis, anchor, "the anchor must follow the chain, not the clock");
        assertTrue(anchor < now, "and it must be BEHIND wall clock whenever the chain is");
    }

    /**
     * ⇒ And the consequence that matters: with the anchor applied, {@code invalidBefore} lands at or
     * before the node's current slot <b>even inside the 182-slot gap that caused the rejection</b>.
     * This is the assertion the old code could not have passed.
     */
    @Test
    void invalidBeforeNoLongerOutrunsTheNodeEvenInsideTheGapThatCausedTheRejection() {
        long lastBlockSlot = 121_127_847L;
        long nodeCurrentSlot = 121_127_848L;                  // what the ledger reported
        long now = millisOfSlot(lastBlockSlot) + 157_000L;    // wall clock, mid-gap

        long anchor = LiquidationExecutor.windowAnchorMillis(now, lastBlockSlot, CONVERTERS);
        long invalidBeforeMillis = anchor - 30_000L;
        long invalidBeforeSlot = CONVERTERS.time().toSlot(
                java.time.LocalDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(invalidBeforeMillis), ZoneOffset.UTC));

        assertTrue(invalidBeforeSlot <= nodeCurrentSlot,
                "invalidBefore " + invalidBeforeSlot + " must not exceed the node's current slot "
                        + nodeCurrentSlot);

        // And the old behaviour, for contrast — this is what was actually submitted and rejected.
        long oldInvalidBeforeSlot = CONVERTERS.time().toSlot(
                java.time.LocalDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(now - 30_000L), ZoneOffset.UTC));
        assertTrue(oldInvalidBeforeSlot > nodeCurrentSlot,
                "the fixture must reproduce the ORIGINAL failure, or this test proves nothing: "
                        + oldInvalidBeforeSlot + " vs " + nodeCurrentSlot);
    }

    /** A tip that is fresh must not push the window into the past for no reason. */
    @Test
    void aFreshTipLeavesWallClockAlone() {
        long lastBlockSlot = 121_128_440L;
        long now = millisOfSlot(lastBlockSlot) + 3_000L;
        assertEquals(now, LiquidationExecutor.windowAnchorMillis(now, lastBlockSlot + 5, CONVERTERS),
                "when the chain is at or ahead of us, wall clock is the right anchor");
    }

    /**
     * ⛔ Zero means UNKNOWN, not "the epoch". Before the first block arrives, anchoring on a raw zero
     * would date every transaction to 1970 — a far worse failure than the one being fixed.
     */
    @Test
    void noBlockSeenYetFallsBackToWallClockRatherThanNineteenSeventy() {
        long now = millisOfSlot(121_128_440L);
        assertEquals(now, LiquidationExecutor.windowAnchorMillis(now, 0L, CONVERTERS));
        assertEquals(now, LiquidationExecutor.windowAnchorMillis(now, -1L, CONVERTERS));
    }
}
