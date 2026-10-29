package com.fluidtokens.aquarium.offchain.service.loans;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.common.model.Networks;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.random.RandomGenerator;

import static com.fluidtokens.aquarium.offchain.service.loans.OracleStreamTestServer.await;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FAB-174-3: the oracle stream stays honest about whether it is alive — against the in-process server, with
 * scaled {@link OracleStreamClient.Timings} and a jitter source pinned at the middle of its band, so every
 * policy delay is its bare ladder step × {@code retryScale}.
 *
 * <ul>
 *   <li>no pong within {@code pongTimeout} is a dead connection; push silence with pongs answered is not;</li>
 *   <li>the proactive reconnect is break-then-make, with a fresh {@code ts};</li>
 *   <li>401 re-signs at once, then backs off; a stake flip ends a 403 hold; a repeated 429 WARNs once, and again
 *       after a successful open;</li>
 *   <li>every meter exists at zero before the first connection and moves as its scenario says;</li>
 *   <li>FAB-174-2 audit residue R2 (a throwing listener), R4 (the ladder against an injected clock) and R5
 *       (a close that overtakes the handshake's completion);</li>
 *   <li>r3: an ended connection's pong deadline never kills the next connection (F2), and a failure right after
 *       a proactive reconnect waits the first transport step (F4).</li>
 * </ul>
 *
 * <p>⛔ <b>Every assertion on a CLIENT-side fact — a counter, {@code status()}, a close reason, a log line — first
 * awaits that fact itself, never a server-side count.</b> The server counts a request before it answers it, so
 * "the server saw N requests" says nothing about whether the client has finished handling the (N−1)th answer;
 * asserting on the client after such a wait raced its handshake-completion task and failed under CPU load
 * (r2 audit, 2 of 6 cold runs). A status the test reads more than once is captured once, by {@link #awaitState}.
 */
class OracleStreamClientLivenessTest {

    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final String PREVIEW_PUSH = "/loans-v4/oracle-ws/preview-push-20261029T130459Z.json";
    private static final String REFUSED_429 = "{\"message\":\"connection limit reached: 1 per bond held\"}";
    private static final String REFUSED_403 = "{\"message\":\"no Aquarium staking bond found for this stake key\"}";
    private static final Duration LONG = Duration.ofHours(1);

    /** nextDouble() = 0.5: every jitter factor is exactly 1.0. */
    private static final RandomGenerator MID = new RandomGenerator() {
        @Override
        public long nextLong() {
            return 1L << 63;
        }

        @Override
        public double nextDouble() {
            return 0.5;
        }
    };

    private final Account account = new Account(Networks.preview());
    private final OracleStreamClientTest.Recorder recorder = new OracleStreamClientTest.Recorder();
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private Logger captured;
    private Level previousLevel;

    private OracleStreamTestServer server;
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
    void tearDownAndCheckTheLineShape() {
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
        } finally {
            captured.detachAppender(appender);
            captured.setLevel(previousLevel);
        }
        for (ILoggingEvent event : List.copyOf(appender.list)) {
            String message = event.getFormattedMessage();
            assertNull(event.getThrowableProxy(), "an exception was logged with: " + message);
            assertTrue(message.matches("\\[oracle-stream] [a-z-]+ boot=[0-9a-f]{8} ev=\\d+( .*)?"),
                    "the line shape: " + message);
            assertFalse(message.contains(account.stakeAddress()) || message.contains("signature="),
                    "signing material in: " + message);
        }
    }

    // ======================================================================================
    // Liveness
    // ======================================================================================

    @Test
    void noPongWithinThePongTimeoutForcesAReconnect() {
        server = new OracleStreamTestServer().answerPings(false).start();
        Duration ping = Duration.ofMillis(100);
        Duration pong = Duration.ofMillis(300);
        start(server.uri(), timings(ping, pong, LONG, 0.05));
        OracleStreamTestServer.Session first = server.awaitSession(0, WAIT);
        OracleStreamTestServer.Session second = server.awaitSession(1, WAIT);

        assertNotNull(first.closedAt, "the dead connection was closed before the next one");
        long lived = Duration.between(first.openedAt, first.closedAt).toMillis();
        assertTrue(lived >= ping.plus(pong).toMillis() - 50,
                "ended by the first ping's pong deadline, not sooner: " + lived + " ms");
        assertTrue(first.pings.get() >= 1);
        assertFalse(second.openedAt.isBefore(first.closedAt), "break-then-make on a dead connection too");
        await(() -> !warnLines("closed").isEmpty(), WAIT, "the closed line");
        assertTrue(warnLines("closed").getFirst().contains(" reason=no-pong up_s="), lines().toString());
        await(() -> counter(OracleStreamClient.METER_DISCONNECTS, "reason", "no_pong") >= 1, WAIT, "the counter");
        await(() -> !recorder.closeReasons.isEmpty(), WAIT, "onClose");
        assertEquals("no-pong", recorder.closeReasons.getFirst());
    }

    @Test
    void pushSilenceForThreePingIntervalsWithPongsAnsweredCausesNoReconnect() {
        server = new OracleStreamTestServer().start();
        Duration ping = Duration.ofMillis(200);
        Duration pong = Duration.ofMillis(1_000);
        start(server.uri(), timings(ping, pong, LONG, 0.05));
        OracleStreamTestServer.Session session = server.awaitSession(0, WAIT);
        Instant opened = awaitState(OracleStreamClient.Status.State.CONNECTED).since();
        Instant deadline = opened.plus(ping.multipliedBy(3)).plus(pong).plusMillis(200);
        await(() -> !Instant.now().isBefore(deadline), WAIT, "three silent ping intervals and a pong timeout");

        assertTrue(session.pings.get() >= 3, "pings were sent and answered: " + session.pings.get());
        assertEquals(List.of(), recorder.pushes, "fixture: not one push");
        assertTrue(session.open(), "push silence never ends a connection");
        assertEquals(1, server.sessions().size(), "no reconnect");
        assertEquals(List.of(), linesOf("closed"), "no closed line at any level");
        assertEquals(OracleStreamClient.Status.State.CONNECTED, client.status().state());
        assertEquals(1.0, gauge(OracleStreamClient.METER_CONNECTED));
        for (String reason : OracleStreamClient.DISCONNECT_REASONS) {
            assertEquals(0.0, counter(OracleStreamClient.METER_DISCONNECTS, "reason", reason), reason);
        }
    }

    @Test
    void theProactiveReconnectClosesTheOldSocketBeforeOpeningTheNewOneWithAFreshTs() {
        server = new OracleStreamTestServer().start();
        start(server.uri(), timings(Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofMillis(500), 1.0));
        OracleStreamTestServer.Session first = server.awaitSession(0, WAIT);
        OracleStreamTestServer.Session second = server.awaitSession(1, WAIT);

        assertNotNull(first.closedAt, "connection 1 was closed");
        assertFalse(first.closedAt.isAfter(second.openedAt),
                "break-then-make: connection 1 closed at " + first.closedAt + ", connection 2 opened at "
                        + second.openedAt);
        assertTrue(ts(server.queries().get(1)) > ts(server.queries().get(0)), "a fresh ts: " + server.queries());
        await(() -> infoLines("closed").stream().anyMatch(l -> l.contains(" reason=proactive up_s=")), WAIT,
                "the proactive close, at INFO");
        await(() -> recorder.opens.size() >= 2, WAIT, "the second onOpen");
        assertEquals(List.of(), warnLines("closed"), "a planned reconnect is not a warning");
        await(() -> counter(OracleStreamClient.METER_DISCONNECTS, "reason", "proactive") >= 1, WAIT,
                "the proactive disconnect counted");
        await(() -> !recorder.closeReasons.isEmpty(), WAIT, "onClose");
        assertEquals("proactive", recorder.closeReasons.getFirst());
    }

    /**
     * r3 F2: connection A's pong deadline never kills connection B. The server leaves A's first ping unanswered
     * and closes A right after it; the client reconnects to B (first transport step, 50 ms) well within the pong
     * timeout, so A's deadline fires while B is the live connection. B has sent no ping of its own by then, so
     * only the guard that numbers B's pings above the ones sent before it opened keeps A's deadline off B.
     */
    @Test
    void anEndedConnectionsPongDeadlineNeverKillsTheNextConnection() {
        server = new OracleStreamTestServer().answerPings(false).start();
        Duration ping = Duration.ofSeconds(3);
        Duration pong = Duration.ofSeconds(2);
        start(server.uri(), timings(ping, pong, LONG, 0.05));
        OracleStreamTestServer.Session a = server.awaitSession(0, WAIT);
        server.answerPings(true); // read per session at its handshake: A ignores pings, B answers them
        await(() -> a.pings.get() >= 1, WAIT, "A's first ping");
        Instant pinged = Instant.now();
        a.close(1000);

        OracleStreamTestServer.Session b = server.awaitSession(1, WAIT);
        await(() -> recorder.opens.size() == 2, WAIT, "B open on the client");
        Instant bOpen = Instant.now();
        assertTrue(bOpen.isBefore(pinged.plus(pong).minusMillis(500)),
                "fixture: B opened on the client within A's pong timeout, so A's deadline fires while B is live: "
                        + Duration.between(pinged, bOpen).toMillis() + " ms after A's ping");
        assertEquals("server-close(1000)", recorder.closeReasons.getFirst(), "A ended by the server's close");

        Instant afterDeadline = pinged.plus(pong).plusMillis(500);
        await(() -> !Instant.now().isBefore(afterDeadline), WAIT, "A's pong deadline, and some");

        assertEquals(0, b.pings.get(), "fixture: B had not pinged yet, so no pong of B's can cover A's deadline");
        assertTrue(b.open(), "B stays open");
        assertEquals(2, server.sessions().size(), "no third connection");
        assertEquals(0.0, counter(OracleStreamClient.METER_DISCONNECTS, "reason", "no_pong"), "no_pong");
        assertEquals(OracleStreamClient.Status.State.CONNECTED, client.status().state());
        assertEquals(1.0, gauge(OracleStreamClient.METER_CONNECTED));
        assertEquals(List.of("server-close(1000)"), recorder.closeReasons, "B was never closed");
        assertFalse(lines().stream().anyMatch(l -> l.contains(" reason=no-pong ")), lines().toString());
    }

    /**
     * r3 F4, end to end: the client hands the proactive end its real time open. Two quick server closes leave the
     * transport ladder at step 3; the third connection stays up 60 s on the clock and is replaced proactively; the
     * server then closes the fourth at once — and the wait is ladder step 1, not step 3.
     */
    @Test
    void aFailureRightAfterAProactiveReconnectWaitsTheFirstLadderStep() {
        server = new OracleStreamTestServer().start();
        MutableClock clock = new MutableClock(Instant.parse("2026-10-29T12:00:00Z"));
        double scale = 0.5;
        start(server.uri(), timings(Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofMillis(2_500), scale),
                () -> false, clock, newScheduler());

        for (int i = 0; i < 2; i++) {
            int opens = i + 1;
            OracleStreamTestServer.Session quick = server.awaitSession(i, WAIT);
            await(() -> recorder.opens.size() == opens, WAIT, "open " + opens);
            quick.close(1000);
            assertEquals(Duration.ofMillis(Math.round(OracleStreamRetryPolicy.TRANSPORT_LADDER_SECONDS[i] * 1000 * scale)),
                    retryDelay(clock), "fixture: close " + opens + " waits ladder step " + opens);
        }

        server.awaitSession(2, WAIT);
        await(() -> recorder.opens.size() == 3, WAIT, "open 3");
        clock.advance(Duration.ofSeconds(60));
        OracleStreamTestServer.Session fourth = server.awaitSession(3, WAIT);
        await(() -> recorder.opens.size() == 4, WAIT, "open 4, after the proactive reconnect");
        await(() -> infoLines("closed").stream().anyMatch(l -> l.contains(" reason=proactive up_s=60")), WAIT,
                "the proactive close of the connection open for 60 s");
        fourth.close(1000);

        assertEquals(Duration.ofMillis(Math.round(1_000 * scale)), retryDelay(clock),
                "a failure right after the proactive reconnect waits ladder step 1, not step 3");
    }

    // ======================================================================================
    // Refusals
    // ======================================================================================

    @Test
    void a401IsReSignedAtOnceThenBacksOffOnTheScaledAuthLadder() {
        server = new OracleStreamTestServer().refuse(401, OracleStreamTestServer.REFUSED_401).start();
        double scale = 0.005; // 60 s → 300 ms
        start(server.uri(), timings(Duration.ofSeconds(30), Duration.ofSeconds(10), LONG, scale));
        await(() -> server.queries().size() >= 3, WAIT, "three attempts");
        // The server counts the third request before answering it: every client-side fact is awaited itself.
        await(() -> counter(OracleStreamClient.METER_REFUSALS, "status", "401") >= 3, WAIT, "three 401s counted");
        await(() -> debugLines("refused").size() >= 2, WAIT, "the second and third 401 said at DEBUG");
        OracleStreamClient.Status status = awaitState(OracleStreamClient.Status.State.REFUSED);

        List<String> queries = List.copyOf(server.queries());
        long resign = ts(queries.get(1)) - ts(queries.get(0));
        long backoff = ts(queries.get(2)) - ts(queries.get(1));
        long step = Math.round(60_000 * scale);
        assertTrue(resign < step / 2, "the first 401 is retried at once: " + resign + " ms");
        assertTrue(backoff >= step && backoff < step + 250,
                "the second 401 waits the first auth step, " + step + " ms: " + backoff + " ms");

        List<String> warns = warnLines("refused");
        assertEquals(1, warns.size(), "one WARN for the repeated 401: " + lines());
        assertTrue(warns.getFirst().contains(" status=401 msg=\"invalid signature or ts older than 2 minutes\" retry_s=0 "
                + "-- signature rejected; check the system clock"), warns.getFirst());
        assertEquals(401, status.refusalStatus());
        assertEquals("invalid signature or ts older than 2 minutes", status.refusalMessage());
        assertNotNull(status.nextAttemptAt());
    }

    @Test
    void aStakeFlipEndsA403HoldAtOnceAndAnUnknownStakeDoesNot() {
        server = new OracleStreamTestServer().refuse(403, REFUSED_403).start();
        double scale = 0.001; // the 1 h hold → 3.6 s, the 5 min re-check → 300 ms
        AtomicReference<Boolean> stake = new AtomicReference<>(false);
        List<String> readers = new CopyOnWriteArrayList<>();
        AtomicInteger reads = new AtomicInteger();
        BooleanSupplier supplier = () -> {
            readers.add(Thread.currentThread().getName());
            reads.incrementAndGet();
            Boolean value = stake.get();
            if (value == null) {
                throw new IllegalStateException("the index is unavailable");
            }
            return value;
        };
        start(server.uri(), timings(Duration.ofSeconds(30), Duration.ofSeconds(10), LONG, scale), supplier,
                Clock.systemUTC(), newScheduler());
        await(() -> !warnLines("refused").isEmpty(), WAIT, "the 403");
        assertTrue(warnLines("refused").getFirst().contains(" status=403 msg=\"no Aquarium staking bond found for "
                + "this stake key\" retry_s=4 -- no Aquarium staking bond for this wallet's stake key; retrying hourly "
                + "or when the stake appears"), warnLines("refused").getFirst());
        OracleStreamClient.Status held = client.status();
        assertEquals(OracleStreamClient.Status.State.REFUSED, held.state());
        assertEquals(403, held.refusalStatus());

        // Unchanged, then unknown: neither ends the hold.
        await(() -> reads.get() >= 3, WAIT, "two re-checks of an unchanged stake");
        stake.set(null);
        int before = reads.get();
        await(() -> reads.get() >= before + 2, WAIT, "two re-checks of an unknown stake");
        assertEquals(1, server.requests(), "no retry while the stake is unchanged or unknown");

        long flipped = System.nanoTime();
        stake.set(true);
        await(() -> server.requests() >= 2, WAIT, "the retry after the flip");
        long afterFlipMs = Duration.ofNanos(System.nanoTime() - flipped).toMillis();
        assertTrue(afterFlipMs < 1_500, "retried at the next re-check, not after the 3.6 s hold: " + afterFlipMs + " ms");
        assertEquals(List.of("oracle-stream"), readers.stream().distinct().toList(),
                "the stake is read only on the oracle-stream thread");
        await(() -> counter(OracleStreamClient.METER_REFUSALS, "status", "403") >= 2, WAIT,
                "the retried attempt's 403 counted");
    }

    @Test
    void threeRepeated429sWarnOnceThenASuccessThenA429WarnsAgain() {
        server = new OracleStreamTestServer().refuse(429, REFUSED_429).start();
        double scale = 0.005; // 60, 120, 240 s → 300, 600, 1200 ms
        start(server.uri(), timings(Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofMillis(1_500), scale));
        // Switched only once the client has HANDLED the third 429, so the third request cannot be the one accepted.
        await(() -> debugLines("refused").size() >= 2, WAIT, "three 429s handled: one WARN, two repeats");
        server.refuse(0, null); // the next attempt, 1.2 s later, is accepted
        assertEquals(1, warnLines("refused").size(), "three 429s, one WARN: " + lines());
        assertEquals(2, debugLines("refused").size(), lines().toString());
        assertTrue(warnLines("refused").getFirst().contains(" status=429 msg=\"connection limit reached: 1 per bond "
                + "held\" retry_s=0 -- another node on this bond holds the connection; retrying in 0s"),
                warnLines("refused").getFirst());

        server.awaitSession(0, WAIT);
        // Server-side control, set the moment the server holds the session: the proactive reconnect, 1.5 s after
        // the client's open, is refused — and can never be accepted because the test thread was slow to look.
        server.refuse(429, REFUSED_429);
        await(() -> infoLines("connected").size() == 1, WAIT, "the INFO connected line");
        await(() -> warnLines("refused").size() == 2, WAIT, "the 429 after the success");

        List<String> all = lines();
        int connected = indexOf(all, "[oracle-stream] connected ");
        int warnedAgain = all.lastIndexOf(warnLines("refused").get(1));
        assertTrue(connected < warnedAgain, "the second WARN follows the open: " + all);
        assertEquals(1, infoLines("connected").size());
        assertEquals(List.of(), warnLines("closed"), "the reconnect between was proactive, not a warning");
        await(() -> counter(OracleStreamClient.METER_REFUSALS, "status", "429") >= 4, WAIT, "four 429s counted");
    }

    // ======================================================================================
    // Metrics
    // ======================================================================================

    @Test
    void everyMeterIsRegisteredAtZeroBeforeTheFirstConnection() {
        server = new OracleStreamTestServer().start();
        build(server.uri(), timings(Duration.ofSeconds(30), Duration.ofSeconds(10), LONG, 1.0), () -> false,
                Clock.systemUTC(), newScheduler());

        assertEquals(0.0, gauge(OracleStreamClient.METER_CONNECTED));
        assertEquals(0.0, gauge(OracleStreamClient.METER_LAST_PUSH));
        assertEquals(List.of("parsed", "unknown_type", "malformed", "oversize", "binary"), OracleStreamClient.PUSH_RESULTS);
        assertEquals(List.of("400", "401", "403", "429", "other"), OracleStreamClient.REFUSAL_STATUSES);
        assertEquals(List.of("proactive", "server_close", "error", "no_pong"), OracleStreamClient.DISCONNECT_REASONS);
        for (String result : OracleStreamClient.PUSH_RESULTS) {
            assertEquals(0.0, counter(OracleStreamClient.METER_PUSHES, "result", result), result);
        }
        for (String status : OracleStreamClient.REFUSAL_STATUSES) {
            assertEquals(0.0, counter(OracleStreamClient.METER_REFUSALS, "status", status), status);
        }
        for (String reason : OracleStreamClient.DISCONNECT_REASONS) {
            assertEquals(0.0, counter(OracleStreamClient.METER_DISCONNECTS, "reason", reason), reason);
        }
        assertEquals(2 + OracleStreamClient.PUSH_RESULTS.size() + OracleStreamClient.REFUSAL_STATUSES.size()
                + OracleStreamClient.DISCONNECT_REASONS.size(), registry.getMeters().size(), "nothing else");
        assertEquals(0, server.requests(), "fixture: no connection yet");
    }

    @Test
    void thePushAndConnectionMetersMoveAsTheStreamDoes() throws IOException {
        server = new OracleStreamTestServer().start();
        start(server.uri(), timings(Duration.ofSeconds(30), Duration.ofSeconds(10), LONG, 1.0));
        OracleStreamTestServer.Session session = server.awaitSession(0, WAIT);
        await(() -> gauge(OracleStreamClient.METER_CONNECTED) == 1.0, WAIT, "connected = 1");

        session.sendText(resource(PREVIEW_PUSH));
        session.sendText("{\"type\":\"hello\"}");
        session.sendText("{not json");
        session.sendText("x".repeat(OracleStreamClient.MAX_MESSAGE_CHARS + 1));
        session.sendBinary(new byte[]{1});
        session.sendText(resource(PREVIEW_PUSH));
        await(() -> recorder.pushes.size() == 2, WAIT, "two pushes");
        await(() -> counter(OracleStreamClient.METER_PUSHES, "result", "binary") == 1.0, WAIT, "the binary");

        assertEquals(2.0, counter(OracleStreamClient.METER_PUSHES, "result", "parsed"));
        for (String result : List.of("unknown_type", "malformed", "oversize", "binary")) {
            assertEquals(1.0, counter(OracleStreamClient.METER_PUSHES, "result", result), result);
        }
        assertEquals((double) recorder.pushes.get(1).at().getEpochSecond(), gauge(OracleStreamClient.METER_LAST_PUSH));
        OracleStreamClient.Status status = client.status();
        assertEquals(OracleStreamClient.Status.State.CONNECTED, status.state());
        assertEquals(2, status.pushes());
        assertEquals(recorder.pushes.get(1).at(), status.lastPushAt());

        session.close(1000);
        OracleStreamClient.Status retrying = awaitState(OracleStreamClient.Status.State.RETRYING);
        assertEquals(0.0, gauge(OracleStreamClient.METER_CONNECTED));
        assertEquals(1.0, counter(OracleStreamClient.METER_DISCONNECTS, "reason", "server_close"));
        assertEquals("server-close(1000)", retrying.detail());
        assertEquals(2, retrying.pushes(), "the count survives the end");

        OracleStreamTestServer.Session second = server.awaitSession(1, WAIT);
        await(() -> gauge(OracleStreamClient.METER_CONNECTED) == 1.0, WAIT, "connected again");
        second.drop();
        await(() -> counter(OracleStreamClient.METER_DISCONNECTS, "reason", "error") == 1.0, WAIT,
                "a dropped connection is an error");
        assertEquals(0.0, gauge(OracleStreamClient.METER_CONNECTED));
        assertEquals(0.0, counter(OracleStreamClient.METER_DISCONNECTS, "reason", "no_pong"));
        assertEquals(0.0, counter(OracleStreamClient.METER_DISCONNECTS, "reason", "proactive"));
    }

    @Test
    void eachRefusalIsCountedUnderItsStatusAndAnyOtherUnderOther() {
        int[] statuses = {400, 401, 403, 429, 500};
        String[] tags = {"400", "401", "403", "429", "other"};
        for (int i = 0; i < statuses.length; i++) {
            SimpleMeterRegistry meters = new SimpleMeterRegistry();
            try (OracleStreamTestServer refusing = new OracleStreamTestServer().refuse(statuses[i], "{}").start()) {
                ScheduledExecutorService stream = newScheduler();
                ExecutorService transport = Executors.newCachedThreadPool(r -> daemon(r, "oracle-stream-io"));
                OracleStreamClient refused = new OracleStreamClient(refusing.uri(), new OracleStreamAuth(account),
                        timings(Duration.ofSeconds(30), Duration.ofSeconds(10), LONG, 1.0), Clock.systemUTC(),
                        recorder, new OracleStreamLog(), stream, transport, () -> false, meters,
                        new OracleStreamRetryPolicy(MID));
                try {
                    refused.start();
                    String tag = tags[i];
                    await(() -> meters.get(OracleStreamClient.METER_REFUSALS).tag("status", tag).counter().count() >= 1,
                            WAIT, "the " + tag + " refusal");
                    for (String other : tags) {
                        if (!other.equals(tag)) {
                            assertEquals(0.0, meters.get(OracleStreamClient.METER_REFUSALS).tag("status", other)
                                    .counter().count(), statuses[i] + " counted under " + other);
                        }
                    }
                    int status = statuses[i];
                    await(() -> refused.status().state() == OracleStreamClient.Status.State.REFUSED
                            && Integer.valueOf(status).equals(refused.status().refusalStatus()), WAIT, "REFUSED " + status);
                } finally {
                    refused.stop();
                    stream.shutdownNow();
                    transport.shutdownNow();
                }
            }
        }
    }

    // ======================================================================================
    // FAB-174-2 audit residue
    // ======================================================================================

    /**
     * R2: a listener that throws in {@code onPush} produces exactly one {@code listener-error} line naming the
     * callback and the exception's simple class name — and the stream keeps receiving and pinging.
     */
    @Test
    void aThrowingListenerIsLoggedOnceAndTheStreamKeepsReceivingAndPinging() throws IOException {
        server = new OracleStreamTestServer().start();
        AtomicBoolean thrown = new AtomicBoolean();
        List<Instant> delivered = new CopyOnWriteArrayList<>();
        OracleStreamClient.Listener throwingOnce = new OracleStreamClient.Listener() {
            @Override
            public void onOpen(Instant at) {
            }

            @Override
            public void onClose(Instant at, String reason) {
            }

            @Override
            public void onPush(Instant at, long serverTimestamp, OracleRegistryParser.Parsed parsed, int chars) {
                delivered.add(at);
                if (thrown.compareAndSet(false, true)) {
                    throw new IllegalStateException("a listener bug with text that must not be logged");
                }
            }
        };
        Duration ping = Duration.ofMillis(100);
        scheduler = newScheduler();
        io = Executors.newCachedThreadPool(r -> daemon(r, "oracle-stream-io"));
        client = new OracleStreamClient(server.uri(), new OracleStreamAuth(account),
                timings(ping, Duration.ofSeconds(10), LONG, 1.0), Clock.systemUTC(), throwingOnce,
                new OracleStreamLog(), scheduler, io, () -> false, registry, new OracleStreamRetryPolicy(MID));
        client.start();
        OracleStreamTestServer.Session session = server.awaitSession(0, WAIT);
        session.sendText(resource(PREVIEW_PUSH));
        await(() -> !warnLines("listener-error").isEmpty(), WAIT, "the listener-error line");
        int pingsAtThrow = session.pings.get();

        session.sendText(resource(PREVIEW_PUSH));
        await(() -> delivered.size() == 2, WAIT, "the next push");
        await(() -> session.pings.get() >= pingsAtThrow + 2, WAIT, "pings after the throw");

        List<String> errors = linesOf("listener-error");
        assertEquals(1, errors.size(), lines().toString());
        assertTrue(errors.getFirst().matches("\\[oracle-stream] listener-error boot=[0-9a-f]{8} ev=\\d+ "
                + "callback=onPush error=IllegalStateException"), errors.getFirst());
        assertFalse(String.join("\n", lines()).contains("a listener bug"), "never the exception's message");
        assertTrue(session.open(), "the connection stays up");
        assertEquals(2, client.pushesParsed());
    }

    /**
     * R4: the ladder against an injected clock, to the millisecond. The FIRST reconnect after a transport close
     * waits ladder step 1 (not a later one), the next close within 60 s waits step 2, and a connection that had
     * been open for at least 60 s on the clock restarts the ladder at step 1.
     */
    @Test
    void theFirstReconnectUsesStepOneAndTheLadderResetsAfterSixtySecondsOpen() {
        server = new OracleStreamTestServer().start();
        MutableClock clock = new MutableClock(Instant.parse("2026-10-29T12:00:00Z"));
        double scale = 0.5; // the shortest RETRYING window, step 1, is 500 ms: wide enough to be seen under load
        start(server.uri(), timings(Duration.ofSeconds(30), Duration.ofSeconds(10), LONG, scale), () -> false, clock,
                newScheduler());

        OracleStreamTestServer.Session first = server.awaitSession(0, WAIT);
        await(() -> client.status().state() == OracleStreamClient.Status.State.CONNECTED, WAIT, "open 1");
        long closed1 = System.nanoTime();
        first.close(1000);
        assertEquals(Duration.ofMillis(Math.round(1_000 * scale)), retryDelay(clock), "close 1: ladder step 1");

        OracleStreamTestServer.Session second = server.awaitSession(1, WAIT);
        long waitedMs = Duration.ofNanos(System.nanoTime() - closed1).toMillis();
        assertTrue(waitedMs >= Math.round(1_000 * scale) - 20, "the reconnect waited the step: " + waitedMs + " ms");
        await(() -> client.status().state() == OracleStreamClient.Status.State.CONNECTED, WAIT, "open 2");
        second.close(1000);
        assertEquals(Duration.ofMillis(Math.round(2_000 * scale)), retryDelay(clock),
                "close 2, open for 0 s: ladder step 2");

        OracleStreamTestServer.Session third = server.awaitSession(2, WAIT);
        await(() -> client.status().state() == OracleStreamClient.Status.State.CONNECTED, WAIT, "open 3");
        clock.advance(Duration.ofSeconds(60));
        third.close(1000);
        assertEquals(Duration.ofMillis(Math.round(1_000 * scale)), retryDelay(clock),
                "close 3, open for 60 s: the ladder restarted at step 1");
        await(() -> lines().stream().anyMatch(l -> l.contains(" reason=server-close(1000) up_s=60")), WAIT,
                "the closed line of the connection open for 60 s");
    }

    /**
     * R5: a close that overtakes the handshake's completion task. The completion task is held back on the
     * scheduler, the server closes the moment the socket opens, and the client must still say {@code connected}
     * BEFORE it says {@code closed} for that connection — never a {@code closed} line with no {@code connected}.
     */
    @Test
    void aCloseThatOvertakesTheHandshakeCompletionStillSaysConnectedFirst() {
        server = new OracleStreamTestServer().start();
        DelayingScheduler delaying = new DelayingScheduler(Duration.ofMillis(500));
        // A 5 s first step: no second connection muddies the assertions below.
        start(server.uri(), timings(Duration.ofSeconds(30), Duration.ofSeconds(10), LONG, 5.0), () -> false,
                Clock.systemUTC(), delaying);
        OracleStreamTestServer.Session session = server.awaitSession(0, WAIT);
        session.close(1000);
        await(() -> !linesOf("closed").isEmpty(), WAIT, "the closed line");
        await(() -> delaying.released.get() >= 1, WAIT, "the held-back completion task ran");

        assertTrue(delaying.delayed.get() >= 1, "fixture: the completion task was held back");
        List<String> all = lines();
        int connected = indexOf(all, "[oracle-stream] connected ");
        int closed = indexOf(all, "[oracle-stream] closed ");
        assertTrue(connected >= 0, "a connected line: " + all);
        assertTrue(connected < closed, "connected before closed: " + all);
        assertEquals(1, linesOf("connected").size(), all.toString());
        assertTrue(all.get(closed).contains(" reason=server-close(1000) "), all.get(closed));
        assertEquals(1, recorder.opens.size(), "onOpen once");
        assertEquals(1, recorder.closes.size(), "onClose once");
    }

    // ======================================================================================
    // Helpers
    // ======================================================================================

    private static OracleStreamClient.Timings timings(Duration ping, Duration pong, Duration proactive, double scale) {
        return new OracleStreamClient.Timings(OracleStreamClient.Timings.PRODUCTION.tcpConnectTimeout(),
                OracleStreamClient.Timings.PRODUCTION.handshakeTimeout(), ping, pong, proactive, scale);
    }

    private void start(URI uri, OracleStreamClient.Timings timings) {
        start(uri, timings, () -> false, Clock.systemUTC(), newScheduler());
    }

    private void start(URI uri, OracleStreamClient.Timings timings, BooleanSupplier stake, Clock clock,
                       ScheduledExecutorService stream) {
        build(uri, timings, stake, clock, stream);
        client.start();
    }

    private void build(URI uri, OracleStreamClient.Timings timings, BooleanSupplier stake, Clock clock,
                       ScheduledExecutorService stream) {
        AtomicInteger ioThreads = new AtomicInteger();
        scheduler = stream;
        io = Executors.newCachedThreadPool(r -> daemon(r, "oracle-stream-io-" + ioThreads.incrementAndGet()));
        client = new OracleStreamClient(uri, new OracleStreamAuth(account), timings, clock, recorder,
                new OracleStreamLog(), scheduler, io, stake, registry, new OracleStreamRetryPolicy(MID));
    }

    private static ScheduledExecutorService newScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "oracle-stream"));
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    /**
     * The delay to the next attempt, read off ONE RETRYING status against the frozen clock — never a second read,
     * which could already see the CONNECTING that follows it.
     */
    private Duration retryDelay(MutableClock clock) {
        OracleStreamClient.Status retrying = awaitState(OracleStreamClient.Status.State.RETRYING);
        return Duration.between(clock.instant(), retrying.nextAttemptAt());
    }

    /** Waits until the client's status is in {@code state} and returns that one status, captured once. */
    private OracleStreamClient.Status awaitState(OracleStreamClient.Status.State state) {
        AtomicReference<OracleStreamClient.Status> seen = new AtomicReference<>();
        await(() -> {
            OracleStreamClient.Status now = client.status();
            if (now.state() != state) {
                return false;
            }
            seen.set(now);
            return true;
        }, WAIT, state.name());
        return seen.get();
    }

    private double counter(String name, String tag, String value) {
        return registry.get(name).tag(tag, value).counter().count();
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    private static long ts(String query) {
        for (String pair : query.split("&")) {
            if (pair.startsWith("ts=")) {
                return Long.parseLong(pair.substring(3));
            }
        }
        throw new AssertionError("no ts in the query");
    }

    private static int indexOf(List<String> lines, String prefix) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = OracleStreamClientLivenessTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("missing fixture " + path);
            }
            return new String(in.readAllBytes(), UTF_8);
        }
    }

    private List<String> lines() {
        return List.copyOf(appender.list).stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private List<String> linesOf(String event) {
        return lines().stream().filter(m -> m.startsWith("[oracle-stream] " + event + " ")).toList();
    }

    private List<String> warnLines(String event) {
        return linesAt(Level.WARN, event);
    }

    private List<String> infoLines(String event) {
        return linesAt(Level.INFO, event);
    }

    private List<String> debugLines(String event) {
        return linesAt(Level.DEBUG, event);
    }

    private List<String> linesAt(Level level, String event) {
        List<String> out = new ArrayList<>();
        for (ILoggingEvent e : List.copyOf(appender.list)) {
            if (e.getLevel() == level && e.getFormattedMessage().startsWith("[oracle-stream] " + event + " ")) {
                out.add(e.getFormattedMessage());
            }
        }
        return out;
    }

    /** A clock that stands still until told to move. */
    static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
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

    /**
     * The stream's single thread, except that a task {@link java.util.concurrent.CompletableFuture} hands it —
     * the handshake's completion, {@code whenCompleteAsync(..., scheduler)} — runs {@code delay} late, so a close
     * handed off by the transport meanwhile overtakes it.
     */
    static final class DelayingScheduler extends ScheduledThreadPoolExecutor {

        private final Duration delay;
        final AtomicInteger delayed = new AtomicInteger();
        final AtomicInteger released = new AtomicInteger();

        DelayingScheduler(Duration delay) {
            super(1, r -> daemon(r, "oracle-stream"));
            this.delay = delay;
        }

        @Override
        public void execute(Runnable command) {
            if (command.getClass().getName().startsWith("java.util.concurrent.CompletableFuture")) {
                delayed.incrementAndGet();
                super.schedule(() -> {
                    command.run();
                    released.incrementAndGet();
                }, delay.toMillis(), TimeUnit.MILLISECONDS);
            } else {
                super.execute(command);
            }
        }
    }
}
