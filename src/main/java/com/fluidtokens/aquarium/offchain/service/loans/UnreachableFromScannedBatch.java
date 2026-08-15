package com.fluidtokens.aquarium.offchain.service.loans;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link LiquidateTransactionBuilder.Refusal} constant that a batch produced by
 * {@link LiquidationCandidateScanner} can never trigger.
 * <p>
 * These vetoes are defence in depth against a batch that did <em>not</em> come from a scan of the
 * UTxOs being spent — a hand-built {@code Request}, a stale assessment replayed against fresher
 * UTxOs, or a future caller that skips the scanner. They are unreachable only when the batch
 * <em>and</em> the oracle-entry map come from the same scan snapshot; nothing in
 * {@link LiquidateTransactionBuilder} enforces that pairing, so the check still has to run.
 * <p>
 * They are <b>not</b> dead code and must not be removed by a coverage-driven or
 * static-analysis-driven cleanup.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface UnreachableFromScannedBatch {

    /**
     * What makes this constant unreachable when the batch comes from a real scan: either a
     * {@link com.fluidtokens.aquarium.offchain.model.loans.LiquidationExclusion} constant the
     * scanner already excludes on, or {@code "OracleEntry.usableForLiquidation"} for the oracle
     * checks the scanner defers to that method for.
     */
    String scannerFilter();

    /** Why this particular constant, in this particular builder check, is the unreachable one. */
    String reason();
}
