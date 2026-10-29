package com.fluidtokens.aquarium.offchain.service.loans;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.common.model.Networks;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static com.fluidtokens.aquarium.offchain.service.loans.OracleStreamTestServer.await;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FAB-174: the shadow oracle stream's client against an in-process server — connect with a verifiable signed
 * query, reassemble and parse pushes, ignore what is not a push without dropping the connection, surface a
 * refusal's body, reconnect after a close, time out a hung handshake, and ping.
 *
 * <p>Production {@link OracleStreamClient.Timings} throughout, except the ping interval and the retry scale,
 * which are shrunk so a ladder step and a run of pings fit in a test. The handshake-timeout case therefore
 * waits out the real 20 s.
 *
 * <p>⛔ <b>Secrets never reach the log</b>, checked after EVERY case: a {@link ListAppender} on
 * {@code com.fluidtokens.aquarium.offchain} at TRACE must capture no line carrying the signature hex, the key
 * hex or the stake address of any query the server received, nor {@code signature=}, {@code key=} or
 * {@code stakeAddress=}, nor any exception. The test server's own logs are out of scope.
 */
class OracleStreamClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration PING = Duration.ofMillis(300);
    private static final double RETRY_SCALE = 0.2;
    private static final OracleStreamClient.Timings TIMINGS = new OracleStreamClient.Timings(
            OracleStreamClient.Timings.PRODUCTION.tcpConnectTimeout(),
            OracleStreamClient.Timings.PRODUCTION.handshakeTimeout(), PING, RETRY_SCALE);
    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final String PREVIEW_PUSH = "/loans-v4/oracle-ws/preview-push-20261029T130459Z.json";
    private static final String MAINNET_REGISTRY = "/loans-v4/mainnet-oracle-registry-2026-10-01.json";

    private final Account account = new Account(Networks.preview());
    private final Recorder recorder = new Recorder();
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Logger captured;
    private Level previousLevel;

    private OracleStreamTestServer server;
    private OracleStreamTestServer.Hanging hanging;
    private ScheduledExecutorService scheduler;
    private ExecutorService io;
    private OracleStreamClient client;

    @BeforeEach
    void captureTheLog() {
        captured = (Logger) LoggerFactory.getLogger("com.fluidtokens.aquarium.offchain");
        previousLevel = captured.getLevel();
        captured.setLevel(Level.TRACE);
        appender.start();
        captured.addAppender(appender);
    }

    @AfterEach
    void tearDownAndCheckNoSecretWasLogged() throws IOException {
        try {
            if (client != null) {
                client.stop();
            }
            if (scheduler != null) {
                scheduler.shutdownNow();
            }
            if (io != null) {
                io.shutdownNow();
            }
            if (server != null) {
                server.close();
            }
            if (hanging != null) {
                hanging.close();
            }
        } finally {
            captured.detachAppender(appender);
            captured.setLevel(previousLevel);
        }
        assertNoSecretLogged();
    }

    // ======================================================================================
    // Pushes
    // ======================================================================================

    @Test
    void connectsWithVerifiableAuthAndDeliversAParsedPush() throws Exception {
        server = new OracleStreamTestServer().authorize(account.stakeAddress()).start();
        start(server.uri());
        OracleStreamTestServer.Session session = server.awaitSession(0, WAIT);
        await(() -> recorder.opens.size() == 1, WAIT, "onOpen");

        String push = resource(PREVIEW_PUSH);
        session.sendText(push);
        await(() -> recorder.pushes.size() == 1, WAIT, "onPush");

        Recorder.Push received = recorder.pushes.getFirst();
        OracleRegistryParser.Parsed expected = OracleRegistryParser.parse(MAPPER.readTree(push).get("tokens"));
        assertFalse(expected.entries().isEmpty(), "fixture: the preview push parses to entries");
        assertEquals(expected, received.parsed());
        assertEquals(expected.entries(), received.parsed().entries());
        assertEquals(1_793_279_099_046L, received.serverTimestamp());
        assertEquals(push.length(), received.chars());
        assertEquals(List.of(), server.authFailures(), "the server's verification of the signed query");
        assertEquals(1, client.pushesParsed());
        assertTrue(lines().stream().anyMatch(l -> l.contains("connected ") && l.contains(" attempt=1 ")
                && l.contains(" handshake_ms=")), "one connected line: " + lines());
        assertEquals(List.of("oracle-stream"), recorder.threads.stream().distinct().toList(),
                "every Listener callback runs on the oracle-stream thread");
        assertTrue(recorder.threads.size() >= 2, "onOpen and onPush were both recorded");
    }

    @Test
    void theMainnetRegistryArrivesIntactInContinuationFramesOfAtMost16KiB() throws Exception {
        String message = mainnetPush();
        OracleStreamTestServer.Session session = openSession();
        session.sendFragmented(message, 16 * 1024);
        await(() -> recorder.pushes.size() == 1, WAIT, "onPush");

        assertTrue(session.framesSent.get() >= 4, "fixture: ~56 KB went as >= 4 frames, not " + session.framesSent);
        assertMainnetRegistry(message, recorder.pushes.getFirst());
    }

    @Test
    void theMainnetRegistryArrivesIntactInOneFrame() throws Exception {
        String message = mainnetPush();
        OracleStreamTestServer.Session session = openSession();
        session.sendText(message);
        await(() -> recorder.pushes.size() == 1, WAIT, "onPush");

        assertEquals(1, session.framesSent.get());
        assertMainnetRegistry(message, recorder.pushes.getFirst());
    }

    @Test
    void aMessageOverTheLimitIsDroppedAndTheNextPushStillParses() throws Exception {
        OracleStreamTestServer.Session session = openSession();
        session.sendText("x".repeat(OracleStreamClient.MAX_MESSAGE_CHARS + 1));
        session.sendText(resource(PREVIEW_PUSH));
        await(() -> recorder.pushes.size() == 1, WAIT, "onPush after the oversize message");

        assertEquals(1, client.ignored("oversize"));
        assertEquals(1, recorder.pushes.size());
        assertEquals(1, warnLines("push-ignored").size());
        assertTrue(warnLines("push-ignored").getFirst().contains(" reason=oversize "), lines().toString());
        assertTrue(session.open(), "the connection stays up");
    }

    @Test
    void anUnknownTypeTwiceWarnsOnceAndCountsTwo() throws Exception {
        OracleStreamTestServer.Session session = openSession();
        session.sendText("{\"type\":\"hello\",\"x\":1}");
        session.sendText("{\"type\":\"hello\",\"x\":2}");
        session.sendText(resource(PREVIEW_PUSH));
        await(() -> recorder.pushes.size() == 1, WAIT, "onPush after the unknown types");

        assertEquals(2, client.ignored("unknown-type"));
        List<String> warns = warnLines("push-ignored");
        assertEquals(1, warns.size(), lines().toString());
        assertTrue(warns.getFirst().contains(" reason=unknown-type:hello "), warns.toString());
        assertEquals(1, debugLines("push-ignored").size(), "the repeat is DEBUG: " + lines());
        assertTrue(session.open(), "the connection stays up");
    }

    @Test
    void malformedJsonAndABinaryMessageAreCountedAndTheConnectionStaysOpen() throws Exception {
        OracleStreamTestServer.Session session = openSession();
        session.sendText("{not json at all");
        session.sendBinary(new byte[]{1, 2, 3});
        session.sendText(resource(PREVIEW_PUSH));
        await(() -> recorder.pushes.size() == 1, WAIT, "onPush after the malformed and binary messages");

        assertEquals(1, client.ignored("malformed"));
        assertEquals(1, client.ignored("binary"));
        assertTrue(session.open(), "the connection stays up");
        assertEquals(List.of(), recorder.closes);
        assertEquals(1, server.sessions().size(), "no reconnect");
        assertFalse(String.join("\n", lines()).contains("not json"), "the payload is never echoed");
    }

    // ======================================================================================
    // Refusals, ends, timeouts, pings
    // ======================================================================================

    static Stream<Arguments> refusals() {
        return Stream.of(
                Arguments.of(400, "{\"message\":\"missing or malformed stakeAddress, ts, key or signature\"}",
                        "missing or malformed stakeAddress, ts, key or signature"),
                Arguments.of(401, "{\"message\":\"invalid signature or ts older than 2 minutes\"}",
                        "invalid signature or ts older than 2 minutes"),
                Arguments.of(403, "{\"message\":\"no Aquarium staking bond found for this stake key\"}",
                        "no Aquarium staking bond found for this stake key"),
                Arguments.of(429, "{\"message\":\"connection limit reached: 1 per bond held\"}",
                        "connection limit reached: 1 per bond held"),
                Arguments.of(500, "{\"message\":\"internal\"}", "internal"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusals")
    void eachRefusalSurfacesItsBody(int status, String body, String message) {
        server = new OracleStreamTestServer().refuse(status, body).start();
        start(server.uri());
        await(() -> !warnLines("refused").isEmpty(), WAIT, "the refused line");

        String line = warnLines("refused").getFirst();
        assertTrue(line.contains(" status=" + status + " "), line);
        assertTrue(line.contains(" msg=\"" + message + "\" "), line);
        assertTrue(line.contains(" retry_s="), line);
        assertEquals(List.of(), recorder.opens, "onOpen is never called on a refusal");
        assertEquals(0, server.sessions().size());
    }

    @Test
    void aServerCloseIsFollowedByASecondConnectionAfterTheScaledFirstLadderStep() {
        OracleStreamTestServer.Session first = openSession();
        first.close(1000);
        OracleStreamTestServer.Session second = server.awaitSession(1, WAIT);
        await(() -> recorder.closes.size() == 1, WAIT, "onClose");

        Instant closedAt = recorder.closes.getFirst();
        long delayMs = Duration.between(closedAt, second.openedAt).toMillis();
        long step = OracleStreamClient.TRANSPORT_BACKOFF_SECONDS[0] * 1000L;
        assertTrue(delayMs >= Math.round(step * 0.8 * RETRY_SCALE) - 20,
                "reconnected after " + delayMs + " ms, sooner than the first step's lower bound");
        assertTrue(delayMs <= Math.round(step * 1.2 * RETRY_SCALE) + 2_000,
                "reconnected after " + delayMs + " ms");
        assertEquals("server-close(1000)", recorder.closeReasons.getFirst());
        assertTrue(warnLines("closed").stream().anyMatch(l -> l.contains(" reason=server-close(1000) up_s=")),
                lines().toString());
        await(() -> recorder.opens.size() == 2, WAIT, "the second onOpen");
    }

    @Test
    void aHandshakeThatNeverCompletesTimesOut() throws IOException {
        hanging = new OracleStreamTestServer.Hanging();
        long started = System.nanoTime();
        start(hanging.uri());
        Duration handshake = TIMINGS.handshakeTimeout();
        await(() -> !warnLines("closed").isEmpty(), handshake.plusSeconds(15), "the closed line");

        long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        String line = warnLines("closed").getFirst();
        assertTrue(line.contains(" reason=error("), line);
        assertTrue(elapsedMs >= handshake.toMillis() - 1_000,
                "the handshake timeout ended it, not something sooner: " + elapsedMs + " ms, " + line);
        assertTrue(hanging.accepted() >= 1, "the client reached the hanging listener");
        assertEquals(List.of(), recorder.opens);
    }

    @Test
    void atLeastThreePingsArriveWithinFourPingIntervals() {
        server = new OracleStreamTestServer().answerPings(false).start();
        start(server.uri());
        OracleStreamTestServer.Session session = server.awaitSession(0, WAIT);
        Instant deadline = session.openedAt.plus(PING.multipliedBy(4));
        await(() -> !Instant.now().isBefore(deadline), WAIT, "four ping intervals");

        assertTrue(session.pings.get() >= 3, "pings within 4 x " + PING + ": " + session.pings.get());
        assertTrue(session.open(), "unanswered pings do not end the connection in this slice");
    }

    /**
     * The whole life of a connection in one case — signed connect, push, server close, reconnect, a refusal
     * between — so that every line kind is present when the after-each check looks for secrets.
     */
    @Test
    void secretsNeverReachTheLog() throws Exception {
        server = new OracleStreamTestServer().authorize(account.stakeAddress()).start();
        start(server.uri());
        OracleStreamTestServer.Session first = server.awaitSession(0, WAIT);
        await(() -> recorder.opens.size() == 1, WAIT, "onOpen");
        first.sendText(resource(PREVIEW_PUSH));
        first.sendText("{\"type\":\"hello\"}");
        await(() -> recorder.pushes.size() == 1, WAIT, "onPush");
        server.refuse(429, "{\"message\":\"connection limit reached: 1 per bond held\"}");
        first.close(1000);
        await(() -> !warnLines("refused").isEmpty(), WAIT, "a refused reconnect");

        assertTrue(server.queries().size() >= 2, "at least two signed queries were sent");
        for (String kind : List.of("connected", "push-ignored", "closed", "refused")) {
            assertTrue(lines().stream().anyMatch(l -> l.contains("[oracle-stream] " + kind + " ")),
                    "a " + kind + " line was captured: " + lines());
        }
        assertNoSecretLogged();
    }

    // ======================================================================================
    // Helpers
    // ======================================================================================

    private void assertNoSecretLogged() {
        List<String> secrets = new ArrayList<>(List.of("signature=", "key=", "stakeAddress=", account.stakeAddress()));
        List<String> queries = server == null ? List.of() : server.queries();
        for (String query : queries) {
            Map<String, String> params = new LinkedHashMap<>();
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    params.put(pair.substring(0, eq), pair.substring(eq + 1));
                }
            }
            for (String name : List.of("key", "signature", "stakeAddress")) {
                String value = params.get(name);
                if (value != null && !value.isEmpty()) {
                    secrets.add(value);
                }
            }
        }
        List<ILoggingEvent> events = List.copyOf(appender.list);
        assertFalse(events.isEmpty(), "the capture is live: the stream logged something");
        for (ILoggingEvent event : events) {
            String message = event.getFormattedMessage();
            for (String secret : secrets) {
                assertFalse(message.contains(secret), "a log line carries signing material: " + message);
            }
            assertNull(event.getThrowableProxy(), "an exception was logged with: " + message);
            assertTrue(message.startsWith("[oracle-stream] "), "every stream line is prefixed: " + message);
            assertTrue(message.matches("\\[oracle-stream] [a-z-]+ boot=[0-9a-f]{8} ev=\\d+( .*)?"),
                    "the line shape: " + message);
        }
    }

    private OracleStreamTestServer.Session openSession() {
        server = new OracleStreamTestServer().authorize(account.stakeAddress()).start();
        start(server.uri());
        OracleStreamTestServer.Session session = server.awaitSession(server.sessions().size(), WAIT);
        await(() -> recorder.opens.size() == 1, WAIT, "onOpen");
        return session;
    }

    private void start(URI uri) {
        AtomicInteger ioThreads = new AtomicInteger();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "oracle-stream"));
        io = Executors.newCachedThreadPool(r -> daemon(r, "oracle-stream-io-" + ioThreads.incrementAndGet()));
        client = new OracleStreamClient(uri, new OracleStreamAuth(account), TIMINGS, Clock.systemUTC(), recorder,
                new OracleStreamLog(), scheduler, io);
        client.start();
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private static void assertMainnetRegistry(String message, Recorder.Push received) throws Exception {
        OracleRegistryParser.Parsed expected = OracleRegistryParser.parse(MAPPER.readTree(resource(MAINNET_REGISTRY)));
        assertEquals(35, expected.entries().size(), "fixture: the 2026-10-01 mainnet registry parses to 35");
        assertEquals(35, received.parsed().entries().size());
        assertEquals(expected, received.parsed());
        assertEquals(message.length(), received.chars());
    }

    private static String mainnetPush() throws IOException {
        return "{\"type\":\"oracle-tokens\",\"timestamp\":1793279099046,\"tokens\":" + resource(MAINNET_REGISTRY) + "}";
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = OracleStreamClientTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("missing fixture " + path);
            }
            return new String(in.readAllBytes(), UTF_8);
        }
    }

    private List<String> lines() {
        return List.copyOf(appender.list).stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private List<String> warnLines(String event) {
        return linesAt(Level.WARN, event);
    }

    private List<String> debugLines(String event) {
        return linesAt(Level.DEBUG, event);
    }

    private List<String> linesAt(Level level, String event) {
        return List.copyOf(appender.list).stream()
                .filter(e -> e.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("[oracle-stream] " + event + " "))
                .toList();
    }

    /** Records every callback and the thread it ran on. */
    static final class Recorder implements OracleStreamClient.Listener {

        record Push(Instant at, long serverTimestamp, OracleRegistryParser.Parsed parsed, int chars) {
        }

        final List<String> threads = new CopyOnWriteArrayList<>();
        final List<Instant> opens = new CopyOnWriteArrayList<>();
        final List<Instant> closes = new CopyOnWriteArrayList<>();
        final List<String> closeReasons = new CopyOnWriteArrayList<>();
        final List<Push> pushes = new CopyOnWriteArrayList<>();

        @Override
        public void onOpen(Instant at) {
            threads.add(Thread.currentThread().getName());
            opens.add(at);
        }

        @Override
        public void onClose(Instant at, String reason) {
            threads.add(Thread.currentThread().getName());
            closeReasons.add(reason);
            closes.add(at);
        }

        @Override
        public void onPush(Instant at, long serverTimestamp, OracleRegistryParser.Parsed parsed, int chars) {
            threads.add(Thread.currentThread().getName());
            pushes.add(new Push(at, serverTimestamp, parsed, chars));
        }
    }
}
