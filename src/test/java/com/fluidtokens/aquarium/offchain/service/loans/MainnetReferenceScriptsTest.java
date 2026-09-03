package com.fluidtokens.aquarium.offchain.service.loans;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>FluidTokens' mainnet reference-script coordinates, re-verified against the chain — the check
 * that makes shipping them to an operator safe.</b>
 *
 * <h2>What this converts from a one-off into a repeatable answer</h2>
 * Findings §24 verified 27 relayed coordinates by hand, once. This asserts the eight the liquidation
 * path uses and the eleven the compound path uses, live, so a redeploy or a spend turns into a red
 * test rather than a stale line in {@code docker/.env.example}.
 *
 * <h2>⚠ The hazard it exists for is specifically the NAMED keys</h2>
 * The compound path takes one comma-separated list and reads {@code referenceScriptHash} off the
 * chain, so <b>a mislabelled coordinate there is not expressible.</b> The liquidation path takes
 * <b>eight keys named for validators</b>, and — in {@code application.yaml}'s own words — <i>"a key
 * named for a validator holds a COORDINATE, nothing checks the two agree"</i>. This is that check:
 * every named key is asserted against the hash {@code LoansReferenceScriptVerifier} would demand for
 * that name, so a correct coordinate under the wrong key fails here rather than at an operator's boot.
 *
 * <p>⚠ And it re-states §24's own lesson in executable form: <b>the network is part of a coordinate's
 * identity, and it is the part no hash carries.</b> Two of the original 27 were preview transactions
 * whose published hashes matched mainnet perfectly, because those two policies are byte-identical
 * across networks. Only resolving them on the intended network separated them — so this test queries
 * <b>mainnet</b>, and that fact is the evidence, not the hash agreement.
 *
 * <p>Read-only. Gated on {@code BLOCKFROST_KEY}; skips when unset.
 */
@EnabledIfEnvironmentVariable(named = "BLOCKFROST_KEY", matches = ".+",
        disabledReason = "reads mainnet: run with `set -a; . ./.env.mainnet; set +a`")
class MainnetReferenceScriptsTest {

    private static final String CONFIG_POLICY_ID = "db2c498e1b93da91e6a79f58526a1e66591d97ace3f8e43d2619b416";
    private static final String LM_CONFIG_POLICY_ID = "a56b0ac2654663f395601601a7825649e5488905648747e912d870e4";
    private static final String CONFIG_ASSET_NAME = "706172616d6574657273";
    private static final String SMART_TOKENS_SPEND = "fca77bcce1e5e73c97a0bfa8c90f7cd2faff6fd6ed5b6fec1c04eefa";

    private static final String BF = "https://cardano-mainnet.blockfrost.io/api/v0";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20)).build();

    /**
     * ⛔ The EIGHT named liquidation coordinates, exactly as findings §24.4 publishes them and as
     * {@code docker/.env.example} now ships them. Key → {@code txHash#index}.
     */
    private static final Map<String, String> LIQUIDATION = new LinkedHashMap<>();

    /** The ELEVEN compound coordinates, one comma-separated property in production. */
    private static final List<String> COMPOUND = List.of(
            "83d1c5393a53e365eb15a7bdfd1feff560f43f9560bc60c23c4e41de709bae33#0",
            "55a67ecdf41df12275588f01a33cb4d0c88345e05bec7a52be4099dff9597d3d#0",
            "d0549a87da42d048eb1c3b5b8f7811fd2ccd882ad36c85ee209d3a8d1ca0265f#0",
            "d52f3f88e44ca798d9f45313b83267a7ffa01a6105603ed2b2aebcd8383c45ea#0",
            "e5e5bab0c7b39a929af8516f940811ca483dbc23ba647a664c1463c2a70b3fe0#0",
            "ebc11a0346719772709390b11156f6e3b46c5b39d305f80c1f842ceadc9a242b#0",
            "954f8be5773c3ebce3377ecb7a420f407ef18500638bb6d7db0022ed9e9b7c50#0",
            "5215ca557800881b044ce92c77018b92b9d5b6c56f835d6217bc7e1435000f8a#0",
            "8340312072cd352519e01d7e294d3a4cb84a7f0b63f44adef027abf84d2e0bee#0",
            "8bfb510d6d90573280d9a47b94411477f0992228e0b43cb7cb864f2af66b6812#0",
            "15d88c19c9841e7b5cdd125613ff2013993aeb89f871f340c7a2e43fce1373f5#0");

    static {
        LIQUIDATION.put("loan", "f87ed9cc0fd53fd5d8d9c88bfac066fa741aa927e98e5c001496bfb4c82db84f#0");
        LIQUIDATION.put("loan-spend", "46d7195856788885fd4a488dff7bde8bbaf46d5dc4a2fa3dbd12e9cb42129c96#0");
        LIQUIDATION.put("lender-manager", "ebc11a0346719772709390b11156f6e3b46c5b39d305f80c1f842ceadc9a242b#0");
        LIQUIDATION.put("lender-manager-spend", "55a67ecdf41df12275588f01a33cb4d0c88345e05bec7a52be4099dff9597d3d#0");
        LIQUIDATION.put("loan-claim-action", "51eaf4994ee313bf4c95be65656e092d7366b0f397f7ecc1e0113c063fab5f98#0");
        LIQUIDATION.put("lm-liquidate-action", "8ba0dfb30d40361b9bc775f032e2427c799a6cfefce0cbf13e8f1242c990249a#0");
        LIQUIDATION.put("lm-liquidate-and-pay-in-advance-action",
                "2ed58f66779acd64f9add3755dd0686d6841001c90683b369cae0f5f07287476#0");
        LIQUIDATION.put("asset-manager", "e5e5bab0c7b39a929af8516f940811ca483dbc23ba647a664c1463c2a70b3fe0#0");
    }

    private static JsonNode get(String path) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create(BF + path))
                .header("project_id", System.getenv("BLOCKFROST_KEY"))
                .timeout(Duration.ofSeconds(30)).GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("blockfrost " + path + " -> " + response.statusCode()
                    + " (a 404 here IS the finding: the coordinate is stale or on another network)");
        }
        return MAPPER.readTree(response.body());
    }

    /** The {@code reference_script_hash} the chain reports at one {@code txHash#index}. */
    private static String publishedHash(String coordinate) throws IOException, InterruptedException {
        String[] parts = coordinate.split("#");
        int index = Integer.parseInt(parts[1]);
        for (JsonNode output : get("/txs/" + parts[0] + "/utxos").get("outputs")) {
            if (output.get("output_index").asInt() == index) {
                return output.hasNonNull("reference_script_hash")
                        ? output.get("reference_script_hash").asText() : null;
            }
        }
        return null;
    }

    private static LoansContractRegistry mainnetRegistry() {
        return new LoansContractRegistry(CONFIG_POLICY_ID, LM_CONFIG_POLICY_ID, CONFIG_ASSET_NAME,
                SMART_TOKENS_SPEND);
    }

    /**
     * ⛔ Each NAMED key publishes the validator its name claims — the same pairing
     * {@code LoansReferenceScriptVerifier.expectations()} builds, asserted against the chain.
     */
    @Test
    void everyNamedLiquidationCoordinatePublishesTheValidatorItsNameClaims() throws Exception {
        LoansContractRegistry registry = mainnetRegistry();
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("loan", registry.getLoanPolicyId());
        expected.put("loan-spend", registry.getLoanSpendScriptHash());
        expected.put("lender-manager", registry.getLenderManagerWithdrawScriptHash());
        expected.put("lender-manager-spend", registry.getLenderManagerSpendScriptHash());
        expected.put("loan-claim-action", registry.getLoanClaimActionScriptHash());
        expected.put("lm-liquidate-action", registry.getLmLiquidateActionScriptHash());
        expected.put("lm-liquidate-and-pay-in-advance-action",
                registry.getLmLiquidateAndPayInAdvanceActionScriptHash());
        expected.put("asset-manager", registry.getAssetManagerWithdrawScriptHash());

        assertEquals(expected.keySet(), LIQUIDATION.keySet(),
                "the shipped key set must be exactly the set LoansReferenceScriptVerifier checks — a "
                        + "key it does not verify is a coordinate nothing guards");

        List<String> mismatches = new ArrayList<>();
        for (var entry : LIQUIDATION.entrySet()) {
            String published = publishedHash(entry.getValue());
            if (!expected.get(entry.getKey()).equals(published)) {
                mismatches.add(entry.getKey() + " at " + entry.getValue() + " publishes " + published
                        + ", but this node derives " + expected.get(entry.getKey()));
            }
        }
        assertTrue(mismatches.isEmpty(),
                "a shipped mainnet coordinate does not publish the validator its key names. Either "
                        + "FluidTokens redeployed, or the coordinate is filed under the wrong key — "
                        + "and the second is the one no hash check inside a single key would catch. "
                        + mismatches);
    }

    /**
     * The compound list resolves, and every hash it publishes belongs to <b>this</b> deployment.
     *
     * <p>⚠ A weaker check than the one above <b>by design</b>, because the hazard is different: the
     * compound property is an unnamed list and the node reads the hash off the chain, so mislabelling
     * is not expressible. What can still go wrong is a coordinate from a <b>different deployment or
     * network</b> — which is what deployment membership catches.
     */
    @Test
    void everyCompoundCoordinateResolvesAndBelongsToThisDeployment() throws Exception {
        LoansContractRegistry registry = mainnetRegistry();
        // ⚠ derivedHashes() is NOT "every hash this registry derives" — it is the set the ConfigDatum
        // PUBLISHES, which is what LoansConfigVerifier cross-checks. Two withdraw hashes are derived
        // and deliberately absent from it, and asset_manager's is one of them — so using that map
        // alone as a membership oracle understates the deployment and reads a correct coordinate as
        // foreign. (Measured: it rejected e5e5bab0…#0, which the NAMED test above proves is exactly
        // this deployment's asset_manager withdraw validator.)
        Set<String> ofThisDeployment = new LinkedHashSet<>(registry.derivedHashes().values());
        ofThisDeployment.add(registry.getAssetManagerWithdrawScriptHash());
        ofThisDeployment.add(registry.getLockedBorrowerManagerWithdrawScriptHash());

        List<String> problems = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String coordinate : COMPOUND) {
            String published = publishedHash(coordinate);
            if (published == null) {
                problems.add(coordinate + " holds no reference script");
            } else if (!ofThisDeployment.contains(published)) {
                problems.add(coordinate + " publishes " + published
                        + ", which is not a validator this deployment derives");
            } else if (!seen.add(published)) {
                problems.add(coordinate + " publishes " + published + " a second time");
            }
        }
        assertTrue(problems.isEmpty(), "compound reference-script coordinates: " + problems);
        assertEquals(COMPOUND.size(), seen.size(), "eleven coordinates, eleven distinct validators");
    }
}
