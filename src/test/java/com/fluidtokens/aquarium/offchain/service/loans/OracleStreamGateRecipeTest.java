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
 * ⛔ No skip: a missing {@code sh} or {@code awk} fails this test.
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
        List<String> boot2 = bootTwo(false);
        List<String> log = new ArrayList<>(boot1);
        log.addAll(boot1.subList(boot1.size() - 5, boot1.size()));
        log.addAll(boot2);

        Result result = run(log);

        assertEquals(List.of(
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
                "days=7.50",
                "ev_gaps=" + boot(boot1) + ":0," + boot(boot2) + ":0",
                "verdict=PASS reasons="), result.lines(), result.toString());
        assertEquals(0, result.exit(), result.toString());
    }

    /** The same week with one MISMATCH in boot 2 (and its cmp-detail line): FAIL, reason mismatch, exit 1. */
    @Test
    void oneMismatchFails() throws Exception {
        List<String> boot1 = bootOne(600);
        List<String> boot2 = bootTwo(true);
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
        List<String> boot2 = bootTwo(false);
        List<String> log = new ArrayList<>(boot1);
        log.addAll(boot2);

        Result result = run(log);

        assertEquals("conn_s=635040", result.lines().get(9), result.toString());
        assertEquals("uptime_pct=98.00", result.lines().get(10), result.toString());
        assertEquals("unhealed=0", result.lines().get(13), result.toString());
        assertEquals("verdict=FAIL reasons=uptime", result.lines().getLast(), result.toString());
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
        appender.list.clear();
        OracleStreamLog log = new OracleStreamLog(logger);
        OracleStreamComparator c = new OracleStreamComparator(log, null);
        c.onApiLoad(B1.minusSeconds(60), mainnet); // the poller's seed
        c.onOpen(B1);
        push(c, B1.plusSeconds(300), a1);
        c.onApiLoad(B1.plusSeconds(320), entries(a1));
        push(c, B1.plusSeconds(600), p1);
        push(c, B1.plusSeconds(610), p2);
        c.onApiLoad(B1.plusSeconds(640), entries(p2));
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

    private List<String> bootTwo(boolean withMismatch) {
        appender.list.clear();
        OracleStreamLog log = new OracleStreamLog(logger);
        OracleStreamComparator c = new OracleStreamComparator(log, null);
        c.onApiLoad(B2.minusSeconds(60), entries(p2)); // the seed: what the API served when boot 1 ended
        c.onOpen(B2);
        push(c, B2.plusSeconds(200), a1);
        c.onApiLoad(B2.plusSeconds(230), entries(a1));
        if (withMismatch) {
            push(c, B2.plusSeconds(400), p1);
            c.onApiLoad(B2.plusSeconds(430), entries(a1));
            c.tick(B2.plusSeconds(800));
        }
        c.onClose(B2.plusSeconds(6900), "proactive");
        c.onOpen(B2.plusSeconds(6901));
        c.daySummary(Instant.parse("2026-11-06T00:00:00Z"), false);
        c.daySummary(Instant.parse("2026-11-07T00:00:00Z"), false);
        c.daySummary(Instant.parse("2026-11-08T00:00:00Z"), false);
        c.shutdown(B2_END);
        return captured(log);
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
