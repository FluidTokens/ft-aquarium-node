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
 * ⛔ <b>The hashes we DERIVE must equal the hashes the config PUBLISHES — offline, every build.</b>
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
 * Green means <em>the code derives what this snapshot publishes</em>. It does <b>not</b> mean the
 * snapshot is current: the admin key can spend those config UTxOs and replace the datums in place at
 * the same policy ids, and nothing here would notice. <b>{@code LoansConfigVerifierLiveTest} is the
 * only thing that answers that question</b> — when it fails, re-capture the fixtures (see
 * {@code fourth-deployment-config-datum.PROVENANCE.md}), never the reverse.
 */
class ShippedRegistryMatchesPinnedConfigTest {

    private static final String CONFIG = "/loans-v4/fourth-deployment-config-datum.hex";
    private static final String LM_CONFIG = "/loans-v4/fourth-deployment-lm-config-datum.hex";
    /** The THIRD deployment — deliberately kept, and the thing §19.3 confused for a baseline. */
    private static final String THIRD_DEPLOYMENT = "/loans-v4/preview-config-datum.hex";

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
     * ⛔ THE ASSERTION THAT WOULD HAVE STOPPED §19.3. The registry built from what the image ships
     * derives every credential the live config publishes.
     */
    @Test
    void theShippedRegistryDerivesEveryCredentialThePinnedConfigPublishes() throws IOException {
        List<String> mismatches = verifierFor(LoanFixtures.shippedPreviewRegistry())
                .verifyAgainst(fixture(CONFIG), fixture(LM_CONFIG));

        assertTrue(mismatches.isEmpty(),
                "the SHIPPED registry disagrees with the pinned live config. Before concluding the "
                        + "blueprint is stale, read findings §21: check WHICH registry is being "
                        + "compared, then run LoansConfigVerifierLiveTest to see whether the config "
                        + "itself moved. Mismatches: " + mismatches);
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

        assertEquals(1, mismatches.size(), "exactly one field was corrupted: " + mismatches);
        assertTrue(mismatches.get(0).contains(real),
                "the mismatch must name the value the registry expected: " + mismatches.get(0));
    }
}
