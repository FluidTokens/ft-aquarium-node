package com.fluidtokens.aquarium.offchain.service.loans;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.model.loans.LenderManagerDatum;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationExclusion;
import com.fluidtokens.aquarium.offchain.model.loans.UnservableMarket;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.prometheus.PrometheusMetricsExportAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <h2>Reviewer's orientation — what this class proves, and what it does not</h2>
 * <b>Proves:</b> that a market the bot cannot serve is NAMED — in a WARN and in a Prometheus gauge
 * label — that the label set is bounded, and that a healthy or settled market produces neither.
 * <b>Does NOT prove:</b> anything about liquidation. Nothing here builds, prices or submits a
 * transaction; the assessments are hand-built. That no decision changed is
 * {@link LiquidationExecutorTest}'s to carry, and it does
 * ({@code aThrowingReporterChangesNothingAboutTheTransactionThatIsRecorded}).
 * <b>Evaluator:</b> none needed — no transaction exists in this file.
 *
 * <h2>The failure being bought protection against</h2>
 * Giovanni, naming it: <i>"assume a new stable coin loan appears and I wouldn't be able to process
 * that loan if it went sour, well I would need to know."</i> Before this, the executor collapsed
 * every exclusion into a histogram keyed by reason, so the scan could say
 * {@code PRINCIPAL_ORACLE_UNUSABLE=1} and could not say which token. The tests below are written
 * against the token.
 */
class MarketCoverageReporterTest {

    private static final String USDM_POLICY = "c48cbb3d5e57ed56e276bc45f99ab39abe94e6cd7ac39fb402da47ad";

    /** {@code 0014df105553444d} — the CIP-68 (222) prefix plus {@code USDM}, as it appears on chain. */
    private static final String USDM_NAME = "0014df105553444d";

    private static final String USDM_UNIT = USDM_POLICY + USDM_NAME;

    // =====================================================================================
    // 1. the classification — which refusals mean "there is something here we cannot do"
    // =====================================================================================

    /**
     * ⛔ <b>The partition, pinned constant by constant.</b>
     *
     * <p>{@link UnservableMarket#isUnservable} is an exhaustive {@code switch} with no
     * {@code default}, so a new {@link LiquidationExclusion} constant cannot compile until someone
     * classifies it. That protects against forgetting; it does not protect against classifying a
     * new one wrongly, and it does not protect against the far worse direction — quietly widening
     * the true-set until {@code NOT_LIQUIDATABLE} (a HEALTHY loan, the commonest exclusion on a
     * working node) starts paging someone. This test is what stops both.
     */
    @Test
    void onlyTheFourRefusalsThatMeanWeCannotActAreReported() {
        assertEquals(11, LiquidationExclusion.values().length,
                "a LiquidationExclusion constant was added or removed: classify it in "
                        + "UnservableMarket.isUnservable and state it here, because a new reason "
                        + "defaulting to 'servable' is exactly the silence this metric exists to end");

        Set<LiquidationExclusion> unservable = EnumSet.noneOf(LiquidationExclusion.class);
        for (LiquidationExclusion reason : LiquidationExclusion.values()) {
            if (UnservableMarket.isUnservable(reason)) {
                unservable.add(reason);
            }
        }

        assertEquals(EnumSet.of(LiquidationExclusion.PRINCIPAL_ORACLE_UNUSABLE,
                        LiquidationExclusion.COLLATERAL_ORACLE_UNUSABLE,
                        LiquidationExclusion.HEALTH_NOT_COMPUTABLE,
                        LiquidationExclusion.CONVERSION_TO_PRINCIPAL_REQUIRED),
                unservable,
                "the reported set is 'we cannot act, or cannot even tell' — never 'there is nothing "
                        + "to do here'");
    }

    /**
     * The single most consequential half of that partition, stated as behaviour rather than as a
     * set: a healthy market and a settled one must produce <b>no series at all</b>, not a series
     * holding 0. A node whose loans are all fine publishes nothing here.
     */
    @Test
    void aHealthyOrSettledMarketProducesNoSeriesAndNoWarning() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        MarketCoverageReporter reporter = new MarketCoverageReporter(registry);
        List<ILoggingEvent> logged = capture();

        reporter.report(List.of(
                excluded(USDM_UNIT, LiquidationExclusion.NOT_LIQUIDATABLE,
                        "not late and currentLtv <= liquidationLtv"),
                excluded(USDM_UNIT, LiquidationExclusion.LOAN_NOT_FOUND,
                        "no loan under the loan policy shares bond asset name aa"),
                excluded("lovelace", LiquidationExclusion.MODE_NOT_LIQUIDATION,
                        "liquidationMode is NoLiquidationFullCollateralClaim"),
                buildable("lovelace")));

        assertFalse(registry.scrape().contains(UNSERVABLE_METRIC),
                "no market was unservable, so the metric must not exist at all: an always-present "
                        + "series reading 0 invites an alert rule that can never distinguish "
                        + "'nothing wrong' from 'nothing looked'");
        assertTrue(warnings(logged).isEmpty(), () -> "unexpected WARN: " + warnings(logged));
    }

    // =====================================================================================
    // 2. the two outputs Giovanni asked for
    // =====================================================================================

    /**
     * ⛔ <b>The deliverable.</b> A loan whose principal is USDM cannot be priced, so the bot cannot
     * process it — and it says so, naming USDM, in both places.
     *
     * <p>The gauge exposition is asserted as a whole line rather than by
     * {@code contains("USDM-ish")}: the label NAME, the label VALUE and the gauge VALUE are all
     * load-bearing for an alert rule, and a test that checked only the token would stay green if the
     * label were renamed or the sense of the gauge inverted.
     */
    @Test
    void anUnservableMarketIsNamedInAWarnAndInTheGaugeLabel() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        MarketCoverageReporter reporter = new MarketCoverageReporter(registry);
        List<ILoggingEvent> logged = capture();

        reporter.report(List.of(
                excluded(USDM_UNIT, LiquidationExclusion.PRINCIPAL_ORACLE_UNUSABLE,
                        "principal leg: no oracle entry for " + USDM_UNIT),
                buildable("lovelace")));

        assertEquals(1, warnings(logged).size(), () -> "one WARN, once: " + warnings(logged));
        assertEquals("⛔ UNSERVABLE MARKET " + USDM_UNIT + " — PRINCIPAL_ORACLE_UNUSABLE "
                        + "(principal leg: no oracle entry for " + USDM_UNIT + "). A market is the "
                        + "token for the principal, and the bot met a loan in this one it cannot "
                        + "process as it stands. Gauge loans.market.unservable is now 1 for market="
                        + USDM_UNIT + " reason=PRINCIPAL_ORACLE_UNUSABLE",
                warnings(logged).get(0),
                "the WARN must name the asset and carry the scanner's own reason for it");

        assertTrue(registry.scrape().contains(
                        "loans_market_unservable{market=\"" + USDM_UNIT
                                + "\",reason=\"PRINCIPAL_ORACLE_UNUSABLE\"} 1.0"),
                () -> "the gauge must be labelled by market and read 1; scrape was:\n" + registry.scrape());
    }

    /**
     * ⚠ <b>A recovered market keeps its series, holding 0.</b> Deleting it would make "servable
     * again" and "this bot stopped looking" the same observation, and no alert rule can tell those
     * apart. The INFO is what a human reading the log afterwards needs; the 0 is what the alert
     * needs.
     */
    @Test
    void aRecoveredMarketFallsBackToZeroRatherThanDisappearing() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        MarketCoverageReporter reporter = new MarketCoverageReporter(registry);

        reporter.report(List.of(excluded(USDM_UNIT, LiquidationExclusion.PRINCIPAL_ORACLE_UNUSABLE,
                "principal leg: oracle feed for " + USDM_UNIT + " is outside its validity window at 1")));
        List<ILoggingEvent> logged = capture();
        reporter.report(List.of(buildable(USDM_UNIT)));

        assertTrue(registry.scrape().contains(
                        "loans_market_unservable{market=\"" + USDM_UNIT
                                + "\",reason=\"PRINCIPAL_ORACLE_UNUSABLE\"} 0.0"),
                () -> "the series must survive at 0; scrape was:\n" + registry.scrape());
        assertTrue(warnings(logged).isEmpty(), () -> "recovery is not a WARN: " + warnings(logged));
        assertEquals(1, infos(logged).size(), () -> "recovery says so once: " + infos(logged));
        assertTrue(infos(logged).get(0).startsWith("market " + USDM_UNIT + " is servable again"),
                () -> infos(logged).get(0));
    }

    /**
     * ⛔ The cycle runs every minute forever. A WARN per cycle would bury the line that matters
     * under thousands of identical ones, so the log carries TRANSITIONS and the gauge carries the
     * state — which is the division of labour Giovanni described ("a WARN in the logs would be a
     * good starting point" for the human; "a prometheus metric … trigger paging" for the alert).
     */
    @Test
    void theWarnFiresOnTheTransitionAndNotOnEveryCycle() {
        MarketCoverageReporter reporter = new MarketCoverageReporter(new SimpleMeterRegistry());
        List<ILoggingEvent> logged = capture();

        LiquidationAssessment blind = excluded(USDM_UNIT,
                LiquidationExclusion.PRINCIPAL_ORACLE_UNUSABLE, "principal leg: oracle client disabled");
        reporter.report(List.of(blind));
        reporter.report(List.of(blind));
        reporter.report(List.of(blind));

        assertEquals(1, warnings(logged).size(),
                () -> "three cycles, one transition, one WARN: " + warnings(logged));
    }

    // =====================================================================================
    // 3. cardinality — the invariant that makes this metric safe to ship
    // =====================================================================================

    /**
     * ⛔ <b>THE DETAIL IS NOT PART OF THE KEY, AND THIS IS WHY.</b>
     *
     * <p>The scanner's detail for a stale feed embeds the instant it was asked about — "outside its
     * validity window at 1757500000000". Keying the metric on it would mint a fresh series every
     * single cycle, forever, for one market: the unbounded-label failure that takes a metrics
     * backend down. One market, one reason, two different details ⇒ exactly one series and one WARN.
     */
    @Test
    void aChangingDetailDoesNotMintANewSeries() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        MarketCoverageReporter reporter = new MarketCoverageReporter(registry);
        List<ILoggingEvent> logged = capture();

        reporter.report(List.of(excluded(USDM_UNIT, LiquidationExclusion.PRINCIPAL_ORACLE_UNUSABLE,
                "principal leg: oracle feed for x is outside its validity window at 1757500000000")));
        reporter.report(List.of(excluded(USDM_UNIT, LiquidationExclusion.PRINCIPAL_ORACLE_UNUSABLE,
                "principal leg: oracle feed for x is outside its validity window at 1757500060000")));

        assertEquals(1, seriesCount(registry),
                "the (market, reason) pair is the key; the detail only ever reaches a log line");
        assertEquals(1, warnings(logged).size(), () -> warnings(logged).toString());
    }

    /**
     * ⛔ <b>The label set is bounded BY CONSTRUCTION, not by optimism.</b>
     *
     * <p>Anyone can lock a lender bond naming an arbitrary asset as its principal, so "markets the
     * bot has seen" is attacker-influenced rather than a closed set of tens. Past
     * {@link MarketCoverageReporter#MAX_TRACKED_SERIES} no further series is created — and the
     * overflow is COUNTED, because a bot that quietly reports less than it knows is the failure this
     * whole slice exists to end.
     */
    @Test
    void pastTheBoundNoNewSeriesIsCreatedAndTheOverflowIsCounted() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        MarketCoverageReporter reporter = new MarketCoverageReporter(registry);
        List<ILoggingEvent> logged = capture();

        int flood = MarketCoverageReporter.MAX_TRACKED_SERIES + 50;
        List<LiquidationAssessment> spam = new ArrayList<>();
        for (int i = 0; i < flood; i++) {
            spam.add(excluded(String.format("%056x", i) + "6675",
                    LiquidationExclusion.PRINCIPAL_ORACLE_UNUSABLE, "principal leg: oracle client disabled"));
        }
        reporter.report(spam);

        assertEquals(MarketCoverageReporter.MAX_TRACKED_SERIES, seriesCount(registry),
                "the bound is the bound: an unbounded label brings a metrics backend down, which is "
                        + "worse than no metric");
        assertTrue(registry.scrape().contains("loans_market_unservable_untracked 50.0"),
                () -> "the refused pairs must be counted; scrape tail was:\n" + untrackedLine(registry));
        assertTrue(warnings(logged).stream().anyMatch(w -> w.contains("have no metric series")),
                () -> "reaching the bound must be said out loud: " + warnings(logged));
    }

    // =====================================================================================
    // 4. the wiring the metric depends on, which no unit test would otherwise touch
    // =====================================================================================

    /**
     * ⚠ <b>Is a {@code MeterRegistry} actually injectable here?</b> The slice was allowed to add no
     * dependency, so the whole design rests on the actuator starter already transitively providing
     * one. Nothing in this repo had ever asked for a {@code MeterRegistry} before, so that was an
     * assumption rather than a fact — and an assumption that, if wrong, fails at CONTAINER START
     * with every unit test green ({@code ContainerWiringTest}'s whole reason for existing).
     *
     * <p>This stands the relevant auto-configurations up and asks the container for the bean, the
     * same way production gets it.
     */
    @Test
    void theContainerCanBuildTheReporterFromTheAutoConfiguredRegistry() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MetricsAutoConfiguration.class,
                        CompositeMeterRegistryAutoConfiguration.class,
                        PrometheusMetricsExportAutoConfiguration.class))
                .withUserConfiguration(MarketCoverageReporter.class)
                .run(context -> {
                    assertFalse(context.getStartupFailure() != null,
                            () -> "context failed to start: " + context.getStartupFailure());
                    assertTrue(context.getBean(MeterRegistry.class) instanceof PrometheusMeterRegistry,
                            "the registry behind /actuator/prometheus is what the gauge must land in");
                    context.getBean(MarketCoverageReporter.class)
                            .report(List.of(excluded(USDM_UNIT,
                                    LiquidationExclusion.COLLATERAL_ORACLE_UNUSABLE,
                                    "collateral leg: oracle client disabled")));
                    assertTrue(context.getBean(PrometheusMeterRegistry.class).scrape().contains(
                                    "loans_market_unservable{market=\"" + USDM_UNIT
                                            + "\",reason=\"COLLATERAL_ORACLE_UNUSABLE\"} 1.0"),
                            "the container-built reporter must publish onto the scraped registry");
                });
    }

    // =====================================================================================
    // helpers
    // =====================================================================================

    private static final String UNSERVABLE_METRIC = "loans_market_unservable{";

    /** An excluded assessment whose bond names {@code marketUnit} as its principal. */
    private static LiquidationAssessment excluded(String marketUnit, LiquidationExclusion reason,
                                                  String detail) {
        return LiquidationAssessment.excluded(bond(marketUnit), null, reason, detail);
    }

    /** A buildable assessment in {@code marketUnit}: the servable majority a real scan is made of. */
    private static LiquidationAssessment buildable(String marketUnit) {
        return LiquidationAssessment.buildable(bond(marketUnit), null, "buildable liquidation",
                BigInteger.TEN, BigInteger.ZERO, true, BigInteger.ONE);
    }

    /**
     * Only {@code principalAsset} is read by anything under test — the market is the token for the
     * principal — so the rest of the bond is the cheapest thing that constructs.
     */
    private static LenderBond bond(String marketUnit) {
        AssetType principal = "lovelace".equals(marketUnit) ? AssetType.ada()
                : AssetType.fromUnit(marketUnit);
        return new LenderBond("00".repeat(32), 0, "addr_test1", "bond01", "d87980",
                new LenderManagerDatum(null, null, false, BigInteger.ZERO, "", principal));
    }

    private static int seriesCount(MeterRegistry registry) {
        return (int) StreamSupport.stream(registry.getMeters().spliterator(), false)
                .map(Meter::getId)
                .filter(id -> MarketCoverageReporter.UNSERVABLE.equals(id.getName()))
                .count();
    }

    private static String untrackedLine(PrometheusMeterRegistry registry) {
        return registry.scrape().lines()
                .filter(line -> line.startsWith("loans_market_unservable_untracked"))
                .reduce("", (a, b) -> a + b + "\n");
    }

    /** Attaches to the reporter's logger and returns the live event list, as the executor tests do. */
    private static List<ILoggingEvent> capture() {
        Logger logger = (Logger) LoggerFactory.getLogger(MarketCoverageReporter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.detachAndStopAllAppenders();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        return appender.list;
    }

    private static List<String> warnings(List<ILoggingEvent> events) {
        return events.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private static List<String> infos(List<ILoggingEvent> events) {
        return events.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
