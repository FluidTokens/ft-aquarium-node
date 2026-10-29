package com.fluidtokens.aquarium.offchain.service.loans;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * One WebSocket to FluidTokens' oracle stream (FAB-174): connect with a freshly signed query, reassemble
 * {@code oracle-tokens} pushes, parse each with {@link OracleRegistryParser} and hand it to a {@link Listener};
 * reconnect after any end; ping while open. A SHADOW: nothing here reads or writes {@link FluidOracleClient}.
 *
 * <p><b>Transport: the JDK's {@code java.net.http.WebSocket}, not Reactor Netty — decided.</b> A refused
 * handshake carries the reason in its body ({@code {"message":"connection limit reached: 1 per bond held"}}),
 * and Reactor Netty 1.1.22 releases that body before it can be read; the JDK exposes it on
 * {@link WebSocketHandshakeException#getResponse()} (probe log item 36).
 *
 * <p>⛔ <b>Threads.</b> Everything that decides anything runs on the single {@code scheduler} thread: the
 * connect, the completion of the handshake ({@code whenCompleteAsync(..., scheduler)}), decoding, the
 * reconnect, the ping and every {@link Listener} callback. The JDK's {@link WebSocket.Listener} callbacks run
 * on the I/O executor and only append, record and hand off with {@code scheduler.execute}: they never parse
 * and never block. Nothing on the scheduler blocks either. Every task body is wrapped so that a throw is
 * logged by its simple class name and never escapes — a task that throws out of a
 * {@link ScheduledExecutorService} silently cancels itself (the {@code NodeVersionCheck} lesson), and a
 * cancelled ping or reconnect would end the stream in silence.
 *
 * <p>⛔ <b>Pings are not optional.</b> On an idle connection the JDK client stalls delivery of inbound
 * frames until the application writes something: a push stamped 10:54:23 arrived at 10:55:38, the instant
 * the probe sent its close (probe log items 22–23, 25). A ping every {@code pingInterval} keeps delivery
 * prompt, and keeps API Gateway's ~10-minute idle timeout away.
 *
 * <p>⛔ <b>No secret reaches a log line.</b> The signed URI is built per attempt and never logged, an
 * exception is reported by its simple class name only — never its message, which can quote the URI
 * it was handed (the signed query included) or response text — and a message the stream could not use is counted, not echoed.
 *
 * <p>The reconnect here is FAB-174-2 scaffolding — refusals ride the transport ladder too. FAB-174-3 moves it
 * into its own policy, adds the pong timeout, the proactive reconnect and the metrics.
 */
public final class OracleStreamClient {

    /** The largest message reassembled, in chars; a longer one is discarded through to its last part. */
    static final int MAX_MESSAGE_CHARS = 1_048_576;

    /** Seconds before reconnect attempt n (then 60 s repeated), each with ±20% uniform jitter. */
    static final int[] TRANSPORT_BACKOFF_SECONDS = {1, 2, 5, 10, 20, 30, 60};

    /** A connection that had been open this long resets the ladder to its first step when it ends. */
    static final Duration LADDER_RESET_AFTER = Duration.ofSeconds(60);

    /** How long {@link #stop()} waits for the closing handshake before it aborts. */
    static final Duration CLOSE_GRACE = Duration.ofSeconds(1);

    static final String PUSH_TYPE = "oracle-tokens";

    private static final int TYPE_ECHO = 32;
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * @param tcpConnectTimeout the TCP connect bound ({@link HttpClient.Builder#connectTimeout})
     * @param handshakeTimeout  the opening-handshake bound ({@link WebSocket.Builder#connectTimeout}); the
     *                          authorizer's on-chain bond lookup alone took 0.6–2.5 s in the probe
     * @param pingInterval      the RFC 6455 ping period while open
     * @param retryScale        every computed backoff delay is multiplied by this; 1.0 in production
     */
    public record Timings(Duration tcpConnectTimeout, Duration handshakeTimeout, Duration pingInterval,
                          double retryScale) {

        public static final Timings PRODUCTION = new Timings(Duration.ofSeconds(10), Duration.ofSeconds(20),
                Duration.ofSeconds(45), 1.0);
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
    }

    private final URI base;
    private final OracleStreamAuth auth;
    private final Timings timings;
    private final Clock clock;
    private final Listener listener;
    private final OracleStreamLog events;
    private final ScheduledExecutorService scheduler;
    private final Executor ioExecutor;

    private final AtomicLong pushesParsed = new AtomicLong();
    private final Map<String, AtomicLong> ignored = new ConcurrentHashMap<>();

    private volatile boolean stopped;
    private volatile HttpClient http;
    /** The open connection, for {@link #stop()}; written on the scheduler. */
    private volatile Connection live;

    // Confined to the scheduler thread.
    private int ladderStep;
    private int attemptsSinceConnected;

    public OracleStreamClient(URI base, OracleStreamAuth auth, Timings timings, Clock clock, Listener listener,
                              OracleStreamLog log, ScheduledExecutorService scheduler, Executor ioExecutor) {
        this.base = base;
        this.auth = auth;
        this.timings = timings;
        this.clock = clock;
        this.listener = listener;
        this.events = log;
        this.scheduler = scheduler;
        this.ioExecutor = ioExecutor;
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

    /** Pushes parsed so far. Package-private: metrics are FAB-174-3. */
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
        attemptsSinceConnected++;
        Connection connection = new Connection(attemptsSinceConnected, clock.millis());
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
        connection.socket = socket;
        connection.openedAt = clock.instant();
        live = connection;
        attemptsSinceConnected = 0;
        events.info("connected", "attempt=" + connection.attempt
                + " handshake_ms=" + (clock.millis() - connection.startedMillis));
        long period = timings.pingInterval().toMillis();
        connection.ping = scheduler.scheduleAtFixedRate(guarded("ping", () -> ping(connection)),
                period, period, TimeUnit.MILLISECONDS);
        Instant at = connection.openedAt;
        notifyListener("onOpen", () -> listener.onOpen(at));
    }

    /** A refused handshake: its status and the {@code message} of its JSON body. */
    private void refused(Connection connection, WebSocketHandshakeException refusal) {
        connection.ended = true;
        HttpResponse<?> response = refusal.getResponse();
        int status = response == null ? 0 : response.statusCode();
        String message = refusalMessage(response == null ? null : response.body());
        long delayMs = scheduleReconnect(Duration.ZERO);
        events.warnOnChange("refused", String.valueOf(status), "refused",
                "status=" + status + " " + OracleStreamLog.msg(message) + " retry_s=" + Math.round(delayMs / 1000.0));
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

    /** Any end of a connection that was not a refusal: one line, then a reconnect. */
    private void end(Connection connection, String reason, boolean abort) {
        if (connection.ended) {
            return;
        }
        connection.ended = true;
        if (live == connection) {
            live = null;
        }
        if (connection.ping != null) {
            connection.ping.cancel(false);
        }
        if (abort && connection.socket != null) {
            connection.socket.abort();
        }
        if (stopped) {
            return; // stop() says "closed reason=shutdown" itself
        }
        Instant now = clock.instant();
        Duration up = connection.openedAt == null ? Duration.ZERO : Duration.between(connection.openedAt, now);
        events.warnOnChange("closed", reason, "closed", "reason=" + reason + " up_s=" + up.toSeconds());
        if (connection.openedAt != null) {
            notifyListener("onClose", () -> listener.onClose(now, reason));
        }
        scheduleReconnect(up);
    }

    /** Schedules the next connect on the transport ladder and returns its delay in milliseconds. */
    private long scheduleReconnect(Duration endedConnectionUp) {
        if (stopped) {
            return 0;
        }
        if (endedConnectionUp.compareTo(LADDER_RESET_AFTER) >= 0) {
            ladderStep = 0;
        }
        int seconds = TRANSPORT_BACKOFF_SECONDS[Math.min(ladderStep, TRANSPORT_BACKOFF_SECONDS.length - 1)];
        if (ladderStep < TRANSPORT_BACKOFF_SECONDS.length) {
            ladderStep++;
        }
        double jitter = 0.8 + 0.4 * ThreadLocalRandom.current().nextDouble();
        long delayMs = Math.round(seconds * 1000.0 * jitter * timings.retryScale());
        try {
            scheduler.schedule(guarded("connect", this::connect), delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // the scheduler is shutting down: nothing left to reconnect
        }
        return delayMs;
    }

    private void ping(Connection connection) {
        if (connection.ended || connection.socket == null || stopped) {
            return;
        }
        connection.socket.sendPing(ByteBuffer.allocate(0));
    }

    /** One complete text message: an {@code oracle-tokens} push goes to the listener, anything else is counted. */
    private void message(Connection connection, String text, Instant at) {
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
        pushesParsed.incrementAndGet();
        long serverTimestamp = timestamp.longValue();
        int chars = text.length();
        notifyListener("onPush", () -> listener.onPush(at, serverTimestamp, parsed, chars));
    }

    /** Counts a message the stream could not use and says so on change — ⛔ never echoing it. */
    private void ignore(String counter, String state) {
        long count = ignored.computeIfAbsent(counter, k -> new AtomicLong()).incrementAndGet();
        events.warnOnChange("push-ignored", state, "push-ignored", "reason=" + state + " count=" + count);
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
        /** Completed when the server's Close arrives — what {@link #stop()} waits on. */
        final CompletableFuture<Void> peerClosed = new CompletableFuture<>();

        // I/O side
        private StringBuilder text = new StringBuilder();
        private boolean oversize;

        // Scheduler side (socket and openedAt are also read by stop())
        volatile WebSocket socket;
        volatile Instant openedAt;
        ScheduledFuture<?> ping;
        boolean ended;

        Connection(int attempt, long startedMillis) {
            this.attempt = attempt;
            this.startedMillis = startedMillis;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
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
                if (oversize) {
                    oversize = false;
                    handOff("oversize", () -> ignore("oversize", "oversize"));
                } else {
                    String message = text.toString();
                    text = new StringBuilder();
                    handOff("message", () -> message(this, message, at));
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
