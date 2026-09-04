package com.fluidtokens.aquarium.offchain.service.loans;

import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationDecision;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationExclusion;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The liquidation loop's observation window: the last run's summary, plus a bounded ring buffer of
 * the most recent {@link LiquidationDecision}s, newest first.
 *
 * <h2>In memory, and gone at restart</h2>
 * This survives nothing. A restart empties it, and the endpoint then reports an empty log until the
 * next cycle runs — which, at the default delay, is under a minute.
 * <p>
 * That is a deliberate trade, not an oversight. Persisting it would mean a JPA entity and a Flyway
 * migration of this repo's own, and this repo has neither: {@code spring.flyway.locations} is left
 * at the Yaci Store starter's own default, so the node's schema is entirely the indexer's and this
 * app contributes not one migration to it.
 * Adding a location would change the schema management of <em>every</em> operator's node, mainnet
 * included, where lending is disabled entirely and this class does not even exist. A diagnostic
 * ring buffer for a preview-only shadow bot does not justify that blast radius. If durable
 * decisions are ever wanted, that is a deliberate schema decision to take on its own merits, not a
 * side effect of wanting a nicer endpoint.
 *
 * <h2>Thread safety</h2>
 * The scheduler writes and an HTTP thread reads, so every access is under this object's monitor and
 * readers get a copy rather than a live view.
 */
@Service
@Slf4j
@ConditionalOnProperty(prefix = "loans", name = "enabled", havingValue = "true")
public class LiquidationDecisionLog {

    /**
     * What one cycle saw, whether or not it built anything.
     *
     * @param at            epoch millis the run started, or {@code null} if no run has finished yet
     * @param bondsScanned  bonds with a LIVE loan — the population a liquidator can act on. ⚠ Since
     *                      T-060 this EXCLUDES bonds whose loan no longer exists; those are counted
     *                      in {@code settled} instead, and the two still reconcile to what the
     *                      scanner saw
     * @param settled       bonds whose loan is gone. <b>Reported, not suppressed:</b> a bond outliving
     *                      its loan is the ordinary post-settlement state and belongs nowhere near
     *                      the exclusions, but dropping the number entirely would make "the market
     *                      moved on" and "we stopped being able to see them" look identical
     * @param buildable     how many live-loan bonds the scanner passed
     * @param unreadable    ⛔ <b>THE NUMBER THAT SEPARATES "CORRECTLY EMPTY" FROM "BLIND", and it was
     *                      not on this endpoint until 2026-09-04.</b> Loan-bearing UTxOs at the loan
     *                      credential that this node could NOT decode. {@code settled} asserts those
     *                      bonds' loans are GONE; this is the only thing that can contradict it, by
     *                      showing a loan that is present and simply not legible to us — the loans
     *                      decoder is hand-written, so it is a live risk rather than a theoretical
     *                      one. <b>While it is non-zero the settled count is not trustworthy either.</b>
     *                      <p>It existed only in a log line, so an operator reading this endpoint —
     *                      which the readiness page's own empty-state message points them at — could
     *                      not settle the question the page raised.
     * @param exclusions    why the rest were dropped, one count per reason
     */
    public record RunSummary(Long at,
                             int bondsScanned,
                             int settled,
                             int buildable,
                             int unreadable,
                             Map<LiquidationExclusion, Integer> exclusions) {

        public static RunSummary empty() {
            return new RunSummary(null, 0, 0, 0, 0, Map.of());
        }
    }

    private final int capacity;

    /** Newest at the head. Bounded by {@link #capacity}; the oldest is evicted first. */
    private final Deque<LiquidationDecision> decisions = new ArrayDeque<>();

    private RunSummary lastRun = RunSummary.empty();

    public LiquidationDecisionLog(AppConfig.LiquidationConfiguration configuration) {
        // A zero or negative size would make record() a no-op and leave the endpoint permanently
        // blank with no explanation, so it is clamped rather than honoured.
        this.capacity = Math.max(1, configuration.getDecisionLogSize());
    }

    /** Records one cycle's summary, replacing the previous one. */
    public synchronized void recordRun(long at, int bondsScanned, int settled, int buildable,
                                       int unreadable,
                                       Map<LiquidationExclusion, Integer> exclusions) {
        this.lastRun = new RunSummary(at, bondsScanned, settled, buildable, unreadable,
                Map.copyOf(exclusions == null ? new EnumMap<>(LiquidationExclusion.class) : exclusions));
    }

    /** Adds one decision, evicting the oldest once the buffer is full. */
    public synchronized void record(LiquidationDecision decision) {
        decisions.addFirst(decision);
        while (decisions.size() > capacity) {
            decisions.removeLast();
        }
    }

    /** The last run's summary; never null, empty until the first cycle completes. */
    public synchronized RunSummary lastRun() {
        return lastRun;
    }

    /** The most recent decisions, newest first, at most {@code limit} of them. */
    public synchronized List<LiquidationDecision> newestFirst(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<LiquidationDecision> page = new ArrayList<>(Math.min(limit, decisions.size()));
        for (LiquidationDecision decision : decisions) {
            if (page.size() == limit) {
                break;
            }
            page.add(decision);
        }
        return List.copyOf(page);
    }

    /** How many decisions are held right now. */
    public synchronized int size() {
        return decisions.size();
    }

    public int capacity() {
        return capacity;
    }
}
