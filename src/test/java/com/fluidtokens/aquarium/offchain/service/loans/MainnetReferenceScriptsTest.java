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
 * Findings §24 verified 27 relayed coordinates by hand, once. This asserts the nine the liquidation
 * path uses (convert included) and the eleven the compound path uses, live, so a redeploy or a spend turns into a red
 * test rather than a stale line in {@code docker/.env.example}.
 *
 * <h2>⚠ The hazard it exists for is specifically the NAMED keys</h2>
 * The compound path takes one comma-separated list and reads {@code referenceScriptHash} off the
 * chain, so <b>a mislabelled coordinate there is not expressible.</b> The liquidation path takes
 * <b>nine keys named for validators</b>, and — in {@code application.yaml}'s own words — <i>"a key
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

    // ⛔ RE-GROUNDED 2026-10-01. Until then this class derived from the FIRST mainnet deployment's policy
    // ids (db2c498e… / a56b0ac2…), pinned config UTxOs that had been spent, and a compound coordinate the
    // shipped set no longer carried -- red by inspection, and invisible, because it skips without a key.
    // The derivation coordinates are now read from application.yaml like the reference coordinates are:
    // a gate that keeps its own copy of the thing it guards is guarding the copy.
    private static final String CONFIG_ASSET_NAME = "706172616d6574657273";
    /** The config NFT outputs after FluidTokens' 2026-10-01 in-place update (their CONFIG_REF_UTXO / LM_CONFIG_REF_UTXO). */
    private static final String CONFIG = "3d800e98a4da21dc9abcce30c145729406fef7db4d5cd3b4ecd6813aa228a75c#0";
    private static final String LM_CONFIG = "ab3e3aafe7ea0e6fec24d9ac9249e01edb242dee55aeaa2399da097a21177620#0";
    /** lm_compound_action since 2026-10-01 (LMConfigDatum[3]). */
    private static final String COMPOUND = "29f63a1e1e7b268481df871d969b1b250b437a4d9a82aa7bfaf7b6f6252dc946#0";
    /** lm_compound_action before 2026-10-01 -- still unspent, which is what makes it a fair negative. */
    private static final String OLD_COMPOUND = "ec592cc9e0dffdc1fdefa197cb353c4f60a07910c257cd4236293b844ceeabb7#0";
    private static final String COMPOUND_HASH = "71515a89fd399166d8d1e6a63d49822fc2684b76782c8bbf3f817982";

    private static final String BF = "https://cardano-mainnet.blockfrost.io/api/v0";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20)).build();
    private static final Map<String, JsonNode> GET_CACHE = new LinkedHashMap<>();

    /**
     * ⛔ <b>THERE IS NO PINNED COORDINATE LIST HERE ANY MORE, AND ITS ABSENCE IS THE POINT.</b>
     *
     * <p>This class used to keep its own {@code Map} of the eight liquidation coordinates, copied
     * from what {@code application.yaml} shipped. On 2026-09-17 the deployment moved and the shipped
     * values changed; <b>the copy did not</b>. All eight went stale and nothing said so, because this
     * class is gated on {@code BLOCKFROST_KEY} and skips without one — so the staleness sat behind a
     * skip for five days, which is exactly the "a skip is not a pass" failure this repo documents.
     *
     * <p>⇒ The coordinates are now READ FROM {@code application.yaml}, like the compound ones already
     * were. A gate that keeps its own copy of the thing it guards is guarding the copy.
     */
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

    /**
     * ⛔ Read off the creating transaction's own output ({@code consumed_by_tx}), NOT by paging the
     * address's UTxO set. FluidTokens' reference-script address holds 13,074 UTxOs (2026-10-01) and the
     * paging this replaced stopped at 2,000 -- so it reported two live coordinates as spent. A check that
     * fails on the size of someone else's address is measuring the address, not the coordinate.
     */
    private static void assertUnspent(String coordinate, JsonNode output) {
        assertTrue(output != null, coordinate + " is absent from its creating transaction");
        assertTrue(output.has("consumed_by_tx"),
                "blockfrost no longer reports consumed_by_tx -- this check cannot tell spent from unspent");
        assertTrue(output.get("consumed_by_tx").isNull(),
                coordinate + " was spent by " + output.get("consumed_by_tx").asText());
    }

    /** The control: an output known to be spent must read as spent, or the check above proves nothing. */
    @Test
    void aSpentOutputReadsAsSpent() throws Exception {
        // The ConfigDatum UTxO of 2026-09-30, consumed by FluidTokens' 2026-10-01 in-place update.
        String spent = "3add9d6809c408fc627e93c60065e96857ba5c726d405a5808ee33a7f937c450#0";
        assertThrows(AssertionError.class, () -> assertUnspent(spent, publishedOutput(spent)),
                "a known-spent output passed the unspent check");
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

    /** The shipped liquidation coordinates, key → {@code txHash#index}, blanks omitted. */
    @SuppressWarnings("unchecked")
    private static Map<String, String> shippedLiquidationReferences() throws IOException {
        try (InputStream in = MainnetReferenceScriptsTest.class.getClassLoader()
                .getResourceAsStream("application.yaml")) {
            assertTrue(in != null, "application.yaml is absent from the test classpath");
            Iterable<Object> documents = new Yaml().loadAll(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8));
            // ⚠ The FIRST document only: later ones are profiles, and the preview profile blanks every
            // one of these keys on purpose. Reading them all would test preview's blanks against mainnet.
            Map<String, Object> root = (Map<String, Object>) documents.iterator().next();
            Map<String, Object> liquidation = (Map<String, Object>)
                    ((Map<String, Object>) ((Map<String, Object>) root.get("loans")).get("liquidation"))
                            .get("reference-scripts");
            Map<String, String> shipped = new LinkedHashMap<>();
            for (var entry : liquidation.entrySet()) {
                String placeholder = String.valueOf(entry.getValue());
                int colon = placeholder.indexOf(':');
                assertTrue(placeholder.startsWith("${") && colon > 1 && placeholder.endsWith("}"),
                        entry.getKey() + " is not an env-overridable shipped default");
                String coordinate = placeholder.substring(colon + 1, placeholder.length() - 1);
                // A blank slot is a script FluidTokens have not published; the builder inlines it.
                // Not a failure, and nothing to ask the chain about.
                if (!coordinate.isBlank()) {
                    shipped.put(entry.getKey(), coordinate);
                }
            }
            return shipped;
        }
    }

    /** A shipped mainnet default ({@code ${ENV:value}} in the FIRST yaml document), by dotted path. */
    @SuppressWarnings("unchecked")
    static String shipped(String dotted) throws IOException {
        try (InputStream in = MainnetReferenceScriptsTest.class.getClassLoader()
                .getResourceAsStream("application.yaml")) {
            assertTrue(in != null, "application.yaml is absent from the test classpath");
            Object node = new Yaml().loadAll(new String(in.readAllBytes(), StandardCharsets.UTF_8)).iterator().next();
            for (String key : dotted.split("\\.")) {
                node = ((Map<String, Object>) node).get(key);
            }
            String placeholder = String.valueOf(node);
            int colon = placeholder.indexOf(':');
            assertTrue(placeholder.startsWith("${") && colon > 1 && placeholder.endsWith("}"),
                    dotted + " is not an env-overridable shipped default");
            return placeholder.substring(colon + 1, placeholder.length() - 1);
        }
    }

    private static String configPolicyId() throws IOException {
        return shipped("loans.config.policy-id");
    }

    private static String lmConfigPolicyId() throws IOException {
        return shipped("loans.lm-config.policy-id");
    }

    private static LoansContractRegistry mainnetRegistry() throws IOException {
        return new LoansContractRegistry(configPolicyId(), lmConfigPolicyId(),
                CONFIG_ASSET_NAME, shipped("loans.smart-tokens-spend-script-hash"),
                shipped("loans.minswap.pool-policy-id"), shipped("loans.minswap.pool-spend-script-hash"),
                shipped("loans.minswap.order-spend-script-hash"));
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
        // ⚑ The convert action is in the shipped block and was NOT in this map, so its coordinate was
        // the one named key nothing here checked against the chain. It is derivable, so it is checked.
        expected.put("lm-liquidate-and-convert-action",
                registry.getLmLiquidateAndConvertActionScriptHash());

        Map<String, String> shipped = shippedLiquidationReferences();
        assertEquals(expected.keySet(), shipped.keySet(),
                "the shipped key set must be exactly the set LoansReferenceScriptVerifier checks — a "
                        + "key it does not verify is a coordinate nothing guards");

        List<String> mismatches = new ArrayList<>();
        for (var entry : shipped.entrySet()) {
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
        assertCurrentConfig(CONFIG, configPolicyId() + CONFIG_ASSET_NAME, "mainnet-config-datum-2026-10-01.hex");
        assertCurrentConfig(LM_CONFIG, lmConfigPolicyId() + CONFIG_ASSET_NAME,
                "mainnet-lm-config-datum-2026-10-01.hex");
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

    /**
     * ⛔ <b>Every withdraw credential a liquidation path uses has a REGISTERED reward account.</b> An
     * unregistered one passes evaluation and fails only at submit ({@code ConwayWithdrawalsMissingAccounts},
     * findings §57.8), so nothing offline can see it.
     *
     * <p>Measured RED on 2026-10-01 (FluidTokens published the redeployed withdraw scripts unregistered:
     * claim, lm-liquidate, lm-pay-in-advance, lm-convert), then GREEN the same day once they were registered.
     * Registration is read from the account's registration HISTORY, not {@code active} -- §60:
     * active=false did not mean unregistered.
     */
    @Test
    void everyLiquidationWithdrawCredentialIsRegistered() throws Exception {
        LoansContractRegistry registry = mainnetRegistry();
        Map<String, String> credentials = new LinkedHashMap<>();
        credentials.put("loan-claim-action", registry.getLoanClaimActionScriptHash());
        credentials.put("lm-liquidate-action", registry.getLmLiquidateActionScriptHash());
        credentials.put("lm-liquidate-and-pay-in-advance-action", registry.getLmLiquidateAndPayInAdvanceActionScriptHash());
        credentials.put("lm-liquidate-and-convert-action", registry.getLmLiquidateAndConvertActionScriptHash());
        credentials.put("lender-manager", registry.getLenderManagerWithdrawScriptHash());
        credentials.put("asset-manager", registry.getAssetManagerWithdrawScriptHash());
        credentials.put("loan", registry.getLoanPolicyId());

        List<String> unregistered = unregistered(credentials);
        assertTrue(unregistered.isEmpty(), "withdraw credentials with no registered reward account -- every "
                + "liquidation using them fails at submit: " + unregistered);
    }

    /**
     * The compound path's own withdraw credentials, checked separately from the liquidation ones because
     * compound is disabled on mainnet by default: a red here grounds compound, not liquidation.
     * {@code lm_compound_action} moved on 2026-10-01 like the liquidate actions did.
     */
    @Test
    void everyCompoundWithdrawCredentialIsRegistered() throws Exception {
        LoansContractRegistry registry = mainnetRegistry();
        // The builder's own list, pinned to a built transaction in CompoundDryEvalTest -- never a copy here.
        Map<String, String> credentials = new LinkedHashMap<>();
        List<String> names = List.of("asset-manager", "lender-manager", "lm-compound-action", "pool",
                "pool-compound-action", "pool-manager", "pm-compound-liquidity");
        List<String> hashes = CompoundTransactionBuilder.withdrawCredentials(registry);
        assertEquals(names.size(), hashes.size(), "a compound withdrawal was added or removed: name it here");
        for (int i = 0; i < hashes.size(); i++) {
            credentials.put(names.get(i), hashes.get(i));
        }
        List<String> unregistered = unregistered(credentials);
        assertTrue(unregistered.isEmpty(), "compound withdraw credentials with no registered reward account -- "
                + "every compound using them fails at submit: " + unregistered);
    }

    /**
     * Each credential whose MOST RECENT registration action is not {@code registered}. Read newest-first,
     * one event: an ascending page would judge a long history by its 100th event, not its last.
     */
    private static List<String> unregistered(Map<String, String> credentials) throws Exception {
        List<String> unregistered = new ArrayList<>();
        for (var e : credentials.entrySet()) {
            String stake = com.bloxbean.cardano.client.address.AddressProvider.getRewardAddress(
                    com.bloxbean.cardano.client.address.Credential.fromScript(e.getValue()),
                    com.bloxbean.cardano.client.common.model.Networks.mainnet()).toBech32();
            String last;
            try {
                last = mostRecentAction(get(registrationsPath(stake)));
            } catch (IllegalStateException notFound) {
                last = null;   // a 404: the account has never existed
            }
            if (!"registered".equals(last)) {
                unregistered.add(e.getKey() + " " + e.getValue() + " (" + stake + ")");
            }
        }
        return unregistered;
    }

    /** Newest event first, one event: the account's CURRENT state (tested keyless in RegistrationQueryTest). */
    static String registrationsPath(String stakeAddress) {
        return WithdrawAccountRegistration.registrationsPath(stakeAddress);
    }

    /** The action of the first (newest, given {@link #registrationsPath}) event, or null for none. */
    static String mostRecentAction(JsonNode events) {
        return WithdrawAccountRegistration.mostRecentAction(events);
    }
}
