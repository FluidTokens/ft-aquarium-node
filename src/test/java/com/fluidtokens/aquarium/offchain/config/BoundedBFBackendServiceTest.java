package com.fluidtokens.aquarium.offchain.config;

import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import okhttp3.Call;
import okhttp3.EventListener;
import okhttp3.Request;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedBFBackendServiceTest {

    private static final Duration CONTROL_BOUND = Duration.ofMillis(250);
    private static final Duration FIXTURE_BOUND = Duration.ofSeconds(3);

    @Test
    void springPublishesOneBoundedBackendService() {
        new ApplicationContextRunner()
                .withPropertyValues(
                        "blockfrost.url=http://127.0.0.1:9/api/v0/",
                        "blockfrost.key=test-key")
                .withUserConfiguration(BlockfrostConfig.class)
                .run(context -> {
                    assertEquals(1, context.getBeansOfType(BFBackendService.class).size());
                    BFBackendService backend = context.getBean(BFBackendService.class);
                    assertEquals(
                            "com.fluidtokens.aquarium.offchain.config.BoundedBFBackendService",
                            backend.getClass().getName(),
                            "the production Blockfrost bean must own the bounded transport");
                });
    }

    @Test
    void shippedDefaultsBindExactlyAndInvalidPropertyFailsSpringStartup() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.config.location=classpath:application.yaml")
                .withUserConfiguration(BlockfrostConfig.class)
                .run(context -> {
                    assertEquals("5", context.getEnvironment().getProperty("blockfrost.timeout.connect-seconds"));
                    assertEquals("30", context.getEnvironment().getProperty("blockfrost.timeout.read-seconds"));
                    assertEquals("30", context.getEnvironment().getProperty("blockfrost.timeout.write-seconds"));
                    assertEquals("12", context.getEnvironment().getProperty("blockfrost.timeout.discovery-call-seconds"));
                    assertEquals("30", context.getEnvironment().getProperty("blockfrost.timeout.evaluation-call-seconds"));
                    assertEquals("30", context.getEnvironment().getProperty("blockfrost.timeout.submission-call-seconds"));
                    assertEquals("120", context.getEnvironment().getProperty("wallet-coordinator.pass-timeout-seconds"));
                    assertEquals(1, context.getBeansOfType(BFBackendService.class).size());
                });

        new ApplicationContextRunner()
                .withPropertyValues(
                        "blockfrost.url=http://127.0.0.1:9/api/v0/",
                        "blockfrost.key=test-key",
                        "blockfrost.timeout.submission-call-seconds=0")
                .withUserConfiguration(BlockfrostConfig.class)
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertTrue(failure != null);
                    assertTrue(allMessages(failure).contains("blockfrost.timeout.submission-call-seconds"));
                });
    }

    private static String allMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            messages.append(current.getMessage()).append('\n');
        }
        return messages.toString();
    }

    @Test
    void plainCclUsesAnUnboundedWholeCallTimeout() throws Exception {
        BFBackendService backend = new BFBackendService("http://127.0.0.1:9/api/v0/", "test-key");
        Method getRetrofit = findDeclaredMethod(BFBackendService.class, "getRetrofit");
        getRetrofit.setAccessible(true);
        Object retrofit = getRetrofit.invoke(backend);
        Object client = retrofit.getClass().getMethod("callFactory").invoke(retrofit);
        int callTimeoutMillis = (int) client.getClass().getMethod("callTimeoutMillis").invoke(client);

        assertEquals(0, callTimeoutMillis, "plain CCL must remain the fail-first control");
    }

    private static Method findDeclaredMethod(Class<?> type, String name) throws NoSuchMethodException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredMethod(name);
            } catch (NoSuchMethodException ignored) {
                // Continue through the CCL service hierarchy.
            }
        }
        throw new NoSuchMethodException(name);
    }

    @Test
    void plainCclDripResponseOutlivesTheTargetWholeCallBound() throws Exception {
        try (DripServer server = new DripServer()) {
            BFBackendService backend = new BFBackendService(server.baseUrl(), "test-key");
            ExecutorService caller = Executors.newSingleThreadExecutor();
            try {
                Future<?> call = caller.submit(() -> backend.getBlockService().getLatestBlock());
                assertTrue(server.requestRead.await(FIXTURE_BOUND.toMillis(), TimeUnit.MILLISECONDS),
                        "plain CCL never reached the loopback fixture");
                assertThrows(
                        java.util.concurrent.TimeoutException.class,
                        () -> call.get(CONTROL_BOUND.toMillis(), TimeUnit.MILLISECONDS),
                        "bytes arrive inside the read-idle timeout, so plain CCL should remain "
                                + "blocked beyond the target whole-call bound");
            } finally {
                server.closeConnection();
                caller.shutdown();
                assertTrue(caller.awaitTermination(FIXTURE_BOUND.toMillis(), TimeUnit.MILLISECONDS),
                        "the plain CCL control caller did not unwind after fixture teardown");
            }
            assertTrue(server.handlerExited.await(FIXTURE_BOUND.toMillis(), TimeUnit.MILLISECONDS),
                    "the loopback drip handler did not exit");
        }
    }

    @Test
    void everyGetterIsCachedAndUsesTheBoundedTransportIncludingHiddenTxOutput() throws Exception {
        try (FixedResponseServer server = new FixedResponseServer(12,
                "HTTP/1.1 404 Not Found\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}")) {
            TransportEvents events = new TransportEvents();
            BoundedBFBackendService backend = bounded(server.baseUrl(), 2, 2, 2, 2,
                    System::nanoTime, events);

            assertSame(backend.getAssetService(), backend.getAssetService());
            assertSame(backend.getBlockService(), backend.getBlockService());
            assertSame(backend.getNetworkInfoService(), backend.getNetworkInfoService());
            assertSame(backend.getPoolService(), backend.getPoolService());
            assertSame(backend.getTransactionService(), backend.getTransactionService());
            assertSame(backend.getUtxoService(), backend.getUtxoService());
            assertSame(backend.getAddressService(), backend.getAddressService());
            assertSame(backend.getAccountService(), backend.getAccountService());
            assertSame(backend.getEpochService(), backend.getEpochService());
            assertSame(backend.getMetadataService(), backend.getMetadataService());
            assertSame(backend.getScriptService(), backend.getScriptService());

            assertBoundedExchange(events, "/api/v0/assets/asset-probe",
                    () -> backend.getAssetService().getAsset("asset-probe"));
            assertBoundedExchange(events, "/api/v0/blocks/latest",
                    () -> backend.getBlockService().getLatestBlock());
            assertBoundedExchange(events, "/api/v0/genesis",
                    () -> backend.getNetworkInfoService().getNetworkInfo());
            assertBoundedExchange(events, "/api/v0/pools/pool-probe",
                    () -> backend.getPoolService().getPoolInfo("pool-probe"));
            assertBoundedExchange(events, "/api/v0/txs/transaction-probe",
                    () -> backend.getTransactionService().getTransaction("transaction-probe"));
            assertBoundedExchange(events, "/api/v0/addresses/utxo-address-probe/utxos",
                    () -> backend.getUtxoService().getUtxos("utxo-address-probe", 1, 1));
            assertBoundedExchange(events, "/api/v0/addresses/address-probe",
                    () -> backend.getAddressService().getAddressInfo("address-probe"));
            assertBoundedExchange(events, "/api/v0/accounts/account-probe",
                    () -> backend.getAccountService().getAccountInformation("account-probe"));
            assertBoundedExchange(events, "/api/v0/epochs/latest",
                    () -> backend.getEpochService().getLatestEpoch());
            assertBoundedExchange(events, "/api/v0/txs/metadata-probe/metadata",
                    () -> backend.getMetadataService().getJSONMetadataByTxnHash("metadata-probe"));
            assertBoundedExchange(events, "/api/v0/scripts/datum/datum-probe",
                    () -> backend.getScriptService().getScriptDatum("datum-probe"));
            assertBoundedExchange(events, "/api/v0/txs/hidden-probe/utxos",
                    () -> backend.getUtxoService().getTxOutput("hidden-probe", 0));

            assertTrue(server.completed.await(FIXTURE_BOUND.toMillis(), TimeUnit.MILLISECONDS));
            assertEquals(12, server.requests.get());
            assertEquals(12, events.totalStarted());
            assertEquals(12, events.totalTerminated());
        }
    }

    private static void assertBoundedExchange(TransportEvents events, String path, CheckedCall operation)
            throws Exception {
        int startsBefore = events.started(path);
        int terminalsBefore = events.terminated(path);
        operation.run();
        assertEquals(startsBefore + 1, events.started(path),
                "the real CCL request bypassed the application-owned bounded call factory: " + path);
        assertEquals(terminalsBefore + 1, events.terminated(path),
                "the application-owned OkHttp call did not reach a terminal event: " + path);
    }

    @Test
    void discoveryEvaluationAndSubmissionNoResponseCallsHaveWholeCallBounds() throws Exception {
        assertCompletesExceptionallyWithinBound(backend -> backend.getBlockService().getLatestBlock());
        assertCompletesExceptionallyWithinBound(backend -> backend.getTransactionService().evaluateTx(new byte[]{1}));
        assertCompletesExceptionallyWithinBound(backend -> backend.getTransactionService().submitTransaction(new byte[]{1}));
        assertCompletesExceptionallyWithinBound(backend -> backend.getUtxoService().getTxOutput("aa", 0));
    }

    @Test
    void timeoutTerminalEventOccursBeforeTheSynchronousCclInvocationUnwinds() throws Exception {
        try (NoResponseServer server = new NoResponseServer()) {
            AtomicReference<Boolean> terminalSeen = new AtomicReference<>(false);
            BoundedBFBackendService backend = new BoundedBFBackendService(
                    server.baseUrl(), BlockfrostKey.of("test-key"), 1, 5, 5, 1, 1, 1, 2,
                    System::nanoTime,
                    call -> new EventListener() {
                        @Override public void callFailed(Call call, IOException ioe) {
                            terminalSeen.set(true);
                        }
                    });
            ExecutorService caller = Executors.newSingleThreadExecutor();
            try {
                Future<Boolean> call = caller.submit(() -> {
                    try {
                        backend.getBlockService().getLatestBlock();
                        return false;
                    } catch (Exception expected) {
                        return terminalSeen.get();
                    }
                });
                assertTrue(server.requestRead.await(FIXTURE_BOUND.toMillis(), TimeUnit.MILLISECONDS));
                assertTrue(call.get(2_500, TimeUnit.MILLISECONDS),
                        "OkHttp's terminal callFailed event must precede CCL's synchronous throw");
            } finally {
                server.closeConnection();
                caller.shutdown();
                assertTrue(caller.awaitTermination(FIXTURE_BOUND.toMillis(), TimeUnit.MILLISECONDS));
            }
        }
    }

    @Test
    void dripBodyIsBoundedAcrossTheWholeDecodedCall() throws Exception {
        try (DripServer server = new DripServer()) {
            BoundedBFBackendService backend = bounded(server.baseUrl(), 1, 1, 1, 2);
            ExecutorService caller = Executors.newSingleThreadExecutor();
            try {
                Future<?> call = caller.submit(() -> backend.getBlockService().getLatestBlock());
                assertTrue(server.requestRead.await(FIXTURE_BOUND.toMillis(), TimeUnit.MILLISECONDS));
                assertThrows(ExecutionException.class,
                        () -> call.get(2_500, TimeUnit.MILLISECONDS),
                        "the whole-call timeout must include response-body decode while bytes keep arriving");
            } finally {
                server.closeConnection();
                caller.shutdown();
                assertTrue(caller.awaitTermination(FIXTURE_BOUND.toMillis(), TimeUnit.MILLISECONDS));
            }
        }
    }

    @Test
    void submissionUsesDerivedNoReplayNoRedirectClientSharingTheBaseTransport() {
        BoundedBFBackendService backend = bounded("http://127.0.0.1:9/api/v0/", 1, 1, 1, 2);

        assertNotSame(backend.baseClient(), backend.submissionClient());
        assertSame(backend.baseClient().dispatcher(), backend.submissionClient().dispatcher());
        assertSame(backend.baseClient().connectionPool(), backend.submissionClient().connectionPool());
        assertTrue(backend.baseClient().retryOnConnectionFailure(), "discovery/evaluation retain pinned defaults");
        assertTrue(backend.baseClient().followRedirects(), "discovery/evaluation retain pinned defaults");
        assertTrue(backend.baseClient().followSslRedirects(), "discovery/evaluation retain pinned defaults");
        assertTrue(!backend.submissionClient().retryOnConnectionFailure());
        assertTrue(!backend.submissionClient().followRedirects());
        assertTrue(!backend.submissionClient().followSslRedirects());
    }

    @Test
    void submitRedirectIsNotFollowedAndThePostIsNotReplayed() throws Exception {
        try (FixedResponseServer server = new FixedResponseServer(1,
                "HTTP/1.1 307 Temporary Redirect\r\nLocation: /api/v0/redirected\r\n"
                        + "Content-Length: 2\r\nConnection: close\r\n\r\n{}")) {
            bounded(server.baseUrl(), 2, 2, 2, 2).getTransactionService().submitTransaction(new byte[]{1, 2, 3});
            assertTrue(server.completed.await(FIXTURE_BOUND.toMillis(), TimeUnit.MILLISECONDS));
            Thread.sleep(200);
            assertEquals(1, server.requests.get());
            assertEquals("/api/v0/tx/submit", server.paths.getFirst());
            assertEquals(3, server.bodyBytes.get());
        }
    }

    @Test
    void submitResetAfterTheServerReadsTheWholeBodyIsExactlyOnePostAndAmbiguous() throws Exception {
        try (ResetAfterBodyServer server = new ResetAfterBodyServer()) {
            BoundedBFBackendService backend = bounded(server.baseUrl(), 2, 2, 2, 2);
            assertThrows(com.bloxbean.cardano.client.api.exception.ApiException.class,
                    () -> backend.getTransactionService().submitTransaction(new byte[]{1, 2, 3, 4}));
            assertTrue(server.firstRequest.await(FIXTURE_BOUND.toMillis(), TimeUnit.MILLISECONDS));
            assertEquals(1, server.posts.get(), "a fully-read submission with no response is ambiguous, never replayed");
            assertEquals(4, server.bodyBytes.get());
        }
    }

    @Test
    void encodedPathSuffixClassificationIgnoresBasePrefixAndQuery() throws Exception {
        BoundedBFBackendService backend = bounded("http://127.0.0.1:9/prefix/api/v0/", 2, 3, 4, 5);
        Field retrofitField = BoundedBFBackendService.class.getDeclaredField("retrofit");
        retrofitField.setAccessible(true);
        Object retrofit = retrofitField.get(backend);
        Call.Factory factory = (Call.Factory) retrofit.getClass().getMethod("callFactory").invoke(retrofit);

        Call evaluation = factory.newCall(new Request.Builder()
                .url("http://127.0.0.1:9/any/prefix/utils/txs/evaluate?kind=submit").build());
        Call submission = factory.newCall(new Request.Builder()
                .url("http://127.0.0.1:9/any/prefix/tx/submit?kind=evaluate").build());
        Call discovery = factory.newCall(new Request.Builder()
                .url("http://127.0.0.1:9/tx/submit/not-the-suffix?path=/tx/submit").build());
        try {
            assertEquals(TimeUnit.SECONDS.toNanos(3), evaluation.timeout().timeoutNanos());
            assertEquals(TimeUnit.SECONDS.toNanos(4), submission.timeout().timeoutNanos());
            assertEquals(TimeUnit.SECONDS.toNanos(2), discovery.timeout().timeoutNanos());
        } finally {
            evaluation.cancel();
            submission.cancel();
            discovery.cancel();
        }
    }

    @Test
    void passDeadlineUsesRemainingBudgetRejectsNestingAndAlwaysClears() {
        AtomicLong now = new AtomicLong(10);
        BoundedBFBackendService backend = bounded("http://127.0.0.1:9/api/v0/", 2, 2, 2, 1, now::get);

        backend.runWithinPassDeadline(deadline -> {
            assertEquals(1_000_000_000L, deadline.remainingNanos());
            now.addAndGet(400_000_000L);
            assertEquals(600_000_000L, deadline.remainingNanos());
            assertThrows(IllegalStateException.class, () -> backend.runWithinPassDeadline(ignored -> {}));
            now.addAndGet(600_000_000L);
            assertTrue(deadline.isExpired());
            assertEquals(0, deadline.remainingNanos());
            IllegalStateException expired = assertThrows(IllegalStateException.class,
                    () -> backend.getBlockService().getLatestBlock());
            assertTrue(expired.getMessage().contains("expired"));
        });

        now.set(10);
        assertThrows(IllegalArgumentException.class,
                () -> backend.runWithinPassDeadline(ignored -> { throw new IllegalArgumentException("body"); }));
        backend.runWithinPassDeadline(deadline -> assertTrue(!deadline.isExpired()));
    }

    @Test
    void passRemainderCapsTheRealCallAndExpiredScopeOpensNoConnection() throws Exception {
        AtomicLong now = new AtomicLong(100);
        try (NoResponseServer server = new NoResponseServer()) {
            BoundedBFBackendService backend = bounded(server.baseUrl(), 2, 2, 2, 1, now::get);
            long started = System.nanoTime();
            backend.runWithinPassDeadline(deadline -> {
                now.addAndGet(500_000_000L);
                assertThrows(com.bloxbean.cardano.client.api.exception.ApiException.class,
                        () -> backend.getBlockService().getLatestBlock());
            });
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(elapsedMillis < 1_500, "remaining half-second pass budget was not applied: " + elapsedMillis);
            assertTrue(server.requestRead.await(FIXTURE_BOUND.toMillis(), TimeUnit.MILLISECONDS));
        }

        now.set(100);
        try (NoResponseServer server = new NoResponseServer()) {
            BoundedBFBackendService backend = bounded(server.baseUrl(), 2, 2, 2, 1, now::get);
            backend.runWithinPassDeadline(deadline -> {
                now.addAndGet(1_000_000_000L);
                assertThrows(IllegalStateException.class, () -> backend.getBlockService().getLatestBlock());
            });
            assertTrue(!server.requestRead.await(150, TimeUnit.MILLISECONDS),
                    "an expired scope must refuse before a request or connection is created");
        }
    }

    @Test
    void absoluteMonotonicDeadlineRemainsCorrectAcrossLongOverflow() {
        AtomicLong now = new AtomicLong(Long.MAX_VALUE - 100);
        BoundedBFBackendService backend = bounded("http://127.0.0.1:9/api/v0/", 2, 2, 2, 1, now::get);
        backend.runWithinPassDeadline(deadline -> {
            assertEquals(1_000_000_000L, deadline.remainingNanos());
            now.addAndGet(250_000_000L);
            assertEquals(750_000_000L, deadline.remainingNanos());
        });
    }

    @Test
    void passScopeIsConfinedToTheCallingThread() throws Exception {
        AtomicLong now = new AtomicLong(1);
        BoundedBFBackendService backend = bounded("http://127.0.0.1:9/api/v0/", 2, 2, 2, 1, now::get);
        Call.Factory factory = callFactory(backend);
        Request request = new Request.Builder().url("http://127.0.0.1:9/api/v0/blocks/latest").build();
        ExecutorService helper = Executors.newSingleThreadExecutor();
        try {
            backend.runWithinPassDeadline(deadline -> {
                now.addAndGet(500_000_000L);
                Call scoped = factory.newCall(request);
                try {
                    assertEquals(500_000_000L, scoped.timeout().timeoutNanos());
                    Future<Long> unscoped = helper.submit(() -> {
                        Call call = factory.newCall(request);
                        try { return call.timeout().timeoutNanos(); } finally { call.cancel(); }
                    });
                    assertEquals(TimeUnit.SECONDS.toNanos(2), unscoped.get());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    scoped.cancel();
                }
            });
        } finally {
            helper.shutdownNow();
            assertTrue(helper.awaitTermination(FIXTURE_BOUND.toMillis(), TimeUnit.MILLISECONDS));
        }
    }

    private static Call.Factory callFactory(BoundedBFBackendService backend) throws Exception {
        Field retrofitField = BoundedBFBackendService.class.getDeclaredField("retrofit");
        retrofitField.setAccessible(true);
        Object retrofit = retrofitField.get(backend);
        return (Call.Factory) retrofit.getClass().getMethod("callFactory").invoke(retrofit);
    }

    @Test
    void durationValidationNamesTheInvalidPropertyAndRejectsEveryBoundary() {
        for (int position = 0; position < 7; position++) {
            long[] values = {1, 1, 1, 1, 1, 1, 1};
            values[position] = 0;
            IllegalArgumentException zero = assertThrows(IllegalArgumentException.class, () -> construct(values));
            assertTrue(zero.getMessage().contains(propertyAt(position)));
            values[position] = -1;
            IllegalArgumentException negative = assertThrows(IllegalArgumentException.class, () -> construct(values));
            assertTrue(negative.getMessage().contains(propertyAt(position)));
            values[position] = Integer.MAX_VALUE / 1_000L + 1;
            IllegalArgumentException millis = assertThrows(IllegalArgumentException.class, () -> construct(values));
            assertTrue(millis.getMessage().contains(propertyAt(position)));
            values[position] = Long.MAX_VALUE;
            IllegalArgumentException overflow = assertThrows(IllegalArgumentException.class, () -> construct(values));
            assertTrue(overflow.getMessage().contains(propertyAt(position)));
        }
        construct(new long[]{5, 30, 30, 12, 30, 30, 120});
    }

    private static String propertyAt(int position) {
        return switch (position) {
            case 0 -> "blockfrost.timeout.connect-seconds";
            case 1 -> "blockfrost.timeout.read-seconds";
            case 2 -> "blockfrost.timeout.write-seconds";
            case 3 -> "blockfrost.timeout.discovery-call-seconds";
            case 4 -> "blockfrost.timeout.evaluation-call-seconds";
            case 5 -> "blockfrost.timeout.submission-call-seconds";
            case 6 -> "wallet-coordinator.pass-timeout-seconds";
            default -> throw new IllegalArgumentException();
        };
    }

    private static BoundedBFBackendService construct(long[] value) {
        return new BoundedBFBackendService("http://127.0.0.1:9/api/v0/", BlockfrostKey.of("test-key"),
                value[0], value[1], value[2], value[3], value[4], value[5], value[6]);
    }

    private static BoundedBFBackendService bounded(String url, long discovery, long evaluation,
                                                    long submission, long pass) {
        return bounded(url, discovery, evaluation, submission, pass, System::nanoTime);
    }

    private static BoundedBFBackendService bounded(String url, long discovery, long evaluation,
                                                    long submission, long pass, java.util.function.LongSupplier clock) {
        return new BoundedBFBackendService(url, BlockfrostKey.of("test-key"),
                1, 5, 5, discovery, evaluation, submission, pass, clock);
    }

    private static BoundedBFBackendService bounded(String url, long discovery, long evaluation,
                                                    long submission, long pass,
                                                    java.util.function.LongSupplier clock,
                                                    EventListener.Factory eventListenerFactory) {
        return new BoundedBFBackendService(url, BlockfrostKey.of("test-key"),
                1, 5, 5, discovery, evaluation, submission, pass, clock, eventListenerFactory);
    }

    @FunctionalInterface
    private interface CheckedCall {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface BackendCall {
        void run(BoundedBFBackendService backend) throws Exception;
    }

    private static final class TransportEvents implements EventListener.Factory {
        private final ConcurrentHashMap<String, AtomicInteger> starts = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, AtomicInteger> terminals = new ConcurrentHashMap<>();

        @Override
        public EventListener create(Call call) {
            return new EventListener() {
                @Override
                public void callStart(Call startedCall) {
                    increment(starts, path(startedCall));
                }

                @Override
                public void callEnd(Call endedCall) {
                    increment(terminals, path(endedCall));
                }

                @Override
                public void callFailed(Call failedCall, IOException ioe) {
                    increment(terminals, path(failedCall));
                }
            };
        }

        private int started(String path) {
            return count(starts, path);
        }

        private int terminated(String path) {
            return count(terminals, path);
        }

        private int totalStarted() {
            return starts.values().stream().mapToInt(AtomicInteger::get).sum();
        }

        private int totalTerminated() {
            return terminals.values().stream().mapToInt(AtomicInteger::get).sum();
        }

        private static String path(Call call) {
            return call.request().url().encodedPath();
        }

        private static int count(ConcurrentHashMap<String, AtomicInteger> events, String path) {
            AtomicInteger count = events.get(path);
            return count == null ? 0 : count.get();
        }

        private static void increment(ConcurrentHashMap<String, AtomicInteger> events, String path) {
            events.computeIfAbsent(path, ignored -> new AtomicInteger()).incrementAndGet();
        }
    }

    private static void assertCompletesExceptionallyWithinBound(BackendCall operation) throws Exception {
        try (NoResponseServer server = new NoResponseServer()) {
            BoundedBFBackendService backend = bounded(server.baseUrl(), 1, 1, 1, 2);
            ExecutorService caller = Executors.newSingleThreadExecutor();
            try {
                Future<?> call = caller.submit(() -> {
                    operation.run(backend);
                    return null;
                });
                assertTrue(server.requestRead.await(FIXTURE_BOUND.toMillis(), TimeUnit.MILLISECONDS));
                assertThrows(ExecutionException.class, () -> call.get(2_500, TimeUnit.MILLISECONDS));
            } finally {
                server.closeConnection();
                caller.shutdown();
                assertTrue(caller.awaitTermination(FIXTURE_BOUND.toMillis(), TimeUnit.MILLISECONDS));
            }
        }
    }

    private static final class DripServer implements AutoCloseable {
        private final ServerSocket server;
        private final ExecutorService handler = Executors.newSingleThreadExecutor();
        private final AtomicReference<Socket> connection = new AtomicReference<>();
        private final CountDownLatch requestRead = new CountDownLatch(1);
        private final CountDownLatch handlerExited = new CountDownLatch(1);

        private DripServer() throws IOException {
            server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            handler.submit(this::serve);
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getLocalPort() + "/api/v0/";
        }

        private void serve() {
            try (Socket socket = server.accept()) {
                connection.set(socket);
                readHeaders(socket.getInputStream());
                requestRead.countDown();
                OutputStream out = socket.getOutputStream();
                out.write(("HTTP/1.1 200 OK\r\n"
                        + "Content-Type: application/json\r\n"
                        + "Content-Length: 100000\r\n"
                        + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write("{\"hash\":\"".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                while (!socket.isClosed()) {
                    out.write('a');
                    out.flush();
                    Thread.sleep(50);
                }
            } catch (IOException ignored) {
                // Expected when the fixture closes the owned socket.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                handlerExited.countDown();
            }
        }

        private static void readHeaders(InputStream input) throws IOException {
            ByteArrayOutputStream received = new ByteArrayOutputStream();
            int previous3 = -1;
            int previous2 = -1;
            int previous1 = -1;
            for (int next; (next = input.read()) != -1; ) {
                received.write(next);
                if (previous3 == '\r' && previous2 == '\n' && previous1 == '\r' && next == '\n') {
                    return;
                }
                previous3 = previous2;
                previous2 = previous1;
                previous1 = next;
            }
            throw new IOException("client closed before request headers completed");
        }

        private void closeConnection() throws IOException {
            Socket socket = connection.get();
            if (socket != null) {
                socket.close();
            }
        }

        @Override
        public void close() throws Exception {
            closeConnection();
            server.close();
            handler.shutdownNow();
            assertTrue(handler.awaitTermination(FIXTURE_BOUND.toMillis(), TimeUnit.MILLISECONDS),
                    "the loopback fixture executor did not terminate");
        }
    }

    private static final class NoResponseServer implements AutoCloseable {
        private final ServerSocket server;
        private final ExecutorService handler = Executors.newSingleThreadExecutor();
        private final AtomicReference<Socket> connection = new AtomicReference<>();
        private final CountDownLatch requestRead = new CountDownLatch(1);

        private NoResponseServer() throws IOException {
            server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            handler.submit(() -> {
                try (Socket socket = server.accept()) {
                    connection.set(socket);
                    DripServer.readHeaders(socket.getInputStream());
                    requestRead.countDown();
                    socket.getInputStream().read();
                } catch (IOException ignored) { }
            });
        }

        private String baseUrl() { return "http://127.0.0.1:" + server.getLocalPort() + "/api/v0/"; }
        private void closeConnection() throws IOException {
            Socket socket = connection.get();
            if (socket != null) socket.close();
        }
        @Override public void close() throws Exception {
            closeConnection();
            server.close();
            handler.shutdownNow();
            assertTrue(handler.awaitTermination(FIXTURE_BOUND.toMillis(), TimeUnit.MILLISECONDS));
        }
    }

    private static final class FixedResponseServer implements AutoCloseable {
        private final ServerSocket server;
        private final ExecutorService handler = Executors.newSingleThreadExecutor();
        private final CountDownLatch completed;
        private final String response;
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicInteger bodyBytes = new AtomicInteger();
        private final java.util.List<String> paths = new java.util.concurrent.CopyOnWriteArrayList<>();

        private FixedResponseServer(int expected, String response) throws IOException {
            this.completed = new CountDownLatch(expected);
            this.response = response;
            server = new ServerSocket(0, expected, InetAddress.getLoopbackAddress());
            server.setSoTimeout((int) FIXTURE_BOUND.toMillis());
            handler.submit(() -> serve(expected));
        }

        private String baseUrl() { return "http://127.0.0.1:" + server.getLocalPort() + "/api/v0/"; }

        private void serve(int expected) {
            for (int i = 0; i < expected; i++) {
                try (Socket socket = server.accept()) {
                    byte[] header = readHeaderBytes(socket.getInputStream());
                    String text = new String(header, StandardCharsets.US_ASCII);
                    String[] first = text.substring(0, text.indexOf("\r\n")).split(" ");
                    paths.add(first[1]);
                    int length = contentLength(text);
                    int read = 0;
                    while (read < length && socket.getInputStream().read() != -1) read++;
                    bodyBytes.addAndGet(read);
                    requests.incrementAndGet();
                    socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    completed.countDown();
                } catch (SocketTimeoutException timeout) {
                    return;
                } catch (IOException ignored) {
                    return;
                }
            }
        }

        private static byte[] readHeaderBytes(InputStream input) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int a = -1, b = -1, c = -1;
            for (int next; (next = input.read()) != -1; ) {
                bytes.write(next);
                if (a == '\r' && b == '\n' && c == '\r' && next == '\n') return bytes.toByteArray();
                a = b; b = c; c = next;
            }
            throw new IOException("incomplete headers");
        }

        private static int contentLength(String headers) {
            for (String line : headers.split("\r\n")) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    return Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                }
            }
            return 0;
        }

        @Override public void close() throws Exception {
            server.close();
            handler.shutdownNow();
            assertTrue(handler.awaitTermination(FIXTURE_BOUND.toMillis(), TimeUnit.MILLISECONDS));
        }
    }

    private static final class ResetAfterBodyServer implements AutoCloseable {
        private final ServerSocket server;
        private final ExecutorService handler = Executors.newSingleThreadExecutor();
        private final CountDownLatch firstRequest = new CountDownLatch(1);
        private final AtomicInteger posts = new AtomicInteger();
        private final AtomicInteger bodyBytes = new AtomicInteger();

        private ResetAfterBodyServer() throws IOException {
            server = new ServerSocket(0, 2, InetAddress.getLoopbackAddress());
            server.setSoTimeout(700);
            handler.submit(this::serve);
        }

        private String baseUrl() { return "http://127.0.0.1:" + server.getLocalPort() + "/api/v0/"; }

        private void serve() {
            try {
                while (true) {
                    try (Socket socket = server.accept()) {
                        byte[] header = FixedResponseServer.readHeaderBytes(socket.getInputStream());
                        String text = new String(header, StandardCharsets.US_ASCII);
                        if (text.startsWith("POST ")) posts.incrementAndGet();
                        int length = FixedResponseServer.contentLength(text);
                        for (int read = 0; read < length; read++) {
                            if (socket.getInputStream().read() == -1) break;
                            bodyBytes.incrementAndGet();
                        }
                        firstRequest.countDown();
                        socket.setSoLinger(true, 0);
                    }
                }
            } catch (SocketTimeoutException expected) {
                // A second accept would be an automatic replay; the quiet window proves there was none.
            } catch (IOException ignored) { }
        }

        @Override public void close() throws Exception {
            server.close();
            handler.shutdownNow();
            assertTrue(handler.awaitTermination(FIXTURE_BOUND.toMillis(), TimeUnit.MILLISECONDS));
        }
    }
}
