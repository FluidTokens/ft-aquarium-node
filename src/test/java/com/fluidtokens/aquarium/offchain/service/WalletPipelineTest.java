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

            try (WalletPipeline pipeline = new WalletPipeline(backend, liquidation, compound, housekeeping, HUNG, true)) {
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

    /**
     * FAB-186 (i): one summary line per pass — the stage outcomes, or {@code SKIPPED} (FAB-195), and the Blockfrost
     * calls the pass's deadline counted, by kind. INFO when a stage's outcome is neither NO_OP nor SKIPPED or
     * the pass evaluated or submitted anything; DEBUG for a pass that only discovered. FAB-195 (b): that rule is
     * what {@code logEveryPass=false} keeps.
     */
    @Test
    void eachPassEndsWithOneSummaryLineCarryingTheStageResultsAndTheBlockfrostCalls() throws Exception {
        try (Harness h = new Harness()) {
            h.logEveryPass = false;
            h.start(
                    fence -> {
                        h.record("L");
                        long slot = h.pipeline.stateForTest().inFlight().slot();
                        h.currentDeadline.get().counts.set(slot == 1
                                ? new BoundedBFBackendService.CallCounts(3, 1, 0)
                                : slot == 2 ? new BoundedBFBackendService.CallCounts(5, 2, 1)
                                : new BoundedBFBackendService.CallCounts(4, 0, 0));
                        return slot == 2 ? WorkResult.SUBMITTED : WorkResult.DEFINITE_FAILURE;
                    },
                    fence -> { h.record("C"); return WorkResult.DEFINITE_FAILURE; },
                    fence -> { h.record("H"); return WorkResult.DEFINITE_FAILURE; });

            // Every stage a definite failure, but the pass EVALUATED once: INFO.
            h.pipeline.offerBlock(1, "a");
            h.awaitPasses(1);
            h.barrier();
            assertEquals(1, count(Level.INFO, "wallet pipeline pass #1 at slot 1 (a), trigger block, 0 block(s) folded "
                    + "in, waited 0.0 s, took 0.0 s: liquidation NO_OP, "
                    + "compound NO_OP, housekeeping NO_OP; Blockfrost calls discovery 3, "
                    + "evaluate 1, submit 0"), logs.list.toString());

            // A liquidation SUBMITTED ends the pass: the later stages are SKIPPED, and the line is INFO.
            h.pipeline.offerBlock(2, "b");
            h.awaitPasses(1);
            h.barrier();
            assertEquals(1, count(Level.INFO, "wallet pipeline pass #2 at slot 2 (b), trigger block, 0 block(s) folded "
                    + "in, waited 0.0 s, took 0.0 s: liquidation SUBMITTED, "
                    + "compound SKIPPED, housekeeping SKIPPED; Blockfrost calls discovery 5, evaluate 2, submit 1"),
                    logs.list.toString());

            // Every stage NO_OP and nothing was evaluated or submitted: DEBUG, not INFO.
            h.pipeline.offerBlock(3, "c");
            h.awaitPasses(1);
            h.barrier();
            String quiet = "wallet pipeline pass #3 at slot 3 (c), trigger block, 0 block(s) folded in, waited 0.0 s, "
                    + "took 0.0 s: liquidation NO_OP, compound "
                    + "NO_OP, housekeeping NO_OP; Blockfrost calls discovery 4, evaluate 0, submit 0";
            assertEquals(1, count(Level.DEBUG, quiet), logs.list.toString());
            assertEquals(0, count(Level.INFO, "wallet pipeline pass #3 at slot 3"), logs.list.toString());
            assertEquals(3, logs.list.stream()
                    .filter(e -> e.getFormattedMessage().startsWith("wallet pipeline pass #")).count(),
                    "exactly one summary line per pass");
        }
    }

    /** FAB-186 (i): a deadline that counts nothing still gets its summary line, with zeros. */
    @Test
    void aDeadlineThatCountsNothingSummarisesZeroCalls() throws Exception {
        try (Harness h = new Harness()) {
            h.logEveryPass = false;
            h.start(
                    fence -> { h.record("L"); return WorkResult.WOULD_SUBMIT; },
                    fence -> { h.record("C"); return WorkResult.DEFINITE_FAILURE; },
                    fence -> { h.record("H"); return WorkResult.DEFINITE_FAILURE; });
            h.pipeline.offerBlock(9, "z");
            h.awaitPasses(1);
            h.barrier();
            assertEquals(1, count(Level.INFO, "wallet pipeline pass #1 at slot 9 (z), trigger block, 0 block(s) folded "
                    + "in, waited 0.0 s, took 0.0 s: liquidation WOULD_SUBMIT, "
                    + "compound NO_OP, housekeeping NO_OP; Blockfrost calls discovery 0, "
                    + "evaluate 0, submit 0"), logs.list.toString());
        }
    }

    // ---- FAB-195: one line per pass, with its trigger, folds, wait and duration ------------------------------------

    /**
     * FAB-195 (a): with {@code logEveryPass} on, a pass where every stage is NO_OP and nothing was evaluated or
     * submitted still says its summary at INFO, exactly once; and a pass whose stage threw still gets its one line,
     * the stage ERROR with one error and the later stages SKIPPED.
     */
    @Test
    void withLogEveryPassAQuietPassSaysItsSummaryAtInfoExactlyOnce() throws Exception {
        AtomicInteger liquidations = new AtomicInteger();
        try (Harness h = new Harness()) {
            h.start(
                    fence -> {
                        h.record("L");
                        if (liquidations.incrementAndGet() == 2) {
                            throw new IllegalStateException("liquidation fixture failure");
                        }
                        return WorkResult.DEFINITE_FAILURE;
                    },
                    fence -> { h.record("C"); return WorkResult.DEFINITE_FAILURE; },
                    fence -> { h.record("H"); return WorkResult.DEFINITE_FAILURE; });

            h.pipeline.offerBlock(1, "a");
            h.awaitPasses(1);
            h.barrier();
            assertEquals(List.of("wallet pipeline pass #1 at slot 1 (a), trigger block, 0 block(s) folded in, waited "
                            + "0.0 s, took 0.0 s: liquidation NO_OP, compound NO_OP, housekeeping "
                            + "NO_OP; Blockfrost calls discovery 0, evaluate 0, submit 0"),
                    summaries(Level.INFO), logs.list.toString());
            assertEquals(List.of(), summaries(Level.DEBUG), logs.list.toString());

            h.pipeline.offerBlock(2, "b");
            h.awaitPasses(1);
            h.barrier();
            assertEquals(1, count(Level.ERROR, "wallet pipeline: the pass at slot 2 (b) failed"));
            assertEquals(List.of("wallet pipeline pass #2 at slot 2 (b), trigger block, 0 block(s) folded in, waited "
                            + "0.0 s, took 0.0 s: liquidation ERROR (error 1), compound SKIPPED, housekeeping SKIPPED; "
                            + "Blockfrost calls discovery 0, evaluate 0, submit 0"),
                    summaries(Level.INFO).subList(1, summaries(Level.INFO).size()), logs.list.toString());
            assertEquals(2, summaries().size(), "exactly one summary line per pass");
        }
    }

    /**
     * FAB-195 (c), (f): the trigger says what started the pass — a block alone {@code block}; a price change with
     * nothing pending {@code price}, at the SAME slot; a block and a price change both queued while a pass runs
     * {@code block+price}. The pass number goes up by one per pass.
     */
    @Test
    void theTriggerSaysWhatStartedThePassAndThePassNumberCountsUp() throws Exception {
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
                    fence -> WorkResult.DEFINITE_FAILURE,
                    fence -> WorkResult.DEFINITE_FAILURE);

            h.pipeline.offerBlock(5, "a");
            h.awaitPasses(1);
            h.barrier();

            h.pipeline.offerPriceChange();
            h.awaitPasses(1);
            h.barrier();

            block.set(true);
            h.pipeline.offerBlock(6, "b");
            assertTrue(entered.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
            h.pipeline.offerBlock(7, "c");
            h.pipeline.offerPriceChange();
            release.countDown();
            h.awaitPasses(2);
            h.barrier();

            assertEquals(List.of("L 0/5:a", "L 0/5:a", "L 0/6:b", "L 0/7:c"), h.calls());
            assertEquals(List.of(
                    "wallet pipeline pass #1 at slot 5 (a), trigger block, 0 block(s) folded in",
                    "wallet pipeline pass #2 at slot 5 (a), trigger price, 0 block(s) folded in",
                    "wallet pipeline pass #3 at slot 6 (b), trigger block, 0 block(s) folded in",
                    "wallet pipeline pass #4 at slot 7 (c), trigger block+price, 0 block(s) folded in"), heads());
        }
    }

    /**
     * FAB-195 (d): three blocks offered while a pass runs fold into one pass on the newest, which says
     * {@code 2 block(s) folded in}; an offer the pipeline drops (equal or older) is not a fold; the pass after a lone
     * block says {@code 0}.
     */
    @Test
    void theBlocksANewerBlockReplacedAreCountedAsFoldedIn() throws Exception {
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
            h.pipeline.offerBlock(10, "a");   // duplicate of the in-flight point: dropped
            h.pipeline.offerBlock(9, "x");    // older than the in-flight point: dropped
            h.pipeline.offerBlock(11, "b");
            h.pipeline.offerBlock(12, "c");
            h.pipeline.offerBlock(12, "c");   // duplicate of the pending point: dropped
            h.pipeline.offerBlock(11, "y");   // older than the pending point: dropped
            h.pipeline.offerBlock(13, "d");
            assertEquals(new WalletPipeline.ChainPointView(0, 13, "d"), h.pipeline.stateForTest().pending());
            release.countDown();
            h.awaitPasses(2);
            h.barrier();

            h.pipeline.offerBlock(14, "e");
            h.awaitPasses(1);
            h.barrier();

            assertEquals(List.of("L 0/10:a", "L 0/13:d", "L 0/14:e"), h.calls());
            assertEquals(List.of(
                    "wallet pipeline pass #1 at slot 10 (a), trigger block, 0 block(s) folded in",
                    "wallet pipeline pass #2 at slot 13 (d), trigger block, 2 block(s) folded in",
                    "wallet pipeline pass #3 at slot 14 (e), trigger block, 0 block(s) folded in"), heads());
        }
    }

    /**
     * FAB-195 (e): {@code waited} runs from the first trigger no pass has served to the pass start, and {@code took}
     * from the pass start to its end, both on the pipeline's clock. A trigger at t, the pass starting at t+3 s and
     * ending at t+5 s says {@code waited 3.0 s, took 2.0 s}. A price change is a trigger too, and the FIRST trigger
     * counts, not the last.
     */
    @Test
    void waitedRunsFromTheFirstUnservedTriggerAndTookIsThePassesOwnDuration() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        Semaphore release = new Semaphore(0);
        AtomicBoolean block = new AtomicBoolean(true);
        try (Harness h = new Harness()) {
            h.start(
                    fence -> {
                        h.record("L");
                        if (block.getAndSet(false)) {
                            entered.countDown();
                            assertTrue(release.tryAcquire(WAIT.toMillis(), TimeUnit.MILLISECONDS));
                        } else {
                            h.clock.advance(Duration.ofSeconds(2));
                        }
                        return WorkResult.DEFINITE_FAILURE;
                    },
                    fence -> WorkResult.DEFINITE_FAILURE,
                    fence -> WorkResult.DEFINITE_FAILURE);

            h.pipeline.offerBlock(1, "a");
            await(() -> entered.getCount() == 1);
            h.pipeline.offerBlock(2, "b");          // the trigger, at t
            h.clock.advance(Duration.ofSeconds(3));
            release.release();                      // the pass for it starts at t+3 s and its stage takes 2 s
            h.awaitPasses(2);
            h.barrier();

            block.set(true);
            h.pipeline.offerBlock(3, "c");
            assertTrue(entered.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
            h.pipeline.offerPriceChange();          // the first trigger, at t'
            h.clock.advance(Duration.ofSeconds(1));
            h.pipeline.offerBlock(4, "d");          // a later one, at t'+1 s
            h.clock.advance(Duration.ofSeconds(2));
            release.release();                      // the pass for both starts at t'+3 s
            h.awaitPasses(2);
            h.barrier();

            assertEquals(List.of("L 0/1:a", "L 0/2:b", "L 0/3:c", "L 0/4:d"), h.calls());
            List<String> lines = summaries(Level.INFO);
            assertEquals(4, lines.size(), logs.list.toString());
            assertTrue(lines.get(0).startsWith("wallet pipeline pass #1 at slot 1 (a), trigger block, 0 block(s) "
                    + "folded in, waited 0.0 s, took 3.0 s: "), lines.get(0));
            assertEquals("wallet pipeline pass #2 at slot 2 (b), trigger block, 0 block(s) folded in, waited 3.0 s, "
                    + "took 2.0 s: liquidation NO_OP, compound NO_OP, housekeeping "
                    + "NO_OP; Blockfrost calls discovery 0, evaluate 0, submit 0", lines.get(1));
            assertTrue(lines.get(3).startsWith("wallet pipeline pass #4 at slot 4 (d), trigger block+price, 0 block(s) "
                    + "folded in, waited 3.0 s, took 2.0 s: "), lines.get(3));
        }
    }

    /**
     * FAB-195: a rollback drops the queued work, and with it that work's fold count and first-trigger instant; the
     * pass for the next block counts from that block alone.
     */
    @Test
    void aRollbackForgetsTheFoldsAndTheWaitOfTheWorkItDropped() throws Exception {
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

            h.pipeline.offerBlock(1, "a");
            assertTrue(entered.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
            h.pipeline.offerBlock(2, "b");
            h.pipeline.offerBlock(3, "c");          // one fold
            h.clock.advance(Duration.ofSeconds(5));
            h.pipeline.rollback();
            h.clock.advance(Duration.ofSeconds(1));
            h.pipeline.offerBlock(2, "r");          // the next generation's first trigger
            h.clock.advance(Duration.ofSeconds(2));
            release.countDown();
            h.awaitPasses(2);
            h.barrier();

            assertEquals(List.of("L 0/1:a", "L 1/2:r"), h.calls());
            List<String> lines = summaries(Level.INFO);
            assertEquals(2, lines.size(), logs.list.toString());
            assertTrue(lines.get(1).startsWith("wallet pipeline pass #2 at slot 2 (r), trigger block, 0 block(s) "
                    + "folded in, waited 2.0 s, took 0.0 s: "), lines.get(1));
        }
    }

    /**
     * FAB-195 r2 (h): a price change before any point is known schedules nothing, so it is no trigger for the wait.
     * Price changes at t and t+3600 s, then the first block at t+3605 s whose pass starts at once: {@code waited 0.0
     * s}, counted from the block. The price changes still mark the pass {@code block+price} — today's priceDirty
     * carry-over, asserted so a change to it is visible.
     */
    @Test
    void aPriceChangeBeforeAnyPointStartsNoWait() throws Exception {
        try (Harness h = new Harness()) {
            h.start(
                    fence -> { h.record("L"); return WorkResult.DEFINITE_FAILURE; },
                    fence -> WorkResult.DEFINITE_FAILURE,
                    fence -> WorkResult.DEFINITE_FAILURE);

            h.pipeline.offerPriceChange();          // t
            h.clock.advance(Duration.ofSeconds(3600));
            h.pipeline.offerPriceChange();          // t+3600 s
            h.clock.advance(Duration.ofSeconds(5));
            h.barrier();
            assertEquals(0, h.executes.get(), "a price change with no known point schedules nothing");

            h.pipeline.offerBlock(1, "a");          // t+3605 s, and its pass starts at once
            h.awaitPasses(1);
            h.barrier();

            assertEquals(List.of("L 0/1:a"), h.calls());
            List<String> lines = summaries(Level.INFO);
            assertEquals(1, lines.size(), logs.list.toString());
            assertTrue(lines.get(0).startsWith("wallet pipeline pass #1 at slot 1 (a), trigger block+price, 0 block(s) "
                    + "folded in, waited 0.0 s, took 0.0 s: "), lines.get(0));
        }
    }

    /**
     * FAB-195 r2 (i): the same after a rollback — no point of the new generation is known, so price changes at t and
     * t+3600 s schedule nothing and the next generation's first block, at t+3605 s, says {@code waited 0.0 s}.
     */
    @Test
    void aPriceChangeAfterARollbackStartsNoWait() throws Exception {
        try (Harness h = new Harness()) {
            h.start(
                    fence -> { h.record("L"); return WorkResult.DEFINITE_FAILURE; },
                    fence -> WorkResult.DEFINITE_FAILURE,
                    fence -> WorkResult.DEFINITE_FAILURE);

            h.pipeline.offerBlock(5, "a");
            h.awaitPasses(1);
            h.barrier();
            int executes = h.executes.get();

            h.pipeline.rollback();
            h.pipeline.offerPriceChange();          // t
            h.clock.advance(Duration.ofSeconds(3600));
            h.pipeline.offerPriceChange();          // t+3600 s
            h.clock.advance(Duration.ofSeconds(5));
            h.barrier();
            assertEquals(executes, h.executes.get(), "a price change after a rollback schedules nothing");

            h.pipeline.offerBlock(4, "r");          // t+3605 s, and its pass starts at once
            h.awaitPasses(1);
            h.barrier();

            assertEquals(List.of("L 0/5:a", "L 1/4:r"), h.calls());
            List<String> lines = summaries(Level.INFO);
            assertEquals(2, lines.size(), logs.list.toString());
            assertTrue(lines.get(1).startsWith("wallet pipeline pass #2 at slot 4 (r), trigger block+price, 0 block(s) "
                    + "folded in, waited 0.0 s, took 0.0 s: "), lines.get(1));
        }
    }

    /**
     * FAB-195 r2 (j): an offer the pipeline drops (a duplicate of the in-flight point, or an older slot) is not a
     * trigger, so it does not start the wait. Both are offered at t while a pass runs; the real newer block comes at
     * t+5 s and its pass starts at t+7 s, which says {@code waited 2.0 s}, not 7.0 s.
     */
    @Test
    void droppedOffersDoNotStartTheWait() throws Exception {
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
            h.pipeline.offerBlock(10, "a");         // t: duplicate of the in-flight point, dropped
            h.pipeline.offerBlock(9, "x");          // t: older than the in-flight point, dropped
            h.clock.advance(Duration.ofSeconds(5));
            h.pipeline.offerBlock(11, "b");         // t+5 s: the real trigger
            h.clock.advance(Duration.ofSeconds(2));
            release.countDown();                    // its pass starts at t+7 s
            h.awaitPasses(2);
            h.barrier();

            assertEquals(List.of("L 0/10:a", "L 0/11:b"), h.calls());
            List<String> lines = summaries(Level.INFO);
            assertEquals(2, lines.size(), logs.list.toString());
            assertTrue(lines.get(1).startsWith("wallet pipeline pass #2 at slot 11 (b), trigger block, 0 block(s) "
                    + "folded in, waited 2.0 s, took 0.0 s: "), lines.get(1));
        }
    }

    /**
     * FAB-195-2 (k): a price change during the generation's first pass, with nothing pending and no point handled
     * yet, is re-run as a {@code price} pass once that pass ends, so it starts the wait. The price change at t, the
     * first pass released at t+7 s: the price pass says {@code waited 7.0 s}.
     */
    @Test
    void aPriceChangeDuringTheFirstPassStartsTheWait() throws Exception {
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
            h.pipeline.offerPriceChange();          // t: nothing pending, nothing handled, block 10 in flight
            h.clock.advance(Duration.ofSeconds(7));
            release.countDown();                    // the price pass starts at t+7 s
            h.awaitPasses(2);
            h.barrier();

            assertEquals(List.of("L 0/10:a", "L 0/10:a"), h.calls());
            List<String> lines = summaries(Level.INFO);
            assertEquals(2, lines.size(), logs.list.toString());
            assertTrue(lines.get(1).startsWith("wallet pipeline pass #2 at slot 10 (a), trigger price, 0 block(s) "
                    + "folded in, waited 7.0 s, took 0.0 s: "), lines.get(1));
        }
    }

    /**
     * FAB-195-2 (l): after a rollback, the older generation's pass still in flight is never re-run, so a price change
     * then starts no wait. Rollback and price change at t during block 10's pass; block 11 at t+60 s, released at
     * once: block 11's pass says {@code waited 0.0 s}, counted from block 11.
     */
    @Test
    void aPriceChangeDuringAnOlderGenerationsPassStartsNoWait() throws Exception {
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
            h.pipeline.rollback();
            h.pipeline.offerPriceChange();          // t: only the older generation's pass is in flight
            h.clock.advance(Duration.ofSeconds(60));
            h.pipeline.offerBlock(11, "b");         // t+60 s: the new generation's first trigger
            release.countDown();                    // its pass starts at t+60 s
            h.awaitPasses(2);
            h.barrier();

            assertEquals(List.of("L 0/10:a", "L 1/11:b"), h.calls());
            List<String> lines = summaries(Level.INFO);
            assertEquals(2, lines.size(), logs.list.toString());
            assertTrue(lines.get(1).startsWith("wallet pipeline pass #2 at slot 11 (b), trigger block+price, 0 block(s) "
                    + "folded in, waited 0.0 s, took 0.0 s: "), lines.get(1));
        }
    }

    /**
     * FAB-195-2 residue R1: the same as (k), in the FIRST pass of a generation after a rollback — generation 1, not 0.
     * Block 10 (generation 0) runs and ends; a rollback; block 11's pass (#2) is held inside liquidation; a price change
     * at t, nothing pending and nothing handled in generation 1, is re-run as a {@code price} pass once #2 ends, so it
     * starts the wait: released at t+7 s, pass #3 says {@code waited 7.0 s}. Only the in-flight pass's generation being
     * the CURRENT one decides it, never generation 0.
     */
    @Test
    void aPriceChangeDuringTheFirstPassAfterARollbackStartsTheWait() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger liquidations = new AtomicInteger();
        try (Harness h = new Harness()) {
            h.start(
                    fence -> {
                        h.record("L");
                        if (liquidations.incrementAndGet() == 2) {
                            entered.countDown();
                            assertTrue(release.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
                        }
                        return WorkResult.DEFINITE_FAILURE;
                    },
                    fence -> WorkResult.DEFINITE_FAILURE,
                    fence -> WorkResult.DEFINITE_FAILURE);

            h.pipeline.offerBlock(10, "a");         // generation 0: runs and ends
            h.awaitPasses(1);
            h.barrier();

            h.pipeline.rollback();
            h.pipeline.offerBlock(11, "b");         // generation 1's first pass, #2, held
            assertTrue(entered.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
            h.pipeline.offerPriceChange();          // t: nothing pending, nothing handled, block 11 in flight
            h.clock.advance(Duration.ofSeconds(7));
            release.countDown();                    // the price pass starts at t+7 s
            h.awaitPasses(2);
            h.barrier();

            assertEquals(List.of("L 0/10:a", "L 1/11:b", "L 1/11:b"), h.calls());
            List<String> lines = summaries(Level.INFO);
            assertEquals(3, lines.size(), logs.list.toString());
            assertTrue(lines.get(2).startsWith("wallet pipeline pass #3 at slot 11 (b), trigger price, 0 block(s) "
                    + "folded in, waited 7.0 s, took 0.0 s: "), lines.get(2));
        }
    }

    // ---- FAB-195: stage outcomes on the pass line ------------------------------------------------------------------

    /**
     * (P1) A stage's outcome is its strongest count, then its non-zero counts in order: liquidation tallies attempted
     * 1, error 1 and no-op 3 and answers DEFINITE_FAILURE — ATTEMPTED, ranked above ERROR; compound answers
     * DEFINITE_FAILURE and counts nothing — a bare NO_OP; housekeeping counts no-op 1. Nothing went out, so all three
     * stages ran: the tally never ends a pass.
     */
    @Test
    void aStagesOutcomeIsItsStrongestCountFollowedByItsNonZeroCounts() throws Exception {
        try (Harness h = new Harness()) {
            h.start(
                    fence -> {
                        h.record("L");
                        fence.tally().recordAttempted();
                        fence.tally().recordError();
                        fence.tally().recordNoOp();
                        fence.tally().recordNoOp();
                        fence.tally().recordNoOp();
                        return WorkResult.DEFINITE_FAILURE;
                    },
                    fence -> { h.record("C"); return WorkResult.DEFINITE_FAILURE; },
                    fence -> { h.record("H"); fence.tally().recordNoOp(); return WorkResult.DEFINITE_FAILURE; });

            h.pipeline.offerBlock(1, "a");
            h.awaitPasses(1);
            h.barrier();

            assertEquals(List.of("L 0/1:a", "C 0/1:a", "H 0/1:a"), h.calls(), "all three stages ran");
            assertEquals(List.of("wallet pipeline pass #1 at slot 1 (a), trigger block, 0 block(s) folded in, waited "
                            + "0.0 s, took 0.0 s: liquidation ATTEMPTED (attempted 1, error 1, no-op 3), compound NO_OP, "
                            + "housekeeping NO_OP (no-op 1); Blockfrost calls discovery 0, evaluate 0, submit 0"),
                    summaries(), logs.list.toString());
        }
    }

    /**
     * (P2) The ladder. SUBMITTED outranks WOULD_SUBMIT and ends the pass (the later stages are SKIPPED and not
     * called); WOULD_SUBMIT outranks ATTEMPTED and does not end it; ERROR outranks NO_OP and does not end it either.
     */
    @Test
    void theOutcomeLadderAndWhichOfItsRungsEndAPass() throws Exception {
        try (Harness h = new Harness()) {
            h.start(
                    fence -> {
                        h.record("L");
                        long slot = h.pipeline.stateForTest().inFlight().slot();
                        if (slot == 1) {
                            fence.tally().recordSubmitted();
                            fence.tally().recordWouldSubmit();
                            return WorkResult.SUBMITTED;
                        }
                        if (slot == 2) {
                            fence.tally().recordWouldSubmit();
                            fence.tally().recordAttempted();
                            return WorkResult.WOULD_SUBMIT;
                        }
                        return WorkResult.DEFINITE_FAILURE;
                    },
                    fence -> {
                        h.record("C");
                        if (h.pipeline.stateForTest().inFlight().slot() == 3) {
                            fence.tally().recordError();
                            fence.tally().recordNoOp();
                            fence.tally().recordNoOp();
                        }
                        return WorkResult.DEFINITE_FAILURE;
                    },
                    fence -> { h.record("H"); return WorkResult.DEFINITE_FAILURE; });

            for (int slot = 1; slot <= 3; slot++) {
                h.pipeline.offerBlock(slot, "abc".substring(slot - 1, slot));
                h.awaitPasses(1);
                h.barrier();
            }

            assertEquals(List.of("L 0/1:a", "L 0/2:b", "C 0/2:b", "H 0/2:b", "L 0/3:c", "C 0/3:c", "H 0/3:c"),
                    h.calls());
            List<String> lines = summaries();
            assertEquals(3, lines.size(), logs.list.toString());
            assertTrue(lines.get(0).endsWith("took 0.0 s: liquidation SUBMITTED (submitted 1, would-submit 1), compound "
                    + "SKIPPED, housekeeping SKIPPED; Blockfrost calls discovery 0, evaluate 0, submit 0"), lines.get(0));
            assertTrue(lines.get(1).endsWith("took 0.0 s: liquidation WOULD_SUBMIT (would-submit 1, attempted 1), "
                    + "compound NO_OP, housekeeping NO_OP; Blockfrost calls discovery 0, evaluate 0, submit 0"),
                    lines.get(1));
            assertTrue(lines.get(2).endsWith("took 0.0 s: liquidation NO_OP, compound ERROR (error 1, no-op 2), "
                    + "housekeeping NO_OP; Blockfrost calls discovery 0, evaluate 0, submit 0"), lines.get(2));
        }
    }

    /**
     * (P3) AMBIGUOUS — or a stage that answers nothing — says SUBMITTED, with no count of its own (a display merge),
     * and still ends the pass exactly as before: compound AMBIGUOUS leaves housekeeping SKIPPED and uncalled; a
     * liquidation answering null leaves both later stages SKIPPED.
     */
    @Test
    void ambiguousAndANullAnswerSaySubmittedAndStillEndThePass() throws Exception {
        try (Harness h = new Harness()) {
            h.start(
                    fence -> {
                        h.record("L");
                        return h.pipeline.stateForTest().inFlight().slot() == 2 ? null : WorkResult.DEFINITE_FAILURE;
                    },
                    fence -> { h.record("C"); return WorkResult.AMBIGUOUS; },
                    fence -> { h.record("H"); return WorkResult.DEFINITE_FAILURE; });

            h.pipeline.offerBlock(1, "a");
            h.awaitPasses(1);
            h.barrier();
            h.pipeline.offerBlock(2, "b");
            h.awaitPasses(1);
            h.barrier();

            assertEquals(List.of("L 0/1:a", "C 0/1:a", "L 0/2:b"), h.calls());
            List<String> lines = summaries();
            assertEquals(2, lines.size(), logs.list.toString());
            assertTrue(lines.get(0).endsWith("took 0.0 s: liquidation NO_OP, compound SUBMITTED, housekeeping SKIPPED; "
                    + "Blockfrost calls discovery 0, evaluate 0, submit 0"), lines.get(0));
            assertTrue(lines.get(1).endsWith("took 0.0 s: liquidation SUBMITTED, compound SKIPPED, housekeeping SKIPPED; "
                    + "Blockfrost calls discovery 0, evaluate 0, submit 0"), lines.get(1));
        }
    }

    /**
     * (P4) A stage that threw gains one error: alone it says ERROR (error 1); after tallying attempted 1 it says
     * ATTEMPTED (attempted 1, error 1). Either way the later stages are SKIPPED.
     */
    @Test
    void aStageThatThrewGainsOneError() throws Exception {
        try (Harness h = new Harness()) {
            h.start(
                    fence -> {
                        h.record("L");
                        if (h.pipeline.stateForTest().inFlight().slot() == 2) {
                            fence.tally().recordAttempted();
                        }
                        throw new IllegalStateException("liquidation fixture failure");
                    },
                    fence -> { h.record("C"); return WorkResult.DEFINITE_FAILURE; },
                    fence -> { h.record("H"); return WorkResult.DEFINITE_FAILURE; });

            h.pipeline.offerBlock(1, "a");
            h.awaitPasses(1);
            h.barrier();
            h.pipeline.offerBlock(2, "b");
            h.awaitPasses(1);
            h.barrier();

            assertEquals(List.of("L 0/1:a", "L 0/2:b"), h.calls());
            List<String> lines = summaries();
            assertEquals(2, lines.size(), logs.list.toString());
            assertTrue(lines.get(0).endsWith("took 0.0 s: liquidation ERROR (error 1), compound SKIPPED, housekeeping "
                    + "SKIPPED; Blockfrost calls discovery 0, evaluate 0, submit 0"), lines.get(0));
            assertTrue(lines.get(1).endsWith("took 0.0 s: liquidation ATTEMPTED (attempted 1, error 1), compound "
                    + "SKIPPED, housekeeping SKIPPED; Blockfrost calls discovery 0, evaluate 0, submit 0"), lines.get(1));
        }
    }

    /**
     * (P5) With {@code logEveryPass} off, an ERROR is news: a pass whose only outcome other than NO_OP is a compound
     * ERROR, with nothing evaluated or submitted, says its line at INFO. A pass where every stage is NO_OP — with its
     * no-op counts — and nothing was evaluated or submitted says it at DEBUG.
     */
    @Test
    void withoutLogEveryPassAnErrorIsNewsAndCountedNoOpsAreNot() throws Exception {
        try (Harness h = new Harness()) {
            h.logEveryPass = false;
            h.start(
                    fence -> { h.record("L"); fence.tally().recordNoOp(); return WorkResult.DEFINITE_FAILURE; },
                    fence -> {
                        h.record("C");
                        if (h.pipeline.stateForTest().inFlight().slot() == 1) {
                            fence.tally().recordError();
                        } else {
                            fence.tally().recordNoOp();
                            fence.tally().recordNoOp();
                        }
                        return WorkResult.DEFINITE_FAILURE;
                    },
                    fence -> { h.record("H"); fence.tally().recordNoOp(); return WorkResult.DEFINITE_FAILURE; });

            h.pipeline.offerBlock(1, "a");
            h.awaitPasses(1);
            h.barrier();
            h.pipeline.offerBlock(2, "b");
            h.awaitPasses(1);
            h.barrier();

            assertEquals(List.of("wallet pipeline pass #1 at slot 1 (a), trigger block, 0 block(s) folded in, waited "
                            + "0.0 s, took 0.0 s: liquidation NO_OP (no-op 1), compound ERROR (error 1), housekeeping "
                            + "NO_OP (no-op 1); Blockfrost calls discovery 0, evaluate 0, submit 0"),
                    summaries(Level.INFO), logs.list.toString());
            assertEquals(List.of("wallet pipeline pass #2 at slot 2 (b), trigger block, 0 block(s) folded in, waited "
                            + "0.0 s, took 0.0 s: liquidation NO_OP (no-op 1), compound NO_OP (no-op 2), housekeeping "
                            + "NO_OP (no-op 1); Blockfrost calls discovery 0, evaluate 0, submit 0"),
                    summaries(Level.DEBUG), logs.list.toString());
        }
    }

    /** The summary lines at one level, in order. */
    private List<String> summaries(Level level) {
        return logs.list.stream()
                .filter(e -> e.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("wallet pipeline pass #"))
                .toList();
    }

    /** Every summary line, at any level, in order. */
    private List<String> summaries() {
        return logs.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("wallet pipeline pass #"))
                .toList();
    }

    /** Each summary line up to its fold count: number, slot, hash, trigger and folds. */
    private List<String> heads() {
        return summaries().stream().map(m -> m.substring(0, m.indexOf(", waited "))).toList();
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
        /** FAB-195: what the pipeline is built with; set before {@link #start}. */
        private boolean logEveryPass = true;
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
            }, liquidation, compound, housekeeping, HUNG, counting, clock, logEveryPass);
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
        /** FAB-186: what this pass's Blockfrost calls are said to have been; zeros unless a stage sets them. */
        private final AtomicReference<BoundedBFBackendService.CallCounts> counts =
                new AtomicReference<>(BoundedBFBackendService.CallCounts.NONE);

        void expire() {
            expired.set(true);
        }

        @Override public boolean isExpired() { return expired.get(); }

        @Override public long remainingNanos() { return expired.get() ? 0 : Long.MAX_VALUE / 4; }

        @Override public BoundedBFBackendService.CallCounts callCounts() { return counts.get(); }
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
