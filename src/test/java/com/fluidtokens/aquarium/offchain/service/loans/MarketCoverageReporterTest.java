package com.fluidtokens.aquarium.offchain.service.loans;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.CollateralAsset;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.model.loans.LenderManagerDatum;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationExclusion;
import com.fluidtokens.aquarium.offchain.model.loans.Loan;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.model.loans.UnservableMarket;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.prometheus.PrometheusMetricsExportAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <h2>Reviewer's orientation — what this class proves, and what it does not</h2>
 * <b>Proves:</b> that a market the bot cannot serve is NAMED — in a WARN and in a Prometheus gauge
 * label, and named on the LEG that actually lacks a feed — that the label set is bounded and the
 * bound is RECLAIMABLE, that reaching it costs a metric series and never the WARN, that a single
 * oracle blackout says nothing at all, and that a healthy or settled market produces neither output.
 * <b>Does NOT prove:</b> anything about liquidation. Nothing here builds, prices or submits a
 * transaction; the assessments are hand-built. That no decision changed is
 * {@link LiquidationExecutorTest}'s to carry, and it does
 * ({@code aThrowingReporterChangesNothingAboutTheTransactionThatIsRecorded}); that the executor
 * hands the reporter the WHOLE scan rather than only the buildable part is
 * {@code theExecutorHandsTheReporterEveryAssessmentAndNotOnlyTheBuildableOnes}.
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

    /** A second token, so a collateral-side gap has something to be confused with. */
    private static final String SNEK_UNIT =
            "279c909f348e533da5808898f87f9a14bb2c3dfbbacccd631d927a3f" + "534e454b";

    private static final String HOSKY_UNIT =
            "a0028f350aaabe0545fdcb56b039bfb08e4bb4d8c4d7c3c7d481c235" + "484f534b59";

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
     * ⛔ <b>Every reported refusal is attributed to a LEG, and that decides which token is named.</b>
     *
     * <p>It also fixes the ceiling in markets rather than in triples, which is the only unit an
     * operator can reason about: three of the four reported reasons are principal-leg and one is
     * collateral-leg, so a single asset can occupy at most FOUR of
     * {@link MarketCoverageReporter#MAX_TRACKED_SERIES} series — at least 64 distinct assets are
     * always representable, and 256 in the ordinary case of one reason each.
     *
     * <p>The seven servable reasons throw rather than defaulting to a leg. A default would be a
     * guess about a token nothing ever reports, and nothing would ever contradict it.
     */
    @Test
    void everyReportedRefusalIsAttributedToALegAndTheServableOnesRefuseToGuess() {
        EnumSet<LiquidationExclusion> principal = EnumSet.noneOf(LiquidationExclusion.class);
        EnumSet<LiquidationExclusion> collateral = EnumSet.noneOf(LiquidationExclusion.class);
        for (LiquidationExclusion reason : LiquidationExclusion.values()) {
            if (!UnservableMarket.isUnservable(reason)) {
                assertThrows(IllegalArgumentException.class, () -> UnservableMarket.legOf(reason),
                        () -> reason + " is servable, so it has no leg and must not be given one");
                continue;
            }
            (UnservableMarket.legOf(reason) == UnservableMarket.Leg.PRINCIPAL ? principal : collateral)
                    .add(reason);
        }

        assertEquals(EnumSet.of(LiquidationExclusion.PRINCIPAL_ORACLE_UNUSABLE,
                        LiquidationExclusion.HEALTH_NOT_COMPUTABLE,
                        LiquidationExclusion.CONVERSION_TO_PRINCIPAL_REQUIRED),
                principal, "the principal leg is Giovanni's market: the token for the principal");
        assertEquals(EnumSet.of(LiquidationExclusion.COLLATERAL_ORACLE_UNUSABLE), collateral,
                "a collateral feed gap is about the COLLATERAL token, not about the principal");
        assertEquals(4, principal.size() + collateral.size(),
                "one asset occupies at most this many of the " + MarketCoverageReporter.MAX_TRACKED_SERIES
                        + " series, so the bound is worth at least "
                        + MarketCoverageReporter.MAX_TRACKED_SERIES / 4 + " distinct assets");
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

        for (int cycle = 0; cycle < CYCLES_TO_WARN + 2; cycle++) {
            reporter.report(List.of(
                    excluded(USDM_UNIT, LiquidationExclusion.NOT_LIQUIDATABLE,
                            "not late and currentLtv <= liquidationLtv"),
                    excluded(USDM_UNIT, LiquidationExclusion.LOAN_NOT_FOUND,
                            "no loan under the loan policy shares bond asset name aa"),
                    excluded("lovelace", LiquidationExclusion.MODE_NOT_LIQUIDATION,
                            "liquidationMode is NoLiquidationFullCollateralClaim"),
                    buildable("lovelace")));
        }

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
     * {@code contains("USDM-ish")}: the label NAMES, the label VALUES and the gauge VALUE are all
     * load-bearing for an alert rule, and a test that checked only the token would stay green if a
     * label were renamed or the sense of the gauge inverted.
     */
    @Test
    void anUnservableMarketIsNamedInAWarnAndInTheGaugeLabel() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        MarketCoverageReporter reporter = new MarketCoverageReporter(registry);
        List<ILoggingEvent> logged = capture();

        for (int cycle = 0; cycle < CYCLES_TO_WARN; cycle++) {
            reporter.report(List.of(
                    excluded(USDM_UNIT, LiquidationExclusion.PRINCIPAL_ORACLE_UNUSABLE,
                            "principal leg: no oracle entry for " + USDM_UNIT),
                    buildable("lovelace")));
        }

        assertEquals(1, warnings(logged).size(), () -> "one WARN, once: " + warnings(logged));
        assertEquals("⛔ UNSERVABLE MARKET " + USDM_UNIT + " (principal leg) — "
                        + "PRINCIPAL_ORACLE_UNUSABLE (principal leg: no oracle entry for " + USDM_UNIT
                        + "). A market is the token for the principal, and the bot met a loan in "
                        + "this one it cannot process as it stands. Gauge loans.market.unservable "
                        + "is now 1 for market=" + USDM_UNIT
                        + " leg=principal reason=PRINCIPAL_ORACLE_UNUSABLE",
                warnings(logged).get(0),
                "the WARN must name the asset and carry the scanner's own reason for it");

        assertTrue(registry.scrape().contains(
                        "loans_market_unservable{leg=\"principal\",market=\"" + USDM_UNIT
                                + "\",reason=\"PRINCIPAL_ORACLE_UNUSABLE\"} 1.0"),
                () -> "the gauge must be labelled by market and read 1; scrape was:\n" + registry.scrape());
    }

    /**
     * ⛔ <b>A COLLATERAL feed gap names the COLLATERAL token.</b>
     *
     * <p>The defect this pins: {@code COLLATERAL_ORACLE_UNUSABLE} keyed by the principal collapses
     * every ada-principal loan into one series saying {@code market="lovelace"} — so the operator is
     * paged that <b>ada</b> is the market the bot cannot serve, which is false and unactionable,
     * while the token that actually needs a feed appears in no label at all. Giovanni's "a new
     * stablecoin loan appears" arrives as collateral at least as often as as principal.
     *
     * <p>Two ada-principal loans, two different collateral tokens, and therefore two series named
     * after the tokens that lack feeds — and no {@code lovelace} series anywhere.
     */
    @Test
    void aCollateralSideGapNamesTheCollateralTokenAndNotThePrincipal() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        MarketCoverageReporter reporter = new MarketCoverageReporter(registry);
        List<ILoggingEvent> logged = capture();

        for (int cycle = 0; cycle < CYCLES_TO_WARN; cycle++) {
            reporter.report(List.of(
                    collateralGap("lovelace", SNEK_UNIT),
                    collateralGap("lovelace", HOSKY_UNIT)));
        }

        String scrape = registry.scrape();
        assertEquals(2, seriesCount(registry),
                () -> "two different collateral tokens are two markets, not one; scrape was:\n" + scrape);
        assertTrue(scrape.contains("loans_market_unservable{leg=\"collateral\",market=\"" + SNEK_UNIT
                        + "\",reason=\"COLLATERAL_ORACLE_UNUSABLE\"} 1.0"),
                () -> "the collateral token must be the label; scrape was:\n" + scrape);
        assertTrue(scrape.contains("loans_market_unservable{leg=\"collateral\",market=\"" + HOSKY_UNIT
                        + "\",reason=\"COLLATERAL_ORACLE_UNUSABLE\"} 1.0"),
                () -> "the collateral token must be the label; scrape was:\n" + scrape);
        assertFalse(scrape.contains("market=\"lovelace\""),
                () -> "ada is the PRINCIPAL of both loans and is perfectly serviceable; paging on it "
                        + "is the mis-attribution this leg label exists to end. Scrape was:\n" + scrape);

        assertEquals(2, warnings(logged).size(), () -> warnings(logged).toString());
        assertTrue(warnings(logged).stream().anyMatch(w -> w.contains(SNEK_UNIT + " (collateral leg)")),
                () -> "the WARN names the collateral token and says which leg: " + warnings(logged));
        assertTrue(warnings(logged).stream().noneMatch(w -> w.contains("is the token for the principal")),
                () -> "and it must not read the operator Giovanni's definition of a market on a line "
                        + "where the token named is the COLLATERAL: " + warnings(logged));
    }

    /**
     * ⛔ <b>A collateral-leg refusal with no loan does not fall back to the principal.</b>
     *
     * <p>{@link LiquidationAssessment} allows a null loan only for {@code LOAN_NOT_FOUND}, which is
     * servable and never reported — so this shape is a contract violation rather than a case. It
     * throws, loudly, into {@code LiquidationExecutor}'s reporting guard. Naming the principal
     * instead would be indistinguishable from a genuine principal-side gap, which is precisely the
     * confusion the leg label exists to remove.
     */
    @Test
    void aCollateralLegRefusalWithNoLoanRefusesToNameThePrincipalInstead() {
        LiquidationAssessment impossible = LiquidationAssessment.excluded(bond("lovelace"), null,
                LiquidationExclusion.COLLATERAL_ORACLE_UNUSABLE, "collateral leg: no oracle entry");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> UnservableMarket.seenIn(List.of(impossible)));
        assertTrue(thrown.getMessage().contains("refusing to report the principal in its place"),
                () -> thrown.getMessage());
    }

    /**
     * ⚠ <b>A recovered market keeps its series, holding 0.</b> Deleting it eagerly would make
     * "servable again" and "this bot stopped looking" the same observation, and no alert rule can
     * tell those apart. The INFO is what a human reading the log afterwards needs; the 0 is what the
     * alert needs.
     */
    @Test
    void aRecoveredMarketFallsBackToZeroRatherThanDisappearing() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        MarketCoverageReporter reporter = new MarketCoverageReporter(registry);

        for (int cycle = 0; cycle < CYCLES_TO_WARN; cycle++) {
            reporter.report(List.of(excluded(USDM_UNIT, LiquidationExclusion.PRINCIPAL_ORACLE_UNUSABLE,
                    "principal leg: oracle feed for " + USDM_UNIT
                            + " is outside its validity window at 1")));
        }
        List<ILoggingEvent> logged = capture();
        reporter.report(List.of(buildable(USDM_UNIT)));

        assertTrue(registry.scrape().contains(
                        "loans_market_unservable{leg=\"principal\",market=\"" + USDM_UNIT
                                + "\",reason=\"PRINCIPAL_ORACLE_UNUSABLE\"} 0.0"),
                () -> "the series must survive at 0; scrape was:\n" + registry.scrape());
        assertTrue(warnings(logged).isEmpty(), () -> "recovery is not a WARN: " + warnings(logged));
        assertEquals(1, infos(logged).size(), () -> "recovery says so once: " + infos(logged));
        assertTrue(infos(logged).get(0).startsWith("market " + USDM_UNIT + " (principal leg) is "
                        + "servable again"), () -> infos(logged).get(0));
    }

    /**
     * ⛔ The stage scans on every block and every price change, forever. A WARN per scan would bury
     * the line that matters under thousands of identical ones, so the log carries TRANSITIONS and the
     * gauge carries the state — which is the division of labour Giovanni described ("a WARN in the
     * logs would be a good starting point" for the human; "a prometheus metric … trigger paging" for
     * the alert).
     *
     * <p>Wall-clock form: scans every 20 s, the block cadence, up to 80 s say nothing; the scan at
     * 81 s — strictly more than {@link MarketCoverageReporter#MAX_BLACKOUT_SECONDS} — says it once,
     * and the scans after it say nothing more.
     *
     * <p>⚠ The assertion is on the WARN's <b>content</b>, not only on the count. A count alone stays
     * green when the transition WARN is entirely absent and some other WARN — the cardinality bound's,
     * say — happens to fire once instead, which is a way for this mechanism to disappear silently.
     */
    @Test
    void anUnservableMarketWarnsOnceAtEightyOneSecondsAndNotOnEveryScan() {
        MutableClock clock = new MutableClock(T0);
        MarketCoverageReporter reporter = wallClockReporter(new SimpleMeterRegistry(), clock);
        List<ILoggingEvent> logged = capture();

        LiquidationAssessment blind = excluded(USDM_UNIT,
                LiquidationExclusion.PRINCIPAL_ORACLE_UNUSABLE, "principal leg: oracle client disabled");
        for (int second = 0; second <= 80; second += 20) {
            clock.set(T0.plusSeconds(second));
            reporter.report(List.of(blind));
        }
        assertTrue(warnings(logged).isEmpty(),
                () -> "80 s is not MORE than the 80 s blackout ceiling: " + warnings(logged));

        clock.set(T0.plusSeconds(81));
        reporter.report(List.of(blind));
        assertEquals(1, warnings(logged).size(), () -> "81 s: exactly one WARN: " + warnings(logged));

        for (int second = 100; second <= 300; second += 20) {
            clock.set(T0.plusSeconds(second));
            reporter.report(List.of(blind));
        }
        assertEquals(1, warnings(logged).size(),
                () -> "one episode, one WARN, however many scans follow: " + warnings(logged));
        assertTrue(warnings(logged).get(0).startsWith("⛔ UNSERVABLE MARKET"),
                () -> "it must be the UNSERVABLE MARKET line itself, not some other WARN that "
                        + "happens to fire once: " + warnings(logged));
    }

    /**
     * ⛔ <b>A SINGLE ORACLE BLACKOUT SAYS NOTHING AT ALL.</b>
     *
     * <p>Preview has a real ~60–80 s price blackout every five minutes, upstream of us, and it happens
     * ~288 times a day per non-ada market. At the block cadence (~20 s) an 80 s blackout is seen by
     * FIVE consecutive scans — the count the old cadence-derived gate would have needed rethinking
     * for. A WARN on each transition would turn Giovanni's floor into noise on the first network the
     * bot runs on, and the recovery INFO would double it.
     *
     * <p>So the gauge — the continuously-true thing, which an alert rule can hold with its own
     * {@code for:} clause — goes to 1 on the first scan, and the log says nothing until the condition
     * has held for more than {@link MarketCoverageReporter#MAX_BLACKOUT_SECONDS} of wall-clock time.
     */
    @Test
    void anEightySecondBlackoutSeenEveryTwentySecondsProducesNoWarnAndNoInfo() {
        MutableClock clock = new MutableClock(T0);
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        MarketCoverageReporter reporter = wallClockReporter(registry, clock);
        List<ILoggingEvent> logged = capture();

        LiquidationAssessment blacked = excluded(USDM_UNIT,
                LiquidationExclusion.PRINCIPAL_ORACLE_UNUSABLE,
                "principal leg: oracle feed for " + USDM_UNIT
                        + " is outside its validity window at 1757500000000");

        // the blackout: 80 s of it, observed at 0, 20, 40, 60 and 80
        reporter.report(List.of(blacked));
        assertTrue(registry.scrape().contains(
                        "loans_market_unservable{leg=\"principal\",market=\"" + USDM_UNIT
                                + "\",reason=\"PRINCIPAL_ORACLE_UNUSABLE\"} 1.0"),
                () -> "the GAUGE is the continuously-true thing and must not wait; scrape was:\n"
                        + registry.scrape());
        for (int second = 20; second <= 80; second += 20) {
            clock.set(T0.plusSeconds(second));
            reporter.report(List.of(blacked));
        }

        // the feed comes back
        clock.set(T0.plusSeconds(100));
        reporter.report(List.of(buildable(USDM_UNIT)));

        assertTrue(warnings(logged).isEmpty(),
                () -> "a blackout is not a market the bot cannot serve: " + warnings(logged));
        assertTrue(infos(logged).stream().noneMatch(i -> i.contains("is servable again")),
                () -> "and a recovery from something never warned about is not news: " + infos(logged));
    }

    /**
     * The gate is TIME, not a count of scans. Ten scans inside five seconds — a burst of price changes
     * landing together — is ten scans the old count would have warned on long before; it is five
     * seconds of evidence, and says nothing. The next scan, at +90 s, is the one that warns.
     */
    @Test
    void irregularlySpacedScansWarnOnlyOnceTheEpisodeOutlastsEightySeconds() {
        MutableClock clock = new MutableClock(T0);
        MarketCoverageReporter reporter = wallClockReporter(new SimpleMeterRegistry(), clock);
        List<ILoggingEvent> logged = capture();

        LiquidationAssessment blind = excluded(USDM_UNIT,
                LiquidationExclusion.PRINCIPAL_ORACLE_UNUSABLE, "principal leg: oracle client disabled");
        for (int scan = 0; scan < 10; scan++) {
            clock.set(T0.plusMillis(scan * 500L));
            reporter.report(List.of(blind));
        }
        assertTrue(warnings(logged).isEmpty(),
                () -> "ten scans within 5 s are five seconds of evidence, not ten: " + warnings(logged));

        clock.set(T0.plusSeconds(90));
        reporter.report(List.of(blind));
        assertEquals(1, warnings(logged).size(),
                () -> "the +90 s scan is the first past the ceiling and warns, once: " + warnings(logged));
    }

    /**
     * A servable scan ENDS the episode, and the next one starts its own clock. Unservable at 0,
     * servable at 50, unservable again from 60: at 140 the second episode is 80 s old and says
     * nothing — measured from the first episode it would be 140 s and would have warned — and at 141
     * it is 81 s old and warns.
     */
    @Test
    void aServableScanStartsANewEpisodeWithItsOwnClock() {
        MutableClock clock = new MutableClock(T0);
        MarketCoverageReporter reporter = wallClockReporter(new SimpleMeterRegistry(), clock);
        List<ILoggingEvent> logged = capture();

        LiquidationAssessment blind = excluded(USDM_UNIT,
                LiquidationExclusion.PRINCIPAL_ORACLE_UNUSABLE, "principal leg: oracle client disabled");
        reporter.report(List.of(blind));
        clock.set(T0.plusSeconds(50));
        reporter.report(List.of(buildable(USDM_UNIT)));
        for (int second : new int[] {60, 100, 140}) {
            clock.set(T0.plusSeconds(second));
            reporter.report(List.of(blind));
        }
        assertTrue(warnings(logged).isEmpty(),
                () -> "the second episode is 80 s old at 140; the first one is over: " + warnings(logged));

        clock.set(T0.plusSeconds(141));
        reporter.report(List.of(blind));
        assertEquals(1, warnings(logged).size(),
                () -> "81 s into the second episode: one WARN: " + warnings(logged));
    }

    /**
     * The recovery INFO exists only as the closing half of a WARN. An episode that never warned
     * recovers silently; one that did says "servable again" exactly once.
     */
    @Test
    void theRecoveryInfoFollowsOnlyAnEpisodeThatWarned() {
        MutableClock clock = new MutableClock(T0);
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        MarketCoverageReporter reporter = wallClockReporter(registry, clock);
        List<ILoggingEvent> logged = capture();

        LiquidationAssessment blind = excluded(USDM_UNIT,
                LiquidationExclusion.PRINCIPAL_ORACLE_UNUSABLE, "principal leg: oracle client disabled");

        // a short episode: never warned, so its end is not news
        reporter.report(List.of(blind));
        clock.set(T0.plusSeconds(60));
        reporter.report(List.of(blind));
        clock.set(T0.plusSeconds(70));
        reporter.report(List.of(buildable(USDM_UNIT)));
        assertTrue(warnings(logged).isEmpty(), () -> warnings(logged).toString());
        assertTrue(recoveries(logged).isEmpty(),
                () -> "a recovery from something never warned about is not news: " + infos(logged));

        // a long one: warned at 181 (81 s after 100), so its end IS news
        for (int second : new int[] {100, 140, 181}) {
            clock.set(T0.plusSeconds(second));
            reporter.report(List.of(blind));
        }
        assertEquals(1, warnings(logged).size(), () -> warnings(logged).toString());
        clock.set(T0.plusSeconds(200));
        reporter.report(List.of(buildable(USDM_UNIT)));

        assertEquals(1, recoveries(logged).size(), () -> "recovery says so once: " + infos(logged));
        assertTrue(recoveries(logged).get(0).startsWith("market " + USDM_UNIT + " (principal leg) is "
                + "servable again"), () -> recoveries(logged).get(0));
        assertTrue(registry.scrape().contains(
                        "loans_market_unservable{leg=\"principal\",market=\"" + USDM_UNIT
                                + "\",reason=\"PRINCIPAL_ORACLE_UNUSABLE\"} 0.0"),
                () -> "and the gauge is back to 0; scrape was:\n" + registry.scrape());
    }

    /**
     * The override at 1 makes the log follow the gauge exactly, on a network with no blackout: the
     * first unservable scan warns, the first servable one recovers, and the next unservable one warns
     * again — with a clock that never moves, so time plays no part.
     */
    @Test
    void anOverrideOfOneMakesTheLogFollowTheGauge() {
        MutableClock clock = new MutableClock(T0);
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        MarketCoverageReporter reporter = new MarketCoverageReporter(registry, 1, clock);
        List<ILoggingEvent> logged = capture();
        String series = "loans_market_unservable{leg=\"principal\",market=\"" + USDM_UNIT
                + "\",reason=\"PRINCIPAL_ORACLE_UNUSABLE\"} ";

        LiquidationAssessment blind = excluded(USDM_UNIT,
                LiquidationExclusion.PRINCIPAL_ORACLE_UNUSABLE, "principal leg: oracle client disabled");
        reporter.report(List.of(blind));
        assertTrue(registry.scrape().contains(series + "1.0"), registry::scrape);
        assertEquals(1, warnings(logged).size(), () -> "the gauge went to 1, so does the log: "
                + warnings(logged));

        reporter.report(List.of(buildable(USDM_UNIT)));
        assertTrue(registry.scrape().contains(series + "0.0"), registry::scrape);
        assertEquals(1, recoveries(logged).size(), () -> "the gauge fell to 0, the log says so: "
                + infos(logged));

        reporter.report(List.of(blind));
        assertEquals(2, warnings(logged).size(), () -> "a new episode, a new WARN: " + warnings(logged));
    }

    /**
     * The override above zero keeps the consecutive-scan count EXACTLY, whatever the clock says: an
     * hour between the first and second scans is not a reason to warn on the second, and a clock that
     * then stands still is not a reason to withhold the WARN on the third.
     */
    @Test
    void anOverrideOfThreeCountsScansWhateverTheClockSays() {
        MutableClock clock = new MutableClock(T0);
        MarketCoverageReporter reporter = new MarketCoverageReporter(new SimpleMeterRegistry(), 3, clock);
        List<ILoggingEvent> logged = capture();

        LiquidationAssessment blind = excluded(USDM_UNIT,
                LiquidationExclusion.PRINCIPAL_ORACLE_UNUSABLE, "principal leg: oracle client disabled");
        reporter.report(List.of(blind));
        clock.set(T0.plus(Duration.ofHours(1)));
        reporter.report(List.of(blind));
        assertTrue(warnings(logged).isEmpty(),
                () -> "two scans are two, however far apart: " + warnings(logged));

        reporter.report(List.of(blind));
        assertEquals(1, warnings(logged).size(),
                () -> "the third consecutive scan warns, with no time passing: " + warnings(logged));
        reporter.report(List.of(blind));
        assertEquals(1, warnings(logged).size(), () -> "once per episode: " + warnings(logged));
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
     * backend down. One market, one reason, a different detail every cycle ⇒ exactly one series and
     * one WARN.
     */
    @Test
    void aChangingDetailDoesNotMintANewSeries() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        MarketCoverageReporter reporter = new MarketCoverageReporter(registry);
        List<ILoggingEvent> logged = capture();

        for (int cycle = 0; cycle < CYCLES_TO_WARN; cycle++) {
            reporter.report(List.of(excluded(USDM_UNIT, LiquidationExclusion.PRINCIPAL_ORACLE_UNUSABLE,
                    "principal leg: oracle feed for x is outside its validity window at "
                            + (1757500000000L + cycle * 60000L))));
        }

        assertEquals(1, seriesCount(registry),
                "the (market, leg, reason) triple is the key; the detail only ever reaches a log line");
        assertEquals(1, warnings(logged).size(), () -> warnings(logged).toString());
    }

    /**
     * ⛔ <b>The label set is bounded BY CONSTRUCTION, not by optimism — and refusal is never
     * silent.</b>
     *
     * <p>Anyone can lock a lender bond naming an arbitrary asset as its principal, so "markets the
     * bot has seen" is attacker-influenced rather than a closed set of tens. Past
     * {@link MarketCoverageReporter#MAX_TRACKED_SERIES} <em>simultaneously unservable</em> triples
     * no further series is created — and this is the case the bound must survive: every series is
     * reading 1, so there is nothing to evict.
     *
     * <p>⛔ <b>The genuine newcomer is still NAMED.</b> The overflow is counted <em>and</em> the
     * market appears in a WARN of its own. Logs are not a cardinality-limited medium, and a bot that
     * cannot say which market it just stopped covering is the exact failure this slice exists to
     * end — a few hundred min-ada junk bonds must not be able to buy an operator's silence.
     */
    @Test
    void atTheBoundTheNewcomerLosesItsSeriesButNeverItsWarning() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        MarketCoverageReporter reporter = new MarketCoverageReporter(registry);

        List<LiquidationAssessment> junk = new ArrayList<>();
        for (int i = 0; i < MarketCoverageReporter.MAX_TRACKED_SERIES; i++) {
            junk.add(excluded(String.format("%056x", i) + "6675",
                    LiquidationExclusion.PRINCIPAL_ORACLE_UNUSABLE, "principal leg: oracle client disabled"));
        }
        for (int cycle = 0; cycle < CYCLES_TO_WARN; cycle++) {
            reporter.report(junk);
        }

        // the market that actually matters turns up after the flood, and stays
        List<ILoggingEvent> logged = capture();
        List<LiquidationAssessment> withNewcomer = new ArrayList<>(junk);
        withNewcomer.add(excluded(USDM_UNIT, LiquidationExclusion.PRINCIPAL_ORACLE_UNUSABLE,
                "principal leg: no oracle entry for " + USDM_UNIT));
        reporter.report(withNewcomer);

        assertEquals(MarketCoverageReporter.MAX_TRACKED_SERIES, seriesCount(registry),
                "the bound is the bound: an unbounded label brings a metrics backend down, which is "
                        + "worse than no metric");
        assertFalse(registry.scrape().contains(USDM_UNIT),
                () -> "with every series reading 1 there is nothing to evict, so the newcomer gets "
                        + "no series; scrape was:\n" + registry.scrape());
        assertTrue(registry.scrape().contains("loans_market_unservable_untracked 1.0"),
                () -> "the refused triples must be counted; scrape tail was:\n" + untrackedLine(registry));

        List<String> named = warnings(logged).stream()
                .filter(w -> w.startsWith("⛔ UNSERVABLE MARKET " + USDM_UNIT))
                .toList();
        assertEquals(1, named.size(),
                () -> "the newcomer must be named in a WARN even with no series to give it: "
                        + warnings(logged));
        assertTrue(named.get(0).contains("IT HAS NO METRIC SERIES"),
                () -> "and the WARN must say the metric is missing, so 'nothing more is wrong' and "
                        + "'we stopped saying' stay distinguishable: " + named.get(0));
        assertTrue(warnings(logged).stream().anyMatch(w -> w.contains("have no metric series")),
                () -> "reaching the bound must also be said as a total: " + warnings(logged));
    }

    /**
     * ⛔ <b>THE BOUND IS A CONCURRENCY CEILING, NOT A LIFETIME QUOTA.</b>
     *
     * <p>The failure this pins: a bound that only ever fills is a one-shot switch-off. Roughly 256
     * min-ada junk bonds — on the order of a thousand ada, and
     * {@code LenderBondService.findAll()} returns every bond at the credential rather than only this
     * operator's — would permanently exhaust the slots, and even after every one of those markets
     * recovered, a genuine new unservable market would get no series for the life of the process.
     *
     * <p>So a slot that has fallen back to 0 is reclaimable, as the executor's former (removed in
     * FAB-134 NQ) quarantine evicted the entry closest to expiry rather than refusing the newcomer.
     */
    @Test
    void aRecoveredSlotIsReclaimedForANewlyUnservableMarket() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        MarketCoverageReporter reporter = new MarketCoverageReporter(registry);

        List<LiquidationAssessment> junk = new ArrayList<>();
        for (int i = 0; i < MarketCoverageReporter.MAX_TRACKED_SERIES; i++) {
            junk.add(excluded(String.format("%056x", i) + "6675",
                    LiquidationExclusion.PRINCIPAL_ORACLE_UNUSABLE, "principal leg: oracle client disabled"));
        }
        reporter.report(junk);
        assertEquals(MarketCoverageReporter.MAX_TRACKED_SERIES, seriesCount(registry),
                "the flood filled every slot");

        // every one of them recovers — the junk bonds are settled, or the feeds arrive
        reporter.report(List.of(buildable("lovelace")));

        // and only now does the market Giovanni cares about turn up
        List<ILoggingEvent> logged = capture();
        for (int cycle = 0; cycle < CYCLES_TO_WARN; cycle++) {
            reporter.report(List.of(excluded(USDM_UNIT,
                    LiquidationExclusion.PRINCIPAL_ORACLE_UNUSABLE,
                    "principal leg: no oracle entry for " + USDM_UNIT)));
        }

        assertEquals(MarketCoverageReporter.MAX_TRACKED_SERIES, seriesCount(registry),
                "still bounded — the newcomer took a slot, it did not add one");
        assertTrue(registry.scrape().contains(
                        "loans_market_unservable{leg=\"principal\",market=\"" + USDM_UNIT
                                + "\",reason=\"PRINCIPAL_ORACLE_UNUSABLE\"} 1.0"),
                () -> "a recovered slot must be reclaimable, or a thousand ada buys permanent "
                        + "silence; scrape was:\n" + registry.scrape());
        assertTrue(registry.scrape().contains("loans_market_unservable_untracked 0.0"),
                () -> "nothing was refused; scrape tail was:\n" + untrackedLine(registry));
        assertEquals(1, warnings(logged).size(), () -> warnings(logged).toString());
        assertTrue(warnings(logged).get(0).startsWith("⛔ UNSERVABLE MARKET " + USDM_UNIT),
                () -> warnings(logged).get(0));
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
     * same way production gets it — including resolving the {@code @Value} on the WARN gate, which
     * is the other thing that can fail at container start and nowhere else.
     */
    @Test
    void theContainerCanBuildTheReporterFromTheAutoConfiguredRegistry() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MetricsAutoConfiguration.class,
                        CompositeMeterRegistryAutoConfiguration.class,
                        PrometheusMetricsExportAutoConfiguration.class))
                .withUserConfiguration(MarketCoverageReporter.class)
                .withPropertyValues(MarketCoverageReporter.WARN_AFTER_CYCLES_PROPERTY + "=1")
                .run(context -> {
                    assertFalse(context.getStartupFailure() != null,
                            () -> "context failed to start: " + context.getStartupFailure());
                    assertTrue(context.getBean(MeterRegistry.class) instanceof PrometheusMeterRegistry,
                            "the registry behind /actuator/prometheus is what the gauge must land in");
                    List<ILoggingEvent> logged = capture();
                    context.getBean(MarketCoverageReporter.class)
                            .report(List.of(collateralGap(USDM_UNIT, SNEK_UNIT)));
                    assertTrue(context.getBean(PrometheusMeterRegistry.class).scrape().contains(
                                    "loans_market_unservable{leg=\"collateral\",market=\"" + SNEK_UNIT
                                            + "\",reason=\"COLLATERAL_ORACLE_UNUSABLE\"} 1.0"),
                            "the container-built reporter must publish onto the scraped registry");
                    // ONE scan warned, so the property above was really bound: the default gate waits
                    // for more than 80 s of wall-clock time and would have said nothing here. A
                    // misspelt property name fails nowhere else — it simply reverts the node to the
                    // default, silently.
                    assertEquals(1, warnings(logged).size(),
                            () -> "the container must bind " + MarketCoverageReporter
                                    .WARN_AFTER_CYCLES_PROPERTY + ": " + warnings(logged));
                });
    }

    /**
     * ⛔ <b>The WARN gate no longer reads the liquidation cadence.</b> Scans follow blocks and price
     * changes, so {@code loans.liquidation.delay-seconds} describes no interval the reporter is
     * called at, and a gate derived from it would be measuring nothing.
     *
     * <p>Two halves. The container builds the reporter with NO {@code delay-seconds} property at all —
     * the property is due to leave the YAML, and the reporter must not be what stops the node booting
     * when it does — and on the default (wall-clock) gate one scan raises the gauge and says nothing.
     * And no constructor's {@code @Value} list names the property: the Spring constructor's names
     * exactly the operator override and nothing else.
     */
    @Test
    void theContainerBuildsTheReporterWithNoCadencePropertyAndNoConstructorReadsIt() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MetricsAutoConfiguration.class,
                        CompositeMeterRegistryAutoConfiguration.class,
                        PrometheusMetricsExportAutoConfiguration.class))
                .withUserConfiguration(MarketCoverageReporter.class)
                .run(context -> {
                    assertFalse(context.getStartupFailure() != null,
                            () -> "context failed to start: " + context.getStartupFailure());
                    assertFalse(context.getEnvironment().containsProperty(CADENCE_PROPERTY),
                            "the instrument: this context really carries no cadence property");
                    List<ILoggingEvent> logged = capture();
                    context.getBean(MarketCoverageReporter.class)
                            .report(List.of(excluded(USDM_UNIT,
                                    LiquidationExclusion.PRINCIPAL_ORACLE_UNUSABLE,
                                    "principal leg: oracle client disabled")));
                    assertTrue(context.getBean(PrometheusMeterRegistry.class).scrape().contains(
                                    "loans_market_unservable{leg=\"principal\",market=\"" + USDM_UNIT
                                            + "\",reason=\"PRINCIPAL_ORACLE_UNUSABLE\"} 1.0"),
                            "the gauge goes to 1 on the first scan");
                    assertTrue(warnings(logged).isEmpty(),
                            () -> "the default gate is wall-clock time, and no time has passed: "
                                    + warnings(logged));
                });

        Constructor<?> injected = null;
        for (Constructor<?> candidate : MarketCoverageReporter.class.getDeclaredConstructors()) {
            for (Parameter parameter : candidate.getParameters()) {
                Value value = parameter.getAnnotation(Value.class);
                assertTrue(value == null || !value.value().contains(CADENCE_PROPERTY),
                        () -> candidate + " still reads " + CADENCE_PROPERTY + ": " + value.value());
            }
            if (candidate.isAnnotationPresent(Autowired.class)) {
                injected = candidate;
            }
        }
        assertTrue(injected != null, "the @Autowired constructor is the one Spring uses");
        List<String> values = new ArrayList<>();
        for (Parameter parameter : injected.getParameters()) {
            Value value = parameter.getAnnotation(Value.class);
            if (value != null) {
                values.add(value.value());
            }
        }
        assertEquals(List.of("${" + MarketCoverageReporter.WARN_AFTER_CYCLES_PROPERTY + ":0}"), values,
                "the Spring constructor reads the operator override, with zero as the sentinel that "
                        + "selects the wall-clock gate, and nothing else");
    }

    // =====================================================================================
    // helpers
    // =====================================================================================

    private static final String UNSERVABLE_METRIC = "loans_market_unservable{";

    /**
     * How many consecutive unservable scans the {@code (MeterRegistry)} seam's count gate needs before
     * it says anything. The tests built on that seam are about what is said, not when.
     */
    private static final int CYCLES_TO_WARN = 3;

    /** The property the WARN gate used to derive from, and must no longer read. */
    private static final String CADENCE_PROPERTY = "loans.liquidation.delay-seconds";

    private static final Instant T0 = Instant.parse("2026-11-01T00:00:00Z");

    /** The production gate — the override absent, so wall-clock time decides — on {@code clock}. */
    private static MarketCoverageReporter wallClockReporter(MeterRegistry registry, Clock clock) {
        return new MarketCoverageReporter(registry, 0, clock);
    }

    /** A clock that stands still until told where to be. */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** An excluded assessment whose bond names {@code marketUnit} as its principal. */
    private static LiquidationAssessment excluded(String marketUnit, LiquidationExclusion reason,
                                                  String detail) {
        return LiquidationAssessment.excluded(bond(marketUnit), null, reason, detail);
    }

    /**
     * A loan whose principal is {@code principalUnit} and whose COLLATERAL token
     * {@code collateralUnit} has no usable feed — the shape that must be named after the collateral.
     */
    private static LiquidationAssessment collateralGap(String principalUnit, String collateralUnit) {
        return LiquidationAssessment.excluded(bond(principalUnit), loan(collateralUnit),
                LiquidationExclusion.COLLATERAL_ORACLE_UNUSABLE,
                "collateral leg: no oracle entry for the oracle nft of " + collateralUnit);
    }

    /** A buildable assessment in {@code marketUnit}: the servable majority a real scan is made of. */
    private static LiquidationAssessment buildable(String marketUnit) {
        return LiquidationAssessment.buildable(bond(marketUnit), null, "buildable liquidation",
                BigInteger.TEN, BigInteger.ZERO, true, BigInteger.ONE);
    }

    /**
     * Only {@code principalAsset} is read by anything under test — the principal leg's market — so
     * the rest of the bond is the cheapest thing that constructs.
     */
    private static LenderBond bond(String marketUnit) {
        return new LenderBond("00".repeat(32), 0, "addr_test1", "bond01", "d87980",
                new LenderManagerDatum(null, null, false, BigInteger.ZERO, "", asset(marketUnit)));
    }

    /**
     * Only {@code collateral} is read by anything under test — the collateral leg's market — so the
     * rest of the datum is the cheapest thing that constructs.
     */
    private static Loan loan(String collateralUnit) {
        AssetType collateral = asset(collateralUnit);
        LoanDatum datum = new LoanDatum(BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO,
                BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, AssetType.ada(), AssetType.ada(),
                BigInteger.ZERO, BigInteger.ZERO, null, null, BigInteger.ZERO, BigInteger.ZERO,
                false, "origin",
                new CollateralAsset(collateral.policyId(), Optional.of(collateral.assetName()),
                        AssetType.ada()));
        return new Loan("11".repeat(32), 0, "addr_test1", "bond01", BigInteger.TEN, BigInteger.TEN,
                datum);
    }

    private static AssetType asset(String unit) {
        return "lovelace".equals(unit) ? AssetType.ada() : AssetType.fromUnit(unit);
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

    /** The "servable again" INFO lines only. */
    private static List<String> recoveries(List<ILoggingEvent> events) {
        return infos(events).stream().filter(info -> info.contains("is servable again")).toList();
    }
}
