package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.account.Account;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Starts the SHADOW oracle stream (FAB-174) once the node is ready, or says in one line why it does not.
 *
 * <p>⛔ <b>A shadow: nothing reads what it produces.</b> Each push is parsed and logged as one
 * {@code [oracle-stream] push} line; no decision, price, readiness figure or {@link FluidOracleClient} state
 * changes. It is on by default ({@code loans.oracle.stream.enabled}, Giovanni's ruling A2 of 2026-10-29) so
 * every operator's log shows how the stream behaves before anything depends on it.
 *
 * <p>⛔ <b>{@link FluidOracleClient} only through an {@link ObjectProvider}, and only to ask whether it
 * exists.</b> With {@code loans.oracle.enabled=false} that bean is absent, and a hard dependency would fail
 * the boot (the trap {@code StaticReferenceInputs} documents). Without it there is nothing to compare the
 * stream against, so the stream stays off.
 *
 * <p>⛔ <b>Its own threads, never Spring's scheduler.</b> Spring's scheduled-task pool is shared by the
 * transaction processor, liquidation, compound and the oracle refresh, and an operator may size it to 1. The
 * stream runs on one daemon {@code oracle-stream} thread plus a daemon {@code oracle-stream-io-<k>} pool for
 * the transport — the same reasoning as {@code NodeVersionCheck}.
 *
 * <p>⛔ <b>Never throws</b>: not from the constructor (which only stores), not from {@link #start()}, not from
 * {@link #stop()}. A shadow must never be the reason a node fails to start.
 */
@Component
public class OracleStreamLauncher {

    static final String MAINNET_HOST = "25mxd4whnj.execute-api.eu-west-1.amazonaws.com";
    static final String PREVIEW_HOST = "2tgda3mk4l.execute-api.eu-west-1.amazonaws.com";
    static final String THREAD_NAME = "oracle-stream";
    static final String IO_THREAD_PREFIX = "oracle-stream-io-";

    private static final int VALUE_ECHO = 16;

    private final Account account;
    private final AppConfig.Network network;
    private final ObjectProvider<FluidOracleClient> oracleClient;
    private final String enabled;
    private final String url;
    private final OracleStreamLog events = new OracleStreamLog();
    private final AtomicLong pushes = new AtomicLong();

    private OracleStreamClient.Timings timings = OracleStreamClient.Timings.PRODUCTION;
    private Clock clock = Clock.systemUTC();

    private boolean started;
    private ScheduledExecutorService scheduler;
    private ExecutorService io;
    private OracleStreamClient client;

    @Autowired
    public OracleStreamLauncher(Account account, AppConfig.Network network,
                                ObjectProvider<FluidOracleClient> oracleClient,
                                @Value("${loans.oracle.stream.enabled:true}") String enabled,
                                @Value("${loans.oracle.stream.url:}") String url) {
        this.account = account;
        this.network = network;
        this.oracleClient = oracleClient;
        this.enabled = enabled;
        this.url = url;
    }

    /** For tests only: the timings and clock the client is built with. */
    synchronized void useForTest(OracleStreamClient.Timings timings, Clock clock) {
        this.timings = timings;
        this.clock = clock;
    }

    /** Pushes logged so far — for tests, which wait on it. */
    long pushes() {
        return pushes.longValue();
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
            events.warn("off", "reason=internal-error(" + OracleStreamLog.simpleName(t) + ")");
        }
    }

    @PreDestroy
    public synchronized void stop() {
        try {
            shutDown();
        } catch (Throwable t) {
            // ⛔ never throws: this runs on Spring's shutdown path
        }
    }

    /** The first state that matches wins, and says so in exactly one line. */
    private void launch() {
        String flag = enabled == null ? "" : enabled.trim().toLowerCase(Locale.ROOT);
        if (flag.equals("false")) {
            events.info("off", "reason=disabled", "loans.oracle.stream.enabled=false");
            return;
        }
        if (!flag.isEmpty() && !flag.equals("true")) {
            events.warn("off", "reason=unrecognised-enabled value="
                    + OracleStreamLog.token(enabled.trim(), VALUE_ECHO));
            return;
        }
        if (oracleClient.getIfAvailable() == null) {
            events.info("off", "reason=no-api-oracle", "loans.oracle.enabled=false: nothing to compare against");
            return;
        }
        String networkName = network.getNetwork();
        String otherHost;
        if ("mainnet".equals(networkName)) {
            otherHost = PREVIEW_HOST;
        } else if ("preview".equals(networkName)) {
            otherHost = MAINNET_HOST;
        } else {
            events.info("off", "reason=network-unsupported network=" + OracleStreamLog.token(networkName, 32));
            return;
        }
        URI uri = validUri(url);
        if (uri == null) {
            events.warn("off", "reason=invalid-url");
            return;
        }
        String host = uri.getHost();
        if (host.equalsIgnoreCase(otherHost)) {
            events.warn("off", "reason=url-network-mismatch network=" + networkName + " host=" + host);
            return;
        }

        events.info("on", "network=" + networkName + " host=" + host);
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
        client = new OracleStreamClient(uri, new OracleStreamAuth(account), timings, clock, new LoggingListener(),
                events, scheduler, io);
        client.start();
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

    /** The shadow's only output: one line per push. FAB-175-1 demotes it when the comparison line replaces it. */
    private final class LoggingListener implements OracleStreamClient.Listener {

        @Override
        public void onOpen(Instant at) {
        }

        @Override
        public void onClose(Instant at, String reason) {
        }

        @Override
        public void onPush(Instant at, long serverTimestamp, OracleRegistryParser.Parsed parsed, int chars) {
            long seq = pushes.incrementAndGet();
            events.info("push", "push=" + seq + " n=" + parsed.entries().size() + " chars=" + chars
                    + " ts=" + serverTimestamp + " dly_ms=" + (at.toEpochMilli() - serverTimestamp));
        }
    }
}
