package com.fluidtokens.aquarium.offchain.service.loans;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.service.loans.OracleStreamComparator.Cmp;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FAB-175: the outcome rules of {@link OracleStreamComparator}, one fake-time test per rule — the rules and
 * constants are the specification (G7), so each is pinned with explicit instants and direct calls, never a clock.
 *
 * <p>The sets compared are real preview payloads (fixture PROVENANCE): {@code A1} is the 13:05:05Z API snapshot,
 * {@code P1} the 13:04:59Z push (parse-equal to the 13:05:25Z snapshot) and {@code P2} the 13:09:59Z push. The
 * three are pairwise different. A push of {@code A1} is the API array parsed as a push's tokens, which is the same
 * parser either way.
 *
 * <p>Seconds are {@code floor(milliseconds / 1000)} throughout.
 */
class OracleStreamComparatorTest {

    private static final Instant OPEN = Instant.parse("2026-10-29T13:00:26Z");
    private static final String PUSH_1 = "/loans-v4/oracle-ws/preview-push-20261029T130459Z.json";
    private static final String PUSH_2 = "/loans-v4/oracle-ws/preview-push-20261029T130959Z.json";
    private static final String API_130505 = "/loans-v4/oracle-ws/preview-api-20261029T130505Z.json";
    private static final String API_130525 = "/loans-v4/oracle-ws/preview-api-20261029T130525Z.json";
    private static final int BUDGET_BYTES = 180;

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private SimpleMeterRegistry registry;
    private OracleStreamComparator comparator;

    private OracleRegistryParser.Parsed a1;
    private OracleRegistryParser.Parsed p1;
    private OracleRegistryParser.Parsed p2;

    @BeforeEach
    void setUp() throws Exception {
        LoggerContext context = new LoggerContext();
        Logger logger = context.getLogger("oracle-stream-comparator-test");
        logger.setLevel(Level.DEBUG);
        appender.setContext(context);
        appender.start();
        logger.addAppender(appender);
        registry = new SimpleMeterRegistry();
        comparator = new OracleStreamComparator(new OracleStreamLog(logger), registry);

        a1 = OracleRegistryParser.parse(FluidOracleClientLoadPinTest.resource(API_130505));
        p1 = OracleRegistryParser.parse(FluidOracleClientLoadPinTest.resource(PUSH_1).path("tokens"));
        p2 = OracleRegistryParser.parse(FluidOracleClientLoadPinTest.resource(PUSH_2).path("tokens"));
        assertEquals(3, Set.of(h(a1), h(p1), h(p2)).size(), "fixture: three different sets");
    }

    // ======================================================================================
    // MATCHED
    // ======================================================================================

    /**
     * The real preview cycle of 2026-10-29: push 1 received 13:04:59.194Z (server timestamp .046), the poller still
     * on the old windows at 13:05:05Z and on the push's entries at 13:05:25Z. Lag = floor(25 806 ms / 1000) = 25.
     */
    @Test
    void matchedWithTheRealPreviewData() throws Exception {
        assertEquals(Double.NaN, lag(), "NaN before any match");
        FluidOracleClient poller = OracleClients.fromFixture(API_130525);
        assertEquals(Set.copyOf(poller.entries()), Set.copyOf(p1.byOracleToken().values()),
                "fixture: the 13:05:25Z API load is parse-equal to push 1");

        comparator.onOpen(OPEN);
        comparator.onPush(Instant.parse("2026-10-29T13:04:59.194Z"), 1793279099046L, p1, 1000);
        assertEquals(List.of(), cmpFields(), "pending until the API serves it");
        comparator.onApiLoad(Instant.parse("2026-10-29T13:05:05Z"), OracleClients.fromFixture(API_130505).entries());
        assertEquals(List.of(), cmpFields(), "the old windows are an observation, not a match");
        comparator.onApiLoad(Instant.parse("2026-10-29T13:05:25Z"), poller.entries());

        assertEquals(List.of("push=1 out=MATCHED n=5 h=" + h(p1) + " lag_s=25 rep=0 gap_s=273 dly_ms=148"),
                cmpFields());
        assertEquals(Level.INFO, cmpEvents().getFirst().getLevel());
        assertEquals(25.0, lag());
        assertCounts(1, 0, 0, 0, 0);

        comparator.tick(Instant.parse("2026-10-29T13:15:00Z"));
        assertEquals(1, cmpFields().size(), "resolved once, never again");
        assertEquals(1, comparator.pushes());
    }

    /** A repeat of a push the API is already on resolves at once, lag 0, {@code rep=1}; the lag gauge keeps rep=0's. */
    @Test
    void aRepeatPushTheApiIsAlreadyOnIsMatchedAtOnceWithRep1() {
        comparator.onOpen(OPEN);
        push(OPEN.plusSeconds(10), a1);
        comparator.onApiLoad(OPEN.plusSeconds(20), entries(a1));
        push(OPEN.plusSeconds(40), a1);

        assertEquals(List.of(
                "push=1 out=MATCHED n=5 h=" + h(a1) + " lag_s=10 rep=0 gap_s=10 dly_ms=100",
                "push=2 out=MATCHED n=5 h=" + h(a1) + " lag_s=0 rep=1 gap_s=30 dly_ms=100"), cmpFields());
        assertEquals(10.0, lag(), "a rep=1 match does not move the lag gauge");
        assertCounts(2, 0, 0, 0, 0);
    }

    /** A repeat the API is NOT on waits like any push, and matches with {@code rep=1} when the API serves it. */
    @Test
    void aRepeatPushTheApiIsNotOnWaitsAndMatchesWithRep1() {
        comparator.onOpen(OPEN);
        push(OPEN.plusSeconds(10), a1);
        comparator.onApiLoad(OPEN.plusSeconds(20), entries(a1));
        push(OPEN.plusSeconds(30), p1);
        comparator.onApiLoad(OPEN.plusSeconds(50), entries(p1));
        push(OPEN.plusSeconds(60), a1);
        assertEquals(2, cmpFields().size(), "the repeat waits");
        comparator.onApiLoad(OPEN.plusSeconds(75), entries(a1));

        assertEquals("push=3 out=MATCHED n=5 h=" + h(a1) + " lag_s=15 rep=1 gap_s=30 dly_ms=100", cmpFields().get(2));
        assertEquals(20.0, lag(), "the last rep=0 match (push 2)");
    }

    /**
     * r2 (d), the window's own edge: an API load exactly {@link OracleStreamComparator#WINDOW} after the push still
     * counts — MATCHED {@code lag_s=300}.
     */
    @Test
    void anApiLoadExactlyWindowAfterThePushIsStillMatched() {
        comparator.onOpen(OPEN);
        push(OPEN.plusSeconds(10), a1);
        comparator.onApiLoad(OPEN.plusSeconds(10).plus(OracleStreamComparator.WINDOW), entries(a1));

        assertEquals(List.of("push=1 out=MATCHED n=5 h=" + h(a1) + " lag_s=300 rep=0 gap_s=10 dly_ms=100"),
                cmpFields());
        assertEquals(300.0, lag());
        assertCounts(1, 0, 0, 0, 0);
    }

    /**
     * r2 R-b, the hand-off race: the poller loads at L and hands its result to the stream thread; a push stamped
     * 3 ms after L is processed BEFORE that hand-off. The push waits, then the load at L matches it — 3 ms "before"
     * it. {@code lag_s} and the gauge are clamped at 0: not behind, within the measurement error.
     */
    @Test
    void aLoadHandedOffAfterAPushStampedJustAfterItIsLagZeroNeverNegative() {
        comparator.onOpen(OPEN);
        comparator.onApiLoad(OPEN.plusSeconds(10), entries(a1));
        Instant loadedAt = OPEN.plusSeconds(200);
        push(loadedAt.plusMillis(3), p1);
        assertEquals(List.of(), cmpFields(), "fixture: the load has not reached the stream thread yet");
        comparator.onApiLoad(loadedAt, entries(p1));

        assertEquals(List.of("push=1 out=MATCHED n=5 h=" + h(p1) + " lag_s=0 rep=0 gap_s=200 dly_ms=100"),
                cmpFields());
        assertEquals(0.0, lag(), "the gauge is clamped too");
        assertCounts(1, 0, 0, 0, 0);
    }

    // ======================================================================================
    // R-a: no invented lag from the API's state before this connection
    // ======================================================================================

    /**
     * r2 R-a, the shape production starts in: the poller's load (the seed) is older than the connection, the first
     * push after connecting carries what the API already served, and the next poll serves it again. That is not a
     * lag — the API had it before this node was listening — so the push is UNCOVERED {@code why=pre-connect} and
     * the lag gauge stays NaN.
     */
    @Test
    void aPushTheApiAlreadyServedBeforeTheConnectionOpenedIsUncoveredPreConnect() {
        comparator.onApiLoad(OPEN.minusSeconds(60), entries(a1));
        comparator.onOpen(OPEN);
        push(OPEN.plusSeconds(100), a1);
        assertEquals(List.of(), cmpFields(), "no API change to it: it waits for the next load");
        comparator.onApiLoad(OPEN.plusSeconds(120), entries(a1));

        assertEquals(List.of("push=1 out=UNCOVERED n=5 h=" + h(a1) + " why=pre-connect gap_s=100 dly_ms=100"),
                cmpFields());
        assertEquals(Level.INFO, cmpEvents().getFirst().getLevel());
        assertEquals(Double.NaN, lag(), "the lag gauge does not move");
        assertCounts(0, 0, 0, 0, 1);

        comparator.tick(OPEN.plusSeconds(900));
        assertOneLinePerPush(1);
    }

    /**
     * r2 R-a, the same rule on the repeat path: a repeat after a reconnect, the API on it since before that
     * reconnect, is UNCOVERED {@code why=pre-connect} rather than MATCHED {@code rep=1}. Before the reconnect the
     * same repeat matched, which is the control.
     */
    @Test
    void aRepeatPushTheApiWasOnBeforeAReconnectIsUncoveredPreConnect() {
        comparator.onOpen(OPEN);
        push(OPEN.plusSeconds(10), a1);
        comparator.onApiLoad(OPEN.plusSeconds(20), entries(a1));
        push(OPEN.plusSeconds(40), a1);
        comparator.onClose(OPEN.plusSeconds(100), "no-pong");
        comparator.onOpen(OPEN.plusSeconds(200));
        comparator.onApiLoad(OPEN.plusSeconds(230), entries(a1));
        push(OPEN.plusSeconds(250), a1);

        assertEquals(List.of(
                "push=1 out=MATCHED n=5 h=" + h(a1) + " lag_s=10 rep=0 gap_s=10 dly_ms=100",
                "push=2 out=MATCHED n=5 h=" + h(a1) + " lag_s=0 rep=1 gap_s=30 dly_ms=100",
                "push=3 out=UNCOVERED n=5 h=" + h(a1) + " why=pre-connect gap_s=50 dly_ms=100"), cmpFields());
        assertEquals(10.0, lag());
        assertCounts(2, 0, 0, 0, 1);
    }

    /** r2 R-a's boundary: an API change after the connection opened is a change this node watched — MATCHED. */
    @Test
    void aPushTheApiMovedToAfterTheConnectionOpenedIsMatched() {
        comparator.onApiLoad(OPEN.minusSeconds(60), entries(a1));
        comparator.onOpen(OPEN);
        push(OPEN.plusSeconds(100), p1);
        comparator.onApiLoad(OPEN.plusSeconds(120), entries(p1));

        assertEquals(List.of("push=1 out=MATCHED n=5 h=" + h(p1) + " lag_s=20 rep=0 gap_s=100 dly_ms=100"),
                cmpFields());
        assertEquals(20.0, lag());
    }

    // ======================================================================================
    // SUPERSEDED
    // ======================================================================================

    /** A newer, different push supersedes; the first mark stands; the newest is judged on its own. */
    @Test
    void anOlderPushANewerOneReplacedIsSupersededByTheFirstThatDid() {
        comparator.onOpen(OPEN);
        push(OPEN.plusSeconds(10), a1);
        push(OPEN.plusSeconds(20), p1);
        push(OPEN.plusSeconds(30), p2);
        assertEquals(List.of(), cmpFields());

        comparator.tick(OPEN.plusSeconds(10).plus(OracleStreamComparator.WINDOW));
        assertEquals(List.of(), cmpFields(), "exactly WINDOW old is not yet past it");
        comparator.tick(OPEN.plusSeconds(31).plus(OracleStreamComparator.WINDOW));

        assertEquals(List.of(
                "push=1 out=SUPERSEDED n=5 h=" + h(a1) + " by=2 gap_s=10 dly_ms=100",
                "push=2 out=SUPERSEDED n=5 h=" + h(p1) + " by=3 gap_s=10 dly_ms=100",
                "push=3 out=UNCOVERED n=5 h=" + h(p2) + " why=no-api gap_s=10 dly_ms=100"), cmpFields());
        assertCounts(0, 2, 0, 0, 1);
    }

    /**
     * r2 (c), the supersede rule's {@code hash ≠ h} clause: a repeat of the SAME entries does not supersede the push
     * before it. Both are then judged on the API they saw — MISMATCH — and push 1 is never SUPERSEDED by push 2.
     */
    @Test
    void aRepeatOfTheSameEntriesDoesNotSupersedeThePushBeforeIt() {
        comparator.onOpen(OPEN);
        push(OPEN.plusSeconds(10), a1);
        push(OPEN.plusSeconds(20), a1);
        comparator.onApiLoad(OPEN.plusSeconds(30), entries(p2));
        comparator.tick(OPEN.plusSeconds(400));

        assertEquals(List.of(
                "push=1 out=MISMATCH n=5 h=" + h(a1) + " api=" + h(p2) + " api_n=5 gap_s=10 dly_ms=100",
                "push=2 out=MISMATCH n=5 h=" + h(a1) + " api=" + h(p2) + " api_n=5 gap_s=10 dly_ms=100"),
                cmpFields());
        assertCounts(0, 0, 2, 0, 0);
    }

    // ======================================================================================
    // MISMATCH
    // ======================================================================================

    /**
     * The API was observed in the window and never served the push: MISMATCH naming the API's hash and size; the
     * detail line names the differing NFTs and that the API is still on push 1. The same difference again is said
     * once at WARN — the second detail is DEBUG — while each MISMATCH line itself is said.
     */
    @Test
    void mismatchNamesTheApiAndThatItIsStillOnAnEarlierPushAndItsDetailIsLatched() {
        comparator.onOpen(OPEN);
        push(OPEN.plusSeconds(10), p1);
        comparator.onApiLoad(OPEN.plusSeconds(20), entries(p1));
        push(OPEN.plusSeconds(300), p2);
        comparator.onApiLoad(OPEN.plusSeconds(330), entries(p1));
        comparator.onApiLoad(OPEN.plusSeconds(360), entries(p1));
        comparator.tick(OPEN.plusSeconds(601));

        assertEquals(List.of(
                "push=1 out=MATCHED n=5 h=" + h(p1) + " lag_s=10 rep=0 gap_s=10 dly_ms=100",
                "push=2 out=MISMATCH n=5 h=" + h(p2) + " api=" + h(p1) + " api_n=5 gap_s=290 dly_ms=100"),
                cmpFields());
        assertEquals(Level.WARN, cmpEvents().get(1).getLevel());
        List<String> differ = differingUnits(p2, p1);
        assertTrue(!differ.isEmpty() && differ.size() <= 5, "fixture: " + differ);
        assertEquals(List.of("push=2 out=MISMATCH only_ws=0 only_api=0 differ=" + differ.size() + " api_is_push=1"
                + " -- only_ws:[] only_api:[] differ:[" + String.join(",", differ) + "]"), detailFields(Level.WARN));
        assertCounts(1, 0, 1, 0, 0);

        // The same standing difference: a repeat of push 2, the API still on push 1.
        push(OPEN.plusSeconds(620), p2);
        comparator.onApiLoad(OPEN.plusSeconds(650), entries(p1));
        comparator.tick(OPEN.plusSeconds(921));
        assertEquals("push=3 out=MISMATCH n=5 h=" + h(p2) + " api=" + h(p1) + " api_n=5 gap_s=320 dly_ms=100",
                cmpFields().get(2));
        assertEquals(1, detailFields(Level.WARN).size(), "the same signature is said once at WARN");
        assertEquals(1, detailFields(Level.DEBUG).size(), "and then at DEBUG");
        assertCounts(1, 0, 2, 0, 0);
    }

    // ======================================================================================
    // API_AHEAD
    // ======================================================================================

    /** The API moved to entries no push had carried, 120 s or more after the open; the push then arrives: lead_s. */
    @Test
    void aPushArrivingAfterTheApiHadItIsApiAheadWithItsLead() {
        comparator.onOpen(OPEN);
        push(OPEN.plusSeconds(130), a1);
        comparator.onApiLoad(OPEN.plusSeconds(140), entries(a1));
        comparator.onApiLoad(OPEN.plusMillis(200_500), entries(p1));
        push(OPEN.plusSeconds(230), p1);

        assertEquals(List.of(
                "push=1 out=MATCHED n=5 h=" + h(a1) + " lag_s=10 rep=0 gap_s=130 dly_ms=100",
                "push=2 out=API_AHEAD n=5 h=" + h(p1) + " lead_s=29 gap_s=100 dly_ms=100"), cmpFields());
        assertEquals(Level.WARN, cmpEvents().get(1).getLevel());
        List<String> differ = differingUnits(a1, p1);
        assertEquals(List.of("push=2 out=API_AHEAD only_ws=0 only_api=0 differ=" + differ.size()
                + " -- only_ws:[] only_api:[] differ:[" + String.join(",", differ) + "]"), detailFields(Level.WARN));
        assertCounts(1, 0, 0, 1, 0);

        comparator.tick(OPEN.plusSeconds(900));
        assertEquals(2, cmpFields().size(), "the change a push reported is not reported again as an orphan");
    }

    /** An API change no push carries is reported once WINDOW has passed, with {@code push=-} and the API's n and h. */
    @Test
    void anApiChangeNoPushEverCarriedIsAnOrphanApiAhead() {
        comparator.onOpen(OPEN);
        push(OPEN.plusSeconds(130), a1);
        comparator.onApiLoad(OPEN.plusSeconds(140), entries(a1));
        comparator.onApiLoad(OPEN.plusSeconds(200), entries(p2));
        assertEquals(1, cmpFields().size());

        comparator.tick(OPEN.plusSeconds(200).plus(OracleStreamComparator.WINDOW));
        assertEquals(1, cmpFields().size(), "exactly WINDOW old is not yet past it");
        comparator.tick(OPEN.plusSeconds(201).plus(OracleStreamComparator.WINDOW));
        comparator.tick(OPEN.plusSeconds(211).plus(OracleStreamComparator.WINDOW));

        assertEquals("push=- out=API_AHEAD n=5 h=" + h(p2), cmpFields().get(1));
        assertEquals(2, cmpFields().size(), "reported once");
        assertEquals(Level.WARN, cmpEvents().get(1).getLevel());
        List<String> differ = differingUnits(a1, p2);
        assertEquals(List.of("push=- out=API_AHEAD only_ws=0 only_api=0 differ=" + differ.size()
                + " -- only_ws:[] only_api:[] differ:[" + String.join(",", differ) + "]"), detailFields(Level.WARN));
        assertCounts(1, 0, 0, 1, 0);
    }

    /**
     * An API change less than {@link OracleStreamComparator#API_LAG_ALLOWANCE} after a reconnect may be the API
     * catching up on a push this node missed while disconnected: the push that then carries it is UNCOVERED
     * {@code why=unjudgeable}, never API_AHEAD, and the change is never an orphan. Exactly 120 s after the open is
     * judged.
     */
    @Test
    void anApiChangeWithin120sOfAReconnectIsNotApiAhead() {
        comparator.onOpen(OPEN);
        push(OPEN.plusSeconds(130), a1);
        comparator.onApiLoad(OPEN.plusSeconds(140), entries(a1));
        comparator.onClose(OPEN.plusSeconds(200), "server-close");
        comparator.onOpen(OPEN.plusSeconds(260));
        comparator.onApiLoad(OPEN.plusSeconds(300), entries(p1));
        push(OPEN.plusSeconds(330), p1);
        comparator.tick(OPEN.plusSeconds(900));

        assertEquals(List.of(
                "push=1 out=MATCHED n=5 h=" + h(a1) + " lag_s=10 rep=0 gap_s=130 dly_ms=100",
                "push=2 out=UNCOVERED n=5 h=" + h(p1) + " why=unjudgeable gap_s=70 dly_ms=100"), cmpFields());
        assertEquals(List.of(), detailFields(Level.WARN));
        assertCounts(1, 0, 0, 0, 1);
    }

    @Test
    void anApiChangeExactly120sAfterAReconnectIsJudged() {
        comparator.onOpen(OPEN);
        comparator.onApiLoad(OPEN.plusSeconds(10), entries(a1));
        comparator.onClose(OPEN.plusSeconds(100), "no-pong");
        comparator.onOpen(OPEN.plusSeconds(180));
        comparator.onApiLoad(OPEN.plusSeconds(300), entries(p1));
        push(OPEN.plusSeconds(330), p1);

        assertEquals(List.of("push=1 out=API_AHEAD n=5 h=" + h(p1) + " lead_s=30 gap_s=150 dly_ms=100"), cmpFields());
    }

    /**
     * r2 (d), {@code a < t} in the API_AHEAD rule: an API change at the very instant of the push is not "ahead" of
     * it. The push waits, and the next load serves it: MATCHED.
     */
    @Test
    void anApiChangeAtTheSameInstantAsThePushIsNotApiAhead() {
        comparator.onOpen(OPEN);
        comparator.onApiLoad(OPEN.plusSeconds(10), entries(a1));
        comparator.onApiLoad(OPEN.plusSeconds(200), entries(p1));
        push(OPEN.plusSeconds(200), p1);
        assertEquals(List.of(), cmpFields(), "a change at t is not before t");
        comparator.onApiLoad(OPEN.plusSeconds(230), entries(p1));

        assertEquals(List.of("push=1 out=MATCHED n=5 h=" + h(p1) + " lag_s=30 rep=0 gap_s=200 dly_ms=100"),
                cmpFields());
        assertCounts(1, 0, 0, 0, 0);
        comparator.tick(OPEN.plusSeconds(900));
        assertEquals(1, cmpFields().size(), "the change the push carried is no orphan either");
    }

    @Test
    void anApiChangeWhileDisconnectedIsNotJudged() {
        comparator.onApiLoad(OPEN.minusSeconds(600), entries(a1));
        comparator.onApiLoad(OPEN.minusSeconds(300), entries(p1));
        comparator.onOpen(OPEN);
        push(OPEN.plusSeconds(10), p1);
        comparator.tick(OPEN.plusSeconds(900));

        assertEquals(List.of("push=1 out=UNCOVERED n=5 h=" + h(p1) + " why=unjudgeable gap_s=10 dly_ms=100"),
                cmpFields());
    }

    // ======================================================================================
    // UNCOVERED
    // ======================================================================================

    /** No API load inside the push's window — one after it does not count — is UNCOVERED {@code why=no-api}. */
    @Test
    void noApiLoadInTheWindowIsUncoveredNoApi() {
        comparator.onOpen(OPEN);
        push(OPEN.plusSeconds(10), a1);
        comparator.onApiLoad(OPEN.plusSeconds(10).plus(OracleStreamComparator.WINDOW).plusMillis(1), entries(p1));
        comparator.tick(OPEN.plusSeconds(11).plus(OracleStreamComparator.WINDOW));

        assertEquals(List.of("push=1 out=UNCOVERED n=5 h=" + h(a1) + " why=no-api gap_s=10 dly_ms=100"),
                cmpFields());
        assertEquals(Level.INFO, cmpEvents().getFirst().getLevel());
        assertCounts(0, 0, 0, 0, 1);
    }

    @Test
    void everyPushStillWaitingAtShutdownIsUncoveredShutdownInOrder() {
        comparator.onOpen(OPEN);
        push(OPEN.plusSeconds(10), a1);
        push(OPEN.plusSeconds(20), p1);
        comparator.shutdown(OPEN.plusSeconds(30));
        comparator.shutdown(OPEN.plusSeconds(40));

        assertEquals(List.of(
                "push=1 out=UNCOVERED n=5 h=" + h(a1) + " why=shutdown gap_s=10 dly_ms=100",
                "push=2 out=UNCOVERED n=5 h=" + h(p1) + " why=shutdown gap_s=10 dly_ms=100"), cmpFields());
        assertCounts(0, 0, 0, 0, 2);
    }

    /**
     * r2 R-c: after the comparator's shutdown — the launcher's stop() runs it before it stops the client — a push
     * that still lands is resolved at once as UNCOVERED {@code why=shutdown}, never left without a line.
     */
    @Test
    void aPushAfterShutdownIsUncoveredShutdownAtOnce() {
        comparator.onOpen(OPEN);
        push(OPEN.plusSeconds(10), a1);
        comparator.shutdown(OPEN.plusSeconds(20));
        push(OPEN.plusSeconds(21), p1);
        push(OPEN.plusSeconds(22), a1);

        assertEquals(List.of(
                "push=1 out=UNCOVERED n=5 h=" + h(a1) + " why=shutdown gap_s=10 dly_ms=100",
                "push=2 out=UNCOVERED n=5 h=" + h(p1) + " why=shutdown gap_s=11 dly_ms=100",
                "push=3 out=UNCOVERED n=5 h=" + h(a1) + " why=shutdown gap_s=1 dly_ms=100"), cmpFields());
        comparator.onApiLoad(OPEN.plusSeconds(25), entries(p1));
        comparator.tick(OPEN.plusSeconds(900));
        assertOneLinePerPush(3);
        assertCounts(0, 0, 0, 0, 3);
    }

    /** More than {@link OracleStreamComparator#MAX_PENDING} waiting: the oldest is UNCOVERED {@code why=overflow}. */
    @Test
    void theOldestWaitingPushOverflowsAtMaxPendingPlusOne() {
        comparator.onOpen(OPEN);
        for (int i = 1; i <= OracleStreamComparator.MAX_PENDING; i++) {
            push(OPEN.plusSeconds(i), i % 2 == 1 ? a1 : p1);
        }
        assertEquals(List.of(), cmpFields(), "64 waiting is within the bound");

        push(OPEN.plusSeconds(65), a1);
        assertEquals(List.of("push=1 out=UNCOVERED n=5 h=" + h(a1) + " why=overflow gap_s=1 dly_ms=100"),
                cmpFields());
        push(OPEN.plusSeconds(66), p1);
        assertEquals("push=2 out=UNCOVERED n=5 h=" + h(p1) + " why=overflow gap_s=1 dly_ms=100", cmpFields().get(1));
        assertCounts(0, 0, 0, 0, 2);

        comparator.shutdown(OPEN.plusSeconds(70));
        assertOneLinePerPush(66);
    }

    // ======================================================================================
    // Meters and the byte budget
    // ======================================================================================

    @Test
    void connectedSecondsAccumulateOverOpenCloseOpen() {
        assertEquals(0.0, connectedSeconds());
        comparator.onOpen(OPEN);
        comparator.tick(OPEN.plusSeconds(10));
        assertEquals(10.0, connectedSeconds(), "the open span, up to the latest instant seen");
        comparator.onClose(OPEN.plusMillis(30_500), "server-close");
        assertEquals(30.5, connectedSeconds());
        comparator.tick(OPEN.plusSeconds(100));
        assertEquals(30.5, connectedSeconds(), "nothing accrues while closed");
        comparator.onOpen(OPEN.plusSeconds(100));
        comparator.tick(OPEN.plusSeconds(110));
        assertEquals(40.5, connectedSeconds());
        comparator.onApiLoad(OPEN.plusSeconds(105), entries(a1));
        assertEquals(40.5, connectedSeconds(), "an earlier instant never takes the counter back");
        comparator.tick(OPEN.plusSeconds(120));
        assertEquals(50.5, connectedSeconds());
    }

    @Test
    void everyOutcomeCounterIsRegisteredAtConstructionAtZero() {
        assertCounts(0, 0, 0, 0, 0);
        assertEquals(5, registry.find(OracleStreamComparator.METER_COMPARISON).counters().size());
        assertEquals(Double.NaN, lag());
        assertEquals(0.0, connectedSeconds());
    }

    /**
     * The worst case of each outcome, through the very builders the comparator emits with — a 10-digit {@code ev}
     * and {@code push}, 6-digit seconds, a 4-digit {@code n}, a 10-digit negative {@code dly_ms} — still fits 180
     * bytes as the whole formatted message, so the line stays within 300 under Spring Boot's ~110-byte prefix.
     */
    @Test
    void theWorstCaseCmpMessageOfEachOutcomeFitsTheByteBudget() {
        long push = 9_999_999_999L;
        int n = 9_999;
        String h = "ffffffff";
        String gap = "999999";
        long dly = -9_999_999_999L;
        List<Cmp> worst = List.of(
                OracleStreamComparator.matched(push, n, h, 999_999, 1, gap, dly),
                OracleStreamComparator.superseded(push, n, h, push, gap, dly),
                OracleStreamComparator.mismatch(push, n, h, h, n, gap, dly),
                OracleStreamComparator.apiAhead(push, n, h, 999_999, gap, dly),
                OracleStreamComparator.apiAheadOrphan(n, h),
                OracleStreamComparator.uncovered(push, n, h, "unjudgeable", gap, dly),
                OracleStreamComparator.uncovered(push, n, h, "pre-connect", gap, dly),
                OracleStreamComparator.uncovered(push, n, h, "shutdown", gap, dly),
                OracleStreamComparator.uncovered(push, n, h, "overflow", gap, dly),
                OracleStreamComparator.uncovered(push, n, h, "no-api", gap, dly));
        for (Cmp cmp : worst) {
            String message = OracleStreamLog.PREFIX + "cmp boot=ffffffff ev=9999999999 " + cmp.fields();
            int bytes = message.getBytes(StandardCharsets.UTF_8).length;
            assertTrue(bytes <= BUDGET_BYTES, bytes + " bytes: " + message);
        }
        assertEquals(5, worst.stream().map(Cmp::outcome).distinct().count(), "every outcome");
    }

    // ======================================================================================
    // Helpers
    // ======================================================================================

    /** A push received at {@code at}, stamped by the server 100 ms earlier. */
    private void push(Instant at, OracleRegistryParser.Parsed parsed) {
        comparator.onPush(at, at.toEpochMilli() - 100, parsed, 1000);
    }

    private static Collection<OracleEntry> entries(OracleRegistryParser.Parsed parsed) {
        return parsed.byOracleToken().values();
    }

    private static String h(OracleRegistryParser.Parsed parsed) {
        return OracleStreamComparator.hash(parsed.byOracleToken().values());
    }

    /** The NFT units whose entries differ between two sets of the same NFTs, sorted. */
    private static List<String> differingUnits(OracleRegistryParser.Parsed ws, OracleRegistryParser.Parsed api) {
        Map<String, OracleEntry> wsByUnit = ws.byOracleToken().values().stream()
                .collect(Collectors.toMap(e -> e.oracleToken().toUnit(), Function.identity()));
        Map<String, OracleEntry> apiByUnit = api.byOracleToken().values().stream()
                .collect(Collectors.toMap(e -> e.oracleToken().toUnit(), Function.identity()));
        assertEquals(wsByUnit.keySet(), apiByUnit.keySet(), "fixture: the same NFTs");
        List<String> differ = wsByUnit.keySet().stream().filter(u -> !wsByUnit.get(u).equals(apiByUnit.get(u)))
                .sorted().toList();
        assertNotEquals(List.of(), differ, "fixture: the sets differ");
        return differ;
    }

    private List<ILoggingEvent> cmpEvents() {
        List<ILoggingEvent> events = List.copyOf(appender.list).stream()
                .filter(e -> e.getFormattedMessage().startsWith(OracleStreamLog.PREFIX + "cmp "))
                .toList();
        for (ILoggingEvent event : events) {
            int bytes = event.getFormattedMessage().getBytes(StandardCharsets.UTF_8).length;
            assertTrue(bytes <= BUDGET_BYTES, bytes + " bytes: " + event.getFormattedMessage());
        }
        return events;
    }

    /** Each {@code cmp} line's fields, after {@code boot} and {@code ev}. */
    private List<String> cmpFields() {
        return cmpEvents().stream().map(e -> afterEv(e.getFormattedMessage())).toList();
    }

    private List<String> detailFields(Level level) {
        return List.copyOf(appender.list).stream()
                .filter(e -> e.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith(OracleStreamLog.PREFIX + "cmp-detail "))
                .map(OracleStreamComparatorTest::afterEv)
                .toList();
    }

    private static String afterEv(String message) {
        int ev = message.indexOf(" ev=");
        return message.substring(message.indexOf(' ', ev + 1) + 1);
    }

    private void assertOneLinePerPush(int pushes) {
        List<String> seqs = cmpFields().stream().map(f -> f.substring("push=".length(), f.indexOf(' '))).toList();
        assertEquals(pushes, seqs.size(), "one line per push");
        assertEquals(pushes, Set.copyOf(seqs).size(), "no push twice");
    }

    private void assertCounts(long matched, long superseded, long mismatch, long apiAhead, long uncovered) {
        assertEquals(List.of(matched, superseded, mismatch, apiAhead, uncovered), List.of(
                count("matched"), count("superseded"), count("mismatch"), count("api_ahead"), count("uncovered")),
                "matched, superseded, mismatch, api_ahead, uncovered");
    }

    private long count(String outcome) {
        return (long) registry.get(OracleStreamComparator.METER_COMPARISON).tag("outcome", outcome).counter().count();
    }

    private double lag() {
        return registry.get(OracleStreamComparator.METER_LAG).gauge().value();
    }

    private double connectedSeconds() {
        return registry.get(OracleStreamComparator.METER_CONNECTED_SECONDS).functionCounter().count();
    }
}
