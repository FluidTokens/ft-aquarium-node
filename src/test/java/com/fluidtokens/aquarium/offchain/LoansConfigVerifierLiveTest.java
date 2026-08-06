package com.fluidtokens.aquarium.offchain;

import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.service.LoansConfigVerifier;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one test that actually talks to the chain: it runs the verifier's full path —
 * derive, locate the config NFTs via Blockfrost, read their inline datums, compare —
 * against live preview.
 * <p>
 * {@code LoansConfigVerifierTest} covers the comparison logic offline against recorded
 * datums; this covers the fetch half, which no fixture can. It also doubles as the check
 * for "has FluidTokens redeployed preview again?" — if it goes red with mismatches or a
 * missing config NFT, the coordinates in {@code application.yaml} need refreshing.
 * <p>
 * Skipped unless {@code BLOCKFROST_KEY} is set, so it never breaks a keyless build:
 * <pre>{@code
 *   set -a; . ./.env.preview; set +a
 *   ./gradlew test --tests '*LoansConfigVerifierLiveTest'
 * }</pre>
 */
@EnabledIfEnvironmentVariable(named = "BLOCKFROST_KEY", matches = ".+")
class LoansConfigVerifierLiveTest {

    private static final String PREVIEW_URL = "https://cardano-preview.blockfrost.io/api/v0/";
    private static final String CONFIG_POLICY_ID = "f1a475ea8cccc1e0b7a59b10e79ad171452dc057ffb1cda0df92835c";
    private static final String LM_CONFIG_POLICY_ID = "d0998754ddc3e9cfe80356d7e12db163d03cecc5b6b438dad4f4a3e3";
    private static final String CONFIG_ASSET_NAME = "706172616d6574657273";
    private static final String SMART_TOKENS_SPEND = "fca77bcce1e5e73c97a0bfa8c90f7cd2faff6fd6ed5b6fec1c04eefa";

    @Test
    void previewDeploymentStillMatchesTheCommittedCoordinates() {
        var network = new AppConfig.Network();
        ReflectionTestUtils.setField(network, "network", "preview");
        var registry = new LoansContractRegistry(
                CONFIG_POLICY_ID, LM_CONFIG_POLICY_ID, CONFIG_ASSET_NAME, SMART_TOKENS_SPEND);
        var verifier = new LoansConfigVerifier(registry, SMART_TOKENS_SPEND, network,
                new BFBackendService(PREVIEW_URL, System.getenv("BLOCKFROST_KEY")), true);

        // Exercises the whole @PostConstruct path: locate both config NFTs, read their inline
        // datums, compare every derived hash. Throws if anything has moved or changed.
        verifier.verify();

        assertEquals(SMART_TOKENS_SPEND, verifier.getOnChainSmartTokensSpendScriptHash(),
                "smartTokensSpendScriptHash read live from the ConfigDatum");
    }

    /**
     * A rejected key must not be swallowed as "backend unreachable" — that would silently
     * skip verification on every boot, which is the exact failure this class exists to
     * prevent. Runs with fail-on-unreachable left at its default {@code false}, so the only
     * way this passes is if a 4xx is classified as an answer rather than an outage.
     */
    @Test
    void aRejectedKeyIsAHardFailureNotADegradedBoot() {
        var network = new AppConfig.Network();
        ReflectionTestUtils.setField(network, "network", "preview");
        var registry = new LoansContractRegistry(
                CONFIG_POLICY_ID, LM_CONFIG_POLICY_ID, CONFIG_ASSET_NAME, SMART_TOKENS_SPEND);
        var verifier = new LoansConfigVerifier(registry, SMART_TOKENS_SPEND, network,
                new BFBackendService(PREVIEW_URL, "not-a-real-project-id"), false);

        var e = assertThrows(IllegalStateException.class, verifier::verify);
        assertTrue(e.getMessage().contains("rejected"), "expected a rejection, got: " + e.getMessage());
    }
}
