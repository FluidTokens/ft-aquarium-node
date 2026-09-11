package com.fluidtokens.aquarium.offchain;

import com.fluidtokens.aquarium.offchain.service.LoansConfigVerifier;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link LoansConfigVerifier}'s comparison against the two config datums as they
 * were actually recorded from preview tx {@code 8dd38e97…091c} — the FOURTH deployment (fixtures
 * under {@code src/test/resources/loans-v4}, copied from that transaction's inline datums).
 * <p>
 * The fragile part of the verifier is the set of hardcoded datum field indices — if one is
 * wrong, the verifier compares the wrong field and either passes vacuously or blocks startup
 * for no reason. This test pins them without needing Blockfrost.
 */
class LoansConfigVerifierTest {

    private static final String CONFIG_POLICY_ID = "d46f626fc11750409cf44f3d202f48d1b5df41ad35d62a7364b8e22e";
    private static final String LM_CONFIG_POLICY_ID = "a7d4b762c5a6197ab3b169c2ff1945fdcd4c21cc5f4c180e75441a13";
    private static final String CONFIG_ASSET_NAME = "706172616d6574657273";
    private static final String SMART_TOKENS_SPEND = "fca77bcce1e5e73c97a0bfa8c90f7cd2faff6fd6ed5b6fec1c04eefa";
    private static final String PREVIEW_POOL_SELL_MISMATCH =
            "ConfigDatum[24]: derived cdfa58c27aee3458983247dcde6419e6e8ca13b30e5ba34f95feccb0, "
                    + "chain db9a5bf043f37e744bbb43b96ec89a3e175f7c5523d02dd563ed9c56";
    private static final String PREVIEW_LM_COMPOUND_MISMATCH =
            "LMConfigDatum[3]: derived 7dbcad0e76f5c639c96dd7ffc52f730d1ac0290c46c04fe343edc49b, "
                    + "chain dd4709091734af2dc36321e774cf496222a1f92377ad6c5bef100457";

    private static final String CONFIG_DATUM = fixture("fourth-deployment-config-datum.hex");
    private static final String LM_CONFIG_DATUM = fixture("fourth-deployment-lm-config-datum.hex");

    private static String fixture(String name) {
        try (InputStream is = LoansConfigVerifierTest.class.getResourceAsStream("/loans-v4/" + name)) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read fixture " + name, e);
        }
    }

    private static LoansConfigVerifier verifier(String configPolicyId, String smartTokens) {
        var registry = new LoansContractRegistry(configPolicyId, LM_CONFIG_POLICY_ID, CONFIG_ASSET_NAME, smartTokens);
        // network / backend are only touched by the fetch path, which this test bypasses.
        return new LoansConfigVerifier(registry, smartTokens, null, null, false);
    }

    @Test
    void reportsTheAcceptedLatestBlueprintDeltaFromCapturedPreview() {
        LoansConfigVerifier v = verifier(CONFIG_POLICY_ID, SMART_TOKENS_SPEND);

        List<String> mismatches = v.verifyAgainst(CONFIG_DATUM, LM_CONFIG_DATUM);

        assertEquals(List.of(PREVIEW_POOL_SELL_MISMATCH, PREVIEW_LM_COMPOUND_MISMATCH), mismatches,
                "captured preview remains honest while the latest artifact is ahead of that deployment");
        assertEquals(SMART_TOKENS_SPEND, v.getOnChainSmartTokensSpendScriptHash(),
                "smartTokensSpendScriptHash read back from ConfigDatum field 0");
    }

    /**
     * The reason this class exists: a redeploy changes the config policy id, every derived
     * hash shifts, and the node must refuse to start rather than index a dead deployment.
     */
    @Test
    void rejectsASupersededDeployment() {
        // The real config policy id of the SECOND deployment, superseded by the 2026-08-17 one the
        // fixtures now carry. This is the exact staleness this class is meant to catch, and it is
        // the value this test itself was pinned to until that redeploy.
        String stale = "f1a475ea8cccc1e0b7a59b10e79ad171452dc057ffb1cda0df92835c";

        List<String> mismatches = verifier(stale, SMART_TOKENS_SPEND).verifyAgainst(CONFIG_DATUM, LM_CONFIG_DATUM);

        assertFalse(mismatches.isEmpty(), "stale config policy id must be detected");
        assertTrue(mismatches.stream().anyMatch(m -> m.startsWith("ConfigDatum[10]")),
                "loanSpendScriptHash must be among the mismatches, got: " + mismatches);
    }

    /** A stale smart-tokens hash silently corrupts the pool-manager branch, so it is checked too. */
    @Test
    void rejectsAStaleSmartTokensHash() {
        String wrong = "00".repeat(28);

        List<String> mismatches = verifier(CONFIG_POLICY_ID, wrong).verifyAgainst(CONFIG_DATUM, LM_CONFIG_DATUM);

        assertTrue(mismatches.stream().anyMatch(m -> m.startsWith("ConfigDatum[0]")),
                "smartTokensSpendScriptHash mismatch must be reported, got: " + mismatches);
    }

    /**
     * Without smartTokensSpendScriptHash the pool-manager hashes cannot be derived. Those
     * fields must be skipped, not reported as mismatches. The independently derivable pool-sell
     * field still reports the accepted latest-blueprint delta from captured preview.
     */
    @Test
    void skipsUnderivableFieldsInsteadOfFailing() {
        LoansConfigVerifier v = verifier(CONFIG_POLICY_ID, null);

        assertEquals(List.of(PREVIEW_POOL_SELL_MISMATCH), v.verifyAgainst(CONFIG_DATUM, LM_CONFIG_DATUM),
                "partial derivation must retain only the independently derivable preview delta");
    }
}
