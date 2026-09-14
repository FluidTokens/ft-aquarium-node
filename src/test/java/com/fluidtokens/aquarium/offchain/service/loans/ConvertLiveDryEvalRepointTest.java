package com.fluidtokens.aquarium.offchain.service.loans;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ConvertLiveDryEvalTest#repoint} must fail closed.
 *
 * <h2>Why this class exists separately</h2>
 * {@code ConvertLiveDryEvalTest} is gated on {@code BLOCKFROST_MAINNET_KEY} and targets mainnet, so a
 * test written inside it skips in CI and proves nothing — the exact defect shape this audit spent two
 * turns on. {@code repoint} is a pure string function, so it can be exercised cold, with no key and no
 * network, from here.
 *
 * <h2>What it guards</h2>
 * The rig rewrites live-fetched config datums so they name the traced scripts instead of the real
 * ones. That rewrite used to be a bare {@code String.replace} per entry, which cannot distinguish
 * "rewrote the field I meant" from "matched nothing at all". A substitution that silently matches
 * nothing leaves a datum still naming the REAL hash while the universe supplies the TRACED script —
 * the two disagree, and the rig reports a validator failure that is an artefact of its own plumbing.
 *
 * <p>Its sibling {@link LoanFixtures#syntheticLatestConfigUtxo} already refuses a non-unique match for
 * the same reason. This brings the live rig up to that standard.
 *
 * <h2>The split is real, and it is why "must be present" is the wrong invariant</h2>
 * {@code SWAPPED} carries two substitutions: {@code loanClaimActionScriptHash}, which lives in the
 * MAIN {@code ConfigDatum}, and {@code lmLiquidateAndConvertActionScriptHash}, which lives in the
 * {@code LMConfigDatum}. Each is legitimately absent from the other datum. So the invariant is
 * <b>at most once per datum</b>, plus <b>at least once across the pair</b>.
 */
class ConvertLiveDryEvalRepointTest {

    /** Stands in for loanClaimActionScriptHash — ConfigDatum[11], main config datum only. */
    private static final String REAL_CLAIM = "11".repeat(28);
    private static final String TRACED_CLAIM = "aa".repeat(28);

    /** Stands in for lmLiquidateAndConvertActionScriptHash — LMConfigDatum[5], LM datum only. */
    private static final String REAL_CONVERT = "22".repeat(28);
    private static final String TRACED_CONVERT = "bb".repeat(28);

    /** Present in neither datum: the silent no-op this class exists to catch. */
    private static final String REAL_ABSENT = "33".repeat(28);
    private static final String TRACED_ABSENT = "cc".repeat(28);

    private static final String MAIN_DATUM = "d8799f581c" + REAL_CLAIM + "581c" + "44".repeat(28) + "ff";
    private static final String LM_DATUM = "d8799f581c" + "55".repeat(28) + "581c" + REAL_CONVERT + "ff";

    private static Map<String, String> swapped() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(REAL_CLAIM, TRACED_CLAIM);
        m.put(REAL_CONVERT, TRACED_CONVERT);
        return m;
    }

    /**
     * The ordinary path, and the leg that must stay green in the red run: each substitution rewrites
     * exactly one of the two datums, neither datum is otherwise disturbed, and nothing is left over.
     */
    @Test
    void anOrdinarySplitAcrossTwoDatumsRewritesEachOnceAndLeavesNothingUnapplied() {
        Map<String, String> swapped = swapped();
        Map<String, Integer> applied = new LinkedHashMap<>();

        String main = ConvertLiveDryEvalTest.repoint(MAIN_DATUM, swapped, applied);
        String lm = ConvertLiveDryEvalTest.repoint(LM_DATUM, swapped, applied);

        assertTrue(main.contains(TRACED_CLAIM), "the main datum must now name the traced claim action");
        assertFalse(main.contains(REAL_CLAIM), "the main datum must no longer name the real claim action");
        assertTrue(lm.contains(TRACED_CONVERT), "the LM datum must now name the traced convert action");
        assertFalse(lm.contains(REAL_CONVERT), "the LM datum must no longer name the real convert action");

        // Untouched neighbours survive byte-for-byte: the rewrite is surgical, not a reformat.
        assertTrue(main.contains("44".repeat(28)), "an unrelated main-datum field was disturbed");
        assertTrue(lm.contains("55".repeat(28)), "an unrelated LM-datum field was disturbed");

        assertEquals(List.of(), ConvertLiveDryEvalTest.unappliedSubstitutions(swapped, applied),
                "every declared substitution rewrote something, so none may be reported unapplied");
    }

    /**
     * ⛔ THE DEFECT. A substitution present in neither datum used to rewrite nothing and say nothing.
     * The rig then built against a universe whose config datums still named the real hashes while the
     * script supplier served the traced ones — a disagreement invisible until a validator refused it.
     */
    @Test
    void aSubstitutionThatMatchesNothingAnywhereIsReported() {
        Map<String, String> swapped = swapped();
        swapped.put(REAL_ABSENT, TRACED_ABSENT);
        Map<String, Integer> applied = new LinkedHashMap<>();

        ConvertLiveDryEvalTest.repoint(MAIN_DATUM, swapped, applied);
        ConvertLiveDryEvalTest.repoint(LM_DATUM, swapped, applied);

        assertEquals(List.of(REAL_ABSENT), ConvertLiveDryEvalTest.unappliedSubstitutions(swapped, applied),
                "a substitution that rewrote nothing in either datum must be reported, not ignored");
    }

    /**
     * The other half of fail-closed: an ambiguous match. Two occurrences in one datum mean the
     * substitution cannot say which field it meant, and {@code String.replace} would quietly rewrite
     * both — including a field nobody declared.
     */
    @Test
    void aSubstitutionOccurringTwiceInOneDatumIsRefused() {
        Map<String, String> swapped = Map.of(REAL_CLAIM, TRACED_CLAIM);
        String ambiguous = "d8799f581c" + REAL_CLAIM + "581c" + REAL_CLAIM + "ff";

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ConvertLiveDryEvalTest.repoint(ambiguous, swapped, new LinkedHashMap<>()),
                "two occurrences are ambiguous and must be refused, not rewritten twice");
        assertTrue(e.getMessage().contains(REAL_CLAIM),
                "the refusal must name the offending substitution, got: " + e.getMessage());
    }
}
