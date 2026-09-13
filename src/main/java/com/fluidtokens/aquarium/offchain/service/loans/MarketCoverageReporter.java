package com.fluidtokens.aquarium.offchain.service.loans;

import com.fluidtokens.aquarium.offchain.model.loans.LiquidationAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.UnservableMarket;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
 * <h2>⛔ CARDINALITY IS BOUNDED, AND THE BOUND IS RECLAIMABLE — that distinction is the whole thing</h2>
 * A metric label whose values come from chain data is an unbounded label unless something bounds
 * it: anyone can lock a lender bond naming an arbitrary asset as its principal, so "markets seen"
 * is attacker-influenced, not a closed set of tens. An unbounded label is how a metrics backend
 * falls over, which is worse than no metric at all.
 * <p>
 * {@link #MAX_TRACKED_SERIES} caps how many {@code (market, leg, reason)} triples hold a series
 * <b>at once</b>. It is <b>not</b> a lifetime quota, and the difference is not academic: a ceiling
 * that only ever fills is a one-shot, ~1000-ada switch-off of an operator's market alerting for the
 * life of the process — roughly 256 min-ada junk bonds and the bot goes quiet forever, and
 * {@code LenderBondService.findAll()} returns every bond at the credential, not only this
 * operator's. So the bound behaves like {@code LiquidationExecutor.MAX_QUARANTINED}, whose two
 * mechanisms this mirrors:
 * <ul>
 *   <li><b>eviction</b> — at the ceiling a newcomer is admitted by dropping a series that is
 *       currently reading {@code 0} (the one that has read 0 longest), never one reading {@code 1}
 *       and never one that is unservable on this very cycle. A recovered market losing its series
 *       is the cheapest thing to give up; an active alert is the most expensive;</li>
 *   <li><b>refusal is loud AND named</b> — only when all {@link #MAX_TRACKED_SERIES} series are
 *       simultaneously reading {@code 1} is there nothing to evict. That is already an emergency,
 *       and the newcomer still gets <b>a WARN naming it</b> ({@link #UNTRACKED} counts them).
 *       Logs are not a cardinality-limited medium, so the floor Giovanni asked for never depends on
 *       a metric slot being free.</li>
 * </ul>
 * In markets rather than triples: one asset can occupy at most four series (three principal-leg
 * reasons plus one collateral-leg one), so {@link #MAX_TRACKED_SERIES} guarantees at least 64
 * distinct assets and in practice tracks 256.
 *
 * <h2>Transitions, not repetitions — and not blackouts either</h2>
 * The cycle runs every {@code loans.liquidation.delay-seconds} (default 60). Logging every
 * unservable market every cycle would bury the line that matters under thousands of identical ones,
 * so a WARN is emitted when a triple <b>becomes</b> unservable and an INFO when it stops.
 * <p>
 * ⚠ <b>A transition alone is not enough, because the oracle blackout IS a transition.</b> Preview
 * has a real ~60–80 s price blackout every five minutes, upstream of us, which at the default cycle
 * makes a non-ada market flip unservable and back roughly 288 times a day. A WARN on each flip turns
 * Giovanni's floor into noise on the first network the bot runs on. So the condition must hold for
 * the cadence-derived number from {@link #warnAfterCyclesFor(long)} of <b>consecutive</b> cycles
 * before the first WARN, and the matching INFO is emitted only if that WARN was reached — a
 * blackout that never warned never recovers out loud either.
 * <p>
 * <b>The gauge still goes to 1 on the first cycle.</b> It is the continuously-true thing, and an
 * alert rule holds it with its own {@code for:} clause; the log is the thing that cannot be held
 * after the fact.
 *
 * <p>⚠ <b>A recovered market's series stays, holding 0</b> until the bound needs its slot. Deleting
 * it eagerly would make "servable again" and "we stopped looking" the same observation, and an alert
 * rule cannot tell those apart. (Telling <em>absence</em> from "the bot never scanned" needs a
 * heartbeat, which is ticketed separately — always-present zeros would blow the bound above.)
 *
 * <h2>⚠ Known limitation: a permanently unsupported feed and a stale one share one reason</h2>
 * {@code PRINCIPAL_ORACLE_UNUSABLE} covers both "this feed is a variant we do not model" (permanent,
 * and the thing worth paging on) and "this feed's validity window does not cover now" (transient).
 * The consecutive-cycle gate above is what keeps the second out of the log; distinguishing them in
 * the metric itself needs a structured sub-reason on the scanner's side, which is a decision this
 * slice does not make.
 */
@Component
@Slf4j
public class MarketCoverageReporter {

    /**
     * The name an alert rule matches on. Micrometer renders it as {@code loans_market_unservable}
     * in the Prometheus exposition; {@code 1} means the bot met a loan whose {@code leg} is this
     * token and could not process it, {@code 0} means it did not on the most recent scan.
     */
    static final String UNSERVABLE = "loans.market.unservable";

    /** How many triples were refused a series by {@link #MAX_TRACKED_SERIES} on the last scan. */
    static final String UNTRACKED = "loans.market.unservable.untracked";

    /**
     * The cardinality ceiling: at most this many {@code (market, leg, reason)} series at once.
     * Generous against reality — markets are tens and there are four unservable reasons — and finite
     * in this JVM. It is <b>not</b> finite in the metrics backend: eviction removes the local meter,
     * but Prometheus retains every distinct historical label set for its retention period, and the
     * {@code market} label is derived from on-chain data. That cardinality-retention residue is
     * backlogged; this bound, its eviction policy and their behaviour are unchanged here.
     */
    static final int MAX_TRACKED_SERIES = 256;

    /**
     * Measured upper end of preview's ~60–80 s Charli3 feed blackouts every five minutes, upstream
     * of this service (design §6.7–6.8). This is observed provenance, not a guessed tuning value.
     */
    static final int MAX_BLACKOUT_SECONDS = 80;

    /**
     * How many consecutive cycles a triple must stay unservable before the first WARN at the
     * default 60 s cadence. This is the value {@link #warnAfterCyclesFor(long)} derives there.
     *
     * <p>Overridable with
     * {@link #WARN_AFTER_CYCLES_PROPERTY} — set it to 1 to have the log follow the gauge exactly, on
     * a network with no blackout.
     */
    static final int DEFAULT_WARN_AFTER_CYCLES = 3;

    /** Operator override; zero or absence selects the cadence-derived WARN gate. */
    static final String WARN_AFTER_CYCLES_PROPERTY = "loans.market-coverage.warn-after-cycles";

    /**
     * Derives the first consecutive-cycle count that one measured blackout cannot reach.
     *
     * <p>A blackout of {@code D} seconds, observed by scans at spacing {@code C}, falls on at most
     * {@code floor(D/C) + 1} consecutive scans: an arithmetic progression of spacing {@code C}
     * places at most that many points inside an interval of length {@code D}. Therefore
     * {@code floor(D/C) + 2} is the first count a single blackout cannot reach. Spring's
     * {@code @Scheduled(fixedDelay...)} measures the gap between completions, so the real period is
     * at least {@code C}; this derivation consequently errs toward fewer observations, which is the
     * conservative direction. A non-positive cadence falls back to the production default rather
     * than producing a nonsensical debounce or preventing the node from booting.
     */
    static int warnAfterCyclesFor(long delaySeconds) {
        if (delaySeconds <= 0) {
            return DEFAULT_WARN_AFTER_CYCLES;
        }
        return (int) (MAX_BLACKOUT_SECONDS / delaySeconds) + 2;
    }

    private final MeterRegistry registry;

    private final int warnAfterCycles;

    /** One {@link Coverage} per registered series; also the bound's counter. */
    private final Map<UnservableMarket, Coverage> series = new ConcurrentHashMap<>();

    private final AtomicInteger untracked = new AtomicInteger();

    /**
     * Monotonic cycle number, used only to order eviction candidates ("which series has read 0
     * longest"). Not published anywhere.
     */
    private long cycle;

    @Autowired
    public MarketCoverageReporter(MeterRegistry registry,
                                  @Value("${loans.market-coverage.warn-after-cycles:0}")
                                  int warnAfterCycles,
                                  @Value("${loans.liquidation.delay-seconds:60}")
                                  long delaySeconds) {
        this.registry = Objects.requireNonNull(registry, "registry");
        // Clamped rather than rejected: a nonsensical value here must not stop an operator's node
        // booting over a metric, and 1 is the strictest thing the gate can mean.
        int effectiveWarnAfterCycles = warnAfterCycles == 0
                ? warnAfterCyclesFor(delaySeconds)
                : warnAfterCycles;
        this.warnAfterCycles = Math.max(1, effectiveWarnAfterCycles);
        Gauge.builder(UNTRACKED, untracked, AtomicInteger::doubleValue)
                .description("Distinct (market, leg, reason) triples the bot could not serve and "
                        + "could not give a metric series to, because every series was already "
                        + "reading 1 at the cardinality bound")
                .register(registry);
    }

    /** Test seam; 60 s is the production-default cadence used when Spring does not supply one. */
    MarketCoverageReporter(MeterRegistry registry) {
        this(registry, 0, 60);
    }

    /**
     * Re-read one scan's verdicts and publish the ones that say "we cannot serve this market".
     *
     * <p>Called once per liquidation cycle with <b>every</b> assessment, including the buildable
     * ones — the servable majority is what makes a series fall back to 0.
     */
    public void report(Collection<LiquidationAssessment> assessments) {
        long now = ++cycle;
        SortedMap<UnservableMarket, String> unservable = UnservableMarket.seenIn(assessments);
        Set<UnservableMarket> stillUnservable = unservable.keySet();

        // Recovery first, so a market that changed reason reads as one line clearing and another
        // firing rather than as two simultaneous truths — and so a slot freed this cycle is
        // available to a newcomer in the loop below.
        for (Map.Entry<UnservableMarket, Coverage> tracked : series.entrySet()) {
            if (stillUnservable.contains(tracked.getKey())) {
                continue;
            }
            Coverage coverage = tracked.getValue();
            coverage.unservableCycles = 0;
            if (coverage.gauge.getAndSet(0) == 0) {
                continue;
            }
            coverage.zeroSince = now;
            if (coverage.warned) {
                coverage.warned = false;
                log.info("market {} ({} leg) is servable again — {} no longer applies; gauge {} is "
                                + "back to 0 for market={} leg={} reason={}",
                        tracked.getKey().market(), tracked.getKey().leg().label(),
                        tracked.getKey().reason(), UNSERVABLE, tracked.getKey().market(),
                        tracked.getKey().leg().label(), tracked.getKey().reason());
            }
        }

        int refused = 0;
        for (Map.Entry<UnservableMarket, String> gap : unservable.entrySet()) {
            Coverage coverage = seriesFor(gap.getKey(), stillUnservable, now);
            if (coverage == null) {
                // ⛔ NO SERIES, BUT NEVER NO WARNING. The bound is on metric series; the log has no
                // such ceiling, and Giovanni's WARN is the floor of this slice. Refusing both is how
                // a genuine new unservable market becomes invisible — which is the failure the whole
                // slice exists to end. Un-deduplicated on purpose: with nothing to evict there is
                // nowhere to keep "already said", and every series reading 1 at once is an emergency
                // in which loud beats tidy.
                refused++;
                warn(gap.getKey(), gap.getValue(), " ⚠ IT HAS NO METRIC SERIES: all "
                        + MAX_TRACKED_SERIES + " are already reading 1, so this market is named in "
                        + "this log line and in no metric — see " + UNTRACKED);
                continue;
            }
            coverage.gauge.set(1);
            coverage.zeroSince = Long.MAX_VALUE;
            if (coverage.warned || ++coverage.unservableCycles < warnAfterCycles) {
                continue;
            }
            coverage.warned = true;
            warn(gap.getKey(), gap.getValue(), " Gauge " + UNSERVABLE + " is now 1 for market="
                    + gap.getKey().market() + " leg=" + gap.getKey().leg().label()
                    + " reason=" + gap.getKey().reason());
        }

        if (untracked.getAndSet(refused) == 0 && refused > 0) {
            log.warn("⛔ {} unservable (market, leg, reason) triple(s) have no metric series: all {} "
                            + "series of {} are reading 1 at once. They are counted in {} and named "
                            + "in the WARN lines above — this bot is now reporting LESS than it knows.",
                    refused, MAX_TRACKED_SERIES, UNSERVABLE, UNTRACKED);
        }
    }

    /** The one WARN shape, so "no series available" cannot quietly become a different sentence. */
    private void warn(UnservableMarket market, String detail, String tail) {
        log.warn("⛔ UNSERVABLE MARKET {} ({} leg) — {} ({}). {}{}",
                market.market(), market.leg().label(), market.reason(), detail,
                explain(market.leg()), tail);
    }

    /**
     * ⚠ The sentence has to follow the leg. "A market is the token for the principal" is Giovanni's
     * definition and is exactly right for a principal-side gap — and is a lie on a collateral-side
     * one, where the token named is the collateral and the principal is a different, servable
     * market. An operator reading the wrong sentence goes looking for the wrong loan.
     */
    private static String explain(UnservableMarket.Leg leg) {
        return switch (leg) {
            case PRINCIPAL -> "A market is the token for the principal, and the bot met a loan in "
                    + "this one it cannot process as it stands.";
            case COLLATERAL -> "This token is the COLLATERAL of a loan the bot cannot process as it "
                    + "stands; the loan's own principal is a different market and may be fine.";
        };
    }

    /**
     * The series for one triple, creating it on first sight — evicting a series that is currently
     * reading {@code 0} if the bound is in the way, and returning {@code null} only when every
     * series is reading {@code 1} and there is therefore nothing to give up.
     */
    private Coverage seriesFor(UnservableMarket market, Set<UnservableMarket> stillUnservable,
                               long now) {
        Coverage existing = series.get(market);
        if (existing != null) {
            return existing;
        }
        if (series.size() >= MAX_TRACKED_SERIES && !evictLongestZero(stillUnservable)) {
            return null;
        }
        Coverage coverage = new Coverage();
        coverage.meter = Gauge.builder(UNSERVABLE, coverage.gauge, AtomicInteger::doubleValue)
                .description("1 when the bot met a loan whose principal or collateral is this token "
                        + "and could not process it; 0 when it did not on the most recent scan")
                .tag("market", market.market())
                .tag("leg", market.leg().label())
                .tag("reason", market.reason().name())
                .register(registry);
        coverage.zeroSince = now;
        series.put(market, coverage);
        return coverage;
    }

    /**
     * Drops the series that has been reading {@code 0} longest, so a newly unservable market can
     * have its slot. Mirrors {@code LiquidationExecutor.quarantineUntil}'s "evict the entry closest
     * to expiry and admit the newcomer" — the difference being that a series reading {@code 1} is an
     * active alert and is never a candidate, and neither is one that is unservable on this cycle but
     * has not been raised to 1 yet.
     *
     * @return whether anything could be given up
     */
    private boolean evictLongestZero(Set<UnservableMarket> stillUnservable) {
        Map.Entry<UnservableMarket, Coverage> oldest = null;
        for (Map.Entry<UnservableMarket, Coverage> candidate : series.entrySet()) {
            if (candidate.getValue().gauge.get() != 0 || stillUnservable.contains(candidate.getKey())) {
                continue;
            }
            if (oldest == null || candidate.getValue().zeroSince < oldest.getValue().zeroSince) {
                oldest = candidate;
            }
        }
        if (oldest == null) {
            return false;
        }
        series.remove(oldest.getKey());
        registry.remove(oldest.getValue().meter);
        log.info("dropped the {} series for market={} leg={} reason={} — it has been reading 0 "
                        + "longest, and the {}-series bound needs its slot for a market that is "
                        + "unservable now",
                UNSERVABLE, oldest.getKey().market(), oldest.getKey().leg().label(),
                oldest.getKey().reason(), MAX_TRACKED_SERIES);
        return true;
    }

    /**
     * One tracked triple: the published value, the meter that publishes it (kept so eviction can
     * remove exactly that one), and the small amount of state the WARN gate needs.
     *
     * <p>⚠ Plain fields, not atomics: {@code report} is called from the single liquidation cycle
     * thread. Only {@link #gauge} is read from elsewhere — the metrics scrape thread — and that one
     * is an {@link AtomicInteger} for exactly that reason.
     */
    private static final class Coverage {

        private final AtomicInteger gauge = new AtomicInteger();

        private Gauge meter;

        /** Consecutive cycles this triple has been unservable; the WARN gate's counter. */
        private int unservableCycles;

        /** Whether the WARN has already been emitted for the current unservable episode. */
        private boolean warned;

        /** The cycle at which the gauge last fell to 0; {@code MAX_VALUE} while it reads 1. */
        private long zeroSince = Long.MAX_VALUE;
    }
}
