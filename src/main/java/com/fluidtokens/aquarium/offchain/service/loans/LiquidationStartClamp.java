package com.fluidtokens.aquarium.offchain.service.loans;

import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import org.cardanofoundation.conversions.CardanoConverters;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;

/**
 * <b>Where a liquidation's validity interval starts: never before its oracle feeds open, never ahead of
 * the chain</b> (FAB-183).
 *
 * <h2>Why the start has a lower bound other than the backdate</h2>
 * {@code lib/fluidtokens/oracle.ak} enforces the feed window as a RANGE, at both ends: a feed is usable
 * only if {@code valid_from <= transactionValidFrom} and {@code valid_to >= transactionValidTo}. A start
 * fixed at {@code anchor − 30 s} therefore refuses a freshly pushed price — one whose {@code validFrom}
 * lies inside the last 30 seconds — until a block lands at least 30 seconds after it. Moving the start up
 * to the newest {@code validFrom} the transaction carries makes that feed usable as soon as the chain has
 * reached it.
 *
 * <h2>Computation</h2>
 * <pre>
 *   anchor    = LiquidationExecutor.windowAnchorMillis(now, committedSlot)   // min(now, committed block)
 *   requested = max(anchor − backdate, newest validFrom of the non-ADA legs)
 *   start     = the time of the FIRST slot whose start is ≥ requested       // rounded UP, never down
 *   start > anchor  ⇒  not yet valid: building now would put invalidBefore ahead of the chain
 * </pre>
 * The round-up is what keeps {@code start >= validFrom}: a slot rounded DOWN would open the transaction
 * before the feed and be refused on chain. The handed start is slot-aligned, so each route's inward
 * {@code slotFrom} clamp has nothing left to correct.
 *
 * <p>The synthesised 1:1 ada feed has no window on chain ({@code retrieve_oracle_data} returns it before
 * any window check), so it is ignored; so is a missing feed, which the builders refuse by name.
 *
 * <p>Pure: static methods only, no I/O, no clock.
 */
public final class LiquidationStartClamp {

    private LiquidationStartClamp() {
    }

    /** What the clamp decided for one candidate. */
    public sealed interface Result permits Start, NotYetValid {
    }

    /**
     * The transaction may be built starting at {@code startMillis}, a slot-aligned millisecond that is
     * at or before {@code anchorMillis}.
     *
     * @param raisedByFeed true when a feed's {@code validFrom}, not the backdate, set the start
     */
    public record Start(long startMillis, long anchorMillis, boolean raisedByFeed) implements Result {
    }

    /**
     * The newest feed opens after the chain's position: no start is both ≥ its {@code validFrom} and
     * ≤ the anchor, so nothing can be built in this pass.
     *
     * @param firstSlotMillis the start of the first whole slot at or after {@code newestValidFromMillis} —
     *                        always after the anchor. It is what decides, not the feed's own millisecond:
     *                        with an unknown committed slot the anchor is a non-slot-aligned wall clock,
     *                        and a feed opening just before it can still round up past it, in which case
     *                        {@link #gapMillis()} is zero or negative.
     */
    public record NotYetValid(long newestValidFromMillis, long anchorMillis, long firstSlotMillis)
            implements Result {

        /** How far the newest feed's opening lies past the anchor, in milliseconds. */
        public long gapMillis() {
            return newestValidFromMillis - anchorMillis;
        }
    }

    /**
     * @param nowMillis      wall clock
     * @param committedSlot  the last committed block's slot; {@code 0} means unknown (wall clock is used)
     * @param backdateMillis how far before the anchor the start may lie at the earliest
     * @param legFeeds       the feeds of the transaction's non-ADA legs; ada unit feeds and nulls are ignored
     */
    public static Result clamp(long nowMillis, long committedSlot, long backdateMillis,
                               Collection<OraclePriceFeed> legFeeds, CardanoConverters converters) {
        long anchor = LiquidationExecutor.windowAnchorMillis(nowMillis, committedSlot, converters);
        long floor = anchor - backdateMillis;

        boolean anyFeed = false;
        long newestValidFrom = Long.MIN_VALUE;
        if (legFeeds != null) {
            for (OraclePriceFeed feed : legFeeds) {
                if (feed == null || feed.isSynthesisedUnitFeed()) {
                    continue;
                }
                anyFeed = true;
                newestValidFrom = Math.max(newestValidFrom, feed.validFrom());
            }
        }
        boolean raisedByFeed = anyFeed && newestValidFrom > floor;
        long requested = raisedByFeed ? newestValidFrom : floor;

        long startMillis = firstSlotTimeAtOrAfter(requested, converters);
        if (startMillis > anchor) {
            return new NotYetValid(anyFeed ? newestValidFrom : requested, anchor, startMillis);
        }
        return new Start(startMillis, anchor, raisedByFeed);
    }

    /** The start time of the first slot whose start is ≥ {@code millis}. */
    private static long firstSlotTimeAtOrAfter(long millis, CardanoConverters converters) {
        long slot = converters.time().toSlot(utc(millis));
        if (millisOf(converters.slot().slotToTime(slot)) < millis) {
            slot += 1;
        }
        return millisOf(converters.slot().slotToTime(slot));
    }

    private static LocalDateTime utc(long millis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC);
    }

    private static long millisOf(LocalDateTime time) {
        return time.toInstant(ZoneOffset.UTC).toEpochMilli();
    }
}
