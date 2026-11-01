package com.fluidtokens.aquarium.offchain.service;

import com.fluidtokens.aquarium.offchain.config.BoundedBFBackendService;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The shared wallet's work, run one pass at a time on one owned thread (FAB-183).
 *
 * <p>A committed chain point ({@link #offerBlock}) or a change in the merged price set
 * ({@link #offerPriceChange}) only records state and hands off to the {@code wallet-pipeline}
 * thread; no stage ever runs on the notifying thread. A pass runs liquidation, then compound, then
 * housekeeping, inside one Blockfrost pass deadline and under one {@link SubmitFence}. Something
 * that went out (SUBMITTED or AMBIGUOUS) ends the pass, so the next stage waits for the next
 * trigger; a shadow WOULD_SUBMIT sent nothing and does not.</p>
 *
 * <p>Work is keyed by {@code (generation, slot, blockHash)}. A duplicate of the in-flight, pending
 * or last-handled point is ignored, as is an older slot of the current generation; newer points
 * coalesce to the latest. {@link #rollback()} starts a new generation, so an old pass's fence reads
 * {@code false} from then on and can never BEGIN a submit. A submit already in progress finishes,
 * and the next pass starts only after it returns, because there is one thread.</p>
 *
 * <p>The deadline bounds Blockfrost calls made through {@link BoundedBFBackendService}; it cannot
 * preempt CPU work or local reads inside a stage. The hung-pass WARN is the only signal for those.</p>
 */
@Slf4j
public final class WalletPipeline implements AutoCloseable {

    @FunctionalInterface
    public interface Stage {
        WorkResult run(SubmitFence fence) throws Exception;
    }

    @FunctionalInterface
    interface DeadlineScope {
        void run(Consumer<BoundedBFBackendService.PassDeadline> body);
    }

    private record ChainPoint(long generation, long slot, String blockHash) {}

    record StateForTest(long generation, ChainPointView inFlight, ChainPointView pending,
                        ChainPointView lastHandled, boolean priceDirty, boolean drainScheduled,
                        boolean closed) {}

    record ChainPointView(long generation, long slot, String blockHash) {}

    private static final Duration CLOSE_GRACE = Duration.ofSeconds(10);

    private final Object stateLock = new Object();
    private final DeadlineScope deadlineScope;
    private final Stage liquidation;
    private final Stage compound;
    private final Stage housekeeping;
    private final Duration hungAfter;
    private final ExecutorService worker;
    private final Clock clock;

    private long generation;
    private ChainPoint inFlight;
    private ChainPoint pending;
    private ChainPoint lastHandled;
    private boolean priceDirty;
    private boolean drainScheduled;
    private boolean closed;
    private Instant passStartedAt;
    private boolean hungWarned;
    private volatile Thread workerThread;

    public WalletPipeline(
            BoundedBFBackendService backend,
            Stage liquidation,
            Stage compound,
            Stage housekeeping,
            Duration hungAfter) {
        this(Objects.requireNonNull(backend, "backend")::runWithinPassDeadline,
                liquidation, compound, housekeeping, hungAfter,
                Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "wallet-pipeline");
                    thread.setDaemon(true);
                    return thread;
                }),
                Clock.systemUTC());
    }

    WalletPipeline(
            DeadlineScope deadlineScope,
            Stage liquidation,
            Stage compound,
            Stage housekeeping,
            Duration hungAfter,
            ExecutorService worker,
            Clock clock) {
        this.deadlineScope = Objects.requireNonNull(deadlineScope, "deadlineScope");
        this.liquidation = Objects.requireNonNull(liquidation, "liquidation");
        this.compound = Objects.requireNonNull(compound, "compound");
        this.housekeeping = Objects.requireNonNull(housekeeping, "housekeeping");
        this.hungAfter = Objects.requireNonNull(hungAfter, "hungAfter");
        if (hungAfter.isNegative() || hungAfter.isZero()) {
            throw new IllegalArgumentException("hungAfter must be positive");
        }
        this.worker = Objects.requireNonNull(worker, "worker");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** A committed chain point. Records it and returns; no stage runs on the calling thread. */
    public void offerBlock(long slot, String blockHash) {
        Objects.requireNonNull(blockHash, "blockHash");
        String hungWarning;
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            hungWarning = hungWarningUnderLock();
            offerUnderLock(new ChainPoint(generation, slot, blockHash));
        }
        warn(hungWarning);
    }

    private void offerUnderLock(ChainPoint offered) {
        if (offered.equals(inFlight) || offered.equals(pending) || offered.equals(lastHandled)) {
            return;
        }
        long highWater = highestCurrentGenerationSlot();
        if (highWater != Long.MIN_VALUE && offered.slot() < highWater) {
            return;
        }
        // pending is always current-generation (rollback drops it) and highWater covers it, so a
        // surviving offer is never older than pending: the latest wins, and a different hash at the
        // same slot replaces it.
        pending = offered;
        scheduleDrainUnderLock();
    }

    /**
     * The merged price set changed. Re-runs the last handled point once the current pass (if any)
     * ends; before any point of the current generation is known it runs nothing.
     */
    public void offerPriceChange() {
        String hungWarning;
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            hungWarning = hungWarningUnderLock();
            priceDirty = true;
            if (pending != null || currentLastHandledUnderLock() != null) {
                scheduleDrainUnderLock();
            }
        }
        warn(hungWarning);
    }

    /** Starts a new generation: pending work is dropped and every older pass's fence reads false. */
    public void rollback() {
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            generation++;
            pending = null;
            lastHandled = null;
            priceDirty = false;
        }
    }

    private long highestCurrentGenerationSlot() {
        long highest = Long.MIN_VALUE;
        if (inFlight != null && inFlight.generation() == generation) {
            highest = Math.max(highest, inFlight.slot());
        }
        if (pending != null && pending.generation() == generation) {
            highest = Math.max(highest, pending.slot());
        }
        if (lastHandled != null && lastHandled.generation() == generation) {
            highest = Math.max(highest, lastHandled.slot());
        }
        return highest;
    }

    private ChainPoint currentLastHandledUnderLock() {
        return lastHandled != null && lastHandled.generation() == generation ? lastHandled : null;
    }

    private void scheduleDrainUnderLock() {
        if (drainScheduled || closed) {
            return;
        }
        drainScheduled = true;
        try {
            worker.execute(this::drain);
        } catch (RejectedExecutionException rejected) {
            drainScheduled = false;
            log.warn("wallet pipeline: the worker refused a pass ({}); the trigger is dropped",
                    rejected.toString());
        }
    }

    private void drain() {
        workerThread = Thread.currentThread();
        while (true) {
            ChainPoint point;
            synchronized (stateLock) {
                if (closed) {
                    pending = null;
                    drainScheduled = false;
                    return;
                }
                ChainPoint handled = currentLastHandledUnderLock();
                if (pending != null) {
                    point = pending;
                    pending = null;
                    priceDirty = false;
                } else if (priceDirty && handled != null) {
                    point = handled;
                    priceDirty = false;
                } else {
                    drainScheduled = false;
                    return;
                }
                inFlight = point;
                passStartedAt = clock.instant();
                hungWarned = false;
            }

            runPass(point);

            synchronized (stateLock) {
                if (point.generation() == generation) {
                    lastHandled = point;
                }
                inFlight = null;
                passStartedAt = null;
                hungWarned = false;
            }
        }
    }

    private void runPass(ChainPoint point) {
        log.debug("wallet pipeline: pass start at slot {} ({})", point.slot(), point.blockHash());
        try {
            deadlineScope.run(deadline -> {
                try {
                    runStages(point, deadline);
                } catch (Throwable failure) {
                    passFailed(point, failure);
                }
            });
        } catch (Throwable failure) {
            passFailed(point, failure);
        }
        log.debug("wallet pipeline: pass end at slot {} ({})", point.slot(), point.blockHash());
    }

    private void runStages(ChainPoint point, BoundedBFBackendService.PassDeadline deadline) throws Exception {
        SubmitFence fence = () -> mayProceed(point, deadline);

        WorkResult liquidated = outcome(liquidation.run(fence));
        if (wentOut(liquidated)) {
            log.info("wallet pipeline: liquidation {} at slot {} — compound and housekeeping wait for the next trigger",
                    liquidated, point.slot());
            return;
        }
        if (!fence.mayProceed()) {
            return;
        }

        WorkResult compounded = outcome(compound.run(fence));
        if (wentOut(compounded)) {
            log.info("wallet pipeline: compound {} at slot {} — housekeeping waits for the next trigger",
                    compounded, point.slot());
            return;
        }
        if (!fence.mayProceed()) {
            return;
        }

        housekeeping.run(fence);
    }

    private boolean mayProceed(ChainPoint point, BoundedBFBackendService.PassDeadline deadline) {
        synchronized (stateLock) {
            return point.generation() == generation && !deadline.isExpired() && !closed;
        }
    }

    /** A stage that answers nothing cannot prove it sent nothing. */
    private static WorkResult outcome(WorkResult result) {
        return result == null ? WorkResult.AMBIGUOUS : result;
    }

    /**
     * Whether something may have reached the chain. Deliberately not {@link WorkResult#terminal()}:
     * a shadow WOULD_SUBMIT sent nothing, and ending the pass on it would starve compound and
     * housekeeping on a shadow node holding one profitable loan.
     */
    private static boolean wentOut(WorkResult result) {
        return result == WorkResult.SUBMITTED || result == WorkResult.AMBIGUOUS;
    }

    private void passFailed(ChainPoint point, Throwable failure) {
        if (failure instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        log.error("wallet pipeline: the pass at slot {} ({}) failed; its remaining stages wait for the next trigger",
                point.slot(), point.blockHash(), failure);
    }

    private String hungWarningUnderLock() {
        if (inFlight == null || passStartedAt == null || hungWarned) {
            return null;
        }
        Duration running = Duration.between(passStartedAt, clock.instant());
        if (running.compareTo(hungAfter) <= 0) {
            return null;
        }
        hungWarned = true;
        return "wallet pipeline: the pass at slot " + inFlight.slot() + " has been running for "
                + running.toSeconds() + " s; this trigger is held — a second pass is never started";
    }

    private static void warn(String message) {
        if (message != null) {
            log.warn(message);
        }
    }

    StateForTest stateForTest() {
        synchronized (stateLock) {
            return new StateForTest(generation, view(inFlight), view(pending), view(lastHandled),
                    priceDirty, drainScheduled, closed);
        }
    }

    private static ChainPointView view(ChainPoint point) {
        return point == null ? null : new ChainPointView(point.generation(), point.slot(), point.blockHash());
    }

    /**
     * Stops accepting triggers, drops pending work, closes every fence and waits a bounded time for
     * a running pass. Never throws.
     */
    @Override
    public void close() {
        try {
            synchronized (stateLock) {
                closed = true;
                pending = null;
                priceDirty = false;
            }
            worker.shutdown();
            if (Thread.currentThread() == workerThread) {
                return;
            }
            Duration wait = hungAfter.plus(CLOSE_GRACE);
            if (!worker.awaitTermination(wait.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("wallet pipeline: a pass was still running {} s after close; its fence is closed and it is left to finish",
                        wait.toSeconds());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException failure) {
            log.warn("wallet pipeline: close did not complete cleanly", failure);
        }
    }
}
