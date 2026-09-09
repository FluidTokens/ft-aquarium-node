package com.fluidtokens.aquarium.offchain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-077 — the tripwire on the epic's one EXCLUSION.
 *
 * <h2>Why an exclusion needs a test at all</h2>
 * {@code LiquidateConvertAndCompound} is excluded from the liquidation-completeness epic because its
 * deployed validator refuses everything: at upstream commit {@code e0b818e} (the commit whose
 * {@code plutus.json} is byte-identical to our vendored {@code loans-v4.plutus.json}),
 * {@code lm_liquidate_convert_and_compound_action.ak} is thirteen lines whose {@code withdraw}
 * handler is an unconditional {@code False} and whose {@code else(_)} fails every other purpose —
 * with the comment <i>"We need to be DEX batchers to do this"</i>. The exclusion is total.
 *
 * <p><b>But an exclusion produces no failure when it expires.</b> There have been three redeploys in
 * six weeks; the next one can replace that stub with a real handler, and nothing anywhere would
 * notice — an excluded thing has no test to go red, no builder to break, no candidate to refuse.
 * "Completeness" would silently stop being complete. This class is the mechanism the adversarial
 * review (finding H-3) said the decision lacked: it pins the exclusion to its evidence, so the
 * evidence expiring is a red test rather than a silence.
 *
 * <h2>What is pinned, and why it survives coordinate redeploys</h2>
 * The stub is the one lender-manager action the registry derives with NO config parameters
 * ({@code LoansContractRegistry}: {@code derive("…convert_and_compound_action.actionValidator")},
 * no arguments) — so its compiled code in the blueprint is deployment-independent. A redeploy that
 * only re-mints config NFTs moves every parameterised hash and leaves this one alone. ⇒ This pin
 * fires exactly when the ARTEFACT changes — a re-vendored blueprint — which is precisely when the
 * source must be re-read at the new deployed sha before the exclusion is kept.
 *
 * <p>⚠ If this test goes red: do NOT update the constants to make it green. Re-derive the deployed
 * sha (hash the vendored artefact, search upstream for the match), read
 * {@code validators/lender-manager/lm_liquidate_convert_and_compound_action.ak} at that sha, and
 * either re-affirm the exclusion with the new bytes or REOPEN it in the epic. The pin's value is
 * disposable; its drift-detection is not.
 */
class ExcludedLiquidationActionTest {

    private static final String TITLE_PREFIX =
            "lender_manager/lm_liquidate_convert_and_compound_action.actionValidator";

    /**
     * The stub's entire compiled code, from the vendored blueprint — 85 bytes, small enough to pin
     * whole. Transcribed from {@code loans-v4.plutus.json} on 2026-08-28, whose sha256
     * ({@code a55a1c2e…}) matches upstream {@code e0b818e} and no other commit.
     */
    private static final String STUB_COMPILED_CODE =
            "585301010029800aba2aba1aab9eaab9dab9a4888896600264653001300600198031803800cc0180092225"
                    + "980099b8748010c01cdd500144c928180498041baa0028b200c180300098019baa0068a4d13656"
                    + "400401";

    private static final String STUB_HASH = "435b42cc200719c3868dfe01689ee07e2eeff5f5809f25408cbe4e7d";

    private static List<JsonNode> validators() throws Exception {
        try (InputStream in = ExcludedLiquidationActionTest.class
                .getResourceAsStream("/loans-v4.plutus.json")) {
            assertNotNull(in, "loans-v4.plutus.json is not on the classpath — this test would be vacuous");
            JsonNode root = new ObjectMapper().readTree(in);
            List<JsonNode> out = new ArrayList<>();
            root.get("validators").forEach(out::add);
            return out;
        }
    }

    /**
     * The evidence pin. Both blueprint entries for the excluded action (withdraw and else) must still
     * carry the exact stub bytes read at {@code e0b818e}. A re-vendored blueprint with a real
     * implementation changes these bytes and fails here, which is the tripwire firing as designed.
     */
    @Test
    void theExcludedActionIsStillTheThirteenLineStub() throws Exception {
        List<JsonNode> entries = validators().stream()
                .filter(v -> v.get("title").asText().startsWith(TITLE_PREFIX))
                .toList();
        assertEquals(2, entries.size(),
                "expected the withdraw and else entries for the excluded action; the blueprint's shape "
                        + "changed and the exclusion must be re-derived at the new deployed sha");
        for (JsonNode entry : entries) {
            assertEquals(STUB_COMPILED_CODE, entry.get("compiledCode").asText(),
                    entry.get("title").asText() + " no longer carries the unconditional-False stub. "
                            + "DO NOT update this constant to go green — re-read the validator at the "
                            + "newly deployed sha and re-affirm or REOPEN the exclusion");
            assertEquals(STUB_HASH, entry.get("hash").asText());
        }
    }

    /**
     * The structural contrast that makes "stub" a measurement rather than a label: every OTHER
     * lender-manager action validator is at least an order of magnitude larger. If this margin ever
     * collapses, either a real action shrank implausibly or the stub grew — both worth eyes.
     */
    @Test
    void everyOtherLenderManagerActionIsAtLeastTenTimesLarger() throws Exception {
        int stubBytes = STUB_COMPILED_CODE.length() / 2;
        List<String> tooSmall = new ArrayList<>();
        boolean sawOthers = false;
        for (JsonNode v : validators()) {
            String title = v.get("title").asText();
            if (!title.startsWith("lender_manager/lm_") || title.startsWith(TITLE_PREFIX)) {
                continue;
            }
            sawOthers = true;
            int bytes = v.get("compiledCode").asText().length() / 2;
            if (bytes < stubBytes * 10) {
                tooSmall.add(title + " (" + bytes + " bytes)");
            }
        }
        assertTrue(sawOthers, "no other lender-manager actions found — the title filter is broken");
        assertTrue(tooSmall.isEmpty(),
                "these lender-manager actions are within 10x of the excluded stub's " + stubBytes
                        + " bytes, so size no longer separates a stub from an implementation: "
                        + tooSmall);
    }

    /**
     * Negative control on the pin itself: the constant must actually be the stub and not a stale
     * paste — 85 bytes, and identical across both purposes, which is what an unconditional refusal
     * compiles to and a real implementation (whose withdraw and else differ) would not.
     */
    @Test
    void thePinnedStubIsInternallyConsistent() {
        assertEquals(85, STUB_COMPILED_CODE.length() / 2, "the stub is 85 compiled bytes");
        assertFalse(STUB_COMPILED_CODE.isEmpty());
    }
}
