package com.fluidtokens.aquarium.offchain.service.loans;

import com.fluidtokens.aquarium.offchain.model.loans.LiquidationAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.UnservableMarket;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ⛔ <b>The bot says which market it cannot serve, by name.</b>
 *
 * <h2>What it is for</h2>
 * Giovanni: <i>"assume a new stable coin loan appears and I wouldn't be able to process that loan if
 * it went sour, well I would need to know"</i> — and, for how: <i>"a nice prometheus metric with
 * labels and the label being the asset name … we could add an alert and if there is anything it
 * trigger paging"</i>, with <i>"a WARN in the logs would be a good starting point"</i>.
 * <p>
 * So there are two outputs and they have different jobs. <b>The gauge is what wakes someone</b> —
 * an alert rule fires on {@code loans_market_unservable == 1} and the market is in the label.
 * <b>The log is for the human reading afterwards</b>, and carries the scanner's own sentence about
 * why, which the label deliberately does not.
 *
 * <h2>⚠ It observes; it decides nothing</h2>
 * Everything below is derived from {@link LiquidationAssessment}s the scanner already produced.
 * Before this class existed, {@code LiquidationExecutor} collapsed them into a histogram keyed by
 * reason and <b>the asset identity was gone by the time anything was logged</b> — so this is not new
 * analysis, it is declining to discard something already computed. No liquidation decision reads
 * anything here, and {@code LiquidationExecutor} calls {@link #report} inside its own guard so a
 * fault in observation cannot cost a cycle.
 *
 * <h2>⛔ CARDINALITY IS BOUNDED BY CONSTRUCTION, and that is not a detail</h2>
 * A metric label whose values come from chain data is an unbounded label unless something bounds
 * it: anyone can lock a lender bond naming an arbitrary asset as its principal, so "markets seen"
 * is attacker-influenced, not a closed set of tens. An unbounded label is how a metrics backend
 * falls over, which is worse than no metric at all.
 * <p>
 * Hence {@link #MAX_TRACKED_SERIES}: past that many distinct {@code (market, reason)} pairs, <b>no
 * further series is created</b>. The overflow is not silent — {@code loans_market_unservable_untracked}
 * counts the pairs being refused a series, so the reader can tell "nothing more is wrong" from "we
 * stopped saying". Same shape and same reason as {@code LiquidationExecutor.MAX_QUARANTINED}.
 *
 * <h2>Transitions, not repetitions</h2>
 * The cycle runs every {@code loans.liquidation.delay-seconds}. Logging every unservable market
 * every cycle would bury the line that matters under thousands of identical ones, so a WARN is
 * emitted when a pair <b>becomes</b> unservable and an INFO when it stops. The gauge, not the log,
 * is the thing that is true continuously — which is exactly the division of labour Giovanni asked
 * for.
 * <p>
 * ⚠ <b>A recovered market's series stays, holding 0.</b> Deleting it would make "servable again" and
 * "we stopped looking" the same observation, and an alert rule cannot tell those apart.
 *
 * <h2>⚠ Known limitation: an oracle blackout looks like an unservable market</h2>
 * {@code PRINCIPAL_ORACLE_UNUSABLE} covers both "this feed is a variant we do not model" (permanent,
 * and the thing worth paging on) and "this feed's validity window does not cover now" (transient —
 * preview has a real ~60–80s blackout every 5 minutes, upstream of us). The scanner reports one
 * reason for both, so this gauge flaps for the second case. <b>The WARN's {@code detail} tells them
 * apart</b> ({@code "no oracle entry for …"} / {@code "not usable for liquidation"} versus
 * {@code "outside its validity window at …"}), and an alert rule wanting only the permanent case
 * should hold the condition for longer than a blackout. Distinguishing them in the metric itself
 * needs a structured sub-reason on the scanner's side, which is a decision this slice does not make.
 */
@Component
@Slf4j
public class MarketCoverageReporter {

    /**
     * The name an alert rule matches on. Micrometer renders it as {@code loans_market_unservable}
     * in the Prometheus exposition; {@code 1} means the bot met a loan in that market it cannot
     * process, {@code 0} means it did not on the most recent scan.
     */
    static final String UNSERVABLE = "loans.market.unservable";

    /** How many pairs were refused a series by {@link #MAX_TRACKED_SERIES} on the last scan. */
    static final String UNTRACKED = "loans.market.unservable.untracked";

    /**
     * The cardinality ceiling: at most this many distinct {@code (market, reason)} series. Generous
     * against reality — markets are tens and there are four unservable reasons — and finite against
     * an adversary, which is the only property that matters here.
     */
    static final int MAX_TRACKED_SERIES = 256;

    private final MeterRegistry registry;

    /** One {@link AtomicInteger} per registered series; also the bound's counter. */
    private final Map<UnservableMarket, AtomicInteger> series = new ConcurrentHashMap<>();

    private final AtomicInteger untracked = new AtomicInteger();

    public MarketCoverageReporter(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        Gauge.builder(UNTRACKED, untracked, AtomicInteger::doubleValue)
                .description("Distinct (market, reason) pairs the bot could not serve and could not "
                        + "give a metric series to, because the cardinality bound was reached")
                .register(registry);
    }

    /**
     * Re-read one scan's verdicts and publish the ones that say "we cannot serve this market".
     *
     * <p>Called once per liquidation cycle with <b>every</b> assessment, including the buildable
     * ones — the servable majority is what makes a series fall back to 0.
     */
    public void report(Collection<LiquidationAssessment> assessments) {
        SortedMap<UnservableMarket, String> unservable = UnservableMarket.seenIn(assessments);
        Set<UnservableMarket> stillUnservable = unservable.keySet();

        // Recovery first, so a market that changed reason reads as one line clearing and another
        // firing rather than as two simultaneous truths.
        for (Map.Entry<UnservableMarket, AtomicInteger> tracked : series.entrySet()) {
            if (stillUnservable.contains(tracked.getKey()) || tracked.getValue().getAndSet(0) == 0) {
                continue;
            }
            log.info("market {} is servable again — {} no longer applies; gauge {} is back to 0 "
                            + "for market={} reason={}",
                    tracked.getKey().market(), tracked.getKey().reason(), UNSERVABLE,
                    tracked.getKey().market(), tracked.getKey().reason());
        }

        int refused = 0;
        for (Map.Entry<UnservableMarket, String> gap : unservable.entrySet()) {
            AtomicInteger value = seriesFor(gap.getKey());
            if (value == null) {
                refused++;
                continue;
            }
            if (value.getAndSet(1) == 1) {
                continue;
            }
            log.warn("⛔ UNSERVABLE MARKET {} — {} ({}). A market is the token for the principal, "
                            + "and the bot met a loan in this one it cannot process as it stands. "
                            + "Gauge {} is now 1 for market={} reason={}",
                    gap.getKey().market(), gap.getKey().reason(), gap.getValue(), UNSERVABLE,
                    gap.getKey().market(), gap.getKey().reason());
        }

        if (untracked.getAndSet(refused) == 0 && refused > 0) {
            log.warn("⛔ {} unservable (market, reason) pair(s) have no metric series: the {} bound "
                            + "of {} series is reached. They are counted in {} and nowhere else — "
                            + "this bot is now reporting LESS than it knows.",
                    refused, UNSERVABLE, MAX_TRACKED_SERIES, UNTRACKED);
        }
    }

    /**
     * The series for one pair, creating it on first sight — or {@code null} once
     * {@link #MAX_TRACKED_SERIES} is reached, which is the whole cardinality bound.
     */
    private AtomicInteger seriesFor(UnservableMarket market) {
        AtomicInteger existing = series.get(market);
        if (existing != null) {
            return existing;
        }
        if (series.size() >= MAX_TRACKED_SERIES) {
            return null;
        }
        AtomicInteger value = new AtomicInteger();
        series.put(market, value);
        Gauge.builder(UNSERVABLE, value, AtomicInteger::doubleValue)
                .description("1 when the bot met a loan whose principal is this token and could not "
                        + "process it; 0 when it did not on the most recent scan")
                .tag("market", market.market())
                .tag("reason", market.reason().name())
                .register(registry);
        return value;
    }
}
