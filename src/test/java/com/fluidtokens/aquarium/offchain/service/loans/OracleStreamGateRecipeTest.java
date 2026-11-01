package com.fluidtokens.aquarium.offchain.service.loans;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FAB-175-2: the W3 gate recipe in docs/oracle-stream.md, run exactly as the doc prints it — extracted between its
 * markers, never copied — over a log the REAL comparator wrote: two boots, more than seven days between them,
 * every line behind a Spring Boot-shaped prefix that itself contains {@code [oracle-stream]} (the thread name).
 *
 * <p>The expected figures are worked out by hand from the timeline (G7):
 * <ul>
 *   <li>boot 1, 2026-11-01T06:00Z → 2026-11-05T06:00Z: up_s 345600; one drop of {@code drop} seconds, healed;
 *       pushes 1 and 3 MATCHED (lag 20, 30), push 2 SUPERSEDED; its longest quiet gap is push 3 (06:10:10) to the
 *       drop (11-03T12:00): 193790 s;</li>
 *   <li>boot 2, 2026-11-05T06:05Z → 2026-11-08T18:05Z: up_s 302400; one planned reconnect 1 s long; push 1
 *       MATCHED (lag 30);</li>
 *   <li>Σ up_s 648000 = 7.50 days.</li>
 * </ul>
 * Every FAIL reason has a run of its own that fails for that reason alone (r2: {@code api_ahead}, {@code unhealed},
 * {@code days}, and Giovanni's 2026-10-29 ruling that the gate fails without evidence: {@code no-pushes},
 * {@code lost-lines}), and so do the counting rules a wrong recipe would get wrong quietly: the (boot, push)
 * de-duplication, the 99.00% boundary, a boot that waited days for its first open, and a server message that
 * names {@code boot=} and {@code ev=}.
 *
 * <p>⛔ No skip: a missing {@code sh} or {@code awk} fails this test.
 */
class OracleStreamGateRecipeTest {

    private static final Path DOC = Path.of("docs/oracle-stream.md");
    private static final String START = "<!-- w3-gate-recipe:start -->";
    private static final String END = "<!-- w3-gate-recipe:end -->";
    private static final String SPRING_PREFIX =
            "2026-11-01T00:00:00.000Z  INFO 1 --- [oracle-stream] c.f.a.o.s.l.X : ";

    private static final Instant B1 = Instant.parse("2026-11-01T06:00:00Z");
    private static final Instant B1_DROP = Instant.parse("2026-11-03T12:00:00Z");
    private static final Instant B1_END = Instant.parse("2026-11-05T06:00:00Z");
    private static final Instant B2 = Instant.parse("2026-11-05T06:05:00Z");
    private static final Instant B2_END = Instant.parse("2026-11-08T18:05:00Z");
    /** The refused boot: its first cmp-tick, its first open almost two days later, and its end. */
    private static final Instant R0 = Instant.parse("2026-11-01T00:00:10Z");
    private static final Instant R_OPEN = Instant.parse("2026-11-02T23:00:10Z");
    private static final Instant R_END = Instant.parse("2026-11-09T00:00:10Z");

    /** The seven-and-a-half-day week's figures, as {@link #twoBootsOverSevenDaysPass} pins them. */
    private static final List<String> PASS_FIGURES = List.of(
            "boots=2",
            "pushes=4",
            "matched=3",
            "superseded=1",
            "mismatch=0",
            "api_ahead=0",
            "uncovered=0",
            "verified_pct=75.00",
            "up_s=648000",
            "conn_s=647399",
            "uptime_pct=99.90",
            "drops=1",
            "heals=1",
            "unhealed=0",
            "gap_max_s=193790",
            "lag_max_s=30",
            "days=7.50");

    /** How boot 2 goes: as planned, or with the one thing a run is about. */
    private enum Two { PLAIN, MISMATCH, API_AHEAD, UNHEALED, NO_PUSHES }

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Logger logger;
    private Collection<OracleEntry> mainnet;
    private OracleRegistryParser.Parsed a1;
    private OracleRegistryParser.Parsed p1;
    private OracleRegistryParser.Parsed p2;

    @BeforeEach
    void setUp() throws Exception {
        LoggerContext context = new LoggerContext();
        logger = context.getLogger("oracle-stream-gate-recipe-test");
        logger.setLevel(Level.DEBUG); // DEBUG lines repeat an ev: they must not read as gaps
        appender.setContext(context);
        appender.start();
        logger.addAppender(appender);
        mainnet = OracleClients.mainnetTwoVersions().entries();
        a1 = OracleRegistryParser.parse(FluidOracleClientLoadPinTest.resource(
                "/loans-v4/oracle-ws/preview-api-20261029T130505Z.json"));
        p1 = OracleRegistryParser.parse(FluidOracleClientLoadPinTest.resource(
                "/loans-v4/oracle-ws/preview-push-20261029T130459Z.json").path("tokens"));
        p2 = OracleRegistryParser.parse(FluidOracleClientLoadPinTest.resource(
                "/loans-v4/oracle-ws/preview-push-20261029T130959Z.json").path("tokens"));
    }

    /**
     * Seven and a half days, a drop of 600 s healed, a 1 s planned reconnect: 647399 / 648000 connected = 99.90%,
     * no MISMATCH, no API_AHEAD — PASS, exit 0. Boot 1's last lines are exported twice (an overlapping daily
     * export) and change nothing.
     */
    @Test
    void twoBootsOverSevenDaysPass() throws Exception {
        List<String> boot1 = bootOne(600);
        List<String> boot2 = bootTwo(Two.PLAIN);
        List<String> log = new ArrayList<>(boot1);
        log.addAll(boot1.subList(boot1.size() - 5, boot1.size()));
        log.addAll(boot2);
        assertTrue(log.stream().filter(l -> l.contains("[oracle-stream] cmp ")).allMatch(
                l -> l.matches(".* dly_ms=-?\\d+ ts=\\d+ rcv=\\d+ xfer_ms=\\d+")),
                "fixture: every cmp line carries FAB-178's ts, rcv and xfer_ms, and the figures do not move");

        Result result = run(log);

        assertEquals(figures(PASS_FIGURES, "ev_gaps=" + boot(boot1) + ":0," + boot(boot2) + ":0",
                "verdict=PASS reasons="), result.lines(), result.toString());
        assertEquals(0, result.exit(), result.toString());
    }

    /**
     * r4, the (boot, push) de-duplication: boot 1 exported twice in full — every {@code cmp} and {@code day} line
     * repeated — counts nothing twice. The figures are the PASS week's exactly.
     */
    @Test
    void aBootExportedTwiceInFullCountsOnce() throws Exception {
        List<String> boot1 = bootOne(600);
        List<String> boot2 = bootTwo(Two.PLAIN);
        List<String> log = new ArrayList<>(boot1);
        log.addAll(boot1);
        log.addAll(boot2);
        assertEquals(3, boot1.stream().filter(l -> l.contains("[oracle-stream] cmp ")).count(), "fixture: 3 cmp");
        assertEquals(7, log.stream().filter(l -> l.contains("[oracle-stream] cmp ")).count(), "fixture: twice, +1");

        Result result = run(log);

        assertEquals(figures(PASS_FIGURES, "ev_gaps=" + boot(boot1) + ":0," + boot(boot2) + ":0",
                "verdict=PASS reasons="), result.lines(), result.toString());
        assertEquals(0, result.exit(), result.toString());
    }

    /**
     * F2, {@code api_ahead}: in boot 2 the API moves to P1, which no push carries; once
     * {@link OracleStreamComparator#WINDOW} has passed that is an orphan {@code push=-}, and the push of P1 that
     * then arrives is API_AHEAD again, attached, with the SAME {@code h}. One API change: {@code api_ahead=1}.
     */
    @Test
    void anOrphanAndALatePushOfTheSameApiChangeAreOneApiAheadAndFail() throws Exception {
        List<String> boot1 = bootOne(600);
        List<String> boot2 = bootTwo(Two.API_AHEAD);
        List<String> log = new ArrayList<>(boot1);
        log.addAll(boot2);
        String h = OracleStreamComparator.hash(entries(p1));
        assertEquals(1, log.stream().filter(l -> l.contains(" push=- out=API_AHEAD n=5 h=" + h)).count(), "fixture");
        assertEquals(1, log.stream().filter(l -> l.contains(" push=2 out=API_AHEAD n=5 h=" + h + " lead_s=350 "))
                .count(), "fixture: the late push, same h");

        Result result = run(log);

        assertEquals(figures(PASS_FIGURES, "pushes=5", "api_ahead=1", "verified_pct=60.00",
                "ev_gaps=" + boot(boot1) + ":0," + boot(boot2) + ":0",
                "verdict=FAIL reasons=api_ahead"), result.lines(), result.toString());
        assertEquals(1, result.exit(), result.toString());
    }

    /**
     * F2, {@code unhealed}: boot 2's connection drops 60 s before its shutdown and never reopens. 647339 of 648000
     * connected is still 99.89%, so the drop with no heal is the only reason. Its quiet gap, the reopen at +6901 to
     * that drop, is now the longest: 295439 s.
     */
    @Test
    void aDropWithNoReopenBeforeShutdownFailsUnhealed() throws Exception {
        List<String> boot1 = bootOne(600);
        List<String> boot2 = bootTwo(Two.UNHEALED);
        List<String> log = new ArrayList<>(boot1);
        log.addAll(boot2);

        Result result = run(log);

        assertEquals(figures(PASS_FIGURES, "conn_s=647339", "uptime_pct=99.89", "drops=2", "unhealed=1",
                "gap_max_s=295439", "ev_gaps=" + boot(boot1) + ":0," + boot(boot2) + ":0",
                "verdict=FAIL reasons=unhealed"), result.lines(), result.toString());
        assertEquals(1, result.exit(), result.toString());
    }

    /** F2, {@code days}: boot 1 alone is four days, healthy in every other way. */
    @Test
    void oneBootOfFourDaysFailsDays() throws Exception {
        List<String> boot1 = bootOne(600);

        Result result = run(boot1);

        assertEquals(figures(PASS_FIGURES, "boots=1", "pushes=3", "matched=2", "verified_pct=66.66", "up_s=345600",
                "conn_s=345000", "uptime_pct=99.82", "days=4.00", "ev_gaps=" + boot(boot1) + ":0",
                "verdict=FAIL reasons=days"), result.lines(), result.toString());
        assertEquals(1, result.exit(), result.toString());
    }

    /**
     * Giovanni's ruling (2026-10-29), the gate fails without evidence — {@code no-pushes}: the same healthy week
     * with not one push. Nothing was compared, so nothing was proven. {@code verified_pct} has nothing to divide by
     * (F4: {@code pushes + 0}, which BusyBox awk needs) and the longest quiet gap is boot 1's open to its drop.
     */
    @Test
    void aWeekWithNoPushFailsNoPushes() throws Exception {
        List<String> boot1 = bootOne(600, false);
        List<String> boot2 = bootTwo(Two.NO_PUSHES);
        List<String> log = new ArrayList<>(boot1);
        log.addAll(boot2);
        assertEquals(0, log.stream().filter(l -> l.contains("[oracle-stream] cmp ")).count(), "fixture: no cmp");

        Result result = run(log);

        assertEquals(figures(PASS_FIGURES, "pushes=0", "matched=0", "superseded=0", "verified_pct=-",
                "gap_max_s=194400", "lag_max_s=0", "ev_gaps=" + boot(boot1) + ":0," + boot(boot2) + ":0",
                "verdict=FAIL reasons=no-pushes"), result.lines(), result.toString());
        assertEquals(1, result.exit(), result.toString());
    }

    /**
     * Giovanni's ruling (2026-10-29), the gate fails without evidence — {@code lost-lines}: the PASS week with one
     * line missing from boot 1 (its first midnight {@code day} line, so no figure moves). The lost line could have
     * been the one that fails the gate, so the gate cannot pass over it.
     */
    @Test
    void oneLostLineFailsLostLines() throws Exception {
        List<String> boot1 = new ArrayList<>(bootOne(600));
        List<String> boot2 = bootTwo(Two.PLAIN);
        String firstDay = boot1.stream().filter(l -> l.contains("[oracle-stream] day ")).findFirst().orElseThrow();
        assertTrue(firstDay.endsWith(" last=0"), firstDay);
        boot1.remove(firstDay);
        List<String> log = new ArrayList<>(boot1);
        log.addAll(boot2);

        Result result = run(log);

        assertEquals(figures(PASS_FIGURES, "ev_gaps=" + boot(boot1) + ":1," + boot(boot2) + ":0",
                "verdict=FAIL reasons=lost-lines"), result.lines(), result.toString());
        assertEquals(1, result.exit(), result.toString());
    }

    /** The same week with one MISMATCH in boot 2 (and its cmp-detail line): FAIL, reason mismatch, exit 1. */
    @Test
    void oneMismatchFails() throws Exception {
        List<String> boot1 = bootOne(600);
        List<String> boot2 = bootTwo(Two.MISMATCH);
        List<String> log = new ArrayList<>(boot1);
        log.addAll(boot2);
        assertEquals(1, log.stream().filter(l -> l.contains(" out=MISMATCH n=")).count(), "fixture: one MISMATCH");

        Result result = run(log);

        assertEquals("mismatch=1", result.lines().get(4), result.toString());
        assertEquals("pushes=5", result.lines().get(1), result.toString());
        assertEquals("verified_pct=60.00", result.lines().get(7), result.toString());
        assertEquals("verdict=FAIL reasons=mismatch", result.lines().getLast(), result.toString());
        assertEquals(1, result.exit(), result.toString());
    }

    /**
     * Boot 1's drop lasts 12959 s: (345600 − 12959) + (302400 − 1) = 635040 connected of 648000 = 98.00% — healed,
     * seven and a half days, nothing else wrong: FAIL, reason uptime only.
     */
    @Test
    void ninetyEightPercentUptimeFails() throws Exception {
        List<String> boot1 = bootOne(12_959);
        List<String> boot2 = bootTwo(Two.PLAIN);
        List<String> log = new ArrayList<>(boot1);
        log.addAll(boot2);

        Result result = run(log);

        assertEquals("conn_s=635040", result.lines().get(9), result.toString());
        assertEquals("uptime_pct=98.00", result.lines().get(10), result.toString());
        assertEquals("unhealed=0", result.lines().get(13), result.toString());
        assertEquals("verdict=FAIL reasons=uptime", result.lines().getLast(), result.toString());
        assertEquals(1, result.exit(), result.toString());
    }

    /**
     * r10, the 99% boundary: a drop of 6479 s leaves (345600 − 6479) + (302400 − 1) = 641520 connected of 648000 —
     * exactly 99.00%, a PASS. One second more, 641519, is 98.99% rounded down: FAIL, reason uptime.
     */
    @Test
    void exactlyNinetyNinePercentPassesAndOneSecondLessFails() throws Exception {
        List<String> boot2 = bootTwo(Two.PLAIN);
        List<String> pass = bootOne(6_479);
        List<String> log = new ArrayList<>(pass);
        log.addAll(boot2);

        Result exact = run(log);

        assertEquals(figures(PASS_FIGURES, "conn_s=641520", "uptime_pct=99.00",
                "ev_gaps=" + boot(pass) + ":0," + boot(boot2) + ":0", "verdict=PASS reasons="), exact.lines(),
                exact.toString());
        assertEquals(0, exact.exit(), exact.toString());

        List<String> fail = bootOne(6_480);
        log = new ArrayList<>(fail);
        log.addAll(boot2);

        Result under = run(log);

        assertEquals(figures(PASS_FIGURES, "conn_s=641519", "uptime_pct=98.99",
                "ev_gaps=" + boot(fail) + ":0," + boot(boot2) + ":0", "verdict=FAIL reasons=uptime"), under.lines(),
                under.toString());
        assertEquals(1, under.exit(), under.toString());
    }

    /**
     * g9, a boot refused for days before its first open: its first {@code cmp-tick} (R0) starts {@code up_s}, so
     * the 47 hours it waited count against its uptime. up_s = R0 → R_END = 691200 (8.00 days), conn_s = R_OPEN →
     * R_END = 522000: 75.52%, FAIL uptime. Were the tick not to start the clock, up_s would begin at the first
     * midnight {@code day} line, a day late, and the refused day would vanish from the figures.
     */
    @Test
    void aBootRefusedForDaysBeforeItsFirstOpenCountsThemAsDown() throws Exception {
        List<String> boot = refusedBoot("no bond");

        Result result = run(boot);

        assertEquals(refusedFigures(boot), result.lines(), result.toString());
        assertEquals(1, result.exit(), result.toString());
    }

    /**
     * F5: a server's refusal text is quoted on the line as {@code msg="…"} and may say anything — here
     * {@code ev=900 boot=zzzz}. The recipe reads {@code boot} and {@code ev} by position, so that line is boot R's
     * and its own {@code ev}: one boot, no gap, the same figures as the plain refused boot.
     */
    @Test
    void aServerMessageNamingBootAndEvChangesNothing() throws Exception {
        List<String> boot = refusedBoot("x ev=900 boot=zzzz");
        assertEquals(1, boot.stream().filter(l -> l.contains(" msg=\"x ev=900 boot=zzzz\" ")).count(), "fixture");

        Result result = run(boot);

        assertEquals(refusedFigures(boot), result.lines(), result.toString());
        assertEquals(1, result.exit(), result.toString());
    }

    /** The doc's block is a single fenced {@code sh} block between the markers, so what the test runs is what it shows. */
    @Test
    void theRecipeIsOneFencedShBlockBetweenTheMarkers() throws IOException {
        String doc = Files.readString(DOC, StandardCharsets.UTF_8);
        assertEquals(1, count(doc, START), "one start marker");
        assertEquals(1, count(doc, END), "one end marker");
        String between = doc.substring(doc.indexOf(START) + START.length(), doc.indexOf(END)).strip();
        assertTrue(between.startsWith("```sh\n") && between.endsWith("\n```"), between);
        assertEquals(2, count(between, "```"), "exactly one fence pair");
        assertNotEquals("", recipe().strip());
    }

    // ======================================================================================
    // The two boots, through the real comparator
    // ======================================================================================

    private List<String> bootOne(long dropSeconds) {
        return bootOne(dropSeconds, true);
    }

    private List<String> bootOne(long dropSeconds, boolean withPushes) {
        appender.list.clear();
        OracleStreamLog log = new OracleStreamLog(logger);
        OracleStreamComparator c = new OracleStreamComparator(log, null);
        c.onApiLoad(B1.minusSeconds(60), mainnet); // the poller's seed
        c.onOpen(B1);
        if (withPushes) {
            push(c, B1.plusSeconds(300), a1);
            c.onApiLoad(B1.plusSeconds(320), entries(a1));
            push(c, B1.plusSeconds(600), p1);
            push(c, B1.plusSeconds(610), p2);
            c.onApiLoad(B1.plusSeconds(640), entries(p2));
        }
        c.tick(B1.plusSeconds(1000));
        c.daySummary(Instant.parse("2026-11-02T00:00:00Z"), false);
        c.daySummary(Instant.parse("2026-11-03T00:00:00Z"), false);
        c.onClose(B1_DROP, "no-pong");
        c.onOpen(B1_DROP.plusSeconds(dropSeconds));
        c.daySummary(Instant.parse("2026-11-04T00:00:00Z"), false);
        c.daySummary(Instant.parse("2026-11-05T00:00:00Z"), false);
        c.shutdown(B1_END);
        return captured(log);
    }

    private List<String> bootTwo(Two variant) {
        appender.list.clear();
        OracleStreamLog log = new OracleStreamLog(logger);
        OracleStreamComparator c = new OracleStreamComparator(log, null);
        c.onApiLoad(B2.minusSeconds(60), entries(p2)); // the seed: what the API served when boot 1 ended
        c.onOpen(B2);
        if (variant != Two.NO_PUSHES) {
            push(c, B2.plusSeconds(200), a1);
            c.onApiLoad(B2.plusSeconds(230), entries(a1));
        }
        if (variant == Two.MISMATCH) {
            push(c, B2.plusSeconds(400), p1);
            c.onApiLoad(B2.plusSeconds(430), entries(a1));
            c.tick(B2.plusSeconds(800));
        }
        if (variant == Two.API_AHEAD) {
            c.onApiLoad(B2.plusSeconds(400), entries(p1)); // no push carries P1 yet
            c.tick(B2.plusSeconds(701)); // WINDOW past the change: the orphan
            push(c, B2.plusSeconds(750), p1); // then a push carries it: attached, lead_s 350
        }
        c.onClose(B2.plusSeconds(6900), "proactive");
        c.onOpen(B2.plusSeconds(6901));
        c.daySummary(Instant.parse("2026-11-06T00:00:00Z"), false);
        c.daySummary(Instant.parse("2026-11-07T00:00:00Z"), false);
        c.daySummary(Instant.parse("2026-11-08T00:00:00Z"), false);
        if (variant == Two.UNHEALED) {
            c.onClose(B2_END.minusSeconds(60), "no-pong");
        }
        c.shutdown(B2_END);
        return captured(log);
    }

    /**
     * A boot whose connection is refused from R0 until R_OPEN: the comparator hears only its {@code cmp-tick}s and
     * the first midnight; the client says {@code refused} with the server's {@code message}. One push, MATCHED.
     */
    private List<String> refusedBoot(String serverMessage) {
        appender.list.clear();
        OracleStreamLog log = new OracleStreamLog(logger);
        OracleStreamComparator c = new OracleStreamComparator(log, null);
        c.onApiLoad(R0.minusSeconds(60), mainnet); // the poller's seed
        c.tick(R0);
        log.warn("refused", "status=403 " + OracleStreamLog.msg(serverMessage) + " retry_s=3600",
                "no Aquarium staking bond for this wallet's stake key; retrying hourly or when the stake appears");
        c.tick(R0.plusSeconds(10));
        c.daySummary(Instant.parse("2026-11-02T00:00:00Z"), false);
        c.onOpen(R_OPEN);
        push(c, R_OPEN.plusSeconds(300), a1);
        c.onApiLoad(R_OPEN.plusSeconds(330), entries(a1));
        for (int day = 3; day <= 9; day++) {
            c.daySummary(Instant.parse("2026-11-0" + day + "T00:00:00Z"), false);
        }
        c.shutdown(R_END);
        return captured(log);
    }

    /** {@link #refusedBoot}'s figures, worked out by hand. */
    private static List<String> refusedFigures(List<String> boot) {
        return List.of(
                "boots=1",
                "pushes=1",
                "matched=1",
                "superseded=0",
                "mismatch=0",
                "api_ahead=0",
                "uncovered=0",
                "verified_pct=100.00",
                "up_s=691200",
                "conn_s=522000",
                "uptime_pct=75.52",
                "drops=0",
                "heals=0",
                "unhealed=0",
                "gap_max_s=300",
                "lag_max_s=30",
                "days=8.00",
                "ev_gaps=" + boot(boot) + ":0",
                "verdict=FAIL reasons=uptime");
    }

    /**
     * {@code figures} with each {@code key=value} in {@code changes} replacing the line of the same key; a change
     * whose key is not there ({@code ev_gaps}, {@code verdict}) is appended, in order.
     */
    private static List<String> figures(List<String> figures, String... changes) {
        List<String> out = new ArrayList<>(figures);
        for (String change : changes) {
            String key = change.substring(0, change.indexOf('=') + 1);
            int at = -1;
            for (int i = 0; i < out.size(); i++) {
                if (out.get(i).startsWith(key)) {
                    at = i;
                }
            }
            if (at >= 0) {
                out.set(at, change);
            } else {
                out.add(change);
            }
        }
        return out;
    }

    /** Every line the boot logged, behind the fake Spring Boot prefix, in order. */
    private List<String> captured(OracleStreamLog log) {
        List<String> lines = List.copyOf(appender.list).stream()
                .map(ILoggingEvent::getFormattedMessage)
                .peek(m -> assertTrue(m.contains(" boot=" + log.bootId() + " "), m))
                .map(m -> SPRING_PREFIX + m)
                .toList();
        assertTrue(lines.stream().anyMatch(l -> l.contains("[oracle-stream] day ") && l.endsWith(" last=1")),
                "fixture: the boot's final day line");
        return lines;
    }

    private static String boot(List<String> lines) {
        String first = lines.getFirst();
        int at = first.indexOf(" boot=") + " boot=".length();
        return first.substring(at, first.indexOf(' ', at));
    }

    private static void push(OracleStreamComparator c, Instant at, OracleRegistryParser.Parsed parsed) {
        c.onPush(at, at.toEpochMilli() - 100, parsed, 1000);
    }

    private static Collection<OracleEntry> entries(OracleRegistryParser.Parsed parsed) {
        return parsed.byOracleToken().values();
    }

    // ======================================================================================
    // Running the doc's recipe
    // ======================================================================================

    record Result(int exit, List<String> lines, String stderr) {
    }

    /** The fenced block between the markers, fence lines dropped — what an operator saves as w3-gate.sh. */
    private static String recipe() throws IOException {
        String doc = Files.readString(DOC, StandardCharsets.UTF_8);
        String between = doc.substring(doc.indexOf(START) + START.length(), doc.indexOf(END));
        StringBuilder script = new StringBuilder();
        for (String line : between.split("\n", -1)) {
            if (!line.startsWith("```")) {
                script.append(line).append('\n');
            }
        }
        return script.toString();
    }

    /**
     * {@code sh -c <recipe>} with the log on stdin, under {@code POSIXLY_CORRECT} (GNU awk then refuses its own
     * extensions). A missing {@code sh} throws; a missing {@code awk} exits 127 — both fail the caller's asserts.
     */
    private static Result run(List<String> log) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("sh", "-c", recipe());
        builder.environment().put("POSIXLY_CORRECT", "1");
        builder.environment().put("LC_ALL", "C");
        Process process = builder.start();
        CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> read(process.getInputStream()));
        CompletableFuture<String> err = CompletableFuture.supplyAsync(() -> read(process.getErrorStream()));
        try (OutputStream in = process.getOutputStream()) {
            in.write((String.join("\n", log) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the recipe did not finish");
        String stdout = out.get(5, TimeUnit.SECONDS);
        String stderr = err.get(5, TimeUnit.SECONDS);
        assertEquals("", stderr, "the recipe wrote to stderr");
        List<String> lines = stdout.isEmpty() ? List.of() : List.of(stdout.split("\n"));
        assertTrue(!lines.isEmpty() && lines.getLast().startsWith("verdict="), "no verdict line: " + stdout);
        return new Result(process.exitValue(), lines, stderr);
    }

    private static String read(InputStream stream) {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            n++;
        }
        return n;
    }
}
