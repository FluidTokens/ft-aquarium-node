package com.fluidtokens.aquarium.offchain.service.loans;

import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import org.cardanofoundation.conversions.CardanoConverters;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FAB-183: a liquidation's validity interval starts no earlier than the newest oracle feed its
 * transaction carries, and never ahead of the chain.
 *
 * <p>{@code lib/fluidtokens/oracle.ak} enforces a feed's window at both ends ({@code valid_from <=
 * transactionValidFrom}, {@code valid_to >= transactionValidTo}), so a start fixed at {@code anchor − 30 s}
 * refused a freshly pushed price until a block landed 30 s after its {@code validFrom}. These cases pin the
 * clamp's arithmetic exactly — the round-up direction, the anchor bound and the unchanged floor — because
 * each of them can be broken without any other test going red.
 *
 * <p>Preview converters, whose slots are one second long: {@link #T} is a whole slot, so every offset
 * below that is not a multiple of 1 000 ms lands mid-slot on purpose.
 */
class LiquidationStartClampTest {

    private static final CardanoConverters CONVERTERS = LoanFixtures.converters();

    private static final long BACKDATE = 30_000L;

    /** A slot-aligned instant well inside preview's history. */
    private static final long T = 1_700_000_000_000L;

    private static final AssetType TOKEN = new AssetType("c0".repeat(28), "544f4b");
    private static final AssetType OTHER_TOKEN = new AssetType("c1".repeat(28), "4f544852");

    private static long slotOf(long millis) {
        return CONVERTERS.time().toSlot(LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC));
    }

    private static long millisOfSlot(long slot) {
        return CONVERTERS.slot().slotToTime(slot).toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    /**
     * What the Audit base built for this window start: each route's {@code validitySlots} took
     * {@code toSlot(floor)} and moved one slot later when that slot began before the floor. Re-derived here
     * the routes' way, so the "unchanged where no feed is fresh" invariant is compared against the base's
     * own rule rather than against the clamp's.
     */
    private static long baseStartMillis(long floorMillis) {
        long slot = slotOf(floorMillis);
        if (millisOfSlot(slot) < floorMillis) {
            slot += 1;
        }
        return millisOfSlot(slot);
    }

    private static OraclePriceFeed feed(AssetType token, long validFrom) {
        return OraclePriceFeed.priceDataCharlie(token, BigInteger.valueOf(50), BigInteger.ONE,
                validFrom, validFrom + 600_000L);
    }

    private static LiquidationStartClamp.Start start(LiquidationStartClamp.Result result) {
        return assertInstanceOf(LiquidationStartClamp.Start.class, result, "expected a start: " + result);
    }

    private static LiquidationStartClamp.NotYetValid notYet(LiquidationStartClamp.Result result) {
        return assertInstanceOf(LiquidationStartClamp.NotYetValid.class, result,
                "expected not-yet-valid: " + result);
    }

    // ---- unchanged where no feed is fresh -------------------------------------------------------

    /**
     * No non-ADA feed: the start is the first slot at or after {@code anchor − 30 s} — exactly the slot the
     * base built. With an unknown committed slot (0) the anchor is wall clock, deliberately mid-slot here,
     * so the round-up has something to do.
     */
    @Test
    void withNoFeedTheStartIsTheFirstSlotAtOrAfterTheFloorAsTheBaseBuilt() {
        long now = T + 400L;   // mid-slot: the floor T − 29 600 is mid-slot too

        LiquidationStartClamp.Start start = start(LiquidationStartClamp.clamp(now, 0L, BACKDATE, List.of(),
                CONVERTERS));

        assertEquals(baseStartMillis(now - BACKDATE), start.startMillis());
        assertEquals(T - 29_000L, start.startMillis(), "the floor T − 29 600 rounds UP to T − 29 000");
        assertEquals(now, start.anchorMillis(), "committed slot 0 is unknown: the anchor is wall clock");
        assertFalse(start.raisedByFeed());
    }

    /** And with a known committed slot, the anchor is that block's time when the chain lags wall clock. */
    @Test
    void withNoFeedAndAKnownCommittedSlotTheStartIsTheFloorUnderTheCommittedBlock() {
        long committedSlot = slotOf(T - 157_000L);
        long tip = millisOfSlot(committedSlot);

        LiquidationStartClamp.Start start = start(LiquidationStartClamp.clamp(T, committedSlot, BACKDATE,
                List.of(), CONVERTERS));

        assertEquals(tip, start.anchorMillis());
        assertEquals(baseStartMillis(tip - BACKDATE), start.startMillis());
        assertEquals(tip - BACKDATE, start.startMillis(), "a slot-aligned tip less 30 s is already a slot");
    }

    /** A feed that opened at or before {@code anchor − 30 s} moves nothing: same start as the base. */
    @Test
    void aFeedOpeningAtOrBeforeTheFloorLeavesTheBaseStart() {
        long now = T + 400L;
        long floor = now - BACKDATE;
        for (long validFrom : new long[]{floor, floor - 1L, now - 3_600_000L}) {
            LiquidationStartClamp.Start start = start(LiquidationStartClamp.clamp(now, 0L, BACKDATE,
                    List.of(feed(TOKEN, validFrom)), CONVERTERS));

            assertEquals(baseStartMillis(floor), start.startMillis(), "validFrom " + validFrom);
            assertFalse(start.raisedByFeed(), "validFrom " + validFrom);
        }
    }

    // ---- a fresh feed raises the start --------------------------------------------------------------

    /**
     * {@code anchor − 30 s < F ≤ anchor}: the start is the first whole slot at or after F, which is ≥ F and
     * ≤ the anchor. F is mid-slot, so rounding DOWN (a slot that opens before the feed — refused on chain)
     * and rounding UP give different answers, and only the second is right.
     */
    @Test
    void aFeedOpeningInsideTheBackdateRaisesTheStartToItsFirstWholeSlot() {
        long committedSlot = slotOf(T);
        long anchor = millisOfSlot(committedSlot);
        long validFrom = anchor - 10_500L;

        LiquidationStartClamp.Start start = start(LiquidationStartClamp.clamp(T + 20_000L, committedSlot,
                BACKDATE, List.of(feed(TOKEN, validFrom)), CONVERTERS));

        assertEquals(anchor - 10_000L, start.startMillis(), "rounded UP to a whole slot");
        assertTrue(start.startMillis() >= validFrom, "the transaction must not open before the feed");
        assertTrue(start.startMillis() <= anchor, "and never ahead of the chain");
        assertEquals(millisOfSlot(slotOf(start.startMillis())), start.startMillis(), "slot-aligned");
        assertTrue(start.startMillis() > baseStartMillis(anchor - BACKDATE),
                "the base would have started at the floor, before the feed opened");
        assertTrue(start.raisedByFeed());
        assertEquals(anchor, start.anchorMillis());
    }

    /** F exactly at the anchor is still buildable: the start is the anchor itself. */
    @Test
    void aFeedOpeningExactlyAtTheAnchorStartsAtTheAnchor() {
        long committedSlot = slotOf(T);
        long anchor = millisOfSlot(committedSlot);

        LiquidationStartClamp.Start start = start(LiquidationStartClamp.clamp(T + 5_000L, committedSlot,
                BACKDATE, List.of(feed(TOKEN, anchor)), CONVERTERS));

        assertEquals(anchor, start.startMillis());
    }

    // ---- not yet valid ------------------------------------------------------------------------------

    /** F after the anchor: no start is both ≥ F and ≤ the anchor, so nothing is built this pass. */
    @Test
    void aFeedOpeningAfterTheAnchorIsNotYetValid() {
        long committedSlot = slotOf(T);
        long anchor = millisOfSlot(committedSlot);
        long validFrom = anchor + 2_000L;

        LiquidationStartClamp.NotYetValid notYet = notYet(LiquidationStartClamp.clamp(T + 20_000L,
                committedSlot, BACKDATE, List.of(feed(TOKEN, validFrom)), CONVERTERS));

        assertEquals(validFrom, notYet.newestValidFromMillis());
        assertEquals(anchor, notYet.anchorMillis());
        assertEquals(2_000L, notYet.gapMillis());
        assertEquals(validFrom, notYet.firstSlotMillis(), "already a whole slot");
    }

    /**
     * The edge the round-up creates: the committed slot is unknown, so the anchor is a mid-slot wall clock,
     * and a feed opening just BEFORE it rounds up to a slot just AFTER it. That start would be ahead of the
     * chain, so it is not-yet-valid even though F ≤ anchor.
     */
    @Test
    void withAnUnknownCommittedSlotAFeedWhoseFirstSlotPassesNowIsNotYetValid() {
        long now = T + 500L;
        long validFrom = T + 200L;   // ≤ now, but its first whole slot is T + 1 000 > now

        LiquidationStartClamp.NotYetValid notYet = notYet(LiquidationStartClamp.clamp(now, 0L, BACKDATE,
                List.of(feed(TOKEN, validFrom)), CONVERTERS));

        assertEquals(now, notYet.anchorMillis());
        assertEquals(validFrom, notYet.newestValidFromMillis());
        assertEquals(T + 1_000L, notYet.firstSlotMillis());
        assertTrue(notYet.gapMillis() <= 0, "the feed itself opened before the anchor; its slot did not");
    }

    // ---- which feeds count --------------------------------------------------------------------------

    /** Two legs: the newer validFrom decides, whichever order they arrive in. */
    @Test
    void withTwoLegsTheNewerValidFromDecides() {
        long committedSlot = slotOf(T);
        long anchor = millisOfSlot(committedSlot);
        OraclePriceFeed older = feed(TOKEN, anchor - 25_300L);
        OraclePriceFeed newer = feed(OTHER_TOKEN, anchor - 7_700L);

        for (List<OraclePriceFeed> legs : List.of(List.of(older, newer), List.of(newer, older))) {
            LiquidationStartClamp.Start start = start(LiquidationStartClamp.clamp(T + 20_000L, committedSlot,
                    BACKDATE, legs, CONVERTERS));
            assertEquals(anchor - 7_000L, start.startMillis(), "the max of the two, rounded up");
        }
    }

    /**
     * A synthesised ada unit feed has no window on chain ({@code retrieve_oracle_data} returns it before any
     * window check), so it is ignored — even one carrying a validFrom past the anchor, which would otherwise
     * make the candidate not-yet-valid. Nulls are ignored too.
     */
    @Test
    void anAdaUnitFeedIsIgnored() {
        long committedSlot = slotOf(T);
        long anchor = millisOfSlot(committedSlot);
        OraclePriceFeed adaWithAFutureWindow = new OraclePriceFeed(OraclePriceFeed.Variant.AGGREGATED,
                AssetType.ada(), BigInteger.ONE, BigInteger.ONE, anchor + 5_000L, anchor + 600_000L);
        assertTrue(adaWithAFutureWindow.isSynthesisedUnitFeed(), "the fixture must be the ada unit shape");

        LiquidationStartClamp.Start start = start(LiquidationStartClamp.clamp(T + 20_000L, committedSlot,
                BACKDATE, Arrays.asList(adaWithAFutureWindow, OraclePriceFeed.unit(), null), CONVERTERS));

        assertEquals(baseStartMillis(anchor - BACKDATE), start.startMillis());
        assertFalse(start.raisedByFeed());
    }
}
