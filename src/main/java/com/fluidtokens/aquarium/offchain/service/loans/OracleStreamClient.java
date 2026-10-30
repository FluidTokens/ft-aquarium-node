package com.fluidtokens.aquarium.offchain.service.loans;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * One WebSocket to FluidTokens' oracle stream (FAB-174): connect with a freshly signed query, reassemble
 * {@code oracle-tokens} pushes, parse each with {@link OracleRegistryParser} and hand it to a {@link Listener};
 * ping while open and treat a missing pong as a dead connection; reconnect proactively before the server's
 * two-hour cap; and after every end, wait as {@link OracleStreamRetryPolicy} says. A SHADOW: nothing here
 * reads or writes {@link FluidOracleClient}.
 *
 * <p><b>Transport: the JDK's {@code java.net.http.WebSocket}, not Reactor Netty — decided.</b> A refused
 * handshake carries the reason in its body ({@code {"message":"connection limit reached: 1 per bond held"}}),
 * and Reactor Netty 1.1.22 releases that body before it can be read; the JDK exposes it on
 * {@link WebSocketHandshakeException#getResponse()} (probe log item 36).
 *
 * <p>⛔ <b>Threads.</b> Everything that decides anything runs on the single {@code scheduler} thread: the
 * connect, the completion of the handshake ({@code whenCompleteAsync(..., scheduler)}), decoding, the
 * reconnect, the ping and its pong deadline, the proactive reconnect, the stake re-check and every
 * {@link Listener} callback. The JDK's {@link WebSocket.Listener} callbacks run on the I/O executor and only
 * append, record and hand off with {@code scheduler.execute}: they never parse and never block. Nothing on the
 * scheduler blocks either. Every task body is wrapped so that a throw is logged by its simple class name and
 * never escapes — a task that throws out of a {@link ScheduledExecutorService} silently cancels itself (the
 * {@code NodeVersionCheck} lesson), and a cancelled ping or pong deadline would end liveness in silence.
 *
 * <p>⛔ <b>Liveness is a pong, never a push.</b> A ping goes out every {@code pingInterval}; when no pong has
 * arrived {@code pongTimeout} after it, the connection is dead: aborted, {@code closed reason=no-pong}, and the
 * transport ladder. Push silence is normal — pushes arrive only when a price changes — and never ends a
 * connection. The pings are not optional for a second reason: on an idle connection the JDK client stalls
 * delivery of inbound frames until the application writes something (probe log items 22–23, 25), and API
 * Gateway drops a connection idle for about ten minutes (item 13).
 *
 * <p>⛔ <b>Break-then-make.</b> The proactive reconnect closes the old socket — {@code Close(1000, "proactive")},
 * aborted after {@link #CLOSE_GRACE} if the server does not answer — and only then opens the next one, with a
 * fresh {@code ts}: a bond grants ONE connection, and a second socket opened first would be refused with 429.
 *
 * <p>⛔ <b>One WARN per state change.</b> A refusal or an end is said at WARN when its state key differs from
 * the last one said, and at DEBUG while it repeats. The latch is this client's own and is cleared by every
 * successful open, so the same refusal after a connection is a new state and WARNs again.
 *
 * <p>⛔ <b>No secret reaches a log line.</b> The signed URI is built per attempt and never logged, an
 * exception is reported by its simple class name only — never its message, which can quote the URI
 * it was handed (the signed query included) or response text — and a message the stream could not use is
 * counted, not echoed.
 */
public final class OracleStreamClient {

    /** The largest message reassembled, in chars; a longer one is discarded through to its last part. */
    static final int MAX_MESSAGE_CHARS = 1_048_576;

    /** How long {@link #stop()} and the proactive reconnect wait for the closing handshake before they abort. */
    static final Duration CLOSE_GRACE = Duration.ofSeconds(1);

    static final String PUSH_TYPE = "oracle-tokens";

    static final String METER_CONNECTED = "aquarium.oracle.stream.connected";
    static final String METER_PUSHES = "aquarium.oracle.stream.pushes";
    static final String METER_REFUSALS = "aquarium.oracle.stream.refusals";
    static final String METER_DISCONNECTS = "aquarium.oracle.stream.disconnects";
    static final String METER_LAST_PUSH = "aquarium.oracle.stream.last.push.seconds";

    /** Every tag value of each counter, registered at construction so that absence reads as zero. */
    static final List<String> PUSH_RESULTS = List.of("parsed", "unknown_type", "malformed", "oversize", "binary");
    static final List<String> REFUSAL_STATUSES = List.of("400", "401", "403", "429", "other");
    static final List<String> DISCONNECT_REASONS = List.of("proactive", "server_close", "error", "no_pong");

    private static final int TYPE_ECHO = 32;
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * @param tcpConnectTimeout       the TCP connect bound ({@link HttpClient.Builder#connectTimeout})
     * @param handshakeTimeout        the opening-handshake bound ({@link WebSocket.Builder#connectTimeout}); the
     *                                authorizer's on-chain bond lookup alone took 0.6–2.5 s in the probe
     * @param pingInterval            the RFC 6455 ping period while open
     * @param pongTimeout             no pong this long after a ping means the connection is dead
     * @param proactiveReconnectAfter how long a connection stays open before it is replaced, break-then-make;
     *                                under the server's two-hour cap
     * @param retryScale              every delay {@link OracleStreamRetryPolicy} computes, and the stake re-check
     *                                interval, is multiplied by this; 1.0 in production
     */
    public record Timings(Duration tcpConnectTimeout, Duration handshakeTimeout, Duration pingInterval,
                          Duration pongTimeout, Duration proactiveReconnectAfter, double retryScale) {

        public static final Timings PRODUCTION = new Timings(Duration.ofSeconds(10), Duration.ofSeconds(20),
                Duration.ofSeconds(45), Duration.ofSeconds(10), Duration.ofMinutes(115), 1.0);
    }

    /**
     * What the stream saw. Invoked only on the scheduler thread; a throw is caught and logged by its simple
     * class name.
     */
    public interface Listener {

        void onOpen(Instant at);

        void onClose(Instant at, String reason);

        /**
         * @param at              when the message's last part arrived
         * @param serverTimestamp the message's {@code timestamp}, epoch milliseconds
         * @param chars           the message's length in chars
         */
        void onPush(Instant at, long serverTimestamp, OracleRegistryParser.Parsed parsed, int chars);

        /**
         * The form the client calls; by default it forgets {@code xferMs} and calls the four-argument form.
         *
         * @param at              when the message's last part arrived, read before it was parsed
         * @param serverTimestamp the message's {@code timestamp}, epoch milliseconds
         * @param xferMs          milliseconds from the message's first frame to its last frame; 0 for a single frame
         * @param chars           the message's length in chars
         */
        default void onPush(Instant at, long serverTimestamp, long xferMs, OracleRegistryParser.Parsed parsed,
                            int chars) {
            onPush(at, serverTimestamp, parsed, chars);
        }
    }

    /**
     * Where the stream stands, for the readiness page. Immutable and published through a {@code volatile}
     * field, so any thread may read it at any time.
     *
     * @param state          what the stream is doing
     * @param detail         OFF: why; RETRYING: the reason the last connection ended; null otherwise
     * @param since          when it entered {@code state} (for CONNECTED, when the connection opened)
     * @param lastPushAt     when the last parsed push arrived; null before any
     * @param pushes         pushes parsed since the client was built
     * @param refusalStatus  REFUSED: the handshake's HTTP status
     * @param refusalMessage REFUSED: the {@code message} of the refusal's body, possibly empty
     * @param nextAttemptAt  RETRYING and REFUSED: when the next attempt is due
     */
    public record Status(State state, String detail, Instant since, Instant lastPushAt, long pushes,
                         Integer refusalStatus, String refusalMessage, Instant nextAttemptAt) {

        public enum State { OFF, CONNECTING, CONNECTED, RETRYING, REFUSED }

        /** OFF, with why. */
        public static Status off(String detail, Instant since) {
            return new Status(State.OFF, detail, since, null, 0, null, null, null);
        }
    }

    private final URI base;
    private final OracleStreamAuth auth;
    private final Timings timings;
    private final Clock clock;
    private final Listener listener;
    private final OracleStreamLog events;
    private final ScheduledExecutorService scheduler;
    private final Executor ioExecutor;
    private final BooleanSupplier stakePresent;
    private final OracleStreamRetryPolicy policy;

    private final AtomicLong pushesParsed = new AtomicLong();
    private final Map<String, AtomicLong> ignored = new ConcurrentHashMap<>();

    // Metrics: the gauges' state objects are held here, strongly, for as long as the client lives.
    private final AtomicInteger connectedGauge = new AtomicInteger();
    private final AtomicLong lastPushSeconds = new AtomicLong();
    private final Map<String, Counter> pushCounters = new LinkedHashMap<>();
    private final Map<String, Counter> refusalCounters = new LinkedHashMap<>();
    private final Map<String, Counter> disconnectCounters = new LinkedHashMap<>();

    private volatile boolean stopped;
    private volatile HttpClient http;
    /** The open connection, for {@link #stop()}; written on the scheduler. */
    private volatile Connection live;
    private volatile Status status;

    // Confined to the scheduler thread.
    private int attemptsSinceConnected;
    private long pingSeq;
    private ScheduledFuture<?> nextConnect;
    private ScheduledFuture<?> stakeRecheck;
    /** The client-side WARN latch: the state key last said at WARN; cleared by every successful open. */
    private String warnedState;

    public OracleStreamClient(URI base, OracleStreamAuth auth, Timings timings, Clock clock, Listener listener,
                              OracleStreamLog log, ScheduledExecutorService scheduler, Executor ioExecutor,
                              BooleanSupplier stakePresent, MeterRegistry meterRegistry) {
        this(base, auth, timings, clock, listener, log, scheduler, ioExecutor, stakePresent, meterRegistry,
                new OracleStreamRetryPolicy(new SplittableRandom()));
    }

    /** For tests: a policy with a known jitter source. */
    OracleStreamClient(URI base, OracleStreamAuth auth, Timings timings, Clock clock, Listener listener,
                       OracleStreamLog log, ScheduledExecutorService scheduler, Executor ioExecutor,
                       BooleanSupplier stakePresent, MeterRegistry meterRegistry, OracleStreamRetryPolicy policy) {
        this.base = base;
        this.auth = auth;
        this.timings = timings;
        this.clock = clock;
        this.listener = listener;
        this.events = log;
        this.scheduler = scheduler;
        this.ioExecutor = ioExecutor;
        this.stakePresent = stakePresent;
        this.policy = policy;
        this.status = new Status(Status.State.CONNECTING, null, clock == null ? null : clock.instant(), null, 0,
                null, null, null);
        if (meterRegistry != null) {
            register(meterRegistry);
        }
    }

    private void register(MeterRegistry registry) {
        Gauge.builder(METER_CONNECTED, connectedGauge, AtomicInteger::doubleValue)
                .description("1 while the shadow oracle stream holds an open connection, 0 otherwise")
                .register(registry);
        Gauge.builder(METER_LAST_PUSH, lastPushSeconds, AtomicLong::doubleValue)
                .description("Epoch seconds of the last oracle stream push that parsed; 0 before any")
                .register(registry);
        for (String result : PUSH_RESULTS) {
            pushCounters.put(result, Counter.builder(METER_PUSHES).tag("result", result)
                    .description("Oracle stream messages received, by what became of them")
                    .register(registry));
        }
        for (String code : REFUSAL_STATUSES) {
            refusalCounters.put(code, Counter.builder(METER_REFUSALS).tag("status", code)
                    .description("Oracle stream handshakes refused, by HTTP status")
                    .register(registry));
        }
        for (String reason : DISCONNECT_REASONS) {
            disconnectCounters.put(reason, Counter.builder(METER_DISCONNECTS).tag("reason", reason)
                    .description("Open oracle stream connections that ended, by why")
                    .register(registry));
        }
    }

    // ======================================================================================
    // Lifecycle
    // ======================================================================================

    /** Builds the transport and schedules the first connect. Returns at once; a second call does nothing. */
    public synchronized void start() {
        if (stopped || http != null) {
            return;
        }
        http = HttpClient.newBuilder()
                .connectTimeout(timings.tcpConnectTimeout())
                .executor(ioExecutor)
                .build();
        handOff("connect", this::connect);
    }

    /**
     * Sends {@code Close(1000, "shutdown")}, waits up to {@link #CLOSE_GRACE} for the closing handshake, aborts,
     * and shuts the transport down. Never throws. The executors belong to the caller.
     */
    public void stop() {
        try {
            stopped = true;
            connectedGauge.set(0);
            Status last = status;
            status = new Status(Status.State.OFF, "stopped", clock.instant(), last.lastPushAt(), last.pushes(),
                    null, null, null);
            Connection connection = live;
            live = null;
            if (connection != null && connection.socket != null) {
                Duration up = connection.openedAt == null ? Duration.ZERO
                        : Duration.between(connection.openedAt, clock.instant());
                try {
                    connection.socket.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown")
                            .thenCompose(socket -> connection.peerClosed)
                            .get(CLOSE_GRACE.toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    // no closing handshake within the grace: aborted below
                }
                connection.socket.abort();
                events.info("closed", "reason=shutdown up_s=" + up.toSeconds());
            }
            HttpClient client = http;
            if (client != null) {
                client.shutdownNow();
            }
        } catch (Throwable t) {
            // ⛔ never throws: this runs on Spring's shutdown path
        }
    }

    /** Where the stream stands now. Never blocks; safe from any thread. */
    public Status status() {
        return status;
    }

    /** Pushes parsed so far. */
    long pushesParsed() {
        return pushesParsed.longValue();
    }

    /** Messages ignored for {@code reason}: {@code oversize}, {@code unknown-type}, {@code malformed}, {@code binary}. */
    long ignored(String reason) {
        return ignored.getOrDefault(reason, new AtomicLong()).longValue();
    }

    /** ⛔ Never the signed URI. */
    @Override
    public String toString() {
        return "OracleStreamClient[host=" + (base == null ? null : base.getHost()) + "]";
    }

    // ======================================================================================
    // On the scheduler
    // ======================================================================================

    private void connect() {
        if (stopped) {
            return;
        }
        cancel(stakeRecheck);
        stakeRecheck = null;
        nextConnect = null;
        attemptsSinceConnected++;
        Connection connection = new Connection(attemptsSinceConnected, clock.millis());
        Status last = status;
        status = new Status(Status.State.CONNECTING, null, clock.instant(), last.lastPushAt(), last.pushes(),
                null, null, null);
        events.debug("connecting", "attempt=" + connection.attempt);
        CompletableFuture<WebSocket> opening;
        try {
            // A fresh ts on every attempt: the authorizer refuses a signature older than two minutes.
            URI signed = auth.signedUri(base, clock.millis());
            opening = http.newWebSocketBuilder()
                    .connectTimeout(timings.handshakeTimeout())
                    .buildAsync(signed, connection);
        } catch (RuntimeException e) {
            end(connection, "error(" + OracleStreamLog.simpleName(e) + ")", false);
            return;
        }
        opening.whenCompleteAsync((socket, failure) ->
                guarded("opened", () -> opened(connection, socket, failure)).run(), scheduler);
    }

    private void opened(Connection connection, WebSocket socket, Throwable failure) {
        if (failure != null) {
            if (connection.ended) {
                return;
            }
            Throwable cause = unwrap(failure);
            if (cause instanceof WebSocketHandshakeException refusal) {
                refused(connection, refusal);
            } else {
                end(connection, "error(" + OracleStreamLog.simpleName(cause) + ")", false);
            }
            return;
        }
        if (connection.ended || stopped) {
            socket.abort();
            return;
        }
        markOpen(connection, socket);
        long period = timings.pingInterval().toMillis();
        connection.ping = scheduler.scheduleAtFixedRate(guarded("ping", () -> ping(connection)),
                period, period, TimeUnit.MILLISECONDS);
        connection.proactive = scheduler.schedule(guarded("proactive", this::reconnectProactively),
                timings.proactiveReconnectAfter().toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * The connection is open: one {@code connected} line, the gauge, the status, the listener — and the WARN
     * latch and the policy's success-reset ladders cleared. Also reached from {@link #end} when a close or an
     * error overtook the handshake's completion task, so that no {@code closed} line is ever said for a
     * connection that never said {@code connected}.
     */
    private void markOpen(Connection connection, WebSocket socket) {
        connection.socket = socket;
        connection.openedAt = clock.instant();
        connection.pingSeqBase = pingSeq;
        live = connection;
        attemptsSinceConnected = 0;
        warnedState = null;
        policy.onConnected();
        connectedGauge.set(1);
        Status last = status;
        status = new Status(Status.State.CONNECTED, null, connection.openedAt, last.lastPushAt(), last.pushes(),
                null, null, null);
        events.info("connected", "attempt=" + connection.attempt
                + " handshake_ms=" + (clock.millis() - connection.startedMillis));
        Instant at = connection.openedAt;
        notifyListener("onOpen", () -> listener.onOpen(at));
    }

    /** A refused handshake: its status and the {@code message} of its JSON body. */
    private void refused(Connection connection, WebSocketHandshakeException refusal) {
        connection.ended = true;
        HttpResponse<?> response = refusal.getResponse();
        int code = response == null ? 0 : response.statusCode();
        String message = refusalMessage(response == null ? null : response.body());
        count(refusalCounters, REFUSAL_STATUSES.contains(String.valueOf(code)) ? String.valueOf(code) : "other");
        OracleStreamRetryPolicy.Decision decision = policy.next(OracleStreamRetryPolicy.Outcome.refused(code));
        long delayMs = scheduleConnect(decision.delay());
        Instant now = clock.instant();
        Status last = status;
        status = new Status(Status.State.REFUSED, null, now, last.lastPushAt(), last.pushes(), code, message,
                now.plusMillis(delayMs));
        long retrySeconds = Math.round(delayMs / 1000.0);
        warnOnChange(decision.stateKey(), "refused",
                "status=" + code + " " + OracleStreamLog.msg(message) + " retry_s=" + retrySeconds,
                refusalText(code, retrySeconds));
        if (decision.rule() == OracleStreamRetryPolicy.Rule.HOLD_403) {
            startStakeHold();
        }
    }

    /** The operator's reading of a refusal, by status; null for a status with no rule of its own. */
    private static String refusalText(int status, long retrySeconds) {
        return switch (status) {
            case 401 -> "signature rejected; check the system clock";
            case 403 -> "no Aquarium staking bond for this wallet's stake key; retrying hourly or when the stake appears";
            case 429 -> "another node on this bond holds the connection; retrying in " + retrySeconds + "s";
            case 400 -> "request rejected as malformed";
            default -> null;
        };
    }

    /** The {@code message} field of a JSON body that is a {@code byte[]} or a {@code String}; empty otherwise. */
    static String refusalMessage(Object body) {
        String text = body instanceof byte[] bytes ? new String(bytes, UTF_8)
                : body instanceof String string ? string : null;
        if (text == null || text.isBlank()) {
            return "";
        }
        try {
            JsonNode message = JSON.readTree(text).get("message");
            return message != null && message.isTextual() ? message.asText() : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** Any end of a connection that was neither a refusal nor proactive: one line, then the transport ladder. */
    private void end(Connection connection, String reason, boolean abort) {
        if (connection.ended) {
            return;
        }
        if (connection.openedAt == null && connection.handshaken != null && !stopped) {
            // A close or an error overtook the handshake's completion task: it did open, so say so first.
            markOpen(connection, connection.handshaken);
        }
        connection.ended = true;
        if (live == connection) {
            live = null;
        }
        cancel(connection.ping);
        cancel(connection.proactive);
        if (abort && connection.socket != null) {
            connection.socket.abort();
        }
        if (stopped) {
            return; // stop() says "closed reason=shutdown" itself
        }
        connectedGauge.set(0);
        Instant now = clock.instant();
        Duration up = connection.openedAt == null ? Duration.ZERO : Duration.between(connection.openedAt, now);
        if (connection.openedAt != null) {
            count(disconnectCounters, disconnectTag(reason));
        }
        OracleStreamRetryPolicy.Decision decision =
                policy.next(OracleStreamRetryPolicy.Outcome.transport(reason, up));
        long delayMs = scheduleConnect(decision.delay());
        Status last = status;
        status = new Status(Status.State.RETRYING, reason, now, last.lastPushAt(), last.pushes(), null, null,
                now.plusMillis(delayMs));
        warnOnChange(decision.stateKey(), "closed", "reason=" + reason + " up_s=" + up.toSeconds(), null);
        if (connection.openedAt != null) {
            notifyListener("onClose", () -> listener.onClose(now, reason));
        }
    }

    private static String disconnectTag(String reason) {
        if (reason.startsWith("server-close")) {
            return "server_close";
        }
        if (reason.equals("no-pong")) {
            return "no_pong";
        }
        return "error";
    }

    /** Schedules the next connect after {@code delay} × {@code retryScale} and returns that delay in milliseconds. */
    private long scheduleConnect(Duration delay) {
        if (stopped) {
            return 0;
        }
        long delayMs = Math.max(0, Math.round(delay.toMillis() * timings.retryScale()));
        try {
            nextConnect = scheduler.schedule(guarded("connect", this::connect), delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // the scheduler is shutting down: nothing left to reconnect
        }
        return delayMs;
    }

    /** One ping, and the deadline by which its pong must have arrived. */
    private void ping(Connection connection) {
        if (connection.ended || connection.socket == null || stopped) {
            return;
        }
        long seq = ++pingSeq;
        connection.lastPingSeq = seq;
        connection.socket.sendPing(ByteBuffer.allocate(0));
        try {
            scheduler.schedule(guarded("pong-deadline", () -> onPongDeadline(seq)),
                    timings.pongTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // stopping
        }
    }

    /** A pong arrived: every ping sent before it is answered. */
    private void pong(Connection connection) {
        connection.pongedThrough = connection.lastPingSeq;
    }

    /**
     * {@code pongTimeout} after ping {@code pingSeq}: when no pong has arrived since it, the connection is dead.
     * ⛔ Only a missing pong ends a connection here — never the absence of pushes.
     */
    private void onPongDeadline(long pingSeq) {
        Connection connection = live;
        if (stopped || connection == null || connection.ended || pingSeq <= connection.pingSeqBase
                || connection.pongedThrough >= pingSeq) {
            return;
        }
        end(connection, "no-pong", true);
    }

    /**
     * {@code proactiveReconnectAfter} after the open: close first, open the next connection only once the close
     * has completed or been aborted. ⛔ Never two sockets at once — the bond grants one.
     */
    private void reconnectProactively() {
        Connection connection = live;
        if (stopped || connection == null || connection.ended || connection.socket == null) {
            return;
        }
        connection.ended = true;
        live = null;
        cancel(connection.ping);
        connectedGauge.set(0);
        Instant now = clock.instant();
        Duration up = Duration.between(connection.openedAt, now);
        count(disconnectCounters, "proactive");
        events.info("closed", "reason=proactive up_s=" + up.toSeconds());
        Status last = status;
        status = new Status(Status.State.RETRYING, "proactive", now, last.lastPushAt(), last.pushes(), null, null,
                now.plus(CLOSE_GRACE));
        notifyListener("onClose", () -> listener.onClose(now, "proactive"));

        // Scheduler-confined, like everything else here: the first of the two triggers below opens the next one.
        boolean[] reopened = {false};
        Runnable reopen = () -> {
            if (reopened[0]) {
                return;
            }
            reopened[0] = true;
            connection.socket.abort();
            scheduleConnect(policy.next(OracleStreamRetryPolicy.Outcome.proactive(up)).delay());
        };
        try {
            connection.socket.sendClose(WebSocket.NORMAL_CLOSURE, "proactive");
        } catch (RuntimeException e) {
            // no close sent: the abort below ends it
        }
        connection.peerClosed.whenComplete((value, failure) -> handOff("proactive-reopen", reopen));
        try {
            scheduler.schedule(guarded("proactive-abort", reopen), CLOSE_GRACE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // stopping
        }
    }

    /**
     * A 403 hold began: record the stake presence now, then re-read it every {@code STAKE_RECHECK_INTERVAL}
     * until the hold ends. Runs only here, on the scheduler, while no connection is open.
     */
    private void startStakeHold() {
        policy.holdStarted(readStake());
        long period = Math.max(1, Math.round(
                OracleStreamRetryPolicy.STAKE_RECHECK_INTERVAL.toMillis() * timings.retryScale()));
        try {
            stakeRecheck = scheduler.scheduleWithFixedDelay(guarded("stake-recheck", this::recheckStake),
                    period, period, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // stopping
        }
    }

    /** The stake presence changed during a 403 hold: retry at once instead of waiting the hold out. */
    private void recheckStake() {
        if (stopped || live != null || nextConnect == null) {
            return;
        }
        if (!policy.stakeChanged(readStake())) {
            return;
        }
        cancel(nextConnect);
        cancel(stakeRecheck);
        nextConnect = null;
        stakeRecheck = null;
        events.debug("stake-changed", "retry_s=0");
        connect();
    }

    /** ⛔ An exception, or no supplier, is "unknown" — never a change. */
    private Boolean readStake() {
        try {
            return stakePresent == null ? null : stakePresent.getAsBoolean();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * One complete text message: an {@code oracle-tokens} push goes to the listener, anything else is counted.
     * {@code at} and {@code xferMs} were read on the I/O side as its parts arrived, never here.
     */
    private void message(Connection connection, String text, Instant at, long xferMs) {
        if (connection.ended) {
            return;
        }
        JsonNode root;
        try {
            root = JSON.readTree(text);
        } catch (Exception e) {
            ignore("malformed", "malformed");
            return;
        }
        JsonNode type = root == null ? null : root.get("type");
        if (type == null || !type.isTextual()) {
            ignore("malformed", "malformed");
            return;
        }
        if (!PUSH_TYPE.equals(type.asText())) {
            ignore("unknown-type", "unknown-type:" + OracleStreamLog.token(type.asText(), TYPE_ECHO));
            return;
        }
        JsonNode tokens = root.get("tokens");
        JsonNode timestamp = root.get("timestamp");
        if (tokens == null || !tokens.isArray() || timestamp == null || !timestamp.isIntegralNumber()
                || !timestamp.canConvertToLong()) {
            ignore("malformed", "malformed");
            return;
        }
        OracleRegistryParser.Parsed parsed = OracleRegistryParser.parse(tokens);
        long pushes = pushesParsed.incrementAndGet();
        count(pushCounters, "parsed");
        lastPushSeconds.set(at.getEpochSecond());
        Status last = status;
        status = new Status(last.state(), last.detail(), last.since(), at, pushes, last.refusalStatus(),
                last.refusalMessage(), last.nextAttemptAt());
        long serverTimestamp = timestamp.longValue();
        int chars = text.length();
        notifyListener("onPush", () -> listener.onPush(at, serverTimestamp, xferMs, parsed, chars));
    }

    /** Counts a message the stream could not use and says so on change — ⛔ never echoing it. */
    private void ignore(String counter, String state) {
        long count = ignored.computeIfAbsent(counter, k -> new AtomicLong()).incrementAndGet();
        count(pushCounters, counter.replace('-', '_'));
        events.warnOnChange("push-ignored", state, "push-ignored", "reason=" + state + " count=" + count);
    }

    /**
     * The client-side latch: WARN when {@code stateKey} differs from the state last said at WARN, DEBUG while it
     * repeats. {@link #markOpen} clears it, so a repeat after a successful open is a new state.
     */
    private void warnOnChange(String stateKey, String event, String fields, String text) {
        if (stateKey.equals(warnedState)) {
            events.debug(event, text == null ? fields : fields + " -- " + text);
        } else {
            warnedState = stateKey;
            events.warn(event, fields, text);
        }
    }

    private static void count(Map<String, Counter> counters, String tag) {
        Counter counter = counters.getOrDefault(tag, null);
        if (counter != null) {
            counter.increment();
        }
    }

    private static void cancel(ScheduledFuture<?> future) {
        if (future != null) {
            future.cancel(false);
        }
    }

    private void notifyListener(String callback, Runnable call) {
        try {
            call.run();
        } catch (Throwable t) {
            events.warn("listener-error", "callback=" + callback + " error=" + OracleStreamLog.simpleName(t));
        }
    }

    /** A task body that logs a throw by its simple class name and never lets it escape. */
    private Runnable guarded(String task, Runnable body) {
        return () -> {
            try {
                body.run();
            } catch (Throwable t) {
                events.warn("task-error", "task=" + task + " error=" + OracleStreamLog.simpleName(t));
            }
        };
    }

    /** Hands a task to the scheduler; once it is shut down there is nothing left to do. */
    private void handOff(String task, Runnable body) {
        try {
            scheduler.execute(guarded(task, body));
        } catch (RejectedExecutionException e) {
            // stopping
        }
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    // ======================================================================================
    // One connection
    // ======================================================================================

    /**
     * One connection attempt, and its {@link WebSocket.Listener}. The JDK invokes the callbacks one at a time
     * with happens-before between them, so the reassembly buffer needs no lock; the callbacks touch nothing
     * else but the hand-off.
     */
    private final class Connection implements WebSocket.Listener {

        final int attempt;
        final long startedMillis;
        /** Completed when the server's Close arrives, or on an error — what a closing side waits on. */
        final CompletableFuture<Void> peerClosed = new CompletableFuture<>();
        /** The socket the JDK handed {@link #onOpen}: the handshake succeeded, whatever the scheduler has seen. */
        volatile WebSocket handshaken;

        // I/O side
        private StringBuilder text = new StringBuilder();
        private boolean oversize;
        /** When the current message's first part arrived; null between messages. */
        private Instant firstFrameAt;

        // Scheduler side (socket and openedAt are also read by stop())
        volatile WebSocket socket;
        volatile Instant openedAt;
        ScheduledFuture<?> ping;
        ScheduledFuture<?> proactive;
        boolean ended;
        /** The global ping sequence when this connection opened: its own pings are numbered above it. */
        long pingSeqBase;
        long lastPingSeq;
        long pongedThrough;

        Connection(int attempt, long startedMillis) {
            this.attempt = attempt;
            this.startedMillis = startedMillis;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            handshaken = webSocket;
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            // ⛔ The null check, not an empty buffer: an oversize message empties the buffer mid-message.
            if (firstFrameAt == null) {
                firstFrameAt = clock.instant();
            }
            if (!oversize) {
                if (text.length() + data.length() > MAX_MESSAGE_CHARS) {
                    oversize = true;
                    text = new StringBuilder();
                } else {
                    text.append(data);
                }
            }
            if (last) {
                Instant at = clock.instant();
                long xferMs = at.toEpochMilli() - firstFrameAt.toEpochMilli();
                firstFrameAt = null;
                if (oversize) {
                    oversize = false;
                    handOff("oversize", () -> ignore("oversize", "oversize"));
                } else {
                    String message = text.toString();
                    text = new StringBuilder();
                    handOff("message", () -> message(this, message, at, xferMs));
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            if (last) {
                handOff("binary", () -> ignore("binary", "binary"));
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            handOff("pong", () -> pong(this));
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            peerClosed.complete(null);
            handOff("server-close", () -> end(this, "server-close(" + statusCode + ")", false));
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            peerClosed.complete(null);
            String reason = "error(" + OracleStreamLog.simpleName(error) + ")";
            handOff("error", () -> end(this, reason, true));
        }
    }
}
