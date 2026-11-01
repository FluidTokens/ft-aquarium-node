package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.common.model.Networks;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * FAB-174-4: the production {@link OracleStreamClient} against FluidTokens' REAL preview oracle stream, signed in
 * with the staked preview wallet — the one thing the in-process server cannot stand in for: that the authorizer
 * accepts our signature and bond, that a real push arrives promptly with production pings, and that it says what
 * the HTTP registry says shortly after.
 *
 * <p>⛔ <b>Preview only, and never by accident.</b> The URL is a preview literal, never read from the environment,
 * and the account is derived on {@link Networks#preview()}. Two gates, both required: {@code WALLET_MNEMONIC}
 * (from {@code .env.preview-staked}) and {@code AQUARIUM_ORACLE_STREAM_RIG=preview}, an opt-in that no
 * {@code .env.*} file may define. {@code EnvGatedRigReachabilityTest} pins both, and the host.
 *
 * <p>What it measures (probe log items 9–11, 21, 27, 31–36):
 * <ul>
 *   <li>a refusal fails at once, by status: 429 is a slot held by another connection on this bond (a running
 *       preview node, most likely), 403 is no bond for the stake key, 401 is a signature or clock problem;</li>
 *   <li>otherwise one parsed push within 7 minutes — pushes come only on change, about every 5 minutes on
 *       preview, on one global schedule;</li>
 *   <li>the push arrived within 5 s of its server {@code timestamp}: with production pings the JDK client's
 *       idle-delivery stall (items 22–23) must not show;</li>
 *   <li>{@code testapi}'s {@code /get-oracle-tokens}, polled every 10 s for up to 120 s, reaches the same parsed
 *       entries — it lags the push by 6–30 s (item 34) — and the lag is printed.</li>
 * </ul>
 *
 * <p>⛔ <b>Nothing secret is printed</b>: not the mnemonic, the stake key, the signature or the signed query. The
 * client's own log lines carry none of them by construction ({@link OracleStreamLog}).
 */
@EnabledIfEnvironmentVariable(named = "WALLET_MNEMONIC", matches = ".+")
@EnabledIfEnvironmentVariable(named = "AQUARIUM_ORACLE_STREAM_RIG", matches = "preview")
class OracleStreamPreviewLiveTest {

    private static final URI PREVIEW_STREAM = URI.create("wss://2tgda3mk4l.execute-api.eu-west-1.amazonaws.com/dev");
    private static final URI PREVIEW_API = URI.create("https://testapi.fluidtokens.com/get-oracle-tokens");

    private static final Duration PUSH_WAIT = Duration.ofMinutes(7);
    private static final Duration MAX_DELIVERY_DELAY = Duration.ofSeconds(5);
    private static final Duration API_POLL = Duration.ofSeconds(10);
    private static final Duration API_WAIT = Duration.ofSeconds(120);

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void aRealPreviewPushArrivesPromptlyAndTheApiServesTheSameEntriesShortlyAfter() throws Exception {
        String indexValue = System.getenv("WALLET_ACCOUNT_INDEX");
        int index = indexValue == null || indexValue.isBlank() ? 0 : Integer.parseInt(indexValue.trim());
        Account account = Account.createFromMnemonic(Networks.preview(), System.getenv("WALLET_MNEMONIC"), index, 0);

        Recorder recorder = new Recorder();
        AtomicInteger ioThreads = new AtomicInteger();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "oracle-stream"));
        ExecutorService io = Executors.newCachedThreadPool(r -> daemon(r, "oracle-stream-io-" + ioThreads.incrementAndGet()));
        // No stake supplier: a 403 fails this rig at once, so the hold it would serve never begins.
        OracleStreamClient client = new OracleStreamClient(PREVIEW_STREAM, new OracleStreamAuth(account),
                OracleStreamClient.Timings.PRODUCTION, Clock.systemUTC(), recorder, new OracleStreamLog(), scheduler,
                io, null, new SimpleMeterRegistry());
        Push push;
        try {
            client.start();
            push = awaitFirstPush(client, recorder);
        } finally {
            client.stop();
            scheduler.shutdownNow();
            io.shutdownNow();
        }

        assertFalse(push.entries().isEmpty(), "the push parsed to no entries");
        long delayMs = push.at().toEpochMilli() - push.serverTimestamp();
        System.out.println("[oracle-stream-rig] push: " + push.entries().size() + " entries, arrival - timestamp = "
                + delayMs + " ms");
        assertTrue(Math.abs(delayMs) < MAX_DELIVERY_DELAY.toMillis(),
                "the push arrived " + delayMs + " ms after its server timestamp: delivery stalled (the JDK client's "
                        + "idle stall, items 22-23) or the clocks disagree");

        // Read-only: GET the public registry, never anything else.
        Set<OracleEntry> api = Set.of();
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        long deadline = System.nanoTime() + API_WAIT.toNanos();
        while (true) {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(PREVIEW_API).GET()
                    .timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                api = new HashSet<>(OracleRegistryParser.parse(JSON.readTree(response.body())).byOracleToken().values());
                if (api.equals(push.entries())) {
                    double lagSeconds = Duration.between(push.at(), Instant.now()).toMillis() / 1000.0;
                    System.out.println("[oracle-stream-rig] the API served the push's entries " + lagSeconds
                            + " s after the push arrived");
                    return;
                }
            }
            if (System.nanoTime() + API_POLL.toNanos() > deadline) {
                break;
            }
            Thread.sleep(API_POLL.toMillis());
        }
        assertEquals(push.entries(), api,
                "testapi did not serve the push's entries within " + API_WAIT.toSeconds() + " s of its arrival");
    }

    /** Waits for one parsed push; a refusal fails at once, by status. */
    private static Push awaitFirstPush(OracleStreamClient client, Recorder recorder) throws InterruptedException {
        long deadline = System.nanoTime() + PUSH_WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (!recorder.pushes.isEmpty()) {
                return recorder.pushes.getFirst();
            }
            OracleStreamClient.Status status = client.status();
            if (status.state() == OracleStreamClient.Status.State.REFUSED) {
                Integer code = status.refusalStatus();
                String message = status.refusalMessage();
                switch (code == null ? 0 : code) {
                    case 429 -> fail("429 slot held elsewhere: another connection on this bond is open (a running "
                            + "preview node?) — " + message);
                    case 403 -> fail("403 no bond for this stake key — source .env.preview-staked");
                    case 401 -> fail("401 — check the system clock");
                    default -> fail("refused " + code + " — " + message);
                }
            }
            Thread.sleep(200);
        }
        return fail("no push within " + PUSH_WAIT.toMinutes() + " min; the client's status: " + client.status().state());
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    /** One parsed push: when it arrived, its server {@code timestamp}, and its entries as a set. */
    private record Push(Instant at, long serverTimestamp, Set<OracleEntry> entries) {
    }

    private static final class Recorder implements OracleStreamClient.Listener {

        final List<Push> pushes = new CopyOnWriteArrayList<>();

        @Override
        public void onOpen(Instant at) {
        }

        @Override
        public void onClose(Instant at, String reason) {
        }

        @Override
        public void onPush(Instant at, long serverTimestamp, OracleRegistryParser.Parsed parsed, int chars) {
            pushes.add(new Push(at, serverTimestamp, new HashSet<>(parsed.byOracleToken().values())));
        }
    }
}
