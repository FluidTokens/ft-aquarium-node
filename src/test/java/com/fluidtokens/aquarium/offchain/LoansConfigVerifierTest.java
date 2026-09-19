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
    /**
     * ⛔ THE ACCEPTED PREVIEW DIVERGENCE — NINE FIELDS SINCE 2026-09-17, was two.
     *
     * <p>These are NOT defects. The image ships one artefact and preview has not been migrated, so
     * every validator FluidTokens changed between the artefact preview was deployed from
     * (revision 4c4d143) and the one shipped now diverges from the captured preview datums. Each
     * entry below is explained by a source change or a cascade from one:
     *
     * <ul>
     *   <li>[2] pool policy, [8] pool spend — {@code pool.ak} changed</li>
     *   <li>[14] loan recast — {@code loan_recast_action.ak} changed</li>
     *   <li>[24] pool sell-lender-position — the original known divergence (revision 4c4d143)</li>
     *   <li>[26] pool-manager spend, [27] pool-manager policy — cascade from the pool family</li>
     *   <li>[28] locked-borrower-manager spend — {@code locked_borrower_manager.ak} changed</li>
     *   <li>LM[3] compound action, LM[6] liquidate-pay-in-advance-and-compound — cascade from
     *       pool-manager</li>
     * </ul>
     *
     * <p>⚠ Indices 26-28 are the 29-FIELD shape: these captured datums predate the
     * {@code poolEditActionScriptHash} insertion at index 26, so nothing here is shifted.
     *
     * <p><b>Do not update the captured bytes to make this green.</b> Pinning the LIST is the point —
     * it fails when the divergence CHANGES, which is the signal that either the artefact moved or
     * preview finally did.
     */
    private static final String PREVIEW_POOL_SELL_MISMATCH =
            "ConfigDatum[24]: derived cdfa58c27aee3458983247dcde6419e6e8ca13b30e5ba34f95feccb0, "
                    + "chain db9a5bf043f37e744bbb43b96ec89a3e175f7c5523d02dd563ed9c56";

    /**
     * The subset that survives when the pool-manager branch cannot be derived at all — no
     * {@code smartTokensSpendScriptHash}, so {@code poolManager*}, {@code lmCompoundAction} and
     * {@code lmLiquidatePayInAdvanceAndCompound} are null and are SKIPPED rather than reported as
     * mismatches. Exactly {@link #PREVIEW_DIVERGENCE} minus [26], [27], LM[3] and LM[6].
     */
    private static final java.util.List<String> PREVIEW_DIVERGENCE_WITHOUT_POOL_MANAGER =
            java.util.List.of(
            "ConfigDatum[2]: derived 1c330cfbd58d994945d29c7c52ec001d054b93f733317ec59d9a0537, "
                    + "chain a33aee4034165f1772e57af5fb975f26c35f7e9080b7e44b4634f227",
            "ConfigDatum[8]: derived 515009399bc0fd2bb204b0a50973a1285415159bed4213e315d739b7, "
                    + "chain bf8c4378bab7de15baddbb5d8805255d89174c08bf36179c21cad685",
            "ConfigDatum[14]: derived 21f4bde7524bbab159eb0293dac262f1193c6266385d983ee761e363, "
                    + "chain 1628910a5fbdba415c3b1bf7304672106659ac527442f10701472753",
            "ConfigDatum[24]: derived cdfa58c27aee3458983247dcde6419e6e8ca13b30e5ba34f95feccb0, "
                    + "chain db9a5bf043f37e744bbb43b96ec89a3e175f7c5523d02dd563ed9c56",
            "ConfigDatum[28]: derived f74b887491c86b1a1b7785c01f15cb7551f4520174589e22efb0df02, "
                    + "chain d815766d61c1241742ff78164cdf8edaef1746a99a242a7fb7938aa6");

    private static final java.util.List<String> PREVIEW_DIVERGENCE = java.util.List.of(
            "ConfigDatum[2]: derived 1c330cfbd58d994945d29c7c52ec001d054b93f733317ec59d9a0537, "
                    + "chain a33aee4034165f1772e57af5fb975f26c35f7e9080b7e44b4634f227",
            "ConfigDatum[8]: derived 515009399bc0fd2bb204b0a50973a1285415159bed4213e315d739b7, "
                    + "chain bf8c4378bab7de15baddbb5d8805255d89174c08bf36179c21cad685",
            "ConfigDatum[14]: derived 21f4bde7524bbab159eb0293dac262f1193c6266385d983ee761e363, "
                    + "chain 1628910a5fbdba415c3b1bf7304672106659ac527442f10701472753",
            "ConfigDatum[24]: derived cdfa58c27aee3458983247dcde6419e6e8ca13b30e5ba34f95feccb0, "
                    + "chain db9a5bf043f37e744bbb43b96ec89a3e175f7c5523d02dd563ed9c56",
            "ConfigDatum[26]: derived 1322b6d1e7e46ac543769a8fcfb43840606f040d2e9efa2e59389d86, "
                    + "chain b4ad9a6f2710d68067177e0de5a4378ebe4fcdfdc929c7488479c313",
            "ConfigDatum[27]: derived c2023c909f66d50886e82ba86a03382313bbb8e994789e0aba588b3d, "
                    + "chain 45ce890c9bcf70f6eed629b5db7c0622e44ca1003e001a2cf951518f",
            "ConfigDatum[28]: derived f74b887491c86b1a1b7785c01f15cb7551f4520174589e22efb0df02, "
                    + "chain d815766d61c1241742ff78164cdf8edaef1746a99a242a7fb7938aa6",
            "LMConfigDatum[3]: derived 7e7563dd1753d0a933922a8da698154eead46f662ccb7c65f748f2c3, "
                    + "chain dd4709091734af2dc36321e774cf496222a1f92377ad6c5bef100457",
            "LMConfigDatum[6]: derived 190a6c685dd2cb61fc2e542b5c43382b649ce98d7a7c97d746d7aa12, "
                    + "chain 70b149e7c84a4cf47fb273d87ed2fe97562f0148bfce0b4681afa480");

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

        assertEquals(PREVIEW_DIVERGENCE, mismatches,
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

        assertEquals(PREVIEW_DIVERGENCE_WITHOUT_POOL_MANAGER, v.verifyAgainst(CONFIG_DATUM, LM_CONFIG_DATUM),
                "partial derivation must retain only the independently derivable preview delta");
    }
}
