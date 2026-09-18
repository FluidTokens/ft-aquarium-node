package com.fluidtokens.aquarium.offchain.service.loans;

import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.service.LoansConfigVerifier;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The old preview deployment is captured honestly after the single-blueprint amendment.</b>
 *
 * <h2>Why this exists</h2>
 * On 2026-09-01 this repo published a finding (§19.3) that the vendored blueprint had gone stale,
 * that the bot was blind to every loan, and that a constitutional re-vendor of
 * {@code loans-v4.plutus.json} was required. <b>All of it was false.</b> The comparison behind it
 * put {@code LoanFixtures.registry()} — pinned to the <b>THIRD</b> deployment on purpose — against
 * the <b>FOURTH</b> deployment's live config. Every credential hangs off the config policy id, so
 * those must differ. <b>The design working was read as the design broken</b> (findings §21).
 *
 * <p>The check that refutes it takes seconds and already existed — in
 * {@code LoansConfigVerifierLiveTest}, which is <b>skipped unless {@code BLOCKFROST_KEY} is set</b>
 * and therefore does not run in an ordinary build. <b>This class runs the same comparison with no
 * network</b>, against a pinned snapshot of the live datums, so the seam is guarded on every
 * {@code ./gradlew test}.
 *
 * <h2>What green means, and what it does not</h2>
 * Green means the captured fourth preview deployment remains byte-honest and differs from the
 * latest shipped artifact at exactly the two fields FluidTokens has not migrated. It does not mean
 * preview is compatible: production startup verification must report this mismatch and refuse the
 * stale pairing. The live test answers whether the captured datums themselves moved.
 */
class ShippedRegistryMatchesPinnedConfigTest {

    private static final String CONFIG = "/loans-v4/fourth-deployment-config-datum.hex";
    private static final String LM_CONFIG = "/loans-v4/fourth-deployment-lm-config-datum.hex";
    /** The THIRD deployment — deliberately kept, and the thing §19.3 confused for a baseline. */
    private static final String THIRD_DEPLOYMENT = "/loans-v4/preview-config-datum.hex";
    private static final List<String> OLD_PREVIEW_MISMATCHES = List.of(
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

    private static String fixture(String path) throws IOException {
        try (InputStream is = ShippedRegistryMatchesPinnedConfigTest.class.getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalStateException("fixture not on the test classpath: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    /** The verifier with no backend: {@link LoansConfigVerifier#verifyAgainst} never fetches. */
    private static LoansConfigVerifier verifierFor(LoansContractRegistry registry) {
        var network = new AppConfig.Network();
        ReflectionTestUtils.setField(network, "network", "preview");
        return new LoansConfigVerifier(registry, LoanFixtures.SMART_TOKENS_SPEND, network, null, true);
    }

    /**
     * The exact accepted mismatch between the one latest artifact and preview's old deployment.
     */
    @Test
    void theLatestArtifactDiffersFromOldPreviewAtExactlyTheTwoMigratedFields() throws IOException {
        List<String> mismatches = verifierFor(LoanFixtures.shippedPreviewRegistry())
                .verifyAgainst(fixture(CONFIG), fixture(LM_CONFIG));

        assertEquals(OLD_PREVIEW_MISMATCHES, mismatches,
                "the old-preview mismatch changed; do not update captured bytes to make this green");
    }

    /**
     * Spot-check in plain sight, so a reader can match this against a block explorer without running
     * anything. These two are the credentials the indexer filters on.
     */
    @Test
    void theDerivedLoanCredentialsAreTheOnesLoansActuallyLiveAt() {
        LoansContractRegistry shipped = LoanFixtures.shippedPreviewRegistry();

        assertEquals("2f1aa941f437e351e3870f7247d735b2bc2952f1c7977426e8960d17",
                shipped.getLoanPolicyId(), "loanPolicyId");
        assertEquals("31e0dc1d75076e4f7795b24c4cc4b5515791bb4eff4af7961e404f3e",
                shipped.getLoanSpendScriptHash(),
                "loanSpendScriptHash — this is the payment credential TankUtxoStorage keeps loan "
                        + "UTxOs for; wrong here means the bot indexes an address nothing lives at");
    }

    /**
     * ⚑ THE TRAP, PINNED. The third-deployment fixture must NOT verify against the shipped registry.
     * If this ever passes, the two registries have silently become the same one and the distinction
     * this repo depends on is gone.
     */
    @Test
    void theThirdDeploymentFixtureMustNotVerify() throws IOException {
        assertNotEquals(fixture(THIRD_DEPLOYMENT), fixture(CONFIG),
                "the third-deployment fixture and the pinned live config must remain distinct files");

        List<String> mismatches = verifierFor(LoanFixtures.shippedPreviewRegistry())
                .verifyAgainst(fixture(THIRD_DEPLOYMENT), fixture(LM_CONFIG));

        assertFalse(mismatches.isEmpty(),
                "the FOURTH-deployment registry verified cleanly against the THIRD deployment's "
                        + "config. That is not possible while the two are different deployments, so "
                        + "either a fixture was overwritten or the shipped coordinates moved.");
    }

    /**
     * ⛔ PROOF THE GREEN IS LOAD-BEARING. A test that cannot fail proves nothing, and the retracted
     * §19.3 is a standing reminder that a comparison can look rigorous and compare the wrong things.
     * Corrupt exactly one published credential and the verifier must name it.
     */
    @Test
    void aSingleCorruptedCredentialIsCaught() throws IOException {
        String real = "31e0dc1d75076e4f7795b24c4cc4b5515791bb4eff4af7961e404f3e";
        String corrupted = "31e0dc1d75076e4f7795b24c4cc4b5515791bb4eff4af7961e404f00";
        String mutated = fixture(CONFIG).replace(real, corrupted);
        assertNotEquals(fixture(CONFIG), mutated, "the mutation did not apply — fixture shape changed");

        List<String> mismatches = verifierFor(LoanFixtures.shippedPreviewRegistry())
                .verifyAgainst(mutated, fixture(LM_CONFIG));

        // ⚠ The corruption is at ConfigDatum[10] and mismatches come out in index order, so it no
        // longer sorts FIRST -- [2] and [8] now precede it. Asserting getFirst() would pass or fail
        // on ordering rather than on the corruption being caught, so this pulls the entry out by
        // name and checks the remainder is exactly the accepted set.
        assertEquals(OLD_PREVIEW_MISMATCHES.size() + 1, mismatches.size(),
                "the corruption must add exactly one mismatch to the accepted old-preview set: "
                        + mismatches);

        List<String> corruptionEntries = mismatches.stream()
                .filter(m -> m.contains("ConfigDatum[10]")).toList();
        assertEquals(1, corruptionEntries.size(),
                "exactly one mismatch must name the corrupted field: " + mismatches);
        assertTrue(corruptionEntries.getFirst().contains(real),
                "the mismatch must name the value the registry expected: " + corruptionEntries.getFirst());

        assertEquals(OLD_PREVIEW_MISMATCHES,
                mismatches.stream().filter(m -> !m.contains("ConfigDatum[10]")).toList(),
                "the independent old-preview controls moved while testing the corruption");
    }
}
