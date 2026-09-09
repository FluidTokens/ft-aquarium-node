package com.fluidtokens.aquarium.offchain.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-057 — <b>a reviewer can tell a deliberate omission from a missing one.</b>
 *
 * <h2>Why this is a declaration and not a refactor</h2>
 * Three builders configure a {@code QuickTxBuilder.TxContext}, and they do <em>not</em> configure it
 * identically — nor should they. The tank submits and the liquidation builders are deliberately
 * submit-incapable; the tank has no reference scripts; only the liquidation paths nominate their own
 * collateral. <b>Forcing identity would be the wrong fix</b>, and it would make the next genuine
 * divergence undeclarable.
 * <p>
 * So this asserts <b>the same set of DECISIONS, not the same code</b>. Every builder must have an
 * entry for every knob: either it sets it, or the omission is declared here with a reason.
 * <b>The declaration IS the diff.</b> Add a knob to one builder and this fails until you say what
 * the other two do about it.
 *
 * <h2>⚑ Why this ticket moved up the queue</h2>
 * Its outcome stopped being a principle and became a defect, twice in one day, both times ahead of
 * this ticket:
 * <ul>
 *   <li><b>The drifted capacity check.</b> T-050 chose collateral inputs separately and T-052 shrank
 *       the nominated wallet utxo. Each was correct alone; the guard between them still measured the
 *       pre-T-050 world, and <em>neither ticket's tests covered the pair</em>.</li>
 *   <li><b>T-061.</b> Two refusal sites, each complete from where it stood, each naming one of two
 *       gates — and the second was written hours after the first by the same author.</li>
 * </ul>
 *
 * <h2>⚠ What this test cannot do</h2>
 * It reads <b>source text</b>, with comments stripped so prose about a knob is not mistaken for a
 * call to it. That is coarse: it sees whether a knob is mentioned in a file, not whether it is
 * reached at runtime, and a knob configured through a helper this test does not know about would
 * read as absent. <b>It is a declaration check, not a behaviour check</b> — its job is to make a
 * divergence impossible to introduce silently, not to prove any builder correct.
 */
class TxContextDeclarationTest {

    private enum Decision { SET, OMITTED }

    private record Entry(Decision decision, String reason) {
        static Entry set() {
            return new Entry(Decision.SET, null);
        }

        static Entry omitted(String reason) {
            return new Entry(Decision.OMITTED, reason);
        }
    }

    private static final Path LIQ = Path.of(
            "src/main/java/com/fluidtokens/aquarium/offchain/service/loans/LiquidateTransactionBuilder.java");
    private static final Path CONVERT = Path.of(
            "src/main/java/com/fluidtokens/aquarium/offchain/service/loans/LiquidatePayInAdvanceTransactionBuilder.java");
    private static final Path TANK = Path.of(
            "src/main/java/com/fluidtokens/aquarium/offchain/service/ScheduledTransactionService.java");

    /**
     * The declaration. Every knob × every builder, and an {@code omitted} entry must say <b>why</b> —
     * a reason a reviewer can disagree with is the entire product of this test.
     */
    private static Map<String, Map<Path, Entry>> declaration() {
        Map<String, Map<Path, Entry>> d = new LinkedHashMap<>();

        // ---- set everywhere: the shape every transaction needs -------------------------------
        for (String knob : List.of("feePayer", "collateralPayer", "validFrom", "validTo",
                "mergeOutputs", "ignoreScriptCostEvaluationError", "withUtxoSelectionStrategy",
                "preBalanceTx", "withChangeAddress")) {
            d.put(knob, new LinkedHashMap<>(Map.of(LIQ, Entry.set(), CONVERT, Entry.set(), TANK, Entry.set())));
        }

        // ---- V5: the structural assertion, installed INSIDE the build pipeline (T-054) --------
        d.put("postBalanceTx", new LinkedHashMap<>(Map.of(
                LIQ, Entry.set(), CONVERT, Entry.set(),
                // ⛔ THE MAINNET PATH HAS NO STRUCTURAL ASSERTIONS AT ALL. This is T-059, queued and
                // NOT prioritised — Giovanni's scope call, because the CCL review's brief was the
                // review and this is new work. Declared here so it is a decision, not an oversight.
                TANK, Entry.omitted("T-059: the tank asserts nothing about the body it built. QUEUED, "
                        + "not decided — and it is the only path that runs on MAINNET"))));

        // ---- collateral: nominated by us on the liquidation paths (T-050) --------------------
        d.put("withCollateralInputs", new LinkedHashMap<>(Map.of(
                LIQ, Entry.set(), CONVERT, Entry.set(),
                TANK, Entry.omitted("relies on cardano-client-lib's automatic collateral, which at "
                        + "0.7.2 builds its own selection strategy invisible to withUtxoSelectionStrategy "
                        + "and hardcodes 5 ADA. Structurally unguardable, not an oversight"))));

        // ---- evaluation ----------------------------------------------------------------------
        d.put("withTxEvaluator", new LinkedHashMap<>(Map.of(
                LIQ, Entry.set(), CONVERT, Entry.set(),
                TANK, Entry.omitted("submits via completeAndWait(), so QuickTxBuilder wires the "
                        + "TransactionProcessor as the evaluator itself; supplying one would be "
                        + "redundant, not absent"))));

        // ---- signing: the liquidation builders must NOT be able to submit ---------------------
        for (String knob : List.of("withSigner", "withRequiredSigners")) {
            d.put(knob, new LinkedHashMap<>(Map.of(
                    LIQ, Entry.omitted("DELIBERATELY SUBMIT-INCAPABLE: these call build(), never "
                            + "complete(); the executor signs and submits separately behind eight vetoes"),
                    CONVERT, Entry.omitted("as LIQ — submit-incapable by construction"),
                    TANK, Entry.set())));
        }

        // ---- reference scripts ----------------------------------------------------------------
        for (String knob : List.of("withReferenceScripts", "removeDuplicateScriptWitnesses")) {
            d.put(knob, new LinkedHashMap<>(Map.of(
                    LIQ, Entry.set(), CONVERT, Entry.set(),
                    TANK, Entry.omitted("the tank references no scripts, so there is no ref-script fee "
                            + "to pay and no duplicate witness to strip (CCL trap 9)"))));
        }

        // ---- the two hooks that do not work on a build() path (T-054, T-055) ------------------
        d.put("withVerifier", new LinkedHashMap<>(Map.of(
                LIQ, Entry.omitted("consulted only inside complete() — the SUBMIT path — and this "
                        + "builder calls build(). It would be dead code, not a safeguard"),
                CONVERT, Entry.omitted("as LIQ — dead on a build() path"),
                // ✅ T-059 slice 1: NOW SET. The tank submits through completeAndWait(), so this is
                // reached — the one place in this arc where the obviously-named API is the right one.
                // This entry was OMITTED with the reason "available here and not used, left for
                // T-059", and THIS TEST IS WHAT CAUGHT THE DECLARATION GOING STALE when the hook was
                // added: the change was made, the suite went red, and the fix was to say so here.
                TANK, Entry.set())));
        d.put("withTxInspector", new LinkedHashMap<>(Map.of(
                LIQ, Entry.omitted("consulted only inside complete() — dead on a build() path"),
                CONVERT, Entry.omitted("as LIQ — dead on a build() path"),
                TANK, Entry.omitted("AVAILABLE HERE and still not used: completeAndWait() reaches it, "
                        + "but withVerifier already carries the structural assertion and an inspector "
                        + "that only logs would duplicate it. Deliberate, not overlooked"))));
        return d;
    }

    /** Comments stripped, so prose ABOUT a knob is never mistaken for a call TO it. */
    private static String code(Path path) throws IOException {
        String src = Files.readString(path);
        src = src.replaceAll("(?s)/\\*.*?\\*/", " ");
        src = src.replaceAll("(?m)//.*$", " ");
        return src;
    }

    @Test
    void everyBuilderDeclaresWhatItDoesAboutEveryTxContextKnob() throws IOException {
        Map<Path, String> sources = new LinkedHashMap<>();
        for (Path p : List.of(LIQ, CONVERT, TANK)) {
            assertTrue(Files.exists(p), "declared source is missing: " + p);
            sources.put(p, code(p));
        }

        List<String> problems = new ArrayList<>();
        declaration().forEach((knob, perBuilder) -> perBuilder.forEach((path, entry) -> {
            boolean present = sources.get(path).contains("." + knob + "(");
            if (entry.decision() == Decision.SET && !present) {
                problems.add(("%s: declared SET on %s but no call is present. Either it was removed "
                        + "and the declaration is now a lie, or it moved somewhere this text check "
                        + "cannot see.").formatted(knob, path.getFileName()));
            }
            if (entry.decision() == Decision.OMITTED && present) {
                problems.add(("%s: declared OMITTED on %s (\"%s\") but a call IS present. If that is "
                        + "deliberate, update the declaration and say why — THAT EDIT IS THE POINT OF "
                        + "THIS TEST.").formatted(knob, path.getFileName(), entry.reason()));
            }
        }));

        assertTrue(problems.isEmpty(),
                "the TxContext declaration no longer matches the builders:\n  - "
                        + String.join("\n  - ", problems));
    }

    /**
     * Positive control. Without this, a declaration listing knobs that exist nowhere would pass
     * vacuously — the blind-instrument failure this suite has met repeatedly, most recently in its
     * own test scaffolding.
     */
    @Test
    void theCheckActuallyFindsCallsAndActuallyFindsTheirAbsence() throws IOException {
        String liq = code(LIQ);
        assertTrue(liq.contains(".feePayer("), "the matcher finds a knob that is definitely set");
        assertTrue(!liq.contains(".withVerifier("),
                "and finds the absence of one that is definitely not — if this ever becomes present, "
                        + "the declaration above is wrong and says so");
    }

    /** Every omission carries a reason. An undeclared 'OMITTED' would be the very thing this prevents. */
    @Test
    void everyDeclaredOmissionGivesAReason() {
        List<String> bare = new ArrayList<>();
        declaration().forEach((knob, perBuilder) -> perBuilder.forEach((path, entry) -> {
            if (entry.decision() == Decision.OMITTED
                    && (entry.reason() == null || entry.reason().isBlank())) {
                bare.add(knob + " on " + path.getFileName());
            }
        }));
        assertTrue(bare.isEmpty(), "omissions declared without a reason are just absences: " + bare);
    }
}
