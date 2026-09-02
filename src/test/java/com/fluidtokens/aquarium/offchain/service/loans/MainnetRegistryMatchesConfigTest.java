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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>Does our vendored blueprint derive what FluidTokens' MAINNET deployment publishes?</b>
 *
 * <p>FluidTokens shipped Lending v4 to mainnet on 2026-09-02. The question that decides whether this
 * node could ever operate there is not whether the coordinates are configured — it is whether the
 * artefact we vendor is the artefact they built. Same test as
 * {@link ShippedRegistryMatchesPinnedConfigTest}, pointed at mainnet.
 *
 * <p>Green means the mainnet deployment derives from the same {@code loans-v4.plutus.json} we ship,
 * so every credential downstream is knowable. <b>Red would mean their mainnet build differs from our
 * vendored artefact</b> — a finding about what is possible at all, not a configuration error.
 *
 * <p>⚠ This asserts DERIVATION only. It says nothing about whether the node should run on mainnet;
 * lending is {@code enabled: false} there, the tank path is untested, and the compound gate
 * hard-fails a negative margin on mainnet by design.
 */
class MainnetRegistryMatchesConfigTest {

    /** Quoted first-hand from FluidTokens' own tooling, 2026-09-02. */
    private static final String CONFIG_POLICY_ID = "db2c498e1b93da91e6a79f58526a1e66591d97ace3f8e43d2619b416";
    private static final String LM_CONFIG_POLICY_ID = "a56b0ac2654663f395601601a7825649e5488905648747e912d870e4";
    private static final String CONFIG_ASSET_NAME = "706172616d6574657273";
    /** Published by the mainnet ConfigDatum itself (field 0); not derivable from the blueprint. */
    private static final String SMART_TOKENS_SPEND = "fca77bcce1e5e73c97a0bfa8c90f7cd2faff6fd6ed5b6fec1c04eefa";

    private static String fixture(String path) throws IOException {
        try (InputStream is = MainnetRegistryMatchesConfigTest.class.getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalStateException("fixture not on the classpath: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    private static LoansContractRegistry mainnetRegistry() {
        return new LoansContractRegistry(CONFIG_POLICY_ID, LM_CONFIG_POLICY_ID, CONFIG_ASSET_NAME,
                SMART_TOKENS_SPEND);
    }

    /** ⛔ THE VERDICT: does the vendored blueprint derive FluidTokens' mainnet credentials? */
    @Test
    void theVendoredBlueprintDerivesTheMainnetDeployment() throws IOException {
        var network = new AppConfig.Network();
        ReflectionTestUtils.setField(network, "network", "mainnet");
        LoansContractRegistry registry = mainnetRegistry();

        List<String> mismatches = new LoansConfigVerifier(registry, SMART_TOKENS_SPEND, network, null, true)
                .verifyAgainst(fixture("/loans-v4/mainnet-config-datum.hex"),
                        fixture("/loans-v4/mainnet-lm-config-datum.hex"));

        assertTrue(mismatches.isEmpty(),
                "the vendored loans-v4.plutus.json does NOT derive FluidTokens' mainnet credentials. "
                        + "That is a finding about what is possible on mainnet at all, not a "
                        + "misconfiguration: their mainnet build differs from the artefact we vendor. "
                        + "Mismatches: " + mismatches);
    }

    /**
     * ⚑ The three values that are IDENTICAL between preview and mainnet, and why that is diagnostic.
     * The bond policies are derived from an integer index rather than from the config policy id, and
     * {@code smartTokensSpendScriptHash} is not derivable at all — so they are exactly the values a
     * new deployment CANNOT move. Their agreement across networks is evidence the same blueprint is
     * in play; it is not evidence that anything else matches (findings §21.2 made that mistake in
     * reverse).
     */
    @Test
    void theNonDerivedCredentialsAreTheSameOnBothNetworks() {
        LoansContractRegistry mainnet = mainnetRegistry();
        LoansContractRegistry preview = LoanFixtures.shippedPreviewRegistry();

        assertEquals(preview.getBorrowerBondPolicyId(), mainnet.getBorrowerBondPolicyId());
        assertEquals(preview.getLenderBondPolicyId(), mainnet.getLenderBondPolicyId());
        assertEquals("fca77bcce1e5e73c97a0bfa8c90f7cd2faff6fd6ed5b6fec1c04eefa", SMART_TOKENS_SPEND);
    }

    /** And the derived credentials must DIFFER, or the two networks are not distinct deployments. */
    @Test
    void theDerivedCredentialsDifferBetweenNetworks() {
        LoansContractRegistry mainnet = mainnetRegistry();
        LoansContractRegistry preview = LoanFixtures.shippedPreviewRegistry();

        assertTrue(!preview.getLoanSpendScriptHash().equals(mainnet.getLoanSpendScriptHash()),
                "preview and mainnet derive the same loanSpend — one of the config policy ids is wrong");
        assertTrue(!preview.getLoanPolicyId().equals(mainnet.getLoanPolicyId()));
    }
}
