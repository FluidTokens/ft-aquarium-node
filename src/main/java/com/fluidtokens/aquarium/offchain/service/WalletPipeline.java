package com.fluidtokens.aquarium.offchain.service;

import com.fluidtokens.aquarium.offchain.config.BoundedBFBackendService;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
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
 * <p>Each pass is numbered when it starts. A pass's fence reads {@code true} only while, under the
 * lock, all four hold: its generation is current, its deadline has not expired, the engine is not
 * closed, and it is still the pass running. So a fence that outlives its pass reads {@code false}
 * even with no rollback — including during a price re-run of the very same point.</p>
 *
 * <p>The deadline bounds Blockfrost calls made through {@link BoundedBFBackendService}; it cannot
 * preempt CPU work or local reads inside a stage. The hung-pass WARN is the only signal for those.</p>
 *
 * <p>Each pass ends with one summary line (FAB-186, FAB-195): its number, its trigger ({@code block},
 * {@code price} or {@code block+price}), the blocks folded into it, how long it waited from the first trigger
 * it serves and how long it took, each stage's result and the Blockfrost calls the pass made, by kind —
 * counted by the deadline, never limited by the count. It is INFO for every pass while {@code logEveryPass}
 * is on; off, only a pass that did something says it at INFO.</p>
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

    /** How a pass started, for its summary line (FAB-195): what triggered it and how long that waited. */
    private record PassStart(long number, String trigger, long blocksFolded, Duration waited, Instant at) {}

    private final Object stateLock = new Object();
    private final DeadlineScope deadlineScope;
    private final Stage liquidation;
    private final Stage compound;
    private final Stage housekeeping;
    private final Duration hungAfter;
    private final ExecutorService worker;
    private final Clock clock;
    private final boolean logEveryPass;

    private long generation;
    private ChainPoint inFlight;
    private ChainPoint pending;
    private ChainPoint lastHandled;
    private boolean priceDirty;
    private boolean drainScheduled;
    private boolean closed;
    private Instant passStartedAt;
    private boolean hungWarned;
    private long passesStarted;
    private long runningPass;
    /** FAB-195: when the first trigger no pass has served yet arrived; null when none is waiting. */
    private Instant firstUnservedTriggerAt;
    /** FAB-195: how many queued blocks a newer block has replaced since a pass last took one. */
    private long blocksFolded;
    private volatile Thread workerThread;

    public WalletPipeline(
            BoundedBFBackendService backend,
            Stage liquidation,
            Stage compound,
            Stage housekeeping,
            Duration hungAfter,
            boolean logEveryPass) {
        this(Objects.requireNonNull(backend, "backend")::runWithinPassDeadline,
                liquidation, compound, housekeeping, hungAfter,
                Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "wallet-pipeline");
                    thread.setDaemon(true);
                    return thread;
                }),
                Clock.systemUTC(), logEveryPass);
    }

    WalletPipeline(
            DeadlineScope deadlineScope,
            Stage liquidation,
            Stage compound,
            Stage housekeeping,
            Duration hungAfter,
            ExecutorService worker,
            Clock clock,
            boolean logEveryPass) {
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
        this.logEveryPass = logEveryPass;
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
        if (pending != null) {
            blocksFolded++;
        }
        pending = offered;
        noteTriggerUnderLock();
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
                noteTriggerUnderLock();
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
            firstUnservedTriggerAt = null;
            blocksFolded = 0;
        }
    }

    /**
     * Remembers when the first trigger not yet served by a pass arrived (FAB-195); a later one keeps it. Only a
     * trigger that schedules work counts: a block the pipeline drops (equal or older) does not, nor does a price
     * change with no point to re-run (at boot, or after a rollback), so the first pass after catch-up does not
     * report the whole catch-up as its wait.
     */
    private void noteTriggerUnderLock() {
        if (firstUnservedTriggerAt == null) {
            firstUnservedTriggerAt = clock.instant();
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
            PassStart start;
            synchronized (stateLock) {
                if (closed) {
                    pending = null;
                    drainScheduled = false;
                    return;
                }
                ChainPoint handled = currentLastHandledUnderLock();
                String trigger;
                long folded;
                if (pending != null) {
                    point = pending;
                    trigger = priceDirty ? "block+price" : "block";
                    folded = blocksFolded;
                    pending = null;
                    priceDirty = false;
                    blocksFolded = 0;
                } else if (priceDirty && handled != null) {
                    point = handled;
                    trigger = "price";
                    folded = 0;
                    priceDirty = false;
                } else {
                    drainScheduled = false;
                    return;
                }
                inFlight = point;
                long pass = ++passesStarted;
                runningPass = pass;
                passStartedAt = clock.instant();
                hungWarned = false;
                Duration waited = firstUnservedTriggerAt == null
                        ? Duration.ZERO : Duration.between(firstUnservedTriggerAt, passStartedAt);
                firstUnservedTriggerAt = null;
                start = new PassStart(pass, trigger, folded, waited, passStartedAt);
            }

            runPass(point, start);

            synchronized (stateLock) {
                if (point.generation() == generation) {
                    lastHandled = point;
                }
                inFlight = null;
                runningPass = 0;
                passStartedAt = null;
                hungWarned = false;
            }
        }
    }

    private void runPass(ChainPoint point, PassStart start) {
        long pass = start.number();
        log.debug("wallet pipeline: pass start at slot {} ({})", point.slot(), point.blockHash());
        PassSummary summary = new PassSummary();
        try {
            deadlineScope.run(deadline -> {
                try {
                    runStages(point, pass, deadline, summary);
                } catch (Throwable failure) {
                    passFailed(point, failure);
                } finally {
                    summary.calls = callCountsOf(deadline);
                }
            });
        } catch (Throwable failure) {
            passFailed(point, failure);
        }
        log.debug("wallet pipeline: pass end at slot {} ({})", point.slot(), point.blockHash());
        logSummary(point, start, summary);
    }

    private void runStages(ChainPoint point, long pass, BoundedBFBackendService.PassDeadline deadline,
                           PassSummary summary) throws Exception {
        SubmitFence fence = () -> mayProceed(point, pass, deadline);

        summary.liquidation = THREW;
        WorkResult liquidated = outcome(liquidation.run(fence));
        summary.liquidation = liquidated.name();
        if (wentOut(liquidated)) {
            log.info("wallet pipeline: liquidation {} at slot {} — compound and housekeeping wait for the next trigger",
                    liquidated, point.slot());
            return;
        }
        if (!fence.mayProceed()) {
            return;
        }

        summary.compound = THREW;
        WorkResult compounded = outcome(compound.run(fence));
        summary.compound = compounded.name();
        if (wentOut(compounded)) {
            log.info("wallet pipeline: compound {} at slot {} — housekeeping waits for the next trigger",
                    compounded, point.slot());
            return;
        }
        if (!fence.mayProceed()) {
            return;
        }

        summary.housekeeping = THREW;
        summary.housekeeping = outcome(housekeeping.run(fence)).name();
    }

    /** A stage that did not run this pass, and one that threw instead of answering. */
    private static final String NOT_RUN = "not run";
    private static final String THREW = "threw";

    /** What one pass did, for its summary line (FAB-186): each stage's result, and its Blockfrost calls. */
    private static final class PassSummary {
        private String liquidation = NOT_RUN;
        private String compound = NOT_RUN;
        private String housekeeping = NOT_RUN;
        private BoundedBFBackendService.CallCounts calls = BoundedBFBackendService.CallCounts.NONE;
    }

    /** The deadline's counts; zeros when it has none to give — a summary line must never fail a pass. */
    private static BoundedBFBackendService.CallCounts callCountsOf(BoundedBFBackendService.PassDeadline deadline) {
        try {
            BoundedBFBackendService.CallCounts counts = deadline == null ? null : deadline.callCounts();
            return counts == null ? BoundedBFBackendService.CallCounts.NONE : counts;
        } catch (RuntimeException e) {
            return BoundedBFBackendService.CallCounts.NONE;
        }
    }

    /**
     * FAB-186 (m5), FAB-195: ONE summary line per pass — its number and slot, its trigger, the blocks folded into
     * it, how long it waited from the first trigger it serves and how long it took, each stage's result or
     * {@code not run}, and the Blockfrost calls the pass made by kind. INFO for every pass while
     * {@code logEveryPass} is on. Off: INFO when a stage answered anything but DEFINITE_FAILURE (or threw), or
     * when the pass evaluated or submitted anything; DEBUG for a pass that did nothing worth saying, which on a
     * quiet node is most of them.
     */
    private void logSummary(ChainPoint point, PassStart start, PassSummary summary) {
        BoundedBFBackendService.CallCounts calls = summary.calls;
        boolean news = said(summary.liquidation) || said(summary.compound) || said(summary.housekeeping)
                || calls.evaluation() + calls.submission() > 0;
        String line = "wallet pipeline pass #" + start.number() + " at slot " + point.slot() + " ("
                + point.blockHash() + "), trigger " + start.trigger() + ", " + start.blocksFolded()
                + " block(s) folded in, waited " + seconds(start.waited()) + " s, took "
                + seconds(Duration.between(start.at(), clock.instant())) + " s: liquidation "
                + summary.liquidation + ", compound " + summary.compound + ", housekeeping " + summary.housekeeping
                + "; Blockfrost calls discovery " + calls.discovery() + ", evaluate " + calls.evaluation()
                + ", submit " + calls.submission();
        if (logEveryPass || news) {
            log.info("{}", line);
        } else {
            log.debug("{}", line);
        }
    }

    /** Seconds with one decimal, whatever the default locale. */
    private static String seconds(Duration duration) {
        return String.format(Locale.ROOT, "%.1f", duration.toMillis() / 1000.0);
    }

    private static boolean said(String stageResult) {
        return !NOT_RUN.equals(stageResult) && !WorkResult.DEFINITE_FAILURE.name().equals(stageResult);
    }

    /**
     * A pass's fence: its generation is current, its deadline has not expired, the engine is not
     * closed, and it is still the running pass (pass numbers start at 1; 0 means none is running).
     */
    private boolean mayProceed(ChainPoint point, long pass, BoundedBFBackendService.PassDeadline deadline) {
        synchronized (stateLock) {
            return point.generation() == generation && !deadline.isExpired() && !closed
                    && pass == runningPass;
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
