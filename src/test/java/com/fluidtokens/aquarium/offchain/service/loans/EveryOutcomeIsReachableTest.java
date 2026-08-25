package com.fluidtokens.aquarium.offchain.service.loans;

import com.fluidtokens.aquarium.offchain.model.loans.LiquidationDecision;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every {@link LiquidationDecision.Outcome} must actually be recorded somewhere.
 *
 * <h2>The defect shape this guards</h2>
 * {@code Outcome.QUARANTINED} was added on 2026-08-25 because the quarantine skip was the <b>only
 * silent exit</b> from {@code consider()} — five of its six paths recorded a decision and that one
 * returned with a debug line, so a held loan was indistinguishable from a loan that was never a
 * candidate. <b>Absence of a record is not a record of absence</b>, and that ambiguity is the same
 * one a quiet market has with a dead deployment.
 *
 * <p>An outcome that is declared and never recorded reproduces exactly that state while <em>looking
 * fixed</em>: the enum documents a case the operator will never be shown. Nothing else in the build
 * can notice, because an unrecorded outcome produces no failure — it produces silence.
 *
 * <h2>⚠ What this does and does not prove</h2>
 * It reads the executor's <b>source</b> and asserts each outcome name appears in it. That proves the
 * value is wired to a call site; it does <b>not</b> prove the call site is reachable, or that the
 * branch guarding it is right. The behavioural rigs that could prove reachability all build on
 * {@code LoanFixtures} and are currently red against a superseded deployment
 * (see {@code docs/tests-pinned-to-chain-state.md}), so this deliberately checks the one thing it can
 * check honestly rather than implying coverage it does not have.
 */
class EveryOutcomeIsReachableTest {

    private static final Path EXECUTOR = Path.of(
            "src/main/java/com/fluidtokens/aquarium/offchain/service/loans/LiquidationExecutor.java");

    @Test
    void everyDeclaredOutcomeIsRecordedByTheExecutor() throws IOException {
        assertTrue(Files.isRegularFile(EXECUTOR),
                "the executor source moved to somewhere this test cannot read (" + EXECUTOR
                        + "), so it is no longer checking anything — repoint it rather than delete it");
        String source = Files.readString(EXECUTOR);

        List<String> unrecorded = new ArrayList<>();
        for (LiquidationDecision.Outcome outcome : LiquidationDecision.Outcome.values()) {
            if (!source.contains("Outcome." + outcome.name())) {
                unrecorded.add(outcome.name());
            }
        }

        assertTrue(unrecorded.isEmpty(),
                ("these outcomes are declared but never recorded, so an operator can never be shown "
                        + "them and the state they describe stays indistinguishable from silence: %s")
                        .formatted(unrecorded));
    }

    /**
     * The quarantine outcome named explicitly, because it is the one this class was written for and a
     * generic loop would stop mentioning it the moment someone removed the value.
     */
    @Test
    void theQuarantineSkipRecordsRatherThanReturningSilently() throws IOException {
        String source = Files.readString(EXECUTOR);
        int skip = source.indexOf("if (isQuarantined(loanUtxoRef, now))");
        assertTrue(skip > 0, "the quarantine skip is no longer where this test expects it");

        String branch = source.substring(skip, Math.min(source.length(), skip + 1_400));
        int record = branch.indexOf("decisionLog.record(");
        int returned = branch.indexOf("return;");
        assertTrue(record > 0 && record < returned,
                "the quarantine branch returns without recording a decision — a held loan is once "
                        + "again indistinguishable from one that was never considered");
        assertTrue(branch.contains("Outcome.QUARANTINED"),
                "the quarantine branch records some other outcome, which would misreport the bot's "
                        + "own hold as a judgement about the loan");
    }
}
