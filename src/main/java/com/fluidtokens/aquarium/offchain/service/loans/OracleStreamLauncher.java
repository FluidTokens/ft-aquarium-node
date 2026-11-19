package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.account.Account;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.config.BuildInfo;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.service.StakerService;
import io.micrometer.core.instrument.Counter;
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
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/**
 * Starts the oracle stream (FAB-174) once the node is ready, or says in one line why it does not.
 *
 * <p>Each push is parsed and judged by an {@link OracleStreamComparator} against what the production poller loads,
 * and said as one {@code [oracle-stream] cmp} line (FAB-175; the push receipt itself is DEBUG). It is on by default
 * ({@code loans.oracle.stream.enabled}, Giovanni's ruling A2 of 2026-10-29).
 *
 * <p>⛔ <b>What it drives depends on {@code loans.oracle.source}</b> (FAB-183). {@code stream}, the default: after the
 * comparator has seen each callback, every parsed push is merged into {@link FluidOracleClient}'s prices and every
 * open and close sets its connection state — newest {@code validFrom} per oracle NFT wins (see
 * {@link FluidOracleClient}); and an API load that changes prices while the stream is connected counts and says
 * the pushes the stream missed ({@code [oracle-stream] missed}). {@code api}, the emergency lever: nothing is
 * merged, and the stream is exactly the shadow it was — no decision, price, readiness figure or
 * {@link FluidOracleClient} state changes because of it. Either way the comparator hears the API's own loads, never
 * the merged prices.
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
    /** NFT units echoed in a {@code missed} line. */
    static final int MISSED_UNITS = 5;
    /** The longest unit echoed: a 56-hex policy id plus an asset name of at most 64 hex. */
    private static final int UNIT_LIMIT = 120;
    static final String METER_MISSED = "aquarium.oracle.stream.missed";
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
    private final String source;
    private final BuildInfo buildInfo;
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
    /** The oracle client the stream merges into, while {@code source=stream} runs; null otherwise. */
    private FluidOracleClient merging;
    /** Why the stream is off while no client runs; what {@link #status()} reports then. */
    private volatile String offDetail = "not started";
    private volatile Instant offSince;

    /**
     * @param buildInfo the build's short commit and dirty flag — by default the banner's
     *                  {@code aquarium.build.commitShort} and {@code aquarium.build.dirty} — sent as the handshake's
     *                  {@code X-FT-Node-Version} (FAB-179), {@code -dirty} appended iff dirty is {@code true};
     *                  sanitised by {@link #nodeVersion} where it is handed to the client, so a replacement bean
     *                  cannot bypass it
     */
    @Autowired
    public OracleStreamLauncher(Account account, AppConfig.Network network,
                                ObjectProvider<FluidOracleClient> oracleClient,
                                StakerService stakerService,
                                MeterRegistry meterRegistry,
                                @Value("${loans.oracle.stream.enabled:true}") String enabled,
                                @Value("${loans.oracle.stream.url:}") String url,
                                @Value("${loans.oracle.source:stream}") String source,
                                BuildInfo buildInfo) {
        this.account = account;
        this.network = network;
        this.oracleClient = oracleClient;
        this.stakerService = stakerService;
        this.meterRegistry = meterRegistry;
        this.enabled = enabled;
        this.url = url;
        this.source = source;
        this.buildInfo = Objects.requireNonNull(buildInfo, "buildInfo");
    }

    /** With the build's provenance given as its two raw values. */
    public OracleStreamLauncher(Account account, AppConfig.Network network,
                                ObjectProvider<FluidOracleClient> oracleClient,
                                StakerService stakerService,
                                MeterRegistry meterRegistry,
                                String enabled,
                                String url,
                                String source,
                                String buildCommitShort,
                                String buildDirty) {
        this(account, network, oracleClient, stakerService, meterRegistry, enabled, url, source,
                new BuildInfo(buildCommitShort, buildDirty));
    }

    /** Without the build's provenance (no {@code X-FT-Node-Version} header), with {@code loans.oracle.source=stream}. */
    public OracleStreamLauncher(Account account, AppConfig.Network network,
                                ObjectProvider<FluidOracleClient> oracleClient,
                                StakerService stakerService,
                                MeterRegistry meterRegistry,
                                String enabled,
                                String url) {
        this(account, network, oracleClient, stakerService, meterRegistry, enabled, url, "stream", "unknown", "unknown");
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
            // The client says no onClose for its own shutdown: the stream's NFTs stop holding entries here.
            FluidOracleClient merged = merging;
            merging = null;
            if (merged != null) {
                merged.streamConnected(false);
            }
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

    /**
     * {@code loans.oracle.source}, trimmed and case-insensitive: blank or {@code stream} is
     * {@link FluidOracleClient.Source#STREAM}, {@code api} is {@link FluidOracleClient.Source#API}, and anything else
     * is API — the stream then drives nothing — with one WARN.
     */
    private FluidOracleClient.Source source() {
        String value = source == null ? "" : source.trim();
        if (value.isEmpty() || value.equalsIgnoreCase("stream")) {
            return FluidOracleClient.Source.STREAM;
        }
        if (!value.equalsIgnoreCase("api")) {
            events.warn("source", "reason=unrecognised value=" + OracleStreamLog.token(value, VALUE_ECHO));
        }
        return FluidOracleClient.Source.API;
    }

    /** One line either way; ON builds the threads and the client. */
    private void launch() {
        FluidOracleClient.Source priceSource = source();
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
        String nodeVersion = nodeVersion(buildInfo.commitShort(), buildInfo.dirty());

        events.info("on", decision.fields() + " node_ver=" + (nodeVersion == null ? "-" : nodeVersion)
                + " source=" + (priceSource == FluidOracleClient.Source.STREAM ? "stream" : "api"));
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
                    comparing.onApiLoad(fc.lastRefresh(), fc.lastApiEntries());
                }
            }));
        }
        OracleStreamClient.Listener listener = comparing;
        if (fc != null && priceSource == FluidOracleClient.Source.STREAM) {
            listener = mergingInto(fc, comparing);
            countMissedPushes(fc);
            merging = fc;
        }
        stream.scheduleWithFixedDelay(guarded("cmp-tick", () -> comparing.tick(streamClock.instant())),
                COMPARE_TICK_SECONDS, COMPARE_TICK_SECONDS, TimeUnit.SECONDS);
        scheduleDaySummary(stream, comparing, streamClock);

        OracleStreamClient created = new OracleStreamClient(uri, new OracleStreamAuth(account), timings, clock,
                listener, events, scheduler, io, () -> !staker.findStakerRefInput().isEmpty(),
                meterRegistry, nodeVersion);
        client = created;
        created.start();
    }

    /**
     * {@code source=stream}: every callback goes to the comparator FIRST and unchanged, then the push or the
     * connection state is written into {@code fc}. Each side is guarded on its own, and a throw is said as the
     * client would say it ({@code listener-error callback=… error=<class>}), so neither side can break the other.
     */
    private OracleStreamClient.Listener mergingInto(FluidOracleClient fc, OracleStreamComparator comparing) {
        return new OracleStreamClient.Listener() {
            @Override
            public void onOpen(Instant at) {
                forward("onOpen", () -> comparing.onOpen(at));
                forward("merge-open", () -> fc.streamConnected(true));
            }

            @Override
            public void onClose(Instant at, String reason) {
                forward("onClose", () -> comparing.onClose(at, reason));
                forward("merge-close", () -> fc.streamConnected(false));
            }

            @Override
            public void onPush(Instant at, long serverTimestamp, OracleRegistryParser.Parsed parsed, int chars) {
                forward("onPush", () -> comparing.onPush(at, serverTimestamp, parsed, chars));
                forward("merge-push", () -> fc.mergeStreamPush(parsed.entries(), at));
            }

            @Override
            public void onPush(Instant at, long serverTimestamp, long xferMs, OracleRegistryParser.Parsed parsed,
                               int chars) {
                forward("onPush", () -> comparing.onPush(at, serverTimestamp, xferMs, parsed, chars));
                forward("merge-push", () -> fc.mergeStreamPush(parsed.entries(), at));
            }
        };
    }

    private void forward(String callback, Runnable call) {
        try {
            call.run();
        } catch (Throwable t) {
            events.warn("listener-error", "callback=" + callback + " error=" + OracleStreamLog.simpleName(t));
        }
    }

    /**
     * Missed-push accounting, {@code source=stream} only. An API load that changes prices while the stream is
     * connected means the stream missed those pushes: counted ({@code stream=connected}) and said as one
     * {@code missed} INFO line. While it is not connected the change is counted ({@code stream=disconnected}) and
     * not said. Changes the stream made itself are not counted.
     */
    private void countMissedPushes(FluidOracleClient fc) {
        MeterRegistry registry = meterRegistry;
        Counter connected = registry == null ? null : Counter.builder(METER_MISSED).tag("stream", "connected")
                .description("Oracle NFTs an API load changed that the stream had not pushed, by connection state")
                .register(registry);
        Counter disconnected = registry == null ? null : Counter.builder(METER_MISSED).tag("stream", "disconnected")
                .description("Oracle NFTs an API load changed that the stream had not pushed, by connection state")
                .register(registry);
        fc.addChangeListener((changedBy, nfts, streamConnected) -> {
            if (changedBy != FluidOracleClient.Source.API) {
                return;
            }
            if (streamConnected) {
                if (connected != null) {
                    connected.increment(nfts.size());
                }
                events.info("missed", "n=" + nfts.size() + " units=" + units(nfts));
            } else if (disconnected != null) {
                disconnected.increment(nfts.size());
            }
        });
    }

    /** At most {@value #MISSED_UNITS} NFT units, sorted, comma-joined, each one a sanitised token. */
    static String units(Set<AssetType> nfts) {
        return nfts.stream()
                .map(nft -> OracleStreamLog.token(nft.toUnit(), UNIT_LIMIT))
                .sorted()
                .limit(MISSED_UNITS)
                .collect(Collectors.joining(","));
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
