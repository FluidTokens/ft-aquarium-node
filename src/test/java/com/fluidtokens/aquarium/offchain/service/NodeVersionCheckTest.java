package com.fluidtokens.aquarium.offchain.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GitHub issue #55: the node says in its log whether the build it runs is behind {@code main}, by asking
 * GitHub's compare API {@code compare/<own sha>...main}.
 *
 * <p>⚠ GitHub reports the HEAD side ({@code main}) relative to the BASE side (this build), so a node BEHIND
 * main is answered {@code status: "ahead"} with its count in {@code ahead_by} — measured against the live API
 * on 2026-10-18; the answers below are those responses, reduced to the fields this class reads.
 *
 * <p>No test here contacts the internet: the seam is a lambda, and the real JDK adapter is exercised only on
 * loopback. Every case asserts the level AND that the run produced exactly one line.
 */
class NodeVersionCheckTest {

    private static final String SHA = "64d43150c047d5d909dd20f58e9f9bd61c82e707";
    private static final String SHORT = "64d43150c047";
    private static final String COMPARE_LINK =
            "https://github.com/FluidTokens/ft-aquarium-node/compare/" + SHA + "...main";
    private static final String THREAD = "version-check";
    /** The real 404 body, verbatim in shape: note its {@code status} field, a STRING. */
    private static final String NOT_FOUND_BODY = "{\"message\":\"Not Found\","
            + "\"documentation_url\":\"https://docs.github.com/rest/commits/commits#compare-two-commits\","
            + "\"status\":\"404\"}";

    private final Logger logger = (Logger) LoggerFactory.getLogger(NodeVersionCheck.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;

    @BeforeEach
    void attach() {
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
    }

    // ======================================================================================

    private static String answer(String status, String aheadBy, String behindBy) {
        StringBuilder json = new StringBuilder("{\"url\":\"https://api.github.com/x\"");
        if (status != null) {
            json.append(",\"status\":").append(status);
        }
        if (aheadBy != null) {
            json.append(",\"ahead_by\":").append(aheadBy);
        }
        if (behindBy != null) {
            json.append(",\"behind_by\":").append(behindBy);
        }
        return json.append(",\"total_commits\":0,\"commits\":[],\"files\":[]}").toString();
    }

    private static NodeVersionCheck.CompareSource serving(int status, String body) {
        return commit -> new NodeVersionCheck.Answer(status, body);
    }

    private static NodeVersionCheck check(String enabled, String commit, String dirty,
                                          NodeVersionCheck.CompareSource source) {
        return new NodeVersionCheck(enabled, commit, dirty, source, Duration.ofHours(24), Duration.ofSeconds(10));
    }

    private static NodeVersionCheck check(NodeVersionCheck.CompareSource source) {
        return check("true", SHA, "false", source);
    }

    private List<ILoggingEvent> lines() {
        synchronized (appender) {
            return new ArrayList<>(appender.list);
        }
    }

    /** Runs once and returns the ONE line it produced, failing if it produced zero or several. */
    private ILoggingEvent onlyLineOf(NodeVersionCheck check) {
        int before = lines().size();
        assertDoesNotThrow(check::runOnce, "a run must never throw");
        List<ILoggingEvent> produced = lines().subList(before, lines().size());
        assertEquals(1, produced.size(), "exactly one line per run: " + produced);
        return produced.getFirst();
    }

    private static void assertUnavailable(ILoggingEvent line) {
        assertEquals(Level.INFO, line.getLevel(), line.getFormattedMessage());
        assertTrue(line.getFormattedMessage().startsWith("version check unavailable: "), line.getFormattedMessage());
        assertFalse(line.getFormattedMessage().contains("behind"), "never reported as behind: "
                + line.getFormattedMessage());
    }

    private static boolean versionCheckThreadAlive() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> THREAD.equals(t.getName()) && t.isAlive());
    }

    private static void awaitNoVersionCheckThread() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (versionCheckThreadAlive() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertFalse(versionCheckThreadAlive(), "a version-check thread outlived stop()");
    }

    // ======================================================================================
    // The four answers GitHub gives
    // ======================================================================================

    @Test
    void identicalIsOneInfoLineNamingTheBuild() {
        ILoggingEvent line = onlyLineOf(check(serving(200, answer("\"identical\"", "0", "0"))));

        assertEquals(Level.INFO, line.getLevel());
        assertTrue(line.getFormattedMessage().contains(SHORT), line.getFormattedMessage());
        assertTrue(line.getFormattedMessage().contains("up to date"), line.getFormattedMessage());
    }

    /** {@code status: ahead} means MAIN is ahead of this build: the node is behind, by {@code ahead_by}. */
    @Test
    void aheadIsOneWarnWithTheCountTheShortShaAndTheCompareLink() {
        ILoggingEvent line = onlyLineOf(check(serving(200, answer("\"ahead\"", "3", "0"))));

        assertEquals(Level.WARN, line.getLevel());
        String message = line.getFormattedMessage();
        assertTrue(message.contains("3 commits behind main"), message);
        assertTrue(message.contains(SHORT), message);
        assertTrue(message.contains(COMPARE_LINK), "the link carries the FULL sha: " + message);
        for (String claim : new String[]{"available", "latest image", "update now"}) {
            assertFalse(message.toLowerCase().contains(claim), "asserts an image exists for main: " + message);
        }
    }

    @Test
    void oneCommitBehindIsSingular() {
        ILoggingEvent line = onlyLineOf(check(serving(200, answer("\"ahead\"", "1", "0"))));

        assertEquals(Level.WARN, line.getLevel());
        assertTrue(line.getFormattedMessage().contains("1 commit behind main"), line.getFormattedMessage());
        assertFalse(line.getFormattedMessage().contains("1 commits"), line.getFormattedMessage());
    }

    @Test
    void divergedIsOneWarnNamingBothCounts() {
        ILoggingEvent line = onlyLineOf(check(serving(200, answer("\"diverged\"", "74", "9"))));

        assertEquals(Level.WARN, line.getLevel());
        String message = line.getFormattedMessage();
        assertTrue(message.contains("74 commits behind main"), message);
        assertTrue(message.contains("9 commits that are not on main"), message);
        assertTrue(message.contains(COMPARE_LINK), message);
    }

    /** {@code status: behind} means this build is AHEAD of main — not a build of main, and never a WARN. */
    @Test
    void behindIsInfoNeverWarn() {
        ILoggingEvent line = onlyLineOf(check(serving(200, answer("\"behind\"", "0", "9"))));

        assertEquals(Level.INFO, line.getLevel());
        assertTrue(line.getFormattedMessage().contains("9 commits ahead of main"), line.getFormattedMessage());
    }

    // ======================================================================================
    // Not an answer: never behind, never WARN
    // ======================================================================================

    /** The 404 body carries {@code "status":"404"}: the HTTP code must be decided before any field is read. */
    @Test
    void notFoundIsUnknownToGitHubNotBehind() {
        ILoggingEvent line = onlyLineOf(check(serving(404, NOT_FOUND_BODY)));

        assertUnavailable(line);
        assertTrue(line.getFormattedMessage().contains("unknown to GitHub"), line.getFormattedMessage());
        for (String bodyText : new String[]{"Not Found", "documentation_url", "docs.github.com"}) {
            assertFalse(line.getFormattedMessage().contains(bodyText),
                    "the 404 body reached the log line: " + line.getFormattedMessage());
        }
    }

    @Test
    void forbiddenAndTooManyRequestsNameTheRateLimit() {
        for (int code : new int[]{403, 429}) {
            ILoggingEvent line = onlyLineOf(check(serving(code, "{\"message\":\"API rate limit exceeded\"}")));

            assertUnavailable(line);
            assertTrue(line.getFormattedMessage().contains("HTTP " + code), line.getFormattedMessage());
            assertTrue(line.getFormattedMessage().contains("limit"), line.getFormattedMessage());
            assertFalse(line.getFormattedMessage().contains("API rate limit exceeded"),
                    "no response text in a log line: " + line.getFormattedMessage());
        }
    }

    @Test
    void anyOtherStatusIsUnavailableWithItsCode() {
        ILoggingEvent line = onlyLineOf(check(serving(500, answer("\"ahead\"", "3", "0"))));

        assertUnavailable(line);
        assertTrue(line.getFormattedMessage().contains("HTTP 500"), line.getFormattedMessage());
    }

    @Test
    void everyFailureOfTheSeamIsUnavailableAndTheRunReturnsNormally() {
        List<Exception> failures = List.of(
                new IOException("Connection refused"),
                new HttpTimeoutException("request timed out"),
                new TimeoutException(),
                new IllegalStateException("boom\r\nforged line"));
        for (Exception failure : failures) {
            ILoggingEvent line = onlyLineOf(check(commit -> {
                if (failure instanceof IOException io) {
                    throw io;
                }
                if (failure instanceof TimeoutException timeout) {
                    throw timeout;
                }
                throw (RuntimeException) failure;
            }));

            assertUnavailable(line);
            assertFalse(line.getFormattedMessage().contains("\n") || line.getFormattedMessage().contains("\r"),
                    "control characters reach the log: " + line.getFormattedMessage());
        }
    }

    /** The JDK's HTTP/1.1 parser quotes the offending response text in a ProtocolException's message. */
    @Test
    void aProtocolErrorIsLoggedByNameWithoutTheResponseText() {
        ILoggingEvent line = onlyLineOf(check(commit -> {
            throw new java.net.ProtocolException("Invalid status line: \"RESPONSE-TEXT-SENTINEL\"");
        }));

        assertUnavailable(line);
        assertTrue(line.getFormattedMessage().contains("ProtocolException"), line.getFormattedMessage());
        assertFalse(line.getFormattedMessage().contains("RESPONSE-TEXT-SENTINEL"), line.getFormattedMessage());
    }

    @Test
    void aTimeoutSaysGitHubDidNotAnswer() {
        ILoggingEvent line = onlyLineOf(check(commit -> {
            throw new TimeoutException();
        }));

        assertUnavailable(line);
        assertTrue(line.getFormattedMessage().contains("did not answer within 10s"), line.getFormattedMessage());
    }

    /** A shape this class does not recognise is not an answer — least of all a count. */
    @Test
    void anUnrecognisedSuccessIsUnavailable() {
        List<String> bodies = List.of(
                "<html>not json</html>",
                "[1,2,3]",
                answer(null, "3", "0"),
                answer("\"sideways\"", "3", "0"),
                answer("3", "3", "0"),
                answer("\"ahead\"", null, "0"),
                answer("\"ahead\"", "3", null),
                answer("\"ahead\"", "0", "0"),
                answer("\"ahead\"", "-3", "0"),
                answer("\"ahead\"", "3.5", "0"),
                answer("\"ahead\"", "\"3\"", "0"),
                answer("\"ahead\"", "3", "2"),
                answer("\"behind\"", "0", "0"),
                answer("\"behind\"", "1", "9"),
                answer("\"diverged\"", "74", "0"),
                answer("\"identical\"", "0", "1"),
                answer("\"identical\"", "2", "0"));
        for (String body : bodies) {
            ILoggingEvent line = onlyLineOf(check(serving(200, body)));

            assertUnavailable(line);
            assertTrue(line.getFormattedMessage().contains("unrecognised answer from GitHub"),
                    body + " -> " + line.getFormattedMessage());
        }
    }

    @Test
    void aBuildWithoutAUsableCommitMakesNoRequest() {
        for (String commit : new String[]{"unknown", "", "64d43150c047", SHA.toUpperCase(), SHA + "0",
                "zz" + SHA.substring(2), SHA.substring(0, 39) + "/", "../" + SHA.substring(3)}) {
            AtomicInteger calls = new AtomicInteger();
            ILoggingEvent line = onlyLineOf(check("true", commit, "unknown", c -> {
                calls.incrementAndGet();
                return new NodeVersionCheck.Answer(200, answer("\"identical\"", "0", "0"));
            }));

            assertUnavailable(line);
            assertEquals(0, calls.get(), "no request for commit '" + commit + "'");
        }
    }

    /** The request names the validated commit and nothing else. */
    @Test
    void theSeamIsAskedForTheRecordedCommit() {
        AtomicReference<String> asked = new AtomicReference<>();
        onlyLineOf(check(commit -> {
            asked.set(commit);
            return new NodeVersionCheck.Answer(200, answer("\"identical\"", "0", "0"));
        }));

        assertEquals(SHA, asked.get());
    }

    @Test
    void aDirtyBuildSaysSoOnWhateverLineItsRunProduces() {
        String identical = onlyLineOf(check("true", SHA, "true", serving(200, answer("\"identical\"", "0", "0"))))
                .getFormattedMessage();
        ILoggingEvent behind = onlyLineOf(check("true", SHA, "true", serving(200, answer("\"ahead\"", "3", "0"))));

        assertTrue(identical.contains("uncommitted changes"), identical);
        assertEquals(Level.WARN, behind.getLevel());
        assertTrue(behind.getFormattedMessage().contains("uncommitted changes"), behind.getFormattedMessage());

        String clean = onlyLineOf(check("true", SHA, "false", serving(200, answer("\"identical\"", "0", "0"))))
                .getFormattedMessage();
        String cleanBehind = onlyLineOf(check("true", SHA, "false", serving(200, answer("\"ahead\"", "3", "0"))))
                .getFormattedMessage();
        assertFalse(clean.contains("uncommitted"), clean);
        assertFalse(cleanBehind.contains("uncommitted"), cleanBehind);
    }

    // ======================================================================================
    // The flag and the lifecycle
    // ======================================================================================

    @Test
    void offMeansOneInfoLineNoThreadAndNoRequest() throws Exception {
        awaitNoVersionCheckThread();
        for (String value : new String[]{"false", " FALSE ", "0", "off", "no"}) {
            AtomicInteger calls = new AtomicInteger();
            NodeVersionCheck check = check(value, SHA, "false", c -> {
                calls.incrementAndGet();
                return new NodeVersionCheck.Answer(200, answer("\"identical\"", "0", "0"));
            });
            int before = lines().size();
            try {
                check.start();
                Thread.sleep(50);

                List<ILoggingEvent> produced = lines().subList(before, lines().size());
                assertEquals(1, produced.size(), "flag '" + value + "': " + produced);
                assertEquals(Level.INFO, produced.getFirst().getLevel());
                String message = produced.getFirst().getFormattedMessage();
                assertTrue(message.startsWith("version check: off"), message);
                assertTrue(message.contains("will not contact GitHub"), message);
                assertEquals(0, calls.get(), "flag '" + value + "' made a request");
                assertFalse(versionCheckThreadAlive(), "flag '" + value + "' created a thread");
            } finally {
                check.stop();
            }
        }
    }

    @Test
    void aValueThatIsNotUnderstoodIsOffAndSaysSo() throws Exception {
        awaitNoVersionCheckThread();
        AtomicInteger calls = new AtomicInteger();
        NodeVersionCheck check = check("banana\n", SHA, "false", c -> {
            calls.incrementAndGet();
            return new NodeVersionCheck.Answer(200, answer("\"identical\"", "0", "0"));
        });
        try {
            check.start();
            Thread.sleep(50);

            List<ILoggingEvent> produced = lines();
            assertEquals(1, produced.size(), produced.toString());
            assertEquals(Level.INFO, produced.getFirst().getLevel());
            String message = produced.getFirst().getFormattedMessage();
            assertTrue(message.startsWith("version check: off"), message);
            assertTrue(message.contains("not understood"), message);
            assertTrue(message.contains("banana"), message);
            assertFalse(message.contains("\n"), message);
            assertEquals(0, calls.get());
            assertFalse(versionCheckThreadAlive());
        } finally {
            check.stop();
        }
    }

    /** Unset, empty (what {@code AQUARIUM_VERSION_CHECK_ENABLED=} resolves to), and the ON words: ON. */
    @Test
    void unsetEmptyAndTheOnWordsMeanOn() throws Exception {
        for (String value : new String[]{null, "", "  ", "true", "TRUE", "on", "yes", "1"}) {
            CountDownLatch asked = new CountDownLatch(1);
            NodeVersionCheck check = check(value, SHA, "false", c -> {
                asked.countDown();
                return new NodeVersionCheck.Answer(200, answer("\"identical\"", "0", "0"));
            });
            try {
                check.start();
                assertTrue(asked.await(5, TimeUnit.SECONDS), "flag '" + value + "' did not run the check");
            } finally {
                check.stop();
                awaitNoVersionCheckThread();
            }
        }
    }

    /** GitHub can take as long as it likes: {@code start()} returns first, and the run is on its own thread. */
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void startReturnsWhileGitHubIsStillThinking() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        NodeVersionCheck check = check(commit -> {
            ranOn.set(Thread.currentThread());
            entered.countDown();
            release.await();
            return new NodeVersionCheck.Answer(200, answer("\"ahead\"", "3", "0"));
        });
        try {
            check.start();

            assertTrue(entered.await(5, TimeUnit.SECONDS), "the check never ran");
            assertEquals(0, lines().size(), "start() returned only after a line: " + lines());
            assertEquals(THREAD, ranOn.get().getName());
            assertTrue(ranOn.get().isDaemon(), "the version-check thread must not keep the JVM alive");
            assertFalse(ranOn.get() == Thread.currentThread());

            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (lines().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            Thread.sleep(50);
            List<ILoggingEvent> produced = lines();
            assertEquals(1, produced.size(), produced.toString());
            assertEquals(Level.WARN, produced.getFirst().getLevel());
        } finally {
            release.countDown();
            check.stop();
        }
        awaitNoVersionCheckThread();
    }

    /** One run that throws must not end the daily check: the next scheduled run still happens. */
    @Test
    void aRunThatFailsDoesNotCancelTheNextOne() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch twice = new CountDownLatch(2);
        NodeVersionCheck check = new NodeVersionCheck("true", SHA, "false", commit -> {
            twice.countDown();
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("first run fails");
            }
            return new NodeVersionCheck.Answer(200, answer("\"identical\"", "0", "0"));
        }, Duration.ofMillis(50), Duration.ofSeconds(10));
        try {
            check.start();
            assertTrue(twice.await(5, TimeUnit.SECONDS), "the run after a failure never happened");
        } finally {
            check.stop();
        }
        awaitNoVersionCheckThread();
    }

    // ======================================================================================
    // The real JDK adapter, on loopback only
    // ======================================================================================

    @Test
    void theAdapterAsksTheComparePathWithAUserAgentAndNoAuthorization() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        AtomicReference<String> userAgent = new AtomicReference<>();
        AtomicReference<String> accept = new AtomicReference<>();
        AtomicBoolean authorization = new AtomicBoolean(true);
        String body = answer("\"identical\"", "0", "0");
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            query.set(exchange.getRequestURI().getRawQuery());
            userAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            accept.set(exchange.getRequestHeaders().getFirst("Accept"));
            authorization.set(exchange.getRequestHeaders().containsKey("Authorization"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        try {
            NodeVersionCheck.GitHubCompareSource adapter = new NodeVersionCheck.GitHubCompareSource(
                    "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(5));

            NodeVersionCheck.Answer answer = adapter.fetch(SHA);

            assertEquals(200, answer.status());
            assertEquals(body, answer.body());
            assertEquals("/repos/FluidTokens/ft-aquarium-node/compare/" + SHA + "...main", path.get());
            assertEquals("per_page=1", query.get());
            assertNotNull(userAgent.get(), "GitHub rejects a request without a User-Agent");
            assertFalse(userAgent.get().isBlank());
            assertEquals("application/vnd.github+json", accept.get());
            assertFalse(authorization.get(), "no Authorization header, ever");

            // must still: the same adapter, driven by the check, produces the up-to-date line.
            ILoggingEvent line = onlyLineOf(check(adapter));
            assertEquals(Level.INFO, line.getLevel());
            assertTrue(line.getFormattedMessage().contains("up to date"), line.getFormattedMessage());
        } finally {
            server.stop(0);
        }
    }

    /** A server that accepts the connection and never answers: the total deadline ends the run. */
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void theAdapterGivesUpAtItsDeadline() throws Exception {
        try (ServerSocket silent = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            NodeVersionCheck.GitHubCompareSource adapter = new NodeVersionCheck.GitHubCompareSource(
                    "http://127.0.0.1:" + silent.getLocalPort(), Duration.ofMillis(300));
            NodeVersionCheck check = new NodeVersionCheck("true", SHA, "false", adapter,
                    Duration.ofHours(24), Duration.ofMillis(300));

            long started = System.nanoTime();
            ILoggingEvent line = onlyLineOf(check);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertUnavailable(line);
            assertTrue(line.getFormattedMessage().contains("did not answer"), line.getFormattedMessage());
            assertTrue(elapsedMillis < 5_000, "the deadline did not bound the run: " + elapsedMillis + " ms");
        }
    }

    /**
     * Headers arrive at once, then the body trickles one byte every 200 ms — 100 bytes, ~20 s. The request
     * timeout alone does not bound reading a body (measured: 65 s against a 1 s {@code HttpRequest.timeout});
     * only the adapter's own total deadline on the future ends this run.
     */
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aBodyThatTricklesIsCutOffAtTheTotalDeadline() throws Exception {
        try (ServerSocket trickle = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread server = new Thread(() -> {
                try (Socket socket = trickle.accept()) {
                    InputStream in = socket.getInputStream();
                    int last4 = 0;
                    while (last4 != 0x0d0a0d0a) {
                        int b = in.read();
                        if (b < 0) {
                            return;
                        }
                        last4 = (last4 << 8) | b;
                    }
                    OutputStream out = socket.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 100\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    for (int i = 0; i < 100; i++) {
                        Thread.sleep(200);
                        out.write(' ');
                        out.flush();
                    }
                } catch (IOException | InterruptedException ignored) {
                    // the client gave up, or the test is over
                }
            }, "trickle-server");
            server.setDaemon(true);
            server.start();
            try {
                NodeVersionCheck.GitHubCompareSource adapter = new NodeVersionCheck.GitHubCompareSource(
                        "http://127.0.0.1:" + trickle.getLocalPort(), Duration.ofSeconds(1));
                NodeVersionCheck check = new NodeVersionCheck("true", SHA, "false", adapter,
                        Duration.ofHours(24), Duration.ofSeconds(1));

                long started = System.nanoTime();
                ILoggingEvent line = onlyLineOf(check);
                long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

                assertUnavailable(line);
                assertTrue(line.getFormattedMessage().contains("did not answer within 1s"), line.getFormattedMessage());
                assertTrue(elapsedMillis < 5_000, "a trickled body held the run for " + elapsedMillis + " ms");
            } finally {
                server.interrupt();
            }
        }
    }

    /**
     * A redirect whose {@code Location} is not a legal URI: the JDK's redirect handling throws an exception
     * QUOTING that header. A transport failure is logged by its class name only, so no part of it appears.
     */
    @Test
    void aMalformedRedirectIsLoggedWithoutTheHeaderText() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().set("Location", "http://exa mple/HEADER-TEXT-SENTINEL");
            exchange.sendResponseHeaders(301, -1);
            exchange.close();
        });
        server.start();
        try {
            NodeVersionCheck.GitHubCompareSource adapter = new NodeVersionCheck.GitHubCompareSource(
                    "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(5));

            ILoggingEvent line = onlyLineOf(check(adapter));

            assertUnavailable(line);
            for (String fragment : new String[]{"HEADER-TEXT-SENTINEL", "SENTINEL", "exa mple", "http://exa"}) {
                assertFalse(line.getFormattedMessage().contains(fragment),
                        "response header text reached the log line: " + line.getFormattedMessage());
            }
        } finally {
            server.stop(0);
        }
    }

    // ======================================================================================
    // The readiness page's snapshot: status()
    // ======================================================================================

    private static final String SENTINEL = "GITHUB-BODY-SENTINEL";

    /** Runs once and returns the status that run published, after checking it logged exactly one line. */
    private NodeVersionCheck.Status statusAfterOneRun(NodeVersionCheck check) {
        onlyLineOf(check);
        return check.status();
    }

    private static void assertStatus(NodeVersionCheck.Status status, NodeVersionCheck.State state,
                                     String shortCommit, int behindMain, int notOnMain, String compareUrl,
                                     String detail, boolean dirty) {
        assertEquals(new NodeVersionCheck.Status(state, shortCommit, behindMain, notOnMain, compareUrl, detail, dirty),
                status);
    }

    @Test
    void beforeTheFirstRunTheStatusIsCheckingAndNamesNoCommit() {
        NodeVersionCheck check = check(serving(200, answer("\"identical\"", "0", "0")));

        assertStatus(check.status(), NodeVersionCheck.State.CHECKING, null, 0, 0, null, null, false);
    }

    @Test
    void theFourAnswersPublishTheirStatesAndCounts() {
        assertStatus(statusAfterOneRun(check(serving(200, answer("\"identical\"", "0", "0")))),
                NodeVersionCheck.State.UP_TO_DATE, SHORT, 0, 0, null, null, false);
        assertStatus(statusAfterOneRun(check(serving(200, answer("\"ahead\"", "1", "0")))),
                NodeVersionCheck.State.BEHIND, SHORT, 1, 0, COMPARE_LINK, null, false);
        assertStatus(statusAfterOneRun(check(serving(200, answer("\"ahead\"", "3", "0")))),
                NodeVersionCheck.State.BEHIND, SHORT, 3, 0, COMPARE_LINK, null, false);
        assertStatus(statusAfterOneRun(check(serving(200, answer("\"diverged\"", "74", "9")))),
                NodeVersionCheck.State.BEHIND, SHORT, 74, 9, COMPARE_LINK, null, false);
        assertStatus(statusAfterOneRun(check(serving(200, answer("\"behind\"", "0", "9")))),
                NodeVersionCheck.State.AHEAD, SHORT, 0, 9, null, null, false);
    }

    /** The page's link and the log's link are one link: the one built from the node's own full commit. */
    @Test
    void theCompareUrlIsTheLoggedLinkAndOnlyBehindCarriesIt() {
        for (String body : new String[]{answer("\"ahead\"", "3", "0"), answer("\"diverged\"", "74", "9")}) {
            NodeVersionCheck check = check(serving(200, body));
            ILoggingEvent line = onlyLineOf(check);
            NodeVersionCheck.Status status = check.status();

            assertEquals(NodeVersionCheck.State.BEHIND, status.state());
            assertTrue(line.getFormattedMessage().endsWith(" " + status.compareUrl()), line.getFormattedMessage());
            assertEquals(COMPARE_LINK, status.compareUrl());
        }
        for (NodeVersionCheck.CompareSource source : List.of(serving(200, answer("\"identical\"", "0", "0")),
                serving(200, answer("\"behind\"", "0", "9")), serving(404, NOT_FOUND_BODY),
                serving(500, answer("\"ahead\"", "3", "0")))) {
            NodeVersionCheck.Status status = statusAfterOneRun(check(source));
            assertNotEquals(NodeVersionCheck.State.BEHIND, status.state());
            assertNull(status.compareUrl(), "only BEHIND carries a compare link: " + status);
        }
    }

    /** Every unavailable outcome is UNKNOWN, and its detail is the logged reason exactly, without the prefix. */
    @Test
    void anUnavailableRunIsUnknownWithTheLoggedReason() {
        record Case(NodeVersionCheck check, String shortCommit, String detail) {
        }
        List<Case> cases = List.of(
                new Case(check(serving(404, NOT_FOUND_BODY)), SHORT,
                        "commit " + SHORT + " is unknown to GitHub — a fork or a local build"),
                new Case(check(serving(403, "{\"message\":\"API rate limit exceeded\"}")), SHORT,
                        "GitHub refused the request (HTTP 403) — usually its limit of 60 unauthenticated requests "
                                + "per hour per IP"),
                new Case(check(commit -> {
                    throw new TimeoutException();
                }), SHORT, "GitHub did not answer within 10s"),
                new Case(check(serving(200, "<html>not json</html>")), SHORT, "unrecognised answer from GitHub"),
                new Case(check(commit -> {
                    throw new IllegalStateException("boom");
                }), SHORT, "could not reach GitHub — IllegalStateException"),
                new Case(check("true", "unknown", "false", serving(200, answer("\"identical\"", "0", "0"))), null,
                        "this build does not record the commit it was built from"),
                new Case(check("true", "64d43150c047", "false", serving(200, answer("\"identical\"", "0", "0"))),
                        null, "this build's recorded commit is not a full 40-character commit id"));
        for (Case c : cases) {
            ILoggingEvent line = onlyLineOf(c.check());
            NodeVersionCheck.Status status = c.check().status();

            assertStatus(status, NodeVersionCheck.State.UNKNOWN, c.shortCommit(), 0, 0, null, c.detail(), false);
            assertEquals("version check unavailable: " + status.detail(), line.getFormattedMessage());
        }
    }

    @Test
    void offAndNotUnderstoodPublishOffWithTheLoggedLine() throws Exception {
        awaitNoVersionCheckThread();
        for (String value : new String[]{"false", "banana\n"}) {
            NodeVersionCheck check = check(value, SHA, "false", serving(200, answer("\"identical\"", "0", "0")));
            int before = lines().size();
            try {
                check.start();

                List<ILoggingEvent> produced = lines().subList(before, lines().size());
                assertEquals(1, produced.size(), produced.toString());
                NodeVersionCheck.Status status = check.status();
                assertStatus(status, NodeVersionCheck.State.OFF, null, 0, 0, null,
                        produced.getFirst().getFormattedMessage().substring("version check: ".length()), false);
                assertTrue(status.detail().startsWith("off"), status.detail());
                assertFalse(versionCheckThreadAlive(), "flag '" + value + "' created a thread");
            } finally {
                check.stop();
            }
        }
    }

    @Test
    void dirtyIsExactlyTheRecordedTrueWhateverTheState() throws Exception {
        assertTrue(statusAfterOneRun(check("true", SHA, "true", serving(200, answer("\"identical\"", "0", "0"))))
                .dirty());
        assertTrue(statusAfterOneRun(check("true", SHA, "true", serving(200, answer("\"ahead\"", "3", "0"))))
                .dirty());
        assertTrue(statusAfterOneRun(check("true", SHA, "true", serving(404, NOT_FOUND_BODY))).dirty());
        for (String notDirty : new String[]{"false", "unknown", "TRUE", "", "null"}) {
            assertFalse(statusAfterOneRun(check("true", SHA, notDirty, serving(200, answer("\"ahead\"", "3", "0"))))
                    .dirty(), "dirty='" + notDirty + "'");
        }

        NodeVersionCheck off = check("false", SHA, "true", serving(200, answer("\"identical\"", "0", "0")));
        try {
            off.start();
            assertEquals(NodeVersionCheck.State.OFF, off.status().state());
            assertTrue(off.status().dirty(), "an off node that is dirty still says so");
        } finally {
            off.stop();
        }
    }

    /** Only the two parsed integers come from GitHub; no text of any response reaches the status. */
    @Test
    void noGitHubTextReachesTheStatus() {
        String extra = ",\"message\":\"" + SENTINEL + "\",\"html_url\":\"https://evil.example/" + SENTINEL + "\"}";
        List<NodeVersionCheck.CompareSource> sources = List.of(
                serving(200, answer("\"ahead\"", "3", "0").replaceFirst("}$", extra)),
                serving(200, answer("\"diverged\"", "74", "9").replaceFirst("}$", extra)),
                serving(200, answer("\"identical\"", "0", "0").replaceFirst("}$", extra)),
                serving(200, answer("\"behind\"", "0", "9").replaceFirst("}$", extra)),
                serving(404, NOT_FOUND_BODY.replaceFirst("}$", extra)),
                serving(403, "{\"message\":\"" + SENTINEL + "\"}"),
                serving(200, "{\"status\":\"" + SENTINEL + "\",\"ahead_by\":3,\"behind_by\":0}"),
                commit -> {
                    throw new java.net.ProtocolException("Invalid status line: \"" + SENTINEL + "\"");
                });
        for (NodeVersionCheck.CompareSource source : sources) {
            NodeVersionCheck.Status status = statusAfterOneRun(check(source));

            assertFalse(status.toString().contains(SENTINEL), "GitHub's text reached the status: " + status);
            assertFalse(status.toString().contains("evil.example"), status.toString());
            assertFalse(status.toString().contains("Not Found"), status.toString());
        }
    }

    /** Each run replaces the last: a later unavailable run clears the earlier compare link. */
    @Test
    void eachRunPublishesItsOwnStatus() {
        AtomicInteger calls = new AtomicInteger();
        NodeVersionCheck check = check(commit -> calls.incrementAndGet() == 1
                ? new NodeVersionCheck.Answer(200, answer("\"ahead\"", "3", "0"))
                : new NodeVersionCheck.Answer(404, NOT_FOUND_BODY));

        assertEquals(NodeVersionCheck.State.BEHIND, statusAfterOneRun(check).state());
        NodeVersionCheck.Status second = statusAfterOneRun(check);
        assertEquals(NodeVersionCheck.State.UNKNOWN, second.state());
        assertNull(second.compareUrl());
    }

    // ======================================================================================
    // Spring wiring and the shipped default
    // ======================================================================================

    /**
     * The production constructor, off: no thread of any kind. Building a JDK {@code HttpClient} starts its
     * selector thread, so the adapter must not build one until it is first asked.
     */
    @Test
    void springWiresTheBeanAndTheReadyEventStartsIt() {
        Set<Long> before = Thread.getAllStackTraces().keySet().stream().map(Thread::threadId)
                .collect(Collectors.toSet());
        new ApplicationContextRunner()
                .withBean(NodeVersionCheck.class)
                .withPropertyValues("aquarium.version-check.enabled=false")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertNotNull(context.getBean(NodeVersionCheck.class));
                    assertEquals(0, lines().size(), "the constructor logs nothing: " + lines());

                    ConfigurableApplicationContext source = context.getSourceApplicationContext();
                    source.publishEvent(new ApplicationReadyEvent(new SpringApplication(), new String[0], source,
                            Duration.ZERO));

                    List<ILoggingEvent> produced = lines();
                    assertEquals(1, produced.size(), produced.toString());
                    assertEquals(Level.INFO, produced.getFirst().getLevel());
                    assertTrue(produced.getFirst().getFormattedMessage().startsWith("version check: off"),
                            produced.getFirst().getFormattedMessage());
                    assertFalse(versionCheckThreadAlive());
                    List<String> started = Thread.getAllStackTraces().keySet().stream()
                            .filter(t -> t.isAlive() && !before.contains(t.threadId()))
                            .map(Thread::getName)
                            .filter(name -> name.contains("HttpClient") || name.startsWith(THREAD))
                            .toList();
                    assertEquals(List.of(), started, "a node with the check off started a thread");
                });
    }

    @Test
    @SuppressWarnings("unchecked")
    void theShippedDefaultIsOnAndOnlyTheBaseDocumentSetsIt() throws IOException {
        String yaml;
        try (InputStream in = NodeVersionCheckTest.class.getClassLoader().getResourceAsStream("application.yaml")) {
            assertNotNull(in, "application.yaml is not on the classpath");
            yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<Map<String, Object>> documents = new ArrayList<>();
        for (Object document : new Yaml().loadAll(yaml)) {
            documents.add((Map<String, Object>) document);
        }

        Map<String, Object> aquarium = (Map<String, Object>) documents.getFirst().get("aquarium");
        assertNotNull(aquarium, "the base document has no aquarium block");
        Map<String, Object> versionCheck = (Map<String, Object>) aquarium.get("version-check");
        assertNotNull(versionCheck, "aquarium.version-check is not in the base document");
        assertEquals("${AQUARIUM_VERSION_CHECK_ENABLED:true}", versionCheck.get("enabled"));
        // A second root `aquarium:` key would silently replace these; they must survive alongside it.
        for (String sibling : new String[]{"genesis", "staking", "tank", "syncing-threshold-minutes"}) {
            assertNotNull(aquarium.get(sibling), "aquarium." + sibling + " is gone from the base document");
        }

        Map<String, Object> preview = null;
        for (Map<String, Object> document : documents) {
            Object spring = document.get("spring");
            if (spring instanceof Map<?, ?> s && s.get("config") instanceof Map<?, ?> c
                    && c.get("activate") instanceof Map<?, ?> a && "preview".equals(a.get("on-profile"))) {
                preview = document;
            }
        }
        assertNotNull(preview, "no document activates the preview profile");
        Map<String, Object> previewAquarium = (Map<String, Object>) preview.get("aquarium");
        assertNotNull(previewAquarium);
        assertFalse(previewAquarium.containsKey("version-check"), "the preview document must not set the key");
    }
}
