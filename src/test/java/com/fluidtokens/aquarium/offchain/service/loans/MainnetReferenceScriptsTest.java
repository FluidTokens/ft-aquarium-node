package com.fluidtokens.aquarium.offchain.service.loans;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    private static final String BLUEPRINT_RESOURCE = "loans-v4-mainnet.plutus.json";
    private static final String MS_POOL_POLICY = "f5808c2c990d86da54bfc97d89cee6efa20cd8461616359478d96b4c";
    private static final String MS_POOL_SPEND = "ea07b733d932129c378af627436e7cbc2ef0bf96e0036bb51b3bde6b";
    private static final String MS_ORDER_SPEND = "c3e28c36c3447315ba5a56f33da6a6ddc1770a876a8d9f0cb3a97c4c";
    private static final String CONFIG = "ffced74c7936e803d9f3aedd5abe7e5261e14515dc1a0b045cdb2f03c8b0d36b#0";
    private static final String LM_CONFIG = "1c4a91283f9fc2bffe13c0b10584b1d1492f770e910bd858da8586c395a8bdaa#0";
    private static final String COMPOUND = "8d92115bb26dece0f197b110b0cf2c9bfa5f542cb1fd4dc53e595f1a1b73341a#0";
    private static final String OLD_COMPOUND = "954f8be5773c3ebce3377ecb7a420f407ef18500638bb6d7db0022ed9e9b7c50#0";
    private static final String COMPOUND_HASH = "ad34c3db53d20c1e368d7fea64724a0b0249b603a57c8f2a1670bda6";

    private static final String BF = "https://cardano-mainnet.blockfrost.io/api/v0";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20)).build();
    private static final Map<String, JsonNode> GET_CACHE = new LinkedHashMap<>();

    /**
     * ⛔ The EIGHT named liquidation coordinates, exactly as findings §24.4 publishes them and as
     * {@code docker/.env.example} now ships them. Key → {@code txHash#index}.
     */
    private static final Map<String, String> LIQUIDATION = new LinkedHashMap<>();

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
        JsonNode cached = GET_CACHE.get(path);
        if (cached != null) return cached;
        var request = HttpRequest.newBuilder(URI.create(BF + path))
                .header("project_id", System.getenv("BLOCKFROST_KEY"))
                .timeout(Duration.ofSeconds(30)).GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("blockfrost " + path + " -> " + response.statusCode()
                    + " (a 404 here IS the finding: the coordinate is stale or on another network)");
        }
        JsonNode body = MAPPER.readTree(response.body());
        GET_CACHE.put(path, body);
        return body;
    }

    private static JsonNode publishedOutput(String coordinate) throws IOException, InterruptedException {
        String[] parts = coordinate.split("#");
        int index = Integer.parseInt(parts[1]);
        for (JsonNode output : get("/txs/" + parts[0] + "/utxos").get("outputs")) {
            if (output.get("output_index").asInt() == index) {
                return output;
            }
        }
        return null;
    }

    /** The {@code reference_script_hash} the chain reports at one {@code txHash#index}. */
    private static String publishedHash(String coordinate) throws IOException, InterruptedException {
        JsonNode output = publishedOutput(coordinate);
        return output != null && output.hasNonNull("reference_script_hash")
                ? output.get("reference_script_hash").asText() : null;
    }

    private static void assertUnspent(String coordinate, JsonNode output) throws Exception {
        assertTrue(output != null, coordinate + " is absent from its creating transaction");
        String[] parts = coordinate.split("#");
        for (int page = 1; page <= 20; page++) {
            JsonNode current = get("/addresses/" + output.get("address").asText()
                    + "/utxos?count=100&page=" + page + "&order=asc");
            for (JsonNode utxo : current) {
                if (parts[0].equals(utxo.get("tx_hash").asText())
                        && Integer.parseInt(parts[1]) == utxo.get("output_index").asInt()) return;
            }
            if (current.size() < 100) break;
        }
        throw new AssertionError(coordinate + " is not in the current UTxO set at its address");
    }

    @SuppressWarnings("unchecked")
    private static List<String> shippedCompoundReferences() throws IOException {
        try (InputStream in = MainnetReferenceScriptsTest.class.getClassLoader()
                .getResourceAsStream("application.yaml")) {
            assertTrue(in != null, "application.yaml is absent from the test classpath");
            Iterable<Object> documents = new Yaml().loadAll(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8));
            Map<String, Object> root = (Map<String, Object>) documents.iterator().next();
            Map<String, Object> loans = (Map<String, Object>) root.get("loans");
            Map<String, Object> compound = (Map<String, Object>) loans.get("compound");
            String placeholder = (String) compound.get("reference-scripts");
            int colon = placeholder.indexOf(':');
            assertTrue(placeholder.startsWith("${") && colon > 1 && placeholder.endsWith("}"),
                    "compound references are not an env-overridable shipped default");
            return List.of(placeholder.substring(colon + 1, placeholder.length() - 1).split(","));
        }
    }

    private static LoansContractRegistry mainnetRegistry() {
        return new LoansContractRegistry(BLUEPRINT_RESOURCE,
                CONFIG_POLICY_ID, LM_CONFIG_POLICY_ID, CONFIG_ASSET_NAME, SMART_TOKENS_SPEND,
                MS_POOL_POLICY, MS_POOL_SPEND, MS_ORDER_SPEND);
    }

    private static Set<String> liveCompoundHashes(List<String> coordinates) throws Exception {
        List<String> problems = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String coordinate : coordinates) {
            JsonNode output = publishedOutput(coordinate);
            assertUnspent(coordinate, output);
            String published = output != null && output.hasNonNull("reference_script_hash")
                    ? output.get("reference_script_hash").asText() : null;
            if (published == null) {
                problems.add(coordinate + " holds no reference script");
            } else if (!seen.add(published)) {
                problems.add(coordinate + " publishes " + published + " a second time");
            }
        }
        assertTrue(problems.isEmpty(), "compound reference-script coordinates: " + problems);
        return seen;
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
            JsonNode output = publishedOutput(entry.getValue());
            assertUnspent(entry.getValue(), output);
            String published = output.hasNonNull("reference_script_hash")
                    ? output.get("reference_script_hash").asText() : null;
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
     * <p>The property is an unnamed list, so the relevant identity is the exact set of eleven
     * validators consumed by {@code CompoundTransactionBuilder}, not a copied coordinate list.
     */
    @Test
    void everyCompoundCoordinateResolvesAndBelongsToThisDeployment() throws Exception {
        LoansContractRegistry registry = mainnetRegistry();
        List<String> coordinates = shippedCompoundReferences();
        Set<String> requiredByBuilder = new LinkedHashSet<>(List.of(
                registry.getAssetManagerSpendScriptHash(), registry.getLenderManagerSpendScriptHash(),
                registry.getPoolSpendScriptHash(), registry.getPoolManagerSpendScriptHash(),
                registry.getAssetManagerWithdrawScriptHash(), registry.getLenderManagerWithdrawScriptHash(),
                registry.getLmCompoundActionScriptHash(), registry.getPoolPolicyId(),
                registry.getPoolCompoundActionScriptHash(), registry.getPoolManagerPolicyId(),
                registry.getPmCompoundLiquidityScriptHash()));

        Set<String> seen = liveCompoundHashes(coordinates);
        assertEquals(11, coordinates.size(), "the shipped builder set has exactly eleven coordinates");
        assertEquals(requiredByBuilder, seen,
                "the shipped coordinates must publish exactly the eleven hashes CompoundTransactionBuilder needs");
        assertEquals(COMPOUND_HASH, publishedHash(COMPOUND), "the supplied compound coordinate identity");

        // Mutation control: the superseded coordinate still resolves and is unspent. Its rejection
        // therefore proves the assertion depends on validator identity, not on auth or existence.
        List<String> wrongExistingOutput = new ArrayList<>(coordinates);
        assertTrue(wrongExistingOutput.remove(COMPOUND), "current compound coordinate missing from YAML");
        wrongExistingOutput.add(OLD_COMPOUND);
        Set<String> wrongHashes = liveCompoundHashes(wrongExistingOutput);
        AssertionError rejection = assertThrows(AssertionError.class,
                () -> assertEquals(requiredByBuilder, wrongHashes,
                        "a different unspent reference output must not satisfy the builder set"));
        assertTrue(rejection.getMessage().contains(COMPOUND_HASH),
                "the mutation was rejected for something other than the current compound identity");
    }

    @Test
    void currentConfigOutputsAreUnspentExactNftsWithCapturedDatums() throws Exception {
        assertCurrentConfig(CONFIG, CONFIG_POLICY_ID + CONFIG_ASSET_NAME, "mainnet-config-datum.hex");
        assertCurrentConfig(LM_CONFIG, LM_CONFIG_POLICY_ID + CONFIG_ASSET_NAME,
                "mainnet-lm-config-datum.hex");
    }

    private static void assertCurrentConfig(String coordinate, String nft, String fixture) throws Exception {
        JsonNode output = publishedOutput(coordinate);
        assertUnspent(coordinate, output);
        long quantity = 0;
        for (JsonNode amount : output.get("amount")) {
            if (nft.equals(amount.get("unit").asText())) quantity += amount.get("quantity").asLong();
        }
        assertEquals(1, quantity, coordinate + " must carry exactly one config NFT " + nft);
        try (InputStream in = MainnetReferenceScriptsTest.class.getResourceAsStream("/loans-v4/" + fixture)) {
            assertTrue(in != null, "missing fixture " + fixture);
            assertEquals(new String(in.readAllBytes(), StandardCharsets.UTF_8).trim(),
                    output.get("inline_datum").asText(), coordinate + " inline datum differs from capture");
        }
    }
}
