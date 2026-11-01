package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.account.Account;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.service.StakerService;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Starts the SHADOW oracle stream (FAB-174) once the node is ready, or says in one line why it does not.
 *
 * <p>⛔ <b>A shadow: nothing reads what it produces.</b> Each push is parsed and judged by an
 * {@link OracleStreamComparator} against what the production poller loads, and said as one
 * {@code [oracle-stream] cmp} line (FAB-175; the push receipt itself is DEBUG); no decision, price, readiness
 * figure or {@link FluidOracleClient} state changes. It is on by default ({@code loans.oracle.stream.enabled},
 * Giovanni's ruling A2 of 2026-10-29) so every operator's log shows how the stream behaves before anything
 * depends on it.
 *
 * <p>⛔ <b>{@link FluidOracleClient} only through an {@link ObjectProvider}, to ask whether it exists and to
 * listen to its loads.</b> With {@code loans.oracle.enabled=false} that bean is absent, and a hard dependency would
 * fail the boot (the trap {@code StaticReferenceInputs} documents). Without it there is nothing to compare the
 * stream against, so the stream stays off. Its {@link FluidOracleClient.LoadListener} only hands the poller's
 * result to the {@code oracle-stream} thread: the stream never fetches the API itself.
 *
 * <p>⛔ <b>Its own threads, never Spring's scheduler.</b> Spring's scheduled-task pool is shared by the
 * transaction processor, liquidation, compound and the oracle refresh, and an operator may size it to 1. The
 * stream runs on one daemon {@code oracle-stream} thread plus a daemon {@code oracle-stream-io-<k>} pool for
 * the transport — the same reasoning as {@code NodeVersionCheck}.
 *
 * <p>⛔ <b>Never throws</b>: not from the constructor (which only stores), not from {@link #start()}, not from
 * {@link #stop()}, not from {@link #status()}. A shadow must never be the reason a node fails to start.
 *
 * <p>The stake presence the client re-reads during a 403 hold is {@link StakerService}'s own verdict — read
 * only on the {@code oracle-stream} thread, only during that hold, while no connection is open.
 *
 * <p>At each UTC midnight of the injected clock the comparator says its cumulative {@code day} line (FAB-175-2), on
 * the {@code oracle-stream} thread: exactly one per midnight. The delay to the next midnight is computed afresh each
 * time from that clock, never a fixed 24 h rate from the boot, so the lines stay on midnight whenever the node
 * started.
 */
@Component
public class OracleStreamLauncher {

    static final String MAINNET_HOST = "25mxd4whnj.execute-api.eu-west-1.amazonaws.com";
    static final String PREVIEW_HOST = "2tgda3mk4l.execute-api.eu-west-1.amazonaws.com";
    static final String THREAD_NAME = "oracle-stream";
    static final String IO_THREAD_PREFIX = "oracle-stream-io-";

    private static final int VALUE_ECHO = 16;
    /** FluidTokens truncate {@code X-FT-Node-Version} to this many characters. */
    static final int NODE_VERSION_MAX = 64;
    /** How often pushes older than {@link OracleStreamComparator#WINDOW} are resolved. */
    private static final long COMPARE_TICK_SECONDS = 10;

    private final Account account;
    private final AppConfig.Network network;
    private final ObjectProvider<FluidOracleClient> oracleClient;
    private final StakerService stakerService;
    private final MeterRegistry meterRegistry;
    private final String enabled;
    private final String url;
    private final String buildCommitShort;
    private final String buildDirty;
    private final OracleStreamLog events = new OracleStreamLog();

    private OracleStreamClient.Timings timings = OracleStreamClient.Timings.PRODUCTION;
    private Clock clock = Clock.systemUTC();

    private boolean started;
    private ScheduledExecutorService scheduler;
    private ExecutorService io;
    /** Read by {@link #status()} from any thread. */
    private volatile OracleStreamClient client;
    /** Confined to the {@code oracle-stream} thread, apart from {@link #pushes()}. */
    private volatile OracleStreamComparator comparator;
    /** {@code day} tasks that woke before their midnight by the injected clock, and so wrote nothing — for tests. */
    private final AtomicLong dayEarlyWakes = new AtomicLong();
    /** Why the stream is off while no client runs; what {@link #status()} reports then. */
    private volatile String offDetail = "not started";
    private volatile Instant offSince;

    /**
     * @param buildCommitShort the jar's own short commit — the banner's {@code aquarium.build.commitShort} — sent as
     *                         the handshake's {@code X-FT-Node-Version} (FAB-179); see {@link #nodeVersion}
     * @param buildDirty       {@code aquarium.build.dirty}: {@code true} appends {@code -dirty}
     */
    @Autowired
    public OracleStreamLauncher(Account account, AppConfig.Network network,
                                ObjectProvider<FluidOracleClient> oracleClient,
                                StakerService stakerService,
                                MeterRegistry meterRegistry,
                                @Value("${loans.oracle.stream.enabled:true}") String enabled,
                                @Value("${loans.oracle.stream.url:}") String url,
                                @Value("${aquarium.build.commitShort:unknown}") String buildCommitShort,
                                @Value("${aquarium.build.dirty:unknown}") String buildDirty) {
        this.account = account;
        this.network = network;
        this.oracleClient = oracleClient;
        this.stakerService = stakerService;
        this.meterRegistry = meterRegistry;
        this.enabled = enabled;
        this.url = url;
        this.buildCommitShort = buildCommitShort;
        this.buildDirty = buildDirty;
    }

    /** Without the build's provenance: the stream sends no {@code X-FT-Node-Version} header. */
    public OracleStreamLauncher(Account account, AppConfig.Network network,
                                ObjectProvider<FluidOracleClient> oracleClient,
                                StakerService stakerService,
                                MeterRegistry meterRegistry,
                                String enabled,
                                String url) {
        this(account, network, oracleClient, stakerService, meterRegistry, enabled, url, "unknown", "unknown");
    }

    /** For tests only: the timings and clock the client is built with. */
    synchronized void useForTest(OracleStreamClient.Timings timings, Clock clock) {
        this.timings = timings;
        this.clock = clock;
    }

    /** {@code day} tasks that woke early and waited again — for tests. */
    long dayEarlyWakes() {
        return dayEarlyWakes.get();
    }

    /** Pushes compared so far — for tests, which wait on it. */
    long pushes() {
        OracleStreamComparator comparing = comparator;
        return comparing == null ? 0 : comparing.pushes();
    }

    // ======================================================================================
    // Lifecycle
    // ======================================================================================

    /**
     * Returns at once: on, the stream connects on its own thread. One line either way. Idempotent.
     */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        try {
            launch();
        } catch (Throwable t) {
            shutDown();
            String reason = "internal-error(" + OracleStreamLog.simpleName(t) + ")";
            off(reason);
            events.warn("off", "reason=" + reason);
        }
    }

    @PreDestroy
    public synchronized void stop() {
        try {
            off("stopped");
            shutDownComparator();
            shutDown();
        } catch (Throwable t) {
            // ⛔ never throws: this runs on Spring's shutdown path
        }
    }

    /**
     * Where the shadow stream stands, for the readiness page: the client's own status while it runs, otherwise
     * OFF with why. Never blocks, never throws; safe from any thread.
     */
    public OracleStreamClient.Status status() {
        try {
            OracleStreamClient running = client;
            return running != null ? running.status() : OracleStreamClient.Status.off(offDetail, offSince);
        } catch (Throwable t) {
            return OracleStreamClient.Status.off("internal-error(" + OracleStreamLog.simpleName(t) + ")", null);
        }
    }

    private void off(String detail) {
        Clock now = clock;
        offSince = now == null ? null : now.instant();
        offDetail = detail;
    }

    /**
     * What {@link #start()} decides, before any thread exists: ON with the URI to connect to, or OFF with the
     * one line that says why.
     *
     * @param uri    the stream URL when ON, null when OFF
     * @param warn   the line is a WARN (an operator's setting is wrong) rather than an INFO
     * @param fields the line's fields: {@code network=… host=…} when ON, {@code reason=…} when OFF
     * @param text   the line's free text, or null
     * @param detail OFF: why, for {@link #status()}, beginning with the reason token; null when ON
     */
    record Decision(URI uri, boolean warn, String fields, String text, String detail) {

        boolean on() {
            return uri != null;
        }

        private static Decision off(boolean warn, String fields, String text, String detail) {
            return new Decision(null, warn, fields, text, detail);
        }
    }

    /**
     * The first state that matches wins. Pure: the inputs are read only as far as the decision needs them, in
     * this order, so a setting that already turns the stream off never touches the oracle bean or the network.
     * Package-private so the shipped hosts can be pinned without opening a socket.
     */
    static Decision decide(String enabled, BooleanSupplier apiOraclePresent, AppConfig.Network networkConfig,
                           String url) {
        String flag = enabled == null ? "" : enabled.trim().toLowerCase(Locale.ROOT);
        if (flag.equals("false")) {
            return Decision.off(false, "reason=disabled", "loans.oracle.stream.enabled=false",
                    "disabled (loans.oracle.stream.enabled=false)");
        }
        if (!flag.isEmpty() && !flag.equals("true")) {
            return Decision.off(true, "reason=unrecognised-enabled value="
                            + OracleStreamLog.token(enabled.trim(), VALUE_ECHO), null,
                    "unrecognised-enabled (loans.oracle.stream.enabled is neither true nor false)");
        }
        if (!apiOraclePresent.getAsBoolean()) {
            return Decision.off(false, "reason=no-api-oracle", "loans.oracle.enabled=false: nothing to compare against",
                    "no-api-oracle (loans.oracle.enabled=false: nothing to compare against)");
        }
        String network = networkConfig.getNetwork();
        String otherHost;
        if ("mainnet".equals(network)) {
            otherHost = PREVIEW_HOST;
        } else if ("preview".equals(network)) {
            otherHost = MAINNET_HOST;
        } else {
            String shown = OracleStreamLog.token(network, 32);
            return Decision.off(false, "reason=network-unsupported network=" + shown, null,
                    "network-unsupported (network " + shown + ")");
        }
        URI uri = validUri(url);
        if (uri == null) {
            return Decision.off(true, "reason=invalid-url", null,
                    "invalid-url (loans.oracle.stream.url must be a ws or wss URL with a host and no query)");
        }
        String host = uri.getHost();
        if (host.equalsIgnoreCase(otherHost)) {
            return Decision.off(true, "reason=url-network-mismatch network=" + network + " host=" + host, null,
                    "url-network-mismatch (host " + host + " is the other network's)");
        }
        return new Decision(uri, false, "network=" + network + " host=" + host, null, null);
    }

    /**
     * The handshake's {@code X-FT-Node-Version} value (FAB-179, asked for by FluidTokens to see which build each node
     * runs), or {@code null} for no header. Pure.
     *
     * <ul>
     *   <li>{@code null} when {@code commitShort} is null, blank, or {@code unknown} (any case) after trim — the
     *       banner's fallback, so a jar without build-info sends nothing rather than a placeholder;</li>
     *   <li>otherwise the trimmed {@code commitShort}, plus {@code -dirty} iff {@code dirty} trimmed is {@code true}
     *       (any case) — any other value, {@code unknown} included, appends nothing;</li>
     *   <li>cut to {@value #NODE_VERSION_MAX} characters, the length FluidTokens truncate the header to;</li>
     *   <li>{@code null} when any character of the result is outside visible ASCII (0x21–0x7E): CR, LF, space, tab,
     *       anything non-ASCII. The JDK's {@code WebSocket.Builder.header} accepts a CR or LF, and its opening
     *       handshake then rejects the value with an {@link IllegalArgumentException} — a failed connection attempt,
     *       on every attempt (measured on JDK 21.0.11). A version is never worth a connection, so such a value is
     *       dropped here, before the client, which checks it again around its {@code header} call.</li>
     * </ul>
     */
    static String nodeVersion(String commitShort, String dirty) {
        if (commitShort == null) {
            return null;
        }
        String commit = commitShort.trim();
        if (commit.isEmpty() || commit.equalsIgnoreCase("unknown")) {
            return null;
        }
        String value = dirty != null && dirty.trim().equalsIgnoreCase("true") ? commit + "-dirty" : commit;
        if (value.length() > NODE_VERSION_MAX) {
            value = value.substring(0, NODE_VERSION_MAX);
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x21 || c > 0x7E) {
                return null;
            }
        }
        return value;
    }

    /** One line either way; ON builds the threads and the client. */
    private void launch() {
        Decision decision = decide(enabled, () -> oracleClient.getIfAvailable() != null, network, url);
        if (!decision.on()) {
            off(decision.detail());
            if (decision.warn()) {
                events.warn("off", decision.fields(), decision.text());
            } else {
                events.info("off", decision.fields(), decision.text());
            }
            return;
        }
        URI uri = decision.uri();
        String nodeVersion = nodeVersion(buildCommitShort, buildDirty);

        events.info("on", decision.fields() + " node_ver=" + (nodeVersion == null ? "-" : nodeVersion));
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        });
        AtomicInteger ioThreads = new AtomicInteger();
        io = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, IO_THREAD_PREFIX + ioThreads.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        StakerService staker = stakerService;
        ScheduledExecutorService stream = scheduler;
        Clock streamClock = clock;
        OracleStreamComparator comparing = new OracleStreamComparator(events, meterRegistry);
        comparator = comparing;

        // The poller's own result, after each of its loads, handed to the stream thread: no fetch, no blocking.
        FluidOracleClient fc = oracleClient.getIfAvailable();
        if (fc != null) {
            fc.addLoadListener((entries, at) -> {
                try {
                    stream.execute(guarded("cmp-api-load", () -> comparing.onApiLoad(at, entries)));
                } catch (RejectedExecutionException ignored) {
                    // the stream has stopped: nothing left to compare
                }
            });
            stream.execute(guarded("cmp-seed", () -> {
                if (!Instant.EPOCH.equals(fc.lastRefresh())) {
                    comparing.onApiLoad(fc.lastRefresh(), fc.entries());
                }
            }));
        }
        stream.scheduleWithFixedDelay(guarded("cmp-tick", () -> comparing.tick(streamClock.instant())),
                COMPARE_TICK_SECONDS, COMPARE_TICK_SECONDS, TimeUnit.SECONDS);
        scheduleDaySummary(stream, comparing, streamClock);

        OracleStreamClient created = new OracleStreamClient(uri, new OracleStreamAuth(account), timings, clock,
                comparing, events, scheduler, io, () -> !staker.findStakerRefInput().isEmpty(),
                meterRegistry, nodeVersion);
        client = created;
        created.start();
    }

    /**
     * The pushes still waiting are resolved {@code UNCOVERED why=shutdown} — on the {@code oracle-stream} thread,
     * like every other call into the comparator, waiting for it at most a second.
     */
    private void shutDownComparator() {
        OracleStreamComparator comparing = comparator;
        ScheduledExecutorService stream = scheduler;
        Clock streamClock = clock;
        if (comparing == null || stream == null) {
            return;
        }
        try {
            Future<?> done = stream.submit(guarded("cmp-shutdown", () -> comparing.shutdown(streamClock.instant())));
            done.get(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // rejected or slow: the stream is going down regardless
        }
    }

    /**
     * The comparator's {@code day} line at the next UTC midnight of {@code streamClock}, then at each midnight after
     * it — see {@link #scheduleDaySummary(ScheduledExecutorService, OracleStreamComparator, Clock, Instant)}.
     */
    private void scheduleDaySummary(ScheduledExecutorService stream, OracleStreamComparator comparing,
                                    Clock streamClock) {
        Instant now = streamClock.instant();
        scheduleDaySummary(stream, comparing, streamClock, now.plus(nextUtcMidnightDelay(now)));
    }

    /**
     * The {@code day} line at {@code target}, a UTC midnight, which then schedules the next one at {@code target} + 1
     * day. The delay is taken from the clock each time, to the nanosecond, never truncated below the target.
     *
     * <p>⛔ <b>One line per midnight.</b> The executor's timer is not the injected clock, so a task may wake before
     * {@code target} by that clock: it then waits again and writes nothing. (FAB-175-2 r1 truncated the delay to
     * milliseconds and re-computed the midnight from the waking instant, so a wake a fraction of a millisecond early
     * fired again at 0 ms — a burst of up to 14 lines per midnight.) A clock that jumps forward over several
     * midnights gets one line for each, at once.
     */
    private void scheduleDaySummary(ScheduledExecutorService stream, OracleStreamComparator comparing,
                                    Clock streamClock, Instant target) {
        try {
            stream.schedule(guarded("cmp-day", () -> {
                Instant now = streamClock.instant();
                if (now.isBefore(target)) {
                    dayEarlyWakes.incrementAndGet();
                    scheduleDaySummary(stream, comparing, streamClock, target);
                    return;
                }
                try {
                    comparing.daySummary(now, false);
                } finally {
                    scheduleDaySummary(stream, comparing, streamClock, target.plus(1, ChronoUnit.DAYS));
                }
            }), delayUntil(target, streamClock.instant()), TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException ignored) {
            // the stream has stopped: its last day line is the comparator's shutdown's
        }
    }

    /** From {@code now} to the next UTC midnight strictly after it: 1 s at 23:59:59Z, 24 h at 00:00:00Z. */
    static Duration nextUtcMidnightDelay(Instant now) {
        return Duration.between(now, now.truncatedTo(ChronoUnit.DAYS).plus(1, ChronoUnit.DAYS));
    }

    /** {@code max(0, target − now)} in nanoseconds: a target a fraction of a millisecond away is not 0. */
    static long delayUntil(Instant target, Instant now) {
        return Math.max(0, Duration.between(now, target).toNanos());
    }

    /** A task body that logs a throw by its simple class name and never lets it escape (it would cancel a tick). */
    private Runnable guarded(String task, Runnable body) {
        return () -> {
            try {
                body.run();
            } catch (Throwable t) {
                events.warn("task-error", "task=" + task + " error=" + OracleStreamLog.simpleName(t));
            }
        };
    }

    /**
     * A ws or wss URL with a host and no query or fragment — the signed query is appended to it — or
     * {@code null}.
     */
    static URI validUri(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        URI uri;
        try {
            uri = new URI(value.trim());
        } catch (Exception e) {
            return null;
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("ws") || scheme.equalsIgnoreCase("wss"))) {
            return null;
        }
        if (uri.getHost() == null || uri.getHost().isEmpty() || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            return null;
        }
        return uri;
    }

    /** Stops the client, then both executors, waiting briefly for their threads. */
    private void shutDown() {
        OracleStreamClient running = client;
        client = null;
        if (running != null) {
            running.stop();
        }
        ScheduledExecutorService stream = scheduler;
        scheduler = null;
        ExecutorService transport = io;
        io = null;
        if (stream != null) {
            stream.shutdownNow();
        }
        if (transport != null) {
            transport.shutdownNow();
        }
        try {
            if (stream != null) {
                stream.awaitTermination(1, TimeUnit.SECONDS);
            }
            if (transport != null) {
                transport.awaitTermination(1, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
