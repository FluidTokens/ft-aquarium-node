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
            "ConfigDatum[24]: derived cdfa58c27aee3458983247dcde6419e6e8ca13b30e5ba34f95feccb0, "
                    + "chain db9a5bf043f37e744bbb43b96ec89a3e175f7c5523d02dd563ed9c56",
            "LMConfigDatum[3]: derived 7dbcad0e76f5c639c96dd7ffc52f730d1ac0290c46c04fe343edc49b, "
                    + "chain dd4709091734af2dc36321e774cf496222a1f92377ad6c5bef100457");

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

        assertEquals(3, mismatches.size(),
                "the corruption must add exactly one mismatch to the two accepted old-preview mismatches: "
                        + mismatches);
        assertTrue(mismatches.getFirst().contains("ConfigDatum[10]")
                        && mismatches.getFirst().contains(real),
                "the mismatch must name the value the registry expected: " + mismatches.get(0));
        assertEquals(OLD_PREVIEW_MISMATCHES, mismatches.subList(1, mismatches.size()),
                "the independent old-preview controls moved while testing the corruption");
    }
}
