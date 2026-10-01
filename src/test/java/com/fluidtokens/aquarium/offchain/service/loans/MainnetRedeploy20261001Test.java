package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ⛔ <b>FluidTokens' 2026-10-01 mainnet redeploy (FTAI-001 / FTAI-002 / FTAI-102), taken up.</b>
 *
 * <p>What has to stay true of the vendored artefact and the registry from here, each asserted against the
 * live datums captured that day (`mainnet-*-config-datum-2026-10-01.hex`) or against the artefact the
 * node shipped before it (`loans-v4-2026-09-17.plutus.json`, upstream {@code aad6c59}):
 * <ul>
 *   <li>the vendored file is the assembled hybrid, byte for byte, and differs from the previous one in
 *       exactly the five validators FluidTokens changed and deployed;</li>
 *   <li>the hybrid's own evidence -- the three live fields only the KEPT pool-manager code can derive;</li>
 *   <li>the control group -- every hash outside the redeploy derives exactly as before;</li>
 *   <li>no re-sync -- the payment credentials the node indexes did not move.</li>
 * </ul>
 * See {@code src/test/resources/loans-v4/blueprint/loans-v4-blueprint-2026-10-01.PROVENANCE.md}.
 */
class MainnetRedeploy20261001Test {

    private static final String CONFIG = "235b32040fe1177c03b1d34febc470440c6eaaa2228a9c1b0e375200";
    private static final String LM_CONFIG = "fb6ae2027358b4a0b62710eb95102d87fa13f66ecf55d8943699c492";
    private static final String ASSET = "706172616d6574657273";
    private static final String SMART = "fca77bcce1e5e73c97a0bfa8c90f7cd2faff6fd6ed5b6fec1c04eefa";
    private static final String MS_POLICY = "f5808c2c990d86da54bfc97d89cee6efa20cd8461616359478d96b4c";
    private static final String MS_POOL = "ea07b733d932129c378af627436e7cbc2ef0bf96e0036bb51b3bde6b";
    private static final String MS_ORDER = "c3e28c36c3447315ba5a56f33da6a6ddc1770a876a8d9f0cb3a97c4c";

    private static final String BEFORE = "loans-v4-2026-09-17.plutus.json";
    private static final String SHIPPED_SHA256 = "a638e71ca047b668f74eeeff1b80e76695626ddfe506a9ca1d2721e9a1e72704";

    private static LoansContractRegistry shipped() {
        return new LoansContractRegistry(CONFIG, LM_CONFIG, ASSET, SMART, MS_POLICY, MS_POOL, MS_ORDER);
    }

    private static LoansContractRegistry before() {
        return new LoansContractRegistry(BEFORE, CONFIG, LM_CONFIG, ASSET, SMART, MS_POLICY, MS_POOL, MS_ORDER);
    }

    private static byte[] resource(String name) throws Exception {
        try (InputStream in = new ClassPathResource(name).getInputStream()) {
            return in.readAllBytes();
        }
    }

    /** Blueprint title -> its {@code hash} field, for every handler entry. */
    private static Map<String, String> hashes(String name) throws Exception {
        Map<String, String> m = new TreeMap<>();
        for (JsonNode v : new ObjectMapper().readTree(resource(name)).get("validators")) {
            m.put(v.get("title").asText(), v.get("hash").asText());
        }
        return m;
    }

    private static List<PlutusData> fields(String fixture) throws Exception {
        String hex = new String(resource("loans-v4/" + fixture), StandardCharsets.UTF_8).trim();
        return ((ConstrPlutusData) PlutusData.deserialize(HexUtil.decodeHexString(hex))).getData().getPlutusDataList();
    }

    private static String hexAt(List<PlutusData> fields, int i) {
        return HexUtil.encodeHexString(((BytesPlutusData) fields.get(i)).getValue());
    }

    @Test
    void theVendoredFileIsTheAssembledHybridAndMovedOnlyTheFiveRedeployedValidators() throws Exception {
        assertEquals(SHIPPED_SHA256, HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(resource("loans-v4.plutus.json"))),
                "the vendored blueprint is not the 2026-10-01 assembly (see assemble-2026-10-01.py)");

        Map<String, String> now = hashes("loans-v4.plutus.json");
        Map<String, String> was = hashes(BEFORE);
        assertEquals(was.keySet(), now.keySet(), "no validator may appear or disappear");
        Set<String> moved = new TreeSet<>();
        now.forEach((title, hash) -> {
            if (!hash.equals(was.get(title))) {
                moved.add(title.replaceAll("\\.[^.]+$", ""));
            }
        });
        // ⛔ pool_manager/pm_cancel_pool_manager and pm_edit_pool are NOT here: upstream's committed fec809e
        // changed them, the chain does not run that change, and FluidTokens confirmed it should not have
        // been merged (Raul, 2026-10-01). A plain re-vendor of upstream's file turns this red.
        assertEquals(Set.of(
                        "lender_manager/lm_compound_action.actionValidator",
                        "loan/loan_claim_action.loan_claim_action",
                        "pool/pool_borrow_action.pool_borrow_action",
                        "pool/pool_edit_action.pool_edit_action",
                        "pool/pool_sell_lender_position.pool_sell_lender_position_action"),
                moved, "exactly the validators FluidTokens redeployed on 2026-10-01");
    }

    /**
     * The hybrid's evidence: these three live fields are derivable ONLY from the kept, pre-2026-09-18
     * pool-manager code (lm_compound_action takes the pool manager's spend hash and policy). And the
     * convert action, which the verifier only LOGS, asserted explicitly.
     */
    @Test
    void theKeptPoolManagerAndTheConvertActionDeriveTheLiveFields() throws Exception {
        LoansContractRegistry registry = shipped();
        List<PlutusData> config = fields("mainnet-config-datum-2026-10-01.hex");
        List<PlutusData> lm = fields("mainnet-lm-config-datum-2026-10-01.hex");

        assertEquals(30, config.size(), "the 30-field ConfigDatum");
        assertEquals(hexAt(config, 27), registry.getPoolManagerSpendScriptHash(), "ConfigDatum[27] poolManagerSpend");
        assertEquals(hexAt(config, 28), registry.getPoolManagerPolicyId(), "ConfigDatum[28] poolManagerPolicyId");
        assertEquals(hexAt(lm, 3), registry.getLmCompoundActionScriptHash(), "LMConfigDatum[3] lm_compound_action");
        assertEquals(hexAt(lm, 5), registry.getLmLiquidateAndConvertActionScriptHash(),
                "LMConfigDatum[5] lm_liquidate_and_convert_action -- the verifier only logs this one");
        assertEquals(hexAt(config, 11), registry.getLoanClaimActionScriptHash(), "ConfigDatum[11] claim (six parameters)");
        assertEquals(hexAt(config, 24), registry.getPoolSellLenderPositionActionScriptHash(),
                "ConfigDatum[24] pool sell (three parameters)");
    }

    /**
     * The control group (CLAUDE.md: validators that did not move are the check that separates "wrong
     * compiler" from "wrong source"). Everything outside the redeploy derives exactly as it did.
     */
    @Test
    void everyHashOutsideTheRedeployDerivesExactlyAsBefore() {
        Map<String, String> now = shipped().derivedHashes();
        Map<String, String> was = before().derivedHashes();
        Set<String> moved = new TreeSet<>();
        now.forEach((k, v) -> {
            if (!v.equals(was.get(k))) {
                moved.add(k);
            }
        });
        assertEquals(new TreeSet<>(Set.of(
                        // code changed
                        "loanClaimActionScriptHash", "poolBorrowActionScriptHash", "poolEditActionScriptHash",
                        "poolSellLenderPositionActionScriptHash", "lmCompoundActionScriptHash",
                        // parameterised by the claim credential
                        "lmLiquidateActionScriptHash", "lmLiquidateAndPayInAdvanceActionScriptHash",
                        "lmLiquidateAndConvertActionScriptHash", "lmLiquidatePayInAdvanceAndCompoundActionScriptHash")),
                moved, "only the redeployed validators and those parameterised by the claim may move");
    }

    /** No wipe, no re-sync: the payment credentials the node indexes are identical before and after. */
    @Test
    void theIndexedPaymentCredentialsDidNotMove() {
        assertEquals(before().indexedPaymentCredentials(), shipped().indexedPaymentCredentials(),
                "an operator upgrading to this release needs no re-sync only while these are unchanged");
    }

    /** The derivation follows the artefact's declared arity, in both directions. */
    @Test
    void theClaimAndSellArityFollowsTheArtefact() {
        Map<String, Integer> now = shipped().appliedParameterCounts();
        Map<String, Integer> was = before().appliedParameterCounts();
        assertEquals(6, now.get("loan/loan_claim_action.loan_claim_action"));
        assertEquals(3, now.get("pool/pool_sell_lender_position.pool_sell_lender_position_action"));
        assertEquals(4, was.get("loan/loan_claim_action.loan_claim_action"));
        assertEquals(2, was.get("pool/pool_sell_lender_position.pool_sell_lender_position_action"));
    }

    /**
     * ⛔ An arity the registry does not know is REFUSED, never derived: both arities of a validator apply
     * cleanly and hash, so deriving an unknown one anyway is a valid-looking hash nobody deployed. The
     * variant is the vendored blueprint with the claim declaring FIVE parameters, written beside the test
     * resources for the duration of this test.
     */
    @Test
    void anUnknownClaimArityIsRefusedRatherThanGuessed() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(resource("loans-v4.plutus.json"));
        for (JsonNode v : root.get("validators")) {
            if (v.get("title").asText().startsWith("loan/loan_claim_action.") && v.has("parameters")) {
                ((com.fasterxml.jackson.databind.node.ArrayNode) v.get("parameters")).remove(5);
            }
        }
        java.nio.file.Path dir = java.nio.file.Path.of(new ClassPathResource(BEFORE).getURL().toURI()).getParent();
        java.nio.file.Path variant = dir.resolve("loans-v4-claim-five-parameters.plutus.json");
        java.nio.file.Files.write(variant, mapper.writeValueAsBytes(root));
        try {
            IllegalStateException refused = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> new LoansContractRegistry(variant.getFileName().toString(),
                            CONFIG, LM_CONFIG, ASSET, SMART, MS_POLICY, MS_POOL, MS_ORDER));
            org.junit.jupiter.api.Assertions.assertTrue(
                    refused.getMessage().contains("loan_claim_action declares 5 parameters"), refused.getMessage());
        } finally {
            java.nio.file.Files.deleteIfExists(variant);
        }
    }
}
