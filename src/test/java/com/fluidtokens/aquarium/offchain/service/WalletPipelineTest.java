package com.fluidtokens.aquarium.offchain.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bloxbean.cardano.client.api.model.Result;
import com.fluidtokens.aquarium.offchain.config.BlockfrostKey;
import com.fluidtokens.aquarium.offchain.config.BoundedBFBackendService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wallet pipeline engine (FAB-183-6): one thread, chain-point coalescing, rollback fencing and
 * bounded passes. Every interleaving is forced with latches, a controllable pass deadline and a
 * controllable clock; "nothing ran" is proven with a barrier task queued on the pipeline's own
 * single worker, never by sleeping and hoping.
 */
class WalletPipelineTest {

    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final Duration HUNG = Duration.ofSeconds(30);

    private ListAppender<ILoggingEvent> logs;
    private Logger logger;
    private Level previousLevel;

    @BeforeEach
    void attachLogs() {
        logger = (Logger) LoggerFactory.getLogger(WalletPipeline.class);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detachLogs() {
        logger.detachAppender(logs);
        logger.setLevel(previousLevel);
    }

    // (a)
    @Test
    void stagesNeverRunOnTheOfferingThread() throws Exception {
        Thread offering = Thread.currentThread();
        Set<Thread> stageThreads = ConcurrentHashMap.newKeySet();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        try (Harness h = new Harness()) {
            h.start(
                    fence -> {
                        stageThreads.add(Thread.currentThread());
                        h.record("L");
                        if (first.getAndSet(false)) {
                            entered.countDown();
                            assertTrue(release.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
                        }
                        return WorkResult.DEFINITE_FAILURE;
                    },
                    fence -> { stageThreads.add(Thread.currentThread()); h.record("C"); return WorkResult.DEFINITE_FAILURE; },
                    fence -> { stageThreads.add(Thread.currentThread()); h.record("H"); return WorkResult.DEFINITE_FAILURE; });

            h.pipeline.offerBlock(1, "a");
            assertTrue(entered.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
            // the stage is blocked; every trigger still returns to its caller at once
            h.pipeline.offerPriceChange();
            h.pipeline.offerBlock(2, "b");
            h.pipeline.rollback();
            h.pipeline.offerBlock(3, "c");
            release.countDown();
            h.awaitPasses(2);
            h.barrier();

            // the rollback closed the first pass's fence, so its later stages did not run
            assertEquals(List.of("L 0/1:a", "L 1/3:c", "C 1/3:c", "H 1/3:c"), h.calls());
            assertEquals(Set.of(h.workerThread()), stageThreads);
            assertFalse(stageThreads.contains(offering));
        }
    }

    // (b)
    static Stream<Arguments> everyLiquidationAndCompoundResult() {
        List<Arguments> combinations = new ArrayList<>();
        for (WorkResult liquidation : WorkResult.values()) {
            for (WorkResult compound : WorkResult.values()) {
                combinations.add(Arguments.of(liquidation, compound));
            }
        }
        return combinations.stream();
    }

    @ParameterizedTest(name = "liquidation {0}, compound {1}")
    @MethodSource("everyLiquidationAndCompoundResult")
    void stageOrderAndGatingFollowWhatWentOut(WorkResult liquidation, WorkResult compound) throws Exception {
        try (Harness h = new Harness()) {
            h.start(
                    fence -> { h.record("L"); return liquidation; },
                    fence -> { h.record("C"); return compound; },
                    fence -> { h.record("H"); return WorkResult.DEFINITE_FAILURE; });
            h.pipeline.offerBlock(7, "a");
            h.awaitPasses(1);
            h.barrier();

            boolean liquidationWentOut = liquidation == WorkResult.SUBMITTED || liquidation == WorkResult.AMBIGUOUS;
            boolean compoundWentOut = compound == WorkResult.SUBMITTED || compound == WorkResult.AMBIGUOUS;
            List<String> expected = new ArrayList<>(List.of("L 0/7:a"));
            if (!liquidationWentOut) {
                expected.add("C 0/7:a");
                if (!compoundWentOut) {
                    expected.add("H 0/7:a");
                }
            }
            assertEquals(expected, h.calls());

            if (liquidationWentOut) {
                assertEquals(1, count(Level.INFO, "wallet pipeline: liquidation " + liquidation
                        + " at slot 7 — compound and housekeeping wait for the next trigger"));
            } else if (compoundWentOut) {
                assertEquals(1, count(Level.INFO, "wallet pipeline: compound " + compound
                        + " at slot 7 — housekeeping waits for the next trigger"));
            } else {
                assertEquals(0, count(Level.INFO, "wallet pipeline:"));
            }
        }
    }

    @Test
    void aStageThatAnswersNullCountsAsAmbiguous() throws Exception {
        try (Harness h = new Harness()) {
            h.start(
                    fence -> { h.record("L"); return null; },
                    fence -> { h.record("C"); return WorkResult.DEFINITE_FAILURE; },
                    fence -> { h.record("H"); return WorkResult.DEFINITE_FAILURE; });
            h.pipeline.offerBlock(1, "a");
            h.awaitPasses(1);
            h.barrier();
            assertEquals(List.of("L 0/1:a"), h.calls());
            assertEquals(1, count(Level.INFO, "liquidation AMBIGUOUS at slot 1"));
        }
        try (Harness h = new Harness()) {
            h.start(
                    fence -> { h.record("L"); return WorkResult.WOULD_SUBMIT; },
                    fence -> { h.record("C"); return null; },
                    fence -> { h.record("H"); return WorkResult.DEFINITE_FAILURE; });
            h.pipeline.offerBlock(1, "a");
            h.awaitPasses(1);
            h.barrier();
            assertEquals(List.of("L 0/1:a", "C 0/1:a"), h.calls());
            assertEquals(1, count(Level.INFO, "compound AMBIGUOUS at slot 1"));
        }
    }

    // (c)
    @Test
    void duplicatesAndOlderSlotsAreIgnoredAndANewHashAtTheSameSlotRuns() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        try (Harness h = new Harness()) {
            h.start(
                    fence -> {
                        h.record("L");
                        if (first.getAndSet(false)) {
                            entered.countDown();
                            assertTrue(release.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
                        }
                        return WorkResult.SUBMITTED;
                    },
                    fence -> WorkResult.DEFINITE_FAILURE,
                    fence -> WorkResult.DEFINITE_FAILURE);

            h.pipeline.offerBlock(10, "a");
            assertTrue(entered.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
            h.pipeline.offerBlock(10, "a");   // duplicate of the in-flight point
            h.pipeline.offerBlock(9, "x");    // older than the in-flight point
            assertNull(h.pipeline.stateForTest().pending());
            release.countDown();
            h.awaitPasses(1);
            h.barrier();
            assertEquals(List.of("L 0/10:a"), h.calls());

            h.pipeline.offerBlock(10, "a");   // duplicate of the last handled point
            h.pipeline.offerBlock(9, "y");    // older than the last handled point
            h.barrier();
            assertEquals(List.of("L 0/10:a"), h.calls());
            assertEquals(1, h.executes.get(), "an ignored offer schedules nothing");

            h.pipeline.offerBlock(10, "b");   // same slot, different hash: a different block
            h.awaitPasses(1);
            h.barrier();
            assertEquals(List.of("L 0/10:a", "L 0/10:b"), h.calls());
            assertEquals(0, h.passesEnded.availablePermits());
        }
    }

    // (d)
    @Test
    void offersDuringARunningPassCoalesceToOneFurtherPassAtTheLatestPoint() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        try (Harness h = new Harness()) {
            h.start(
                    fence -> {
                        h.record("L");
                        if (first.getAndSet(false)) {
                            entered.countDown();
                            assertTrue(release.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
                        }
                        return WorkResult.DEFINITE_FAILURE;
                    },
                    fence -> WorkResult.DEFINITE_FAILURE,
                    fence -> WorkResult.DEFINITE_FAILURE);

            h.pipeline.offerBlock(10, "a");
            assertTrue(entered.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
            h.pipeline.offerBlock(11, "b");
            h.pipeline.offerBlock(12, "c");
            h.pipeline.offerPriceChange();
            h.pipeline.offerBlock(13, "d");
            assertEquals(new WalletPipeline.ChainPointView(0, 13, "d"), h.pipeline.stateForTest().pending());
            release.countDown();
            h.awaitPasses(2);
            h.barrier();

            assertEquals(List.of("L 0/10:a", "L 0/13:d"), h.calls());
            assertEquals(0, h.passesEnded.availablePermits());
            assertEquals(new WalletPipeline.ChainPointView(0, 13, "d"), h.pipeline.stateForTest().lastHandled());
        }
    }

    // (e)
    @Test
    void aPriceChangeReRunsTheLastHandledPointAndBeforeAnyBlockRunsNothing() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean block = new AtomicBoolean(false);
        try (Harness h = new Harness()) {
            h.start(
                    fence -> {
                        h.record("L");
                        if (block.getAndSet(false)) {
                            entered.countDown();
                            assertTrue(release.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
                        }
                        return WorkResult.DEFINITE_FAILURE;
                    },
                    fence -> { h.record("C"); return WorkResult.DEFINITE_FAILURE; },
                    fence -> { h.record("H"); return WorkResult.DEFINITE_FAILURE; });

            h.pipeline.offerPriceChange();
            h.pipeline.offerPriceChange();
            h.barrier();
            assertEquals(List.of(), h.calls());
            assertEquals(0, h.executes.get(), "a price change with no known point schedules nothing");

            h.pipeline.offerBlock(5, "a");
            h.awaitPasses(1);
            h.barrier();
            assertEquals(List.of("L 0/5:a", "C 0/5:a", "H 0/5:a"), h.calls());
            assertEquals(0, h.passesEnded.availablePermits(), "exactly one pass for the block");

            h.pipeline.offerPriceChange();
            h.awaitPasses(1);
            h.barrier();
            assertEquals(List.of("L 0/5:a", "C 0/5:a", "H 0/5:a", "L 0/5:a", "C 0/5:a", "H 0/5:a"), h.calls());
            assertEquals(0, h.passesEnded.availablePermits());

            // a price change during a pass that is followed by a block is served by the block's pass
            h.clearCalls();
            block.set(true);
            h.pipeline.offerBlock(6, "b");
            assertTrue(entered.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
            h.pipeline.offerPriceChange();
            h.pipeline.offerBlock(7, "c");
            release.countDown();
            h.awaitPasses(2);
            h.barrier();
            assertEquals(List.of("L 0/6:b", "C 0/6:b", "H 0/6:b", "L 0/7:c", "C 0/7:c", "H 0/7:c"), h.calls());
            assertEquals(0, h.passesEnded.availablePermits());

            // after a rollback no point of the new generation is known, so a price change runs nothing
            h.clearCalls();
            int executes = h.executes.get();
            h.pipeline.rollback();
            h.pipeline.offerPriceChange();
            h.barrier();
            assertEquals(List.of(), h.calls());
            assertEquals(executes, h.executes.get());
        }
    }

    // (f)
    @Test
    void aRollbackDuringAStageClosesItsFenceBeforeItsSubmitAndDropsThePendingPoint() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Boolean> fenceRead = new AtomicReference<>();
        AtomicInteger submits = new AtomicInteger();
        try (Harness h = new Harness()) {
            h.start(
                    fence -> {
                        h.record("L");
                        entered.countDown();
                        assertTrue(release.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
                        boolean may = fence.mayProceed();
                        fenceRead.set(may);
                        if (may) {
                            submits.incrementAndGet();
                            return WorkResult.SUBMITTED;
                        }
                        return WorkResult.DEFINITE_FAILURE;
                    },
                    fence -> { h.record("C"); return WorkResult.DEFINITE_FAILURE; },
                    fence -> { h.record("H"); return WorkResult.DEFINITE_FAILURE; });

            h.pipeline.offerBlock(5, "a");
            assertTrue(entered.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
            h.pipeline.offerBlock(6, "b");
            assertEquals(new WalletPipeline.ChainPointView(0, 6, "b"), h.pipeline.stateForTest().pending());
            h.pipeline.rollback();
            assertNull(h.pipeline.stateForTest().pending());
            release.countDown();
            h.awaitPasses(1);
            h.barrier();

            assertEquals(Boolean.FALSE, fenceRead.get());
            assertEquals(0, submits.get());
            assertEquals(List.of("L 0/5:a"), h.calls(), "no later stage, and the old pending point never ran");
            assertNull(h.pipeline.stateForTest().lastHandled(), "an old-generation pass is not the handled point");
        }
    }

    // (g)
    @Test
    void aSubmitAlreadyInProgressFinishesIsHonouredAndTheNextPassStartsOnlyAfterItReturns() throws Exception {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch inCall = new CountDownLatch(1);
        CountDownLatch releaseCall = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        try (Harness h = new Harness()) {
            h.start(
                    fence -> {
                        if (first.getAndSet(false) && fence.mayProceed()) {
                            events.add("submit call begins " + h.point());
                            inCall.countDown();
                            assertTrue(releaseCall.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
                            events.add("submit call returns");
                            return WorkResult.SUBMITTED;
                        }
                        events.add("L " + h.point());
                        return WorkResult.DEFINITE_FAILURE;
                    },
                    fence -> { events.add("C " + h.point()); return WorkResult.DEFINITE_FAILURE; },
                    fence -> { events.add("H " + h.point()); return WorkResult.DEFINITE_FAILURE; });

            h.pipeline.offerBlock(1, "a");
            assertTrue(inCall.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
            h.pipeline.rollback();
            h.pipeline.offerBlock(1, "b");
            events.add("rollback and newer offer made");
            releaseCall.countDown();
            h.awaitPasses(2);
            h.barrier();

            assertEquals(List.of(
                    "submit call begins 0/1:a",
                    "rollback and newer offer made",
                    "submit call returns",
                    "L 1/1:b", "C 1/1:b", "H 1/1:b"), events);
            assertEquals(1, count(Level.INFO, "wallet pipeline: liquidation SUBMITTED at slot 1"));
            assertEquals(new WalletPipeline.ChainPointView(1, 1, "b"), h.pipeline.stateForTest().lastHandled());
        }
    }

    // (h)
    @Test
    void aFenceFromAnOldGenerationPassAnswersFalseWhileTheNewPassesOwnFenceAnswersTrue() throws Exception {
        AtomicReference<SubmitFence> oldFence = new AtomicReference<>();
        AtomicReference<Boolean> oldDuringNewPass = new AtomicReference<>();
        AtomicReference<Boolean> ownDuringNewPass = new AtomicReference<>();
        try (Harness h = new Harness()) {
            h.start(
                    fence -> {
                        h.record("L");
                        if (oldFence.compareAndSet(null, fence)) {
                            return WorkResult.DEFINITE_FAILURE;
                        }
                        oldDuringNewPass.set(oldFence.get().mayProceed());
                        ownDuringNewPass.set(fence.mayProceed());
                        return WorkResult.DEFINITE_FAILURE;
                    },
                    fence -> WorkResult.DEFINITE_FAILURE,
                    fence -> WorkResult.DEFINITE_FAILURE);

            h.pipeline.offerBlock(1, "a");
            h.awaitPasses(1);
            h.barrier();
            h.pipeline.rollback();
            assertFalse(oldFence.get().mayProceed());
            h.pipeline.offerBlock(2, "b");
            h.awaitPasses(1);
            h.barrier();

            assertEquals(List.of("L 0/1:a", "L 1/2:b"), h.calls());
            assertEquals(Boolean.FALSE, oldDuringNewPass.get());
            assertEquals(Boolean.TRUE, ownDuringNewPass.get());
        }
    }

    // (h), literal: no rollback — a fence belongs to its pass, not only to its generation
    @Test
    void aFenceFromAnEndedPassAnswersFalseDuringTheNextPassOfTheSameGenerationWhileItsOwnAnswersTrue() throws Exception {
        AtomicReference<SubmitFence> firstFence = new AtomicReference<>();
        AtomicReference<Boolean> firstDuringItsOwnPass = new AtomicReference<>();
        AtomicReference<Boolean> firstDuringSecondPass = new AtomicReference<>();
        AtomicReference<Boolean> ownDuringSecondPass = new AtomicReference<>();
        try (Harness h = new Harness()) {
            h.start(
                    fence -> {
                        h.record("L");
                        if (firstFence.compareAndSet(null, fence)) {
                            firstDuringItsOwnPass.set(fence.mayProceed());
                            return WorkResult.DEFINITE_FAILURE;
                        }
                        firstDuringSecondPass.set(firstFence.get().mayProceed());
                        ownDuringSecondPass.set(fence.mayProceed());
                        return WorkResult.DEFINITE_FAILURE;
                    },
                    fence -> WorkResult.DEFINITE_FAILURE,
                    fence -> WorkResult.DEFINITE_FAILURE);

            h.pipeline.offerBlock(1, "a");
            h.awaitPasses(1);
            h.barrier();
            h.pipeline.offerBlock(2, "b");
            h.awaitPasses(1);
            h.barrier();

            assertEquals(List.of("L 0/1:a", "L 0/2:b"), h.calls());
            assertEquals(0, h.pipeline.stateForTest().generation(), "no rollback happened");
            assertEquals(Boolean.TRUE, firstDuringItsOwnPass.get());
            assertEquals(Boolean.FALSE, firstDuringSecondPass.get());
            assertEquals(Boolean.TRUE, ownDuringSecondPass.get());
        }
    }

    @Test
    void aPassesOwnFenceAnswersFalseOnceThePassHasEndedAndTheEngineIsIdle() throws Exception {
        AtomicReference<SubmitFence> captured = new AtomicReference<>();
        AtomicReference<Boolean> duringItsPass = new AtomicReference<>();
        try (Harness h = new Harness()) {
            h.start(
                    fence -> {
                        h.record("L");
                        captured.set(fence);
                        duringItsPass.set(fence.mayProceed());
                        return WorkResult.DEFINITE_FAILURE;
                    },
                    fence -> WorkResult.DEFINITE_FAILURE,
                    fence -> WorkResult.DEFINITE_FAILURE);

            h.pipeline.offerBlock(1, "a");
            h.awaitPasses(1);
            h.barrier();

            assertEquals(List.of("L 0/1:a"), h.calls());
            assertNull(h.pipeline.stateForTest().inFlight(), "the engine is idle");
            assertFalse(h.pipeline.stateForTest().closed());
            assertEquals(0, h.pipeline.stateForTest().generation(), "no rollback happened");
            assertEquals(Boolean.TRUE, duringItsPass.get());
            assertFalse(captured.get().mayProceed());
        }
    }

    @Test
    void aFenceFromAPassAnswersFalseDuringThePriceReRunOfTheSamePoint() throws Exception {
        AtomicReference<SubmitFence> firstFence = new AtomicReference<>();
        AtomicReference<Boolean> firstDuringReRun = new AtomicReference<>();
        AtomicReference<Boolean> ownDuringReRun = new AtomicReference<>();
        try (Harness h = new Harness()) {
            h.start(
                    fence -> {
                        h.record("L");
                        if (firstFence.compareAndSet(null, fence)) {
                            return WorkResult.DEFINITE_FAILURE;
                        }
                        firstDuringReRun.set(firstFence.get().mayProceed());
                        ownDuringReRun.set(fence.mayProceed());
                        return WorkResult.DEFINITE_FAILURE;
                    },
                    fence -> WorkResult.DEFINITE_FAILURE,
                    fence -> WorkResult.DEFINITE_FAILURE);

            h.pipeline.offerBlock(5, "a");
            h.awaitPasses(1);
            h.barrier();
            h.pipeline.offerPriceChange();
            h.awaitPasses(1);
            h.barrier();

            assertEquals(List.of("L 0/5:a", "L 0/5:a"), h.calls(), "the re-run is the very same point");
            assertEquals(Boolean.FALSE, firstDuringReRun.get());
            assertEquals(Boolean.TRUE, ownDuringReRun.get());
        }
    }

    // (i)
    @Test
    void theDeadlineExpiringJustBeforeASubmitClosesTheFenceAndOnlyTheLatestPendingPointIsKept() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        AtomicReference<Boolean> fenceRead = new AtomicReference<>();
        AtomicInteger submits = new AtomicInteger();
        try (Harness h = new Harness()) {
            h.start(
                    fence -> {
                        h.record("L");
                        if (first.getAndSet(false)) {
                            entered.countDown();
                            assertTrue(release.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
                            h.currentDeadline.get().expire();
                            boolean may = fence.mayProceed();
                            fenceRead.set(may);
                            if (may) {
                                submits.incrementAndGet();
                            }
                        }
                        return WorkResult.DEFINITE_FAILURE;
                    },
                    fence -> { h.record("C"); return WorkResult.DEFINITE_FAILURE; },
                    fence -> { h.record("H"); return WorkResult.DEFINITE_FAILURE; });

            h.pipeline.offerBlock(1, "a");
            assertTrue(entered.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
            h.pipeline.offerBlock(2, "b");
            h.pipeline.offerBlock(3, "c");
            release.countDown();
            h.awaitPasses(2);
            h.barrier();

            assertEquals(Boolean.FALSE, fenceRead.get());
            assertEquals(0, submits.get());
            assertEquals(List.of("L 0/1:a", "L 0/3:c", "C 0/3:c", "H 0/3:c"), h.calls());
            assertEquals(0, h.passesEnded.availablePermits());
        }
    }

    // (j)
    @Test
    void aThrowingStageEndsItsPassAndTheNextOfferStillRunsAPass() throws Exception {
        AtomicInteger liquidations = new AtomicInteger();
        AtomicInteger compounds = new AtomicInteger();
        try (Harness h = new Harness()) {
            h.start(
                    fence -> {
                        h.record("L");
                        if (liquidations.incrementAndGet() == 1) {
                            throw new IllegalStateException("liquidation fixture failure");
                        }
                        return WorkResult.DEFINITE_FAILURE;
                    },
                    fence -> {
                        h.record("C");
                        if (compounds.incrementAndGet() == 1) {
                            throw new java.io.IOException("compound fixture failure");
                        }
                        return WorkResult.DEFINITE_FAILURE;
                    },
                    fence -> { h.record("H"); return WorkResult.DEFINITE_FAILURE; });

            h.pipeline.offerBlock(1, "a");
            h.awaitPasses(1);
            h.barrier();
            assertEquals(List.of("L 0/1:a"), h.calls());
            assertEquals(1, count(Level.ERROR, "wallet pipeline: the pass at slot 1 (a) failed"));

            h.pipeline.offerBlock(2, "b");
            h.awaitPasses(1);
            h.barrier();
            assertEquals(List.of("L 0/1:a", "L 0/2:b", "C 0/2:b"), h.calls());
            assertEquals(1, count(Level.ERROR, "wallet pipeline: the pass at slot 2 (b) failed"));

            h.pipeline.offerBlock(3, "c");
            h.awaitPasses(1);
            h.barrier();
            assertEquals(List.of("L 0/1:a", "L 0/2:b", "C 0/2:b", "L 0/3:c", "C 0/3:c", "H 0/3:c"), h.calls());
            assertEquals(2, count(Level.ERROR, "wallet pipeline:"));
            assertEquals(new WalletPipeline.ChainPointView(0, 3, "c"), h.pipeline.stateForTest().lastHandled());
        }
    }

    // (k)
    @Test
    void aHungPassSaysOneWarnAndNoSecondPassIsStarted() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch enteredAgain = new CountDownLatch(1);
        CountDownLatch releaseAgain = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        try (Harness h = new Harness()) {
            WalletPipeline.Stage tracked = fence -> {
                maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
                try {
                    h.record("S");
                    return WorkResult.DEFINITE_FAILURE;
                } finally {
                    active.decrementAndGet();
                }
            };
            h.start(
                    fence -> {
                        maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
                        try {
                            h.record("L");
                            long slot = h.pipeline.stateForTest().inFlight().slot();
                            if (slot == 1) {
                                entered.countDown();
                                assertTrue(release.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
                            } else if (slot == 10) {
                                enteredAgain.countDown();
                                assertTrue(releaseAgain.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
                            }
                            return WorkResult.DEFINITE_FAILURE;
                        } finally {
                            active.decrementAndGet();
                        }
                    },
                    tracked,
                    tracked);

            h.pipeline.offerBlock(1, "a");
            assertTrue(entered.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
            h.clock.advance(HUNG);
            h.pipeline.offerBlock(2, "b");     // exactly hungAfter: not yet hung
            assertEquals(0, count(Level.WARN, "wallet pipeline:"));

            h.clock.advance(Duration.ofSeconds(1));
            h.pipeline.offerBlock(3, "c");
            h.pipeline.offerBlock(4, "d");
            h.pipeline.offerPriceChange();
            h.pipeline.offerBlock(4, "d");
            assertEquals(1, count(Level.WARN, "wallet pipeline:"));
            assertEquals(1, count(Level.WARN, "wallet pipeline: the pass at slot 1 has been running for 31 s; "
                    + "this trigger is held — a second pass is never started"));
            assertEquals(List.of("L 0/1:a"), h.calls(), "no second pass while the first is running");

            release.countDown();
            h.awaitPasses(2);
            h.barrier();
            assertEquals(List.of("L 0/1:a", "S 0/1:a", "S 0/1:a", "L 0/4:d", "S 0/4:d", "S 0/4:d"), h.calls());
            assertEquals(1, maxActive.get());
            assertEquals(1, count(Level.WARN, "wallet pipeline:"));

            // a later pass that hangs gets its own single WARN
            h.pipeline.offerBlock(10, "x");
            assertTrue(enteredAgain.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
            h.clock.advance(Duration.ofSeconds(45));
            h.pipeline.offerBlock(11, "y");
            h.pipeline.offerBlock(12, "z");
            assertEquals(2, count(Level.WARN, "wallet pipeline:"));
            assertEquals(1, count(Level.WARN, "wallet pipeline: the pass at slot 10 has been running for 45 s"));
            releaseAgain.countDown();
            h.awaitPasses(2);
            h.barrier();
            assertEquals(1, maxActive.get());
        }
    }

    // (l)
    @Test
    void afterCloseOffersRunNothingAndCloseNeverThrows() throws Exception {
        try (Harness h = new Harness()) {
            h.start(
                    fence -> { h.record("L"); return WorkResult.DEFINITE_FAILURE; },
                    fence -> { h.record("C"); return WorkResult.DEFINITE_FAILURE; },
                    fence -> { h.record("H"); return WorkResult.DEFINITE_FAILURE; });
            h.pipeline.offerBlock(1, "a");
            h.awaitPasses(1);
            h.barrier();
            int executes = h.executes.get();

            h.pipeline.close();
            h.pipeline.offerBlock(2, "b");
            h.pipeline.offerPriceChange();
            h.pipeline.rollback();
            h.pipeline.offerBlock(3, "c");
            h.pipeline.close();

            assertTrue(h.delegate.awaitTermination(WAIT.toMillis(), TimeUnit.MILLISECONDS));
            assertEquals(executes, h.executes.get(), "a closed pipeline schedules nothing");
            assertEquals(List.of("L 0/1:a", "C 0/1:a", "H 0/1:a"), h.calls());
            assertEquals(0, h.pipeline.stateForTest().generation(), "a closed pipeline ignores rollback too");
        }
    }

    @Test
    void closingDuringAPassClosesItsFenceAndWaitsForItToEnd() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Boolean> fenceRead = new AtomicReference<>();
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        try (Harness h = new Harness()) {
            h.start(
                    fence -> {
                        h.record("L");
                        entered.countDown();
                        assertTrue(release.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
                        fenceRead.set(fence.mayProceed());
                        return WorkResult.DEFINITE_FAILURE;
                    },
                    fence -> { h.record("C"); return WorkResult.DEFINITE_FAILURE; },
                    fence -> { h.record("H"); return WorkResult.DEFINITE_FAILURE; });
            h.pipeline.offerBlock(1, "a");
            assertTrue(entered.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
            h.pipeline.offerBlock(2, "b");

            Thread closer = new Thread(() -> {
                try {
                    h.pipeline.close();
                } catch (Throwable t) {
                    closeFailure.set(t);
                }
            }, "closer");
            closer.start();
            await(() -> h.pipeline.stateForTest().closed());
            assertNull(h.pipeline.stateForTest().pending(), "close drops pending work");
            release.countDown();
            closer.join(WAIT.toMillis());
            assertFalse(closer.isAlive(), "close returned once the running pass ended");

            assertNull(closeFailure.get());
            assertEquals(Boolean.FALSE, fenceRead.get());
            assertEquals(List.of("L 0/1:a"), h.calls());
            assertTrue(h.delegate.isTerminated());
        }
    }

    // (m)
    @Test
    void realDrippingLoopbackUsesPublicConstructorAndActualSdkSubmit() throws Exception {
        DripServer server = new DripServer();
        try (server) {
            BoundedBFBackendService backend = new BoundedBFBackendService(
                    server.url(), BlockfrostKey.of("fake-key"), 1, 5, 5, 2, 2, 1, 2);
            List<String> events = Collections.synchronizedList(new ArrayList<>());
            Set<Thread> stageThreads = ConcurrentHashMap.newKeySet();
            AtomicReference<WorkResult> outcome = new AtomicReference<>();
            AtomicBoolean first = new AtomicBoolean(true);
            CountDownLatch newerPassDone = new CountDownLatch(1);
            Thread offering = Thread.currentThread();

            WalletPipeline.Stage liquidation = fence -> {
                stageThreads.add(Thread.currentThread());
                if (first.getAndSet(false)) {
                    assertTrue(fence.mayProceed());
                    events.add("submit call begins");
                    WorkResult result;
                    try {
                        Result<String> submitted = backend.getTransactionService().submitTransaction(new byte[]{1, 2, 3});
                        result = WorkResult.classify(submitted);
                    } catch (Exception transportFailure) {
                        result = WorkResult.AMBIGUOUS;
                    }
                    events.add("submit call returns");
                    outcome.set(result);
                    return result;
                }
                events.add("newer pass begins");
                return WorkResult.DEFINITE_FAILURE;
            };
            WalletPipeline.Stage compound = fence -> { stageThreads.add(Thread.currentThread()); return WorkResult.DEFINITE_FAILURE; };
            WalletPipeline.Stage housekeeping = fence -> {
                stageThreads.add(Thread.currentThread());
                newerPassDone.countDown();
                return WorkResult.DEFINITE_FAILURE;
            };

            try (WalletPipeline pipeline = new WalletPipeline(backend, liquidation, compound, housekeeping, HUNG)) {
                pipeline.offerBlock(1, "old");
                assertTrue(server.alive.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), "the submit reached the wire");
                pipeline.offerBlock(2, "new");
                events.add("newer point offered");
                assertTrue(newerPassDone.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
            }

            assertEquals(List.of(
                    "submit call begins",
                    "newer point offered",
                    "submit call returns",
                    "newer pass begins"), events);
            assertEquals(WorkResult.AMBIGUOUS, outcome.get());
            assertEquals(1, server.posts.get());
            assertEquals(1, stageThreads.size());
            Thread worker = stageThreads.iterator().next();
            assertEquals("wallet-pipeline", worker.getName());
            assertTrue(worker.isDaemon());
            assertNotEquals(offering, worker);
            assertEquals(1, count(Level.INFO, "wallet pipeline: liquidation AMBIGUOUS at slot 1"));
        }
        assertTrue(server.done.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
        assertFalse(server.thread.isAlive());
    }

    // (n)
    @Test
    void aRollbackWhileCompoundRunsKeepsHousekeepingFromRunning() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Boolean> compoundFenceAfterRelease = new AtomicReference<>();
        try (Harness h = new Harness()) {
            h.start(
                    fence -> { h.record("L"); return WorkResult.DEFINITE_FAILURE; },
                    fence -> {
                        h.record("C");
                        entered.countDown();
                        assertTrue(release.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
                        compoundFenceAfterRelease.set(fence.mayProceed());
                        return WorkResult.DEFINITE_FAILURE;
                    },
                    fence -> { h.record("H"); return WorkResult.DEFINITE_FAILURE; });

            h.pipeline.offerBlock(1, "a");
            assertTrue(entered.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
            h.pipeline.rollback();
            release.countDown();
            h.awaitPasses(1);
            h.barrier();

            assertEquals(Boolean.FALSE, compoundFenceAfterRelease.get());
            assertEquals(List.of("L 0/1:a", "C 0/1:a"), h.calls(), "housekeeping never runs");
            assertEquals(0, h.passesEnded.availablePermits());
        }
    }

    // (o)
    @Test
    void theDeadlineExpiringWhileCompoundRunsKeepsHousekeepingFromRunning() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Boolean> compoundFenceAfterRelease = new AtomicReference<>();
        try (Harness h = new Harness()) {
            h.start(
                    fence -> { h.record("L"); return WorkResult.DEFINITE_FAILURE; },
                    fence -> {
                        h.record("C");
                        entered.countDown();
                        assertTrue(release.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
                        compoundFenceAfterRelease.set(fence.mayProceed());
                        return WorkResult.DEFINITE_FAILURE;
                    },
                    fence -> { h.record("H"); return WorkResult.DEFINITE_FAILURE; });

            h.pipeline.offerBlock(1, "a");
            assertTrue(entered.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
            h.currentDeadline.get().expire();
            release.countDown();
            h.awaitPasses(1);
            h.barrier();

            assertEquals(Boolean.FALSE, compoundFenceAfterRelease.get());
            assertEquals(0, h.pipeline.stateForTest().generation(), "no rollback happened");
            assertEquals(List.of("L 0/1:a", "C 0/1:a"), h.calls(), "housekeeping never runs");
            assertEquals(0, h.passesEnded.availablePermits());
        }
    }

    // (p)
    @Test
    void afterARollbackAnOlderSlotRunsBecauseOnlyACurrentGenerationInFlightPointCounts() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        try (Harness h = new Harness()) {
            h.start(
                    fence -> {
                        h.record("L");
                        if (first.getAndSet(false)) {
                            entered.countDown();
                            assertTrue(release.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
                        }
                        return WorkResult.DEFINITE_FAILURE;
                    },
                    fence -> WorkResult.DEFINITE_FAILURE,
                    fence -> WorkResult.DEFINITE_FAILURE);

            h.pipeline.offerBlock(5, "a");
            assertTrue(entered.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
            h.pipeline.rollback();
            h.pipeline.offerBlock(3, "r");
            assertEquals(new WalletPipeline.ChainPointView(1, 3, "r"), h.pipeline.stateForTest().pending(),
                    "the old generation's in-flight slot 5 does not make slot 3 older");
            release.countDown();
            h.awaitPasses(2);
            h.barrier();

            assertEquals(List.of("L 0/5:a", "L 1/3:r"), h.calls());
            assertEquals(0, h.passesEnded.availablePermits());
            assertEquals(new WalletPipeline.ChainPointView(1, 3, "r"), h.pipeline.stateForTest().lastHandled());
        }
    }

    private long count(Level level, String fragment) {
        return logs.list.stream()
                .filter(e -> e.getLevel() == level)
                .filter(e -> e.getFormattedMessage().contains(fragment))
                .count();
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < until) {
            Thread.sleep(5);
        }
        assertTrue(condition.getAsBoolean(), "condition timed out");
    }

    /**
     * A pipeline on a test-owned single worker, a fresh controllable deadline per pass, a
     * controllable clock, a count of every task the pipeline hands its worker, and a barrier: a no-op
     * queued behind whatever the pipeline scheduled, so once it has run the pipeline is idle.
     */
    private static final class Harness implements AutoCloseable {
        private final ExecutorService delegate = Executors.newSingleThreadExecutor(r -> new Thread(r, "pipeline-test-worker"));
        private final AtomicInteger executes = new AtomicInteger();
        private final Semaphore passesEnded = new Semaphore(0);
        private final AtomicReference<MutableDeadline> currentDeadline = new AtomicReference<>();
        private final MutableClock clock = new MutableClock(Instant.parse("2026-11-01T00:00:00Z"));
        private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        private final AtomicReference<Thread> worker = new AtomicReference<>();
        private WalletPipeline pipeline;

        void start(WalletPipeline.Stage liquidation, WalletPipeline.Stage compound, WalletPipeline.Stage housekeeping) {
            ExecutorService counting = new AbstractExecutorService() {
                @Override public void execute(Runnable command) {
                    executes.incrementAndGet();
                    delegate.execute(() -> {
                        worker.set(Thread.currentThread());
                        command.run();
                    });
                }
                @Override public void shutdown() { delegate.shutdown(); }
                @Override public List<Runnable> shutdownNow() { return delegate.shutdownNow(); }
                @Override public boolean isShutdown() { return delegate.isShutdown(); }
                @Override public boolean isTerminated() { return delegate.isTerminated(); }
                @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
                    return delegate.awaitTermination(timeout, unit);
                }
            };
            pipeline = new WalletPipeline(body -> {
                MutableDeadline deadline = new MutableDeadline();
                currentDeadline.set(deadline);
                try {
                    body.accept(deadline);
                } finally {
                    passesEnded.release();
                }
            }, liquidation, compound, housekeeping, HUNG, counting, clock);
        }

        String point() {
            WalletPipeline.ChainPointView p = pipeline.stateForTest().inFlight();
            return p.generation() + "/" + p.slot() + ":" + p.blockHash();
        }

        void record(String stage) {
            calls.add(stage + " " + point());
        }

        List<String> calls() {
            synchronized (calls) {
                return List.copyOf(calls);
            }
        }

        void clearCalls() {
            calls.clear();
        }

        Thread workerThread() {
            return worker.get();
        }

        void awaitPasses(int n) throws InterruptedException {
            assertTrue(passesEnded.tryAcquire(n, WAIT.toMillis(), TimeUnit.MILLISECONDS), n + " pass(es) expected");
        }

        void barrier() throws Exception {
            delegate.submit(() -> { }).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public void close() {
            if (pipeline != null) {
                pipeline.close();
            }
            delegate.shutdownNow();
        }
    }

    private static final class MutableDeadline implements BoundedBFBackendService.PassDeadline {
        private final AtomicBoolean expired = new AtomicBoolean();

        void expire() {
            expired.set(true);
        }

        @Override public boolean isExpired() { return expired.get(); }

        @Override public long remainingNanos() { return expired.get() ? 0 : Long.MAX_VALUE / 4; }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        MutableClock(Instant start) {
            now = new AtomicReference<>(start);
        }

        void advance(Duration by) {
            now.updateAndGet(i -> i.plus(by));
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }

        @Override public Clock withZone(ZoneId zone) { return this; }

        @Override public Instant instant() { return now.get(); }
    }

    /** One owned loopback socket that accepts a single request and drips its response forever. */
    private static final class DripServer implements AutoCloseable {
        private final ServerSocket server;
        private final AtomicReference<Socket> active = new AtomicReference<>();
        private final AtomicInteger posts = new AtomicInteger();
        private final CountDownLatch alive = new CountDownLatch(1);
        private final CountDownLatch done = new CountDownLatch(1);
        private final Thread thread;

        private DripServer() throws IOException {
            server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            thread = new Thread(this::serve, "drip-handler");
            thread.start();
        }

        private String url() {
            return "http://127.0.0.1:" + server.getLocalPort() + "/api/v0/";
        }

        private void serve() {
            try (Socket socket = server.accept()) {
                active.set(socket);
                BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
                String first = line(in);
                String header;
                int length = 0;
                while (!(header = line(in)).isEmpty()) {
                    if (header.toLowerCase().startsWith("content-length:")) {
                        length = Integer.parseInt(header.substring(header.indexOf(':') + 1).trim());
                    }
                }
                in.readNBytes(length);
                if (first.startsWith("POST /api/v0/tx/submit ")) {
                    posts.incrementAndGet();
                }
                alive.countDown();
                OutputStream out = socket.getOutputStream();
                out.write(("HTTP/1.1 400 Bad Request\r\nContent-Type: application/json\r\n"
                        + "Content-Length: 100000\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.flush();
                while (true) {
                    out.write(' ');
                    out.flush();
                    Thread.sleep(100);
                }
            } catch (IOException | InterruptedException expected) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        }

        private static String line(BufferedInputStream in) throws IOException {
            StringBuilder s = new StringBuilder();
            int prev = -1;
            int n;
            while ((n = in.read()) != -1) {
                if (prev == '\r' && n == '\n') {
                    s.setLength(s.length() - 1);
                    break;
                }
                s.append((char) n);
                prev = n;
            }
            return s.toString();
        }

        @Override
        public void close() throws Exception {
            server.close();
            Socket socket = active.get();
            if (socket != null) {
                socket.close();
            }
            thread.join(WAIT.toMillis());
            assertFalse(thread.isAlive());
        }
    }
}
