package com.fluidtokens.aquarium.offchain.service;

/**
 * What one wallet-pipeline stage did with the candidates it walked in one pass (FAB-195), for that pass's summary
 * line and nothing else: no stage reads it back, and nothing that decides whether a pass goes on reads it at all.
 *
 * <ul>
 *   <li><b>submitted</b> — accepted into the mempool (a tx hash came back), not yet on chain; compound's and
 *       housekeeping's AMBIGUOUS is counted here too, a display merge only;</li>
 *   <li><b>would-submit</b> — a shadow candidate that would have been submitted;</li>
 *   <li><b>attempted</b> — built and submitted, but not accepted;</li>
 *   <li><b>error</b> — our own failure: an unexpected build failure, or a failure after the build;</li>
 *   <li><b>no-op</b> — nothing was sent: refused by name before submitting.</li>
 * </ul>
 *
 * <p>Created per stage per pass and dropped with the pass; it is only ever touched on the {@code wallet-pipeline}
 * thread.</p>
 */
public final class StageTally {

    private int submitted;
    private int wouldSubmit;
    private int attempted;
    private int error;
    private int noOp;

    public void recordSubmitted() {
        submitted++;
    }

    public void recordWouldSubmit() {
        wouldSubmit++;
    }

    public void recordAttempted() {
        attempted++;
    }

    public void recordError() {
        error++;
    }

    public void recordNoOp() {
        noOp++;
    }

    public int submitted() {
        return submitted;
    }

    public int wouldSubmit() {
        return wouldSubmit;
    }

    public int attempted() {
        return attempted;
    }

    public int error() {
        return error;
    }

    public int noOp() {
        return noOp;
    }
}
