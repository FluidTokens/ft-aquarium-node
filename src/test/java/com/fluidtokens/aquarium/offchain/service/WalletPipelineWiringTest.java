package com.fluidtokens.aquarium.offchain.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.store.events.EventMetadata;
import com.bloxbean.cardano.yaci.store.events.RollbackEvent;
import com.bloxbean.cardano.yaci.store.events.internal.CommitEvent;
import com.fluidtokens.aquarium.offchain.config.BlockfrostConfig;
import com.fluidtokens.aquarium.offchain.config.BoundedBFBackendService;
import com.fluidtokens.aquarium.offchain.service.loans.CompoundExecutor;
import com.fluidtokens.aquarium.offchain.service.loans.FluidOracleClient;
import com.fluidtokens.aquarium.offchain.service.loans.LiquidationExecutor;
import com.fluidtokens.aquarium.offchain.service.loans.LoanFixtures;
import com.fluidtokens.aquarium.offchain.service.wallet.WalletRebalancer;
import com.sun.net.httpserver.HttpServer;
import org.cardanofoundation.conversions.CardanoConverters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ⛔ FAB-183: the wallet pipeline THROUGH THE CONTAINER. "Promoting test code to src/main requires a
 * production-wiring test" (CLAUDE.md): {@code WalletPipelineTest} drives the engine with an executor and a clock
 * it supplies, and proves nothing about which thread a stage runs on once Spring delivers the events.
 *
 * <p>The context is the real {@link WalletPipelineService} and the real {@link BlockEventListener} over the
 * SHIPPED {@code application.yaml} base document, the {@link BoundedBFBackendService} from the production bean
 * method ({@link BlockfrostConfig}, a fake key and an unused loopback URL — nothing is ever called on it), Boot's
 * {@link TransactionAutoConfiguration} and a real Spring transaction manager ({@link RecordingTransactionManager}:
 * no DataSource, it only counts commits and rollbacks). Yaci publishes {@code CommitEvent} inside its block
 * transaction at the tip and with none during initial sync, and both paths are driven here.
 *
 * <p>The three stages are Mockito mocks of the real executor classes, registered as plain singletons so that no
 * production wiring of theirs runs. Each stage call records its thread, {@link BlockEventListener}'s
 * {@code lastCommittedSlot} and the transaction manager's commit count at that instant.
 *
 * <p>⚠ The pipeline service is REGISTERED BEFORE {@link BlockEventListener} on purpose: with no transaction, only
 * the {@code @Order} on {@code BlockEventListener.processCommittedBlock} puts the committed slot ahead of the offer.
 */
class WalletPipelineWiringTest {

    /** Nothing listens here; the backend is constructed and never called. */
    private static final String UNUSED_LOOPBACK = "http://127.0.0.1:9/api/v0/";

    private static final Duration PASS_WAIT = Duration.ofSeconds(10);

    /** How long "nothing happened" is watched for. A handed-off pass starts within milliseconds. */
    private static final Duration QUIET = Duration.ofMillis(500);

    /**
     * A real {@link PlatformTransactionManager} with no resource behind it: Spring's own synchronization machinery
     * runs (so {@code @TransactionalEventListener} sees a live transaction), and a stage can record whether the
     * commit had already happened when it ran.
     */
    static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {

        final AtomicInteger commits = new AtomicInteger();

        final AtomicInteger rollbacks = new AtomicInteger();

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits.incrementAndGet();
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks.incrementAndGet();
        }
    }

    /**
     * One stage call, as seen from inside it.
     *
     * @param insideBackendDeadline whether the call ran inside a pass deadline of the CONTAINER's
     *                              {@link BoundedBFBackendService} bean (only probed by the liquidation stage)
     */
    record StageCall(String stage, String thread, long lastCommittedSlot, int commits, boolean fenceOpen,
                     boolean insideBackendDeadline) {
    }

    /** The three stage mocks, what they answer, and every call they saw. */
    static final class Stages {

        final List<StageCall> calls = new CopyOnWriteArrayList<>();

        final LiquidationExecutor liquidation = mock(LiquidationExecutor.class);

        final CompoundExecutor compound = mock(CompoundExecutor.class);

        final WalletRebalancer housekeeping = mock(WalletRebalancer.class);

        /** Per-call answers; an empty queue answers DEFINITE_FAILURE, so a pass reaches every stage. */
        final Deque<WorkResult> liquidationResults = new ArrayDeque<>();

        final Deque<WorkResult> compoundResults = new ArrayDeque<>();

        /** Runs inside the next liquidation call, before it records; taken once. */
        final AtomicReference<Runnable> beforeLiquidation = new AtomicReference<>();

        final AtomicReference<BlockEventListener> blockEventListener = new AtomicReference<>();

        final AtomicReference<BoundedBFBackendService> backend = new AtomicReference<>();

        final RecordingTransactionManager transactionManager;

        Stages(RecordingTransactionManager transactionManager) {
            this.transactionManager = transactionManager;
            when(liquidation.cycle(anyLong(), any())).thenAnswer(invocation -> {
                Runnable before = beforeLiquidation.getAndSet(null);
                if (before != null) {
                    before.run();
                }
                record("liquidation", invocation.getArgument(1), probeBackendDeadline());
                return next(liquidationResults);
            });
            when(compound.cycle(any(SubmitFence.class))).thenAnswer(invocation -> {
                record("compound", invocation.getArgument(0), false);
                return next(compoundResults);
            });
            when(housekeeping.housekeep(any())).thenAnswer(invocation -> {
                record("housekeeping", invocation.getArgument(0), false);
                return WorkResult.DEFINITE_FAILURE;
            });
        }

        private static synchronized WorkResult next(Deque<WorkResult> results) {
            WorkResult result = results.pollFirst();
            return result == null ? WorkResult.DEFINITE_FAILURE : result;
        }

        /** A nested pass deadline is refused by the backend that already holds one for this thread. */
        private boolean probeBackendDeadline() {
            try {
                backend.get().runWithinPassDeadline(deadline -> {
                });
                return false;
            } catch (IllegalStateException nested) {
                return true;
            }
        }

        private void record(String stage, SubmitFence fence, boolean insideBackendDeadline) {
            calls.add(new StageCall(stage, Thread.currentThread().getName(),
                    blockEventListener.get().getLastCommittedSlot().get(), transactionManager.commits.get(),
                    fence.mayProceed(), insideBackendDeadline));
        }

        List<String> stageNames() {
            return calls.stream().map(StageCall::stage).toList();
        }
    }

    private ListAppender<ILoggingEvent> logs;

    private Logger logger;

    @BeforeEach
    void captureLogs() {
        logger = (Logger) LoggerFactory.getLogger(WalletPipelineService.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
    }

    private static PropertySource<?> shippedBaseDocument() {
        try {
            return new YamlPropertySourceLoader()
                    .load("application.yaml", new ClassPathResource("application.yaml")).get(0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The production-shaped context over the shipped base document; the developer's environment and system
     * properties are removed, so neither decides it.
     */
    private static ApplicationContextRunner runner(Stages stages) {
        return new ApplicationContextRunner()
                .withInitializer(context -> {
                    var sources = context.getEnvironment().getPropertySources();
                    sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                    sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                    sources.addLast(shippedBaseDocument());
                    var beans = context.getBeanFactory();
                    beans.registerSingleton("liquidationExecutor", stages.liquidation);
                    beans.registerSingleton("compoundExecutor", stages.compound);
                    beans.registerSingleton("walletRebalancer", stages.housekeeping);
                })
                .withPropertyValues("blockfrost.url=" + UNUSED_LOOPBACK, "blockfrost.key=fake-test-key")
                .withConfiguration(AutoConfigurations.of(TransactionAutoConfiguration.class))
                .withBean(PlatformTransactionManager.class, () -> stages.transactionManager)
                .withBean(CardanoConverters.class, LoanFixtures::converters)
                // ⚠ Registration order is part of the test: the pipeline service BEFORE the block listener.
                .withUserConfiguration(BlockfrostConfig.class, WalletPipelineService.class, BlockEventListener.class);
    }

    /** Binds the stages to the context's own beans; every test calls it before publishing. */
    private static void bind(Stages stages, org.springframework.context.ApplicationContext context) {
        stages.blockEventListener.set(context.getBean(BlockEventListener.class));
        stages.backend.set(context.getBean(BoundedBFBackendService.class));
    }

    private static long slotAgo(org.springframework.context.ApplicationContext context, Duration age) {
        CardanoConverters converters = context.getBean(CardanoConverters.class);
        return converters.time().toSlot(LocalDateTime.ofInstant(Instant.now().minus(age), ZoneOffset.UTC));
    }

    private static CommitEvent<?> block(long slot, String blockHash) {
        return new CommitEvent<>(EventMetadata.builder().slot(slot).blockHash(blockHash).build(), List.of());
    }

    private static boolean await(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(5);
        }
        return condition.getAsBoolean();
    }

    private static void awaitCalls(Stages stages, int count) throws InterruptedException {
        assertTrue(await(() -> stages.calls.size() >= count, PASS_WAIT),
                "expected " + count + " stage calls, saw " + stages.calls);
    }

    private static void assertQuiet(Stages stages, int count, String why) throws InterruptedException {
        assertFalse(await(() -> stages.calls.size() > count, QUIET), why + ": " + stages.calls);
    }

    // ---- (a)–(c): after the commit, never inside it ------------------------------------------------------------

    /**
     * (a) ⛔ Inside the block's transaction no stage runs; once it commits, the three stages run on the
     * {@code wallet-pipeline} thread — never the publishing one — and each sees the committed slot and the commit.
     */
    @Test
    void insideTheBlocksTransactionNoStageRunsAndAfterTheCommitTheStagesRunOnThePipelineThread() {
        RecordingTransactionManager transactionManager = new RecordingTransactionManager();
        Stages stages = new Stages(transactionManager);
        runner(stages).run(context -> {
            assertNull(context.getStartupFailure(), "the container did not start: " + context.getStartupFailure());
            bind(stages, context);
            long slot = slotAgo(context, Duration.ofMinutes(1));
            String publishingThread = Thread.currentThread().getName();

            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                assertTrue(TransactionSynchronizationManager.isSynchronizationActive(),
                        "the test must publish inside a live Spring transaction");
                context.publishEvent(block(slot, "aa".repeat(32)));
                try {
                    assertQuiet(stages, 0, "a stage ran INSIDE the block's transaction");
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            });

            assertEquals(1, transactionManager.commits.get());
            awaitCalls(stages, 3);
            assertEquals(List.of("liquidation", "compound", "housekeeping"), stages.stageNames(),
                    "one pass, the stages in order");
            for (StageCall call : stages.calls) {
                assertEquals("wallet-pipeline", call.thread(), call.stage() + " ran off the pipeline thread");
                assertNotEquals(publishingThread, call.thread(), call.stage() + " ran on the publishing thread");
                assertTrue(call.lastCommittedSlot() >= slot,
                        call.stage() + " ran before the committed slot reached " + slot + ": " + call);
                assertEquals(1, call.commits(), call.stage() + " ran before the commit: " + call);
                assertTrue(call.fenceOpen(), call.stage() + ": the pass's fence is open");
            }
        });
    }

    /** (b) A block whose transaction rolls back never reaches a stage. */
    @Test
    void aRolledBackBlockTransactionStartsNoPass() {
        RecordingTransactionManager transactionManager = new RecordingTransactionManager();
        Stages stages = new Stages(transactionManager);
        runner(stages).run(context -> {
            assertNull(context.getStartupFailure(), "the container did not start: " + context.getStartupFailure());
            bind(stages, context);

            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                context.publishEvent(block(slotAgo(context, Duration.ofMinutes(1)), "bb".repeat(32)));
                status.setRollbackOnly();
            });

            assertEquals(1, transactionManager.rollbacks.get());
            assertEquals(0, transactionManager.commits.get());
            assertQuiet(stages, 0, "a rolled-back block reached a stage");
        });
    }

    /** (c) With NO transaction — Yaci's initial-sync publish path — a fresh block still starts a pass. */
    @Test
    void withNoTransactionAFreshBlockStillStartsAPass() {
        RecordingTransactionManager transactionManager = new RecordingTransactionManager();
        Stages stages = new Stages(transactionManager);
        runner(stages).run(context -> {
            assertNull(context.getStartupFailure(), "the container did not start: " + context.getStartupFailure());
            bind(stages, context);
            assertFalse(TransactionSynchronizationManager.isSynchronizationActive(), "no transaction here");
            long slot = slotAgo(context, Duration.ofMinutes(1));

            context.publishEvent(block(slot, "cc".repeat(32)));

            awaitCalls(stages, 3);
            assertEquals(List.of("liquidation", "compound", "housekeeping"), stages.stageNames());
            for (StageCall call : stages.calls) {
                assertEquals("wallet-pipeline", call.thread());
                assertTrue(call.lastCommittedSlot() >= slot, String.valueOf(call));
            }
            assertEquals(0, transactionManager.commits.get() + transactionManager.rollbacks.get(),
                    "nothing opened a transaction for it");
        });
    }

    // ---- (d)–(f): which points start a pass --------------------------------------------------------------------

    /** (d) ⛔ A block older than the syncing threshold — initial sync — is never offered. */
    @Test
    void aBlockOlderThanTheSyncingThresholdStartsNoPass() {
        Stages stages = new Stages(new RecordingTransactionManager());
        runner(stages).run(context -> {
            assertNull(context.getStartupFailure(), "the container did not start: " + context.getStartupFailure());
            bind(stages, context);

            context.publishEvent(block(slotAgo(context, Duration.ofMinutes(30)), "dd".repeat(32)));
            assertQuiet(stages, 0, "a 30-minute-old block (shipped threshold 10 min) started a pass");

            long fresh = slotAgo(context, Duration.ofMinutes(1));
            context.publishEvent(block(fresh, "de".repeat(32)));
            awaitCalls(stages, 3);
            assertQuiet(stages, 3, "only the fresh block's pass");
            assertEquals(fresh, stages.calls.get(0).lastCommittedSlot(), "the pass is the fresh block's");
        });
    }

    /** (e) The same committed point twice is one pass, and offered again after it ran is still one. */
    @Test
    void theSamePointPublishedTwiceIsOnePass() {
        Stages stages = new Stages(new RecordingTransactionManager());
        runner(stages).run(context -> {
            assertNull(context.getStartupFailure(), "the container did not start: " + context.getStartupFailure());
            bind(stages, context);
            long slot = slotAgo(context, Duration.ofMinutes(1));

            context.publishEvent(block(slot, "ee".repeat(32)));
            context.publishEvent(block(slot, "ee".repeat(32)));
            awaitCalls(stages, 3);
            assertQuiet(stages, 3, "a duplicate point started a second pass");

            context.publishEvent(block(slot, "ee".repeat(32)));
            assertQuiet(stages, 3, "the last handled point started a second pass");
        });
    }

    /**
     * (f) ⛔ A Yaci rollback fences the pass in flight — it transmits nothing more — and the same slot with a new
     * block hash then starts a fresh pass.
     */
    @Test
    void aRollbackFencesThePassInFlightAndTheSameSlotWithANewHashRunsAgain() {
        Stages stages = new Stages(new RecordingTransactionManager());
        runner(stages).run(context -> {
            assertNull(context.getStartupFailure(), "the container did not start: " + context.getStartupFailure());
            bind(stages, context);
            long slot = slotAgo(context, Duration.ofMinutes(1));
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            stages.beforeLiquidation.set(() -> {
                entered.countDown();
                try {
                    release.await(PASS_WAIT.toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            context.publishEvent(block(slot, "f1".repeat(32)));
            assertTrue(entered.await(PASS_WAIT.toMillis(), TimeUnit.MILLISECONDS), "the first pass started");
            context.publishEvent(RollbackEvent.builder().rollbackTo(new Point(slot - 10, "ab".repeat(32))).build());
            release.countDown();

            awaitCalls(stages, 1);
            assertQuiet(stages, 1, "the fenced pass went on to another stage");
            assertFalse(stages.calls.get(0).fenceOpen(), "after the rollback the old pass's fence must read closed");
            assertTrue(logs.list.stream().anyMatch(e -> e.getLevel() == Level.INFO && e.getFormattedMessage()
                            .equals("wallet pipeline: rollback to slot " + (slot - 10)
                                    + " — queued work dropped, any old pass is fenced")),
                    String.valueOf(logs.list));

            context.publishEvent(block(slot, "f2".repeat(32)));
            awaitCalls(stages, 4);
            assertEquals(List.of("liquidation", "liquidation", "compound", "housekeeping"), stages.stageNames());
            assertTrue(stages.calls.get(1).fenceOpen(), "the new pass's fence is open");
        });
    }

    // ---- (g): price changes -----------------------------------------------------------------------------------

    private static final String TOKEN_POLICY = "aa".repeat(28);

    private static final String NFT_POLICY = "bb".repeat(28);

    private static final long VALID_FROM = 1_793_000_000_000L;

    /** One active multisig registry entry, as the registry serves it, valid for 50 minutes from {@code validFrom}. */
    private static String registry(long validFrom) {
        String hex = HexFormat.of().formatHex("TOK".getBytes(StandardCharsets.UTF_8));
        String nft = HexFormat.of().formatHex("A".getBytes(StandardCharsets.UTF_8));
        return "[{\"active\":true,\"preferredOracle\":\"multisig\",\"oracleVersion\":1,"
                + "\"token\":{\"policyId\":\"" + TOKEN_POLICY + "\",\"assetName\":\"" + hex + "\"},"
                + "\"fluidOracle\":{\"policyId\":\"" + NFT_POLICY + "\",\"assetName\":\"" + nft + "\","
                + "\"rewardAddress\":\"\",\"referenceInput\":\"" + "cc".repeat(32) + "#0\","
                + "\"referenceScript\":\"" + "dd".repeat(32) + "#0\"},"
                + "\"supportedOracle\":{\"multisig\":{\"tokenPriceInLovelaces\":10,\"tokenPriceDenominator\":1000000,"
                + "\"validFrom\":" + validFrom + ",\"validTo\":" + (validFrom + 3_000_000L) + "}}}]";
    }

    /**
     * (g) ⛔ With a {@link FluidOracleClient} bean: a refresh that loads a NEWER {@code validFrom} re-runs the last
     * handled point, on the pipeline thread. The registry is a loopback HTTP server, so the bean's real
     * {@code refresh} path is what changes the prices.
     */
    @Test
    void aMergedPriceChangeFromTheOracleClientStartsAPass() throws IOException {
        AtomicReference<String> served = new AtomicReference<>(registry(VALID_FROM));
        HttpServer registry = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        registry.createContext("/", exchange -> {
            byte[] body = served.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        registry.start();
        try {
            String url = "http://127.0.0.1:" + registry.getAddress().getPort() + "/";
            Stages stages = new Stages(new RecordingTransactionManager());
            runner(stages).withBean(FluidOracleClient.class, () -> new FluidOracleClient(url)).run(context -> {
                assertNull(context.getStartupFailure(), "the container did not start: " + context.getStartupFailure());
                bind(stages, context);
                FluidOracleClient client = context.getBean(FluidOracleClient.class);
                assertNotEquals(Instant.EPOCH, client.lastRefresh(), "the bean loaded the registry at startup");
                AtomicInteger changes = new AtomicInteger();
                client.addChangeListener((source, changed, connected) -> changes.incrementAndGet());

                context.publishEvent(block(slotAgo(context, Duration.ofMinutes(1)), "a1".repeat(32)));
                awaitCalls(stages, 3);

                served.set(registry(VALID_FROM + 60_000L));
                client.refresh();
                assertEquals(1, changes.get(), "the newer validFrom changed the merged prices");

                awaitCalls(stages, 6);
                assertEquals(List.of("liquidation", "compound", "housekeeping",
                        "liquidation", "compound", "housekeeping"), stages.stageNames(), "a second pass");
                for (StageCall call : stages.calls) {
                    assertEquals("wallet-pipeline", call.thread());
                }
                assertQuiet(stages, 6, "one change, one pass");
            });
        } finally {
            registry.stop(0);
        }
    }

    /** (g) Without a {@link FluidOracleClient} bean the context still starts, and blocks still drive the stages. */
    @Test
    void withoutAnOracleClientTheContextStarts() {
        Stages stages = new Stages(new RecordingTransactionManager());
        runner(stages).run(context -> {
            assertNull(context.getStartupFailure(), "the container did not start: " + context.getStartupFailure());
            assertEquals(0, context.getBeanNamesForType(FluidOracleClient.class).length);
            bind(stages, context);
            context.publishEvent(block(slotAgo(context, Duration.ofMinutes(1)), "a2".repeat(32)));
            awaitCalls(stages, 3);
        });
    }

    // ---- (h): a stage that sent something ends the pass --------------------------------------------------------

    /**
     * (h) Liquidation SUBMITTED ends the pass; liquidation DEFINITE_FAILURE goes on to compound; compound
     * SUBMITTED ends it; compound DEFINITE_FAILURE goes on to housekeeping. Three blocks, three passes.
     */
    @Test
    void aStageThatSentSomethingEndsThePass() {
        Stages stages = new Stages(new RecordingTransactionManager());
        stages.liquidationResults.addAll(List.of(WorkResult.SUBMITTED, WorkResult.DEFINITE_FAILURE,
                WorkResult.DEFINITE_FAILURE));
        stages.compoundResults.addAll(List.of(WorkResult.SUBMITTED, WorkResult.DEFINITE_FAILURE));
        runner(stages).run(context -> {
            assertNull(context.getStartupFailure(), "the container did not start: " + context.getStartupFailure());
            bind(stages, context);

            context.publishEvent(block(slotAgo(context, Duration.ofMinutes(3)), "c1".repeat(32)));
            awaitCalls(stages, 1);
            assertQuiet(stages, 1, "liquidation SUBMITTED must end the pass");

            context.publishEvent(block(slotAgo(context, Duration.ofMinutes(2)), "c2".repeat(32)));
            awaitCalls(stages, 3);
            assertQuiet(stages, 3, "compound SUBMITTED must end the pass");

            context.publishEvent(block(slotAgo(context, Duration.ofMinutes(1)), "c3".repeat(32)));
            awaitCalls(stages, 6);

            assertEquals(List.of("liquidation",
                    "liquidation", "compound",
                    "liquidation", "compound", "housekeeping"), stages.stageNames());
        });
    }

    // ---- (i): the backend ------------------------------------------------------------------------------------

    /**
     * (i) The pipeline runs each pass inside a deadline of the container's ONE {@link BoundedBFBackendService}
     * bean — the instance every Blockfrost call goes through — and says what it is at startup.
     */
    @Test
    void thePipelineHoldsTheContainersBoundedBackend() {
        Stages stages = new Stages(new RecordingTransactionManager());
        runner(stages).run(context -> {
            assertNull(context.getStartupFailure(), "the container did not start: " + context.getStartupFailure());
            assertEquals(1, context.getBeanNamesForType(WalletPipelineService.class).length, "one pipeline service");
            assertEquals(1, context.getBeanNamesForType(BoundedBFBackendService.class).length, "one backend");
            bind(stages, context);

            context.publishEvent(block(slotAgo(context, Duration.ofMinutes(1)), "b1".repeat(32)));
            awaitCalls(stages, 1);
            assertTrue(stages.calls.get(0).insideBackendDeadline(),
                    "the pass did not run inside the container backend's deadline: another instance holds it");

            List<String> info = logs.list.stream().filter(e -> e.getLevel() == Level.INFO)
                    .map(ILoggingEvent::getFormattedMessage).toList();
            assertTrue(info.contains("WALLET PIPELINE liquidation → compound → housekeeping, on committed blocks "
                    + "(drift ≤ 10 min) and oracle price changes; no timer; pass deadline 120 s"), String.valueOf(info));
        });
    }

    // ---- (j): no other spender --------------------------------------------------------------------------------

    private static List<String> annotated(Class<?> type, Class<? extends java.lang.annotation.Annotation> annotation) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(m -> AnnotatedElementUtils.hasAnnotation(m, annotation))
                .map(Method::getName)
                .toList();
    }

    /**
     * (j) ⛔ No timer and no listener spends the wallet: the liquidation and compound executors carry no
     * {@code @Scheduled} method and the rebalancer no event listener. The Tank processor and the oracle poll keep
     * their timers, and the pipeline's own entry points are the ones this class proves.
     */
    @Test
    void noTimerAndNoOtherListenerDrivesAWalletStage() throws NoSuchMethodException {
        assertEquals(List.of(), annotated(LiquidationExecutor.class, Scheduled.class), "LiquidationExecutor timers");
        assertEquals(List.of(), annotated(CompoundExecutor.class, Scheduled.class), "CompoundExecutor timers");
        assertEquals(List.of(), annotated(WalletRebalancer.class, EventListener.class), "WalletRebalancer listeners");
        assertEquals(List.of(), annotated(WalletRebalancer.class, TransactionalEventListener.class),
                "WalletRebalancer transactional listeners");

        assertFalse(annotated(ScheduledTransactionService.class, Scheduled.class).isEmpty(),
                "the Tank processor keeps its timer (FAB-143 P5)");
        assertNotNull(AnnotatedElementUtils.findMergedAnnotation(FluidOracleClient.class.getMethod("refresh"),
                Scheduled.class), "the oracle poll keeps its timer");

        Method onCommit = WalletPipelineService.class.getMethod("onCommit", CommitEvent.class);
        TransactionalEventListener afterCommit = onCommit.getAnnotation(TransactionalEventListener.class);
        assertNotNull(afterCommit, "onCommit must be a @TransactionalEventListener");
        assertEquals(TransactionPhase.AFTER_COMMIT, afterCommit.phase());
        assertTrue(afterCommit.fallbackExecution(), "the initial-sync publish path has no transaction");
        assertNull(onCommit.getAnnotation(Order.class),
                "default order: after BlockEventListener's HIGHEST_PRECEDENCE committed-slot listener");
        assertNotNull(WalletPipelineService.class.getMethod("onRollback", RollbackEvent.class)
                .getAnnotation(EventListener.class), "onRollback must be an @EventListener");
        assertEquals(List.of("onCommit", "onRollback"),
                annotated(WalletPipelineService.class, EventListener.class).stream().sorted().toList(),
                "the pipeline's only listeners");
    }
}
