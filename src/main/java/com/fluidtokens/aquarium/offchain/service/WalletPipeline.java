package com.fluidtokens.aquarium.offchain.service;

import com.fluidtokens.aquarium.offchain.config.BoundedBFBackendService;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 * it serves and how long it took, each stage's outcome and the Blockfrost calls the pass made, by kind —
 * counted by the deadline, never limited by the count. A stage's outcome is the strongest of
 * {@code SUBMITTED > WOULD_SUBMIT > ATTEMPTED > ERROR > NO_OP}, or {@code SKIPPED} for a stage that did not run,
 * followed by its non-zero counts from the {@link StageTally} its {@link StageFence} carried. The tally feeds
 * that line and nothing else: what ends a pass is still the stage's returned {@link WorkResult}. It is INFO for
 * every pass while {@code logEveryPass} is on; off, only a pass that did something says it at INFO.</p>
 */
@Slf4j
public final class WalletPipeline implements AutoCloseable {

    @FunctionalInterface
    public interface Stage {
        WorkResult run(StageFence fence) throws Exception;
    }

    /**
     * A stage's fence (FAB-195): the pass's one {@link SubmitFence} rule, plus the {@link StageTally} this stage
     * counts its candidates on for the pass's summary line. Each stage of a pass gets its own, with a fresh tally.
     */
    public interface StageFence extends SubmitFence {
        StageTally tally();
    }

    /** The pass's fence rule with one stage's tally beside it. */
    private record PassStageFence(SubmitFence pass, StageTally tally) implements StageFence {
        @Override
        public boolean mayProceed() {
            return pass.mayProceed();
        }
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
            } else if (inFlight != null && inFlight.generation() == generation) {
                // The generation's first pass is running: the drain already scheduled re-runs its point once it
                // ends, so the wait starts now. An older generation's pass sets no lastHandled and is never re-run.
                noteTriggerUnderLock();
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
     * trigger that leads to a pass counts: a block the pipeline drops (equal or older) does not. A price change
     * counts when there is a point it will re-run — a pending block, a current-generation {@code lastHandled}, or a
     * current-generation pass in flight — and not otherwise (at boot, or after a rollback, even while the older
     * generation's pass is still running), so the first pass after catch-up does not report the whole catch-up as
     * its wait.
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

        summary.liquidation.started = true;
        WorkResult liquidated = outcome(liquidation.run(summary.liquidation.fence(fence)));
        summary.liquidation.result = liquidated;
        if (wentOut(liquidated)) {
            log.info("wallet pipeline: liquidation {} at slot {} — compound and housekeeping wait for the next trigger",
                    liquidated, point.slot());
            return;
        }
        if (!fence.mayProceed()) {
            return;
        }

        summary.compound.started = true;
        WorkResult compounded = outcome(compound.run(summary.compound.fence(fence)));
        summary.compound.result = compounded;
        if (wentOut(compounded)) {
            log.info("wallet pipeline: compound {} at slot {} — housekeeping waits for the next trigger",
                    compounded, point.slot());
            return;
        }
        if (!fence.mayProceed()) {
            return;
        }

        summary.housekeeping.started = true;
        summary.housekeeping.result = outcome(housekeeping.run(summary.housekeeping.fence(fence)));
    }

    /**
     * One stage's share of a pass, for the summary line (FAB-195): whether it started, what it answered (null
     * while it has not — so a stage that started and has no answer threw), and the tally it counted on.
     */
    private static final class StageReport {
        private final StageTally tally = new StageTally();
        private boolean started;
        private WorkResult result;

        private StageFence fence(SubmitFence pass) {
            return new PassStageFence(pass, tally);
        }
    }

    /** What one pass did, for its summary line (FAB-186, FAB-195): each stage's report, and its Blockfrost calls. */
    private static final class PassSummary {
        private final StageReport liquidation = new StageReport();
        private final StageReport compound = new StageReport();
        private final StageReport housekeeping = new StageReport();
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
     * it, how long it waited from the first trigger it serves and how long it took, each stage's outcome with its
     * non-zero counts ({@link #rendered}), and the Blockfrost calls the pass made by kind. INFO for every pass
     * while {@code logEveryPass} is on. Off: INFO when some stage's outcome is neither {@code NO_OP} nor
     * {@code SKIPPED} — an {@code ERROR} included — or when the pass evaluated or submitted anything; DEBUG for a
     * pass that did nothing worth saying, which on a quiet node is most of them.
     */
    private void logSummary(ChainPoint point, PassStart start, PassSummary summary) {
        BoundedBFBackendService.CallCounts calls = summary.calls;
        boolean news = !quiet(summary.liquidation) || !quiet(summary.compound) || !quiet(summary.housekeeping)
                || calls.evaluation() + calls.submission() > 0;
        String line = "wallet pipeline pass #" + start.number() + " at slot " + point.slot() + " ("
                + point.blockHash() + "), trigger " + start.trigger() + ", " + start.blocksFolded()
                + " block(s) folded in, waited " + seconds(start.waited()) + " s, took "
                + seconds(Duration.between(start.at(), clock.instant())) + " s: liquidation "
                + rendered(summary.liquidation) + ", compound " + rendered(summary.compound) + ", housekeeping "
                + rendered(summary.housekeeping)
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

    /** The stage outcomes (FAB-195), strongest first after {@code SKIPPED}, which only a stage that did not run has. */
    private static final String SKIPPED = "SKIPPED";
    private static final String SUBMITTED = "SUBMITTED";
    private static final String WOULD_SUBMIT = "WOULD_SUBMIT";
    private static final String ATTEMPTED = "ATTEMPTED";
    private static final String ERROR = "ERROR";
    private static final String NO_OP = "NO_OP";

    /**
     * FAB-195: one stage's outcome. A stage that did not run is {@code SKIPPED}. Otherwise the strongest that
     * applies: {@code SUBMITTED} (it answered SUBMITTED or AMBIGUOUS, or counted a submitted), {@code WOULD_SUBMIT}
     * (it answered WOULD_SUBMIT, or counted one), {@code ATTEMPTED} (it counted an attempted), {@code ERROR} (it
     * counted an error, or threw), and {@code NO_OP} otherwise.
     */
    private static String outcomeOf(StageReport report) {
        if (!report.started) {
            return SKIPPED;
        }
        StageTally tally = report.tally;
        WorkResult result = report.result;
        if (result == WorkResult.SUBMITTED || result == WorkResult.AMBIGUOUS || tally.submitted() > 0) {
            return SUBMITTED;
        }
        if (result == WorkResult.WOULD_SUBMIT || tally.wouldSubmit() > 0) {
            return WOULD_SUBMIT;
        }
        if (tally.attempted() > 0) {
            return ATTEMPTED;
        }
        if (errors(report) > 0) {
            return ERROR;
        }
        return NO_OP;
    }

    /** A stage's error count: its tally's, plus one when it threw instead of answering. */
    private static int errors(StageReport report) {
        return report.tally.error() + (report.started && report.result == null ? 1 : 0);
    }

    /**
     * FAB-195: one stage as the summary line says it — {@code <OUTCOME>}, then {@code (<counts>)} when any count is
     * non-zero: {@code submitted}, {@code would-submit}, {@code attempted}, {@code error} and {@code no-op}, in that
     * order, non-zero ones only.
     */
    private static String rendered(StageReport report) {
        StageTally tally = report.tally;
        List<String> counts = new ArrayList<>(5);
        count(counts, "submitted", tally.submitted());
        count(counts, "would-submit", tally.wouldSubmit());
        count(counts, "attempted", tally.attempted());
        count(counts, "error", errors(report));
        count(counts, "no-op", tally.noOp());
        String outcome = outcomeOf(report);
        return counts.isEmpty() ? outcome : outcome + " (" + String.join(", ", counts) + ")";
    }

    private static void count(List<String> counts, String name, int n) {
        if (n != 0) {
            counts.add(name + " " + n);
        }
    }

    /** A stage that is no news on its own: it did not run, or it sent nothing and nothing failed. */
    private static boolean quiet(StageReport report) {
        String outcome = outcomeOf(report);
        return outcome.equals(SKIPPED) || outcome.equals(NO_OP);
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
