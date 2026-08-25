package com.fluidtokens.aquarium.offchain;

import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.service.LoansConfigVerifier;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one test that actually talks to the chain: it runs the verifier's full path — derive, locate
 * the config NFTs via Blockfrost, read their inline datums, compare — against live preview, using
 * <b>the coordinates this repo actually ships</b>.
 *
 * <h2>Why it reads application.yaml, and what it cost to learn</h2>
 * This class used to hardcode the config policy ids as constants. It was cited — by its own javadoc
 * — as the check for *"have the coordinates in application.yaml gone stale?"*, and it could not
 * answer that question at all, because those coordinates were never an input to it.
 *
 * <p><b>Measured, 2026-08-25.</b> The preview deployment was repinned in {@code application.yaml};
 * this test was then run and came back green; the green was read as proof the new coordinates
 * verified. It was not. Both arms of the 2x2 built on it were scored against the <em>old</em>
 * deployment still hardcoded here, where the old blueprint passing and the new one failing are
 * simply correct — and say nothing whatever about the new deployment. The wrong conclusion was
 * committed, contradicted a right one already in the record, and was caught only when the deployed
 * image refused to boot with ten mismatches this suite had reported as zero.
 *
 * <p><b>The generalisable part:</b> the run <em>did</em> assert proof-of-variant — on the blueprint,
 * by sha256, on both arms. It did not assert it on the coordinates. <b>The input nobody asserted was
 * the input that never propagated.</b> An experiment that does not manipulate the variable it claims
 * to manipulate produces a real, reproducible, entirely meaningless result. So: assert the variant of
 * <em>every</em> input you claim to vary, not the one you happen to be thinking about.
 *
 * <p>Hence {@link #shippedPreviewCoordinates()}. There is now no constant here to drift: repin
 * {@code application.yaml} and this test changes what it asks the chain, which is the only way it can
 * honestly claim to detect a redeploy.
 *
 * <h2>What it still cannot tell you</h2>
 * Green means <em>the deployment we are pinned to is intact</em>. It does <b>not</b> mean that
 * deployment is the one anyone still uses — superseded config NFTs are never burnt, so a dead pin
 * verifies green forever (findings §12, §16.1). <b>Nothing in the node answers that question</b> —
 * {@code DeploymentLivenessProbe} did, and was removed 2026-08-25 (Giovanni's call). Treat a
 * persistently empty world as a suspected redeploy and check the config policy id by hand.
 *
 * <p>Skipped unless {@code BLOCKFROST_KEY} is set, so it never breaks a keyless build:
 * <pre>{@code
 *   set -a; . ./.env.preview; set +a
 *   ./gradlew cleanTest test --tests '*LoansConfigVerifierLiveTest'
 * }</pre>
 */
@EnabledIfEnvironmentVariable(named = "BLOCKFROST_KEY", matches = ".+")
class LoansConfigVerifierLiveTest {

    private static final String PREVIEW_URL = "https://cardano-preview.blockfrost.io/api/v0/";
    private static final String RESOURCE = "application.yaml";

    /** Mirrors the {@code @Value} default in {@code AppConfig}: the yaml does not set this. */
    private static final String CONFIG_ASSET_NAME_DEFAULT = "706172616d6574657273";

    /** Exactly what the preview profile ships — no constants, nothing to drift. */
    private record Coordinates(String configPolicyId, String lmConfigPolicyId,
                               String configAssetName, String smartTokensSpend) {
    }

    @Test
    void theSHIPPEDPreviewCoordinatesStillMatchTheChain() throws IOException {
        Coordinates shipped = shippedPreviewCoordinates();

        var network = new AppConfig.Network();
        ReflectionTestUtils.setField(network, "network", "preview");
        var registry = new LoansContractRegistry(shipped.configPolicyId(), shipped.lmConfigPolicyId(),
                shipped.configAssetName(), shipped.smartTokensSpend());
        var verifier = new LoansConfigVerifier(registry, shipped.smartTokensSpend(), network,
                new BFBackendService(PREVIEW_URL, System.getenv("BLOCKFROST_KEY")), true);

        // Exercises the whole @PostConstruct path — the same call the running node makes at boot.
        verifier.verify();

        assertEquals(shipped.smartTokensSpend(), verifier.getOnChainSmartTokensSpendScriptHash(),
                "smartTokensSpendScriptHash read live from the ConfigDatum");
    }

    /**
     * A rejected key must not be swallowed as "backend unreachable" — that would silently skip
     * verification on every boot, which is the exact failure this class exists to prevent. Runs with
     * fail-on-unreachable left at its default {@code false}, so the only way this passes is if a 4xx
     * is classified as an answer rather than an outage.
     */
    @Test
    void aRejectedKeyIsAHardFailureNotADegradedBoot() throws IOException {
        Coordinates shipped = shippedPreviewCoordinates();

        var network = new AppConfig.Network();
        ReflectionTestUtils.setField(network, "network", "preview");
        var registry = new LoansContractRegistry(shipped.configPolicyId(), shipped.lmConfigPolicyId(),
                shipped.configAssetName(), shipped.smartTokensSpend());
        var verifier = new LoansConfigVerifier(registry, shipped.smartTokensSpend(), network,
                new BFBackendService(PREVIEW_URL, "not-a-real-project-id"), false);

        var e = assertThrows(IllegalStateException.class, verifier::verify);
        assertTrue(e.getMessage().contains("rejected"), "expected a rejection, got: " + e.getMessage());
    }

    // ---- reading what we ship ------------------------------------------------------------------

    private static Coordinates shippedPreviewCoordinates() throws IOException {
        Map<String, Object> preview = previewDocument();
        Coordinates coordinates = new Coordinates(
                string(preview, "loans.config.policy-id"),
                string(preview, "loans.lm-config.policy-id"),
                CONFIG_ASSET_NAME_DEFAULT,
                string(preview, "loans.smart-tokens-spend-script-hash"));

        // Printed so a failure report names the coordinates that were actually asked about. Without
        // this, "10 mismatches" is indistinguishable from "10 mismatches against something else".
        System.out.println("[live] shipped preview coordinates: config=" + coordinates.configPolicyId()
                + " lmConfig=" + coordinates.lmConfigPolicyId()
                + " smartTokens=" + coordinates.smartTokensSpend());
        return coordinates;
    }

    private static Map<String, Object> previewDocument() throws IOException {
        String yaml;
        try (InputStream in = LoansConfigVerifierLiveTest.class.getClassLoader()
                .getResourceAsStream(RESOURCE)) {
            assertNotNull(in, RESOURCE + " is not on the classpath — this test would be vacuous");
            yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        // The classpath copy must be the file we ship, or this test pins a document nobody deploys.
        assertEquals(Files.readString(Path.of("src/main/resources", RESOURCE)), yaml,
                "the classpath " + RESOURCE + " differs from src/main/resources/" + RESOURCE);

        List<Map<String, Object>> documents = new ArrayList<>();
        for (Object document : new Yaml().loadAll(yaml)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) document;
            documents.add(map);
        }
        for (Map<String, Object> document : documents) {
            Object spring = document.get("spring");
            if (spring instanceof Map<?, ?> springMap
                    && springMap.get("config") instanceof Map<?, ?> configMap
                    && configMap.get("activate") instanceof Map<?, ?> activate
                    && "preview".equals(activate.get("on-profile"))) {
                return document;
            }
        }
        throw new AssertionError("no document activates the preview profile");
    }

    private static String string(Map<String, Object> document, String path) {
        Object current = document;
        for (String segment : path.split("\\.")) {
            assertTrue(current instanceof Map, path + " does not exist: stopped at '" + segment + "'");
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) current;
            current = map.get(segment);
            assertNotNull(current, path + " is not set (missing at '" + segment + "')");
        }
        String value = String.valueOf(current);
        assertTrue(value.matches("[0-9a-f]{56}"),
                path + " is not a 28-byte hex hash: '" + value + "'. If it has become an "
                        + "${ENV:default} placeholder, this reader must learn to unwrap it — silently "
                        + "passing the literal placeholder to the registry would derive nonsense and "
                        + "report it as a redeploy.");
        return value;
    }
}
