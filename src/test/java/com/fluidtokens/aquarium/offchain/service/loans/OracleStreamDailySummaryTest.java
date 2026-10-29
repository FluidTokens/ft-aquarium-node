package com.fluidtokens.aquarium.offchain.service.loans;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FAB-175-2: the cumulative {@code [oracle-stream] day} line — every field after a scripted fake-time sequence, the
 * open span counted in {@code conn_s}, the midnight delay from a fixed clock, and the line's byte bound. The
 * arithmetic is the W3 gate's input (G7), so it is pinned with explicit instants, never a running clock.
 */
class OracleStreamDailySummaryTest {

    private static final Instant T0 = Instant.parse("2026-11-02T10:00:00Z");
    private static final String API = "/loans-v4/oracle-ws/preview-api-20261029T130505Z.json";
    private static final String PUSH_1 = "/loans-v4/oracle-ws/preview-push-20261029T130459Z.json";
    private static final String PUSH_2 = "/loans-v4/oracle-ws/preview-push-20261029T130959Z.json";
    private static final int SUMMARY_BUDGET_BYTES = 250;

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private OracleStreamComparator comparator;
    private OracleRegistryParser.Parsed a1;
    private OracleRegistryParser.Parsed p1;
    private OracleRegistryParser.Parsed p2;

    @BeforeEach
    void setUp() throws Exception {
        LoggerContext context = new LoggerContext();
        Logger logger = context.getLogger("oracle-stream-daily-summary-test");
        logger.setLevel(Level.DEBUG);
        appender.setContext(context);
        appender.start();
        logger.addAppender(appender);
        comparator = new OracleStreamComparator(new OracleStreamLog(logger), null);

        a1 = OracleRegistryParser.parse(FluidOracleClientLoadPinTest.resource(API));
        p1 = OracleRegistryParser.parse(FluidOracleClientLoadPinTest.resource(PUSH_1).path("tokens"));
        p2 = OracleRegistryParser.parse(FluidOracleClientLoadPinTest.resource(PUSH_2).path("tokens"));
        // The poller's seed, before the stream starts: an API-side instant never starts up_s.
        comparator.onApiLoad(T0.minusSeconds(600), OracleClients.mainnetTwoVersions().entries());
    }

    /**
     * Open, two matched pushes, one superseded, a drop and its heal, a planned reconnect, a midnight summary, then
     * shutdown. Every field is worked out by hand:
     * <ul>
     *   <li>up_s counts from the first open (T0): 2101 at the summary, 2201 at shutdown;</li>
     *   <li>conn_s = 1000 (T0 → drop) + 940 (heal at +1060 → proactive close at +2000) + the span open since +2001:
     *       100 at the summary, 200 at shutdown;</li>
     *   <li>gap_max_s = 590: the last push at +410 to the drop at +1000 — larger than 100 (open → first push), 300,
     *       10, 440 (heal → push 4) and 500 (push 4 → the proactive close);</li>
     *   <li>lag_max_s = 40 (push 3), larger than push 1's 20.</li>
     * </ul>
     */
    @Test
    void everyFieldAfterAScriptedSequence() {
        comparator.onOpen(T0);
        push(T0.plusSeconds(100), a1);
        comparator.onApiLoad(T0.plusSeconds(120), entries(a1));
        push(T0.plusSeconds(400), p1);
        push(T0.plusSeconds(410), p2);
        comparator.onApiLoad(T0.plusSeconds(450), entries(p2));
        comparator.tick(T0.plusSeconds(800));
        comparator.onClose(T0.plusSeconds(1000), "no-pong");
        comparator.onOpen(T0.plusSeconds(1060));
        push(T0.plusSeconds(1500), a1);
        comparator.onClose(T0.plusSeconds(2000), "proactive");
        comparator.onOpen(T0.plusSeconds(2001));
        comparator.daySummary(T0.plusSeconds(2101), false);

        assertEquals(List.of("push=4 up_s=2101 conn_s=2040 pushes=4 matched=2 superseded=1 mismatch=0 api_ahead=0"
                + " uncovered=0 lag_max_s=40 gap_max_s=590 drops=1 heals=1 proactive=1 last=0"), dayFields());
        assertEquals(Level.INFO, dayEvents().getFirst().getLevel());

        comparator.shutdown(T0.plusSeconds(2201));
        assertEquals("push=4 up_s=2201 conn_s=2140 pushes=4 matched=2 superseded=1 mismatch=0 api_ahead=0"
                + " uncovered=1 lag_max_s=40 gap_max_s=590 drops=1 heals=1 proactive=1 last=1", dayFields().get(1));
        assertTrue(messages().indexOf(messages().stream().filter(m -> m.contains(" why=shutdown ")).findFirst()
                .orElseThrow()) < messages().indexOf(dayMessages().get(1)), "pending pushes resolve before the line");
    }

    /**
     * {@code conn_s} counts the connection open right now, up to the summary's instant — and nothing while closed.
     * A {@code shutdown} close is not a drop; a drop with no open after it is not healed. {@code gap_max_s} moves
     * at a close (7200: the open to that close, no push), never at a summary: the first line still says 0.
     */
    @Test
    void connSecondsIncludeTheOpenSpanAndOnlyAnOpenAfterADropHeals() {
        comparator.onOpen(T0);
        comparator.daySummary(T0.plusSeconds(3600), false);
        comparator.onClose(T0.plusSeconds(7200), "shutdown");
        comparator.daySummary(T0.plusSeconds(9000), false);
        comparator.onOpen(T0.plusSeconds(9000));
        comparator.onClose(T0.plusMillis(9_500_900), "server-close(1006)");
        comparator.daySummary(T0.plusSeconds(10_000), false);

        assertEquals(List.of(
                "push=0 up_s=3600 conn_s=3600 pushes=0 matched=0 superseded=0 mismatch=0 api_ahead=0 uncovered=0"
                        + " lag_max_s=0 gap_max_s=0 drops=0 heals=0 proactive=0 last=0",
                "push=0 up_s=9000 conn_s=7200 pushes=0 matched=0 superseded=0 mismatch=0 api_ahead=0 uncovered=0"
                        + " lag_max_s=0 gap_max_s=7200 drops=0 heals=0 proactive=0 last=0",
                "push=0 up_s=10000 conn_s=7700 pushes=0 matched=0 superseded=0 mismatch=0 api_ahead=0 uncovered=0"
                        + " lag_max_s=0 gap_max_s=7200 drops=1 heals=0 proactive=0 last=0"), dayFields());
    }

    /** The delay {@code OracleStreamLauncher} schedules the next {@code day} line with, from a fixed clock. */
    @Test
    void theDelayToTheNextUtcMidnight() {
        assertEquals(Duration.ofSeconds(1), delayAt("2026-11-01T23:59:59Z"));
        assertEquals(Duration.ofHours(24), delayAt("2026-11-02T00:00:00Z"), "at midnight: the NEXT one");
        assertEquals(Duration.ofMillis(1), delayAt("2026-11-01T23:59:59.999Z"));
        assertEquals(Duration.parse("PT11H25M3.211S"), delayAt("2026-11-01T12:34:56.789Z"));
    }

    /**
     * Every field at eight digits — about three years of seconds and a hundred million pushes — and
     * {@code lag_max_s} at three (it never exceeds {@link OracleStreamComparator#WINDOW}): the fields fit 250
     * bytes, and the whole message, under a ten-digit {@code ev}, stays within 300.
     */
    @Test
    void theWorstCaseDayLineFitsItsByteBound() {
        long max = 99_999_999L;
        String fields = new OracleStreamComparator.Day(max, max, max, max, max, max, max, max, max, 300, max, max,
                max, max, true).fields();
        int fieldBytes = fields.getBytes(StandardCharsets.UTF_8).length;
        assertTrue(fieldBytes <= SUMMARY_BUDGET_BYTES, fieldBytes + " bytes: " + fields);
        String message = OracleStreamLog.PREFIX + "day boot=ffffffff ev=9999999999 " + fields;
        int messageBytes = message.getBytes(StandardCharsets.UTF_8).length;
        assertTrue(messageBytes <= 300, messageBytes + " bytes: " + message);
    }

    // ======================================================================================
    // Helpers
    // ======================================================================================

    private static Duration delayAt(String instant) {
        return OracleStreamLauncher.nextUtcMidnightDelay(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC).instant());
    }

    private void push(Instant at, OracleRegistryParser.Parsed parsed) {
        comparator.onPush(at, at.toEpochMilli() - 100, parsed, 1000);
    }

    private static Collection<OracleEntry> entries(OracleRegistryParser.Parsed parsed) {
        return parsed.byOracleToken().values();
    }

    private List<String> messages() {
        return List.copyOf(appender.list).stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private List<ILoggingEvent> dayEvents() {
        return List.copyOf(appender.list).stream()
                .filter(e -> e.getFormattedMessage().startsWith(OracleStreamLog.PREFIX + "day "))
                .toList();
    }

    private List<String> dayMessages() {
        return dayEvents().stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /** Each {@code day} line's fields, after {@code boot} and {@code ev}. */
    private List<String> dayFields() {
        return dayMessages().stream().map(m -> {
            int ev = m.indexOf(" ev=");
            return m.substring(m.indexOf(' ', ev + 1) + 1);
        }).toList();
    }
}
