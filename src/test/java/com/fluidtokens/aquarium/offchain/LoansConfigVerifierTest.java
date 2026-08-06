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
 * were actually recorded from preview tx {@code 6de7b7ec…094} (fixtures under
 * {@code src/test/resources/loans-v4}).
 * <p>
 * The fragile part of the verifier is the set of hardcoded datum field indices — if one is
 * wrong, the verifier compares the wrong field and either passes vacuously or blocks startup
 * for no reason. This test pins them without needing Blockfrost.
 */
class LoansConfigVerifierTest {

    private static final String CONFIG_POLICY_ID = "f1a475ea8cccc1e0b7a59b10e79ad171452dc057ffb1cda0df92835c";
    private static final String LM_CONFIG_POLICY_ID = "d0998754ddc3e9cfe80356d7e12db163d03cecc5b6b438dad4f4a3e3";
    private static final String CONFIG_ASSET_NAME = "706172616d6574657273";
    private static final String SMART_TOKENS_SPEND = "fca77bcce1e5e73c97a0bfa8c90f7cd2faff6fd6ed5b6fec1c04eefa";

    private static final String CONFIG_DATUM = fixture("preview-config-datum.hex");
    private static final String LM_CONFIG_DATUM = fixture("preview-lm-config-datum.hex");

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
    void acceptsTheDeploymentItWasConfiguredFor() {
        LoansConfigVerifier v = verifier(CONFIG_POLICY_ID, SMART_TOKENS_SPEND);

        List<String> mismatches = v.verifyAgainst(CONFIG_DATUM, LM_CONFIG_DATUM);

        assertEquals(List.of(), mismatches, "a correctly configured node must verify clean");
        assertEquals(SMART_TOKENS_SPEND, v.getOnChainSmartTokensSpendScriptHash(),
                "smartTokensSpendScriptHash read back from ConfigDatum field 0");
    }

    /**
     * The reason this class exists: a redeploy changes the config policy id, every derived
     * hash shifts, and the node must refuse to start rather than index a dead deployment.
     */
    @Test
    void rejectsASupersededDeployment() {
        // The real config policy id from before the 2026-08-05 preview redeploy.
        String stale = "0e60ea5f8db9d62d5994e983f4901a737271908cd0f59033f154b845";

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
     * fields must be skipped, not reported as mismatches — the liquidation path still starts.
     */
    @Test
    void skipsUnderivableFieldsInsteadOfFailing() {
        LoansConfigVerifier v = verifier(CONFIG_POLICY_ID, null);

        assertEquals(List.of(), v.verifyAgainst(CONFIG_DATUM, LM_CONFIG_DATUM),
                "a partial derivation must verify clean on the fields it can derive");
    }
}
