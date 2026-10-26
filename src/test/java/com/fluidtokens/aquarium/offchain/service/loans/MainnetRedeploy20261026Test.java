package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.UtxoService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.service.LoansConfigVerifier;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ⛔ <b>FluidTokens' 2026-10-23 / 2026-10-26 mainnet datum rewrites, taken up (FAB-166).</b>
 *
 * <p>Both config datums were rewritten in place, config NFTs unchanged:
 * ConfigDatum {@code 75e459a0…#0} (2026-10-23, only [24] pool sell moved) and LMConfigDatum
 * {@code c0f0d821…#0} (2026-10-26, [3] lm_compound ENFORCED and [6] lm_pia_compound advisory). A node on
 * the 2026-10-01 artefact closes its lending gate on LMConfigDatum[3]. What has to stay true from here,
 * each asserted against the live captures ({@code mainnet-*-2026-10-2{3,6}.hex}) or against the artefact
 * the node shipped before ({@code loans-v4-2026-10-01.plutus.json}):
 * <ul>
 *   <li>the vendored file is the assembled hybrid, byte for byte, and differs from the previous one in
 *       exactly the three validators FluidTokens redeployed;</li>
 *   <li>the control group -- every other derived hash is identical;</li>
 *   <li>the real verifier, served the live datums through a stubbed Blockfrost, opens the gate;</li>
 *   <li>the applied compound scripts are the published reference-script bytes, exactly;</li>
 *   <li>every derivable field equals the live one.</li>
 * </ul>
 * See {@code src/test/resources/loans-v4/blueprint/loans-v4-blueprint-2026-10-26.PROVENANCE.md}.
 */
class MainnetRedeploy20261026Test {

    private static final String CONFIG = "235b32040fe1177c03b1d34febc470440c6eaaa2228a9c1b0e375200";
    private static final String LM_CONFIG = "fb6ae2027358b4a0b62710eb95102d87fa13f66ecf55d8943699c492";
    private static final String ASSET = "706172616d6574657273";
    private static final String SMART = "fca77bcce1e5e73c97a0bfa8c90f7cd2faff6fd6ed5b6fec1c04eefa";
    private static final String MS_POLICY = "f5808c2c990d86da54bfc97d89cee6efa20cd8461616359478d96b4c";
    private static final String MS_POOL = "ea07b733d932129c378af627436e7cbc2ef0bf96e0036bb51b3bde6b";
    private static final String MS_ORDER = "c3e28c36c3447315ba5a56f33da6a6ddc1770a876a8d9f0cb3a97c4c";

    private static final String BEFORE = "loans-v4-2026-10-01.plutus.json";
    private static final String SHIPPED_SHA256 = "d2f12d046ee130f93c23ccb490012ace375a21d14fd5c13b824266c504b8ae2d";

    private static final String CONFIG_DATUM = "mainnet-config-datum-2026-10-23.hex";
    private static final String LM_CONFIG_DATUM = "mainnet-lm-config-datum-2026-10-26.hex";
    /** FluidTokens' unpublished pause hash at ConfigDatum[14] since 2026-10-01 -- advisory, never used. */
    private static final String RECAST_PAUSE = "64d9b13f973be664a05c22365f90b222b0f9018b94918d3cb5d0220f";

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

    private static String fixture(String name) throws Exception {
        return new String(resource("loans-v4/" + name), StandardCharsets.UTF_8).trim();
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
        return ((ConstrPlutusData) PlutusData.deserialize(HexUtil.decodeHexString(fixture(fixture))))
                .getData().getPlutusDataList();
    }

    private static String hexAt(List<PlutusData> fields, int i) {
        return HexUtil.encodeHexString(((BytesPlutusData) fields.get(i)).getValue());
    }

    private static String enterpriseAddress(String policy) {
        return AddressProvider.getEntAddress(Credential.fromScript(HexUtil.decodeHexString(policy)),
                Networks.mainnet()).getAddress();
    }

    private static Utxo configUtxo(String policy, String datumHex) {
        return Utxo.builder().txHash("00".repeat(32)).outputIndex(0)
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(2_000_000)),
                        Amount.asset(policy + ASSET, BigInteger.ONE)))
                .inlineDatum(datumHex).build();
    }

    /** The real verifier, reading both datums through a stubbed Blockfrost exactly where production reads them. */
    private static LoansConfigVerifier verifierServingLiveDatums(LoansContractRegistry registry) throws Exception {
        UtxoService utxos = mock(UtxoService.class);
        when(utxos.getUtxos(anyString(), anyInt(), anyInt())).thenReturn(
                Result.<List<Utxo>>success("ok").withValue(List.of()));
        when(utxos.getUtxos(enterpriseAddress(CONFIG), 100, 1)).thenReturn(
                Result.<List<Utxo>>success("ok").withValue(List.of(configUtxo(CONFIG, fixture(CONFIG_DATUM)))));
        when(utxos.getUtxos(enterpriseAddress(LM_CONFIG), 100, 1)).thenReturn(
                Result.<List<Utxo>>success("ok").withValue(List.of(configUtxo(LM_CONFIG, fixture(LM_CONFIG_DATUM)))));
        BFBackendService bf = mock(BFBackendService.class);
        when(bf.getUtxoService()).thenReturn(utxos);

        var network = new AppConfig.Network();
        network.setNetworkForTest("mainnet");
        return new LoansConfigVerifier(registry, SMART, network, bf, false);
    }

    @Test
    void theVendoredFileIsTheAssembledHybridAndMovedOnlyTheThreeRedeployedValidators() throws Exception {
        assertEquals(SHIPPED_SHA256, HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(resource("loans-v4.plutus.json"))),
                "the vendored blueprint is not the 2026-10-26 assembly (see assemble-2026-10-26.py)");

        Map<String, String> now = hashes("loans-v4.plutus.json");
        Map<String, String> was = hashes(BEFORE);
        assertEquals(was.keySet(), now.keySet(), "no validator may appear or disappear");
        Set<String> moved = new TreeSet<>();
        now.forEach((title, hash) -> {
            if (!hash.equals(was.get(title))) {
                moved.add(title.replaceAll("\\.[^.]+$", ""));
            }
        });
        // ⛔ loan_repay_action and loan_recast_action are NOT here: upstream a02d4e2 changed both, and the
        // chain runs neither change (ConfigDatum[12] is still the kept repay; [14] is FluidTokens' pause
        // hash). The pool-manager pair stays kept for the 2026-10-01 reason. A plain re-vendor of a02d4e2
        // turns this red.
        assertEquals(Set.of(
                        "lender_manager/lm_compound_action.actionValidator",
                        "lender_manager/lm_liquidate_pay_in_advance_and_compound_action.actionValidator",
                        "pool/pool_sell_lender_position.pool_sell_lender_position_action"),
                moved, "exactly the validators FluidTokens redeployed on 2026-10-23/26");
    }

    /**
     * The control group (CLAUDE.md: validators that did not move are the check that separates "wrong
     * compiler" from "wrong source"). Everything outside the redeploy derives exactly as it did.
     */
    @Test
    void everyHashOutsideTheRedeployDerivesExactlyAsBefore() {
        Map<String, String> now = shipped().derivedHashes();
        Map<String, String> was = before().derivedHashes();
        assertEquals(was.keySet(), now.keySet());
        Set<String> moved = new TreeSet<>();
        now.forEach((k, v) -> {
            if (!v.equals(was.get(k))) {
                moved.add(k);
            }
        });
        assertEquals(new TreeSet<>(Set.of(
                        "lmCompoundActionScriptHash", "lmLiquidatePayInAdvanceAndCompoundActionScriptHash",
                        "poolSellLenderPositionActionScriptHash")),
                moved, "only the three redeployed validators may move");
    }

    /** No wipe, no re-sync: the payment credentials the node indexes are identical before and after. */
    @Test
    void theIndexedPaymentCredentialsDidNotMove() {
        assertEquals(before().indexedPaymentCredentials(), shipped().indexedPaymentCredentials(),
                "an operator upgrading to this release needs no re-sync only while these are unchanged");
    }

    /**
     * The real startup path against the live datums: the gate stays open, nothing the node uses
     * differs, and the one advisory difference is FluidTokens' recast pause -- as on 2026-10-01.
     */
    @Test
    void theRealVerifierAgainstTheLiveDatumsOpensTheGateWithOnlyTheRecastPauseAdvisory() throws Exception {
        LoansConfigVerifier verifier = verifierServingLiveDatums(shipped());
        verifier.verify();
        assertFalse(verifier.gate().isBlocked(), () -> "the lending gate must stay open: "
                + verifier.gate().blockedReason());

        LoansConfigVerifier.Findings findings =
                verifier.verifyAgainstBySeverity(fixture(CONFIG_DATUM), fixture(LM_CONFIG_DATUM));
        assertEquals(List.of(), findings.enforced(), "nothing the node uses may differ from the live datums");
        assertEquals(1, findings.advisory().size(), () -> "advisory: " + findings.advisory());
        assertTrue(findings.advisory().getFirst().startsWith("ConfigDatum[14]:")
                        && findings.advisory().getFirst().endsWith(RECAST_PAUSE),
                () -> "the only difference must be FluidTokens' recast pause: " + findings.advisory());
    }

    /**
     * Why this release exists, kept as a permanent fact: the artefact the node shipped before, served the
     * same live datums, closes the gate on LMConfigDatum[3] -- the operator-visible failure.
     */
    @Test
    void theOutgoingArtefactClosesTheGateOnLmCompound() throws Exception {
        LoansConfigVerifier verifier = verifierServingLiveDatums(before());
        verifier.verify();
        assertTrue(verifier.gate().isBlocked(), "the 2026-10-01 artefact must not verify against the new datums");

        LoansConfigVerifier.Findings findings =
                verifier.verifyAgainstBySeverity(fixture(CONFIG_DATUM), fixture(LM_CONFIG_DATUM));
        assertEquals(1, findings.enforced().size(), () -> "enforced: " + findings.enforced());
        assertTrue(findings.enforced().getFirst().startsWith("LMConfigDatum[3]:"), findings.enforced()::toString);
        assertEquals(List.of("ConfigDatum[14]", "ConfigDatum[24]", "LMConfigDatum[6]"),
                findings.advisory().stream().map(m -> m.substring(0, m.indexOf(':'))).toList());
    }

    /**
     * ⛔ <b>The applied scripts ARE the published reference scripts, byte for byte.</b>
     *
     * <p>What is compared: {@link PlutusScript#serializeScriptBody()} of the applied script -- the program
     * as ONE CBOR byte string around its flat encoding, i.e. exactly the bytes the ledger hashes after the
     * {@code 0x03} language tag -- against Koios {@code utxo_info} {@code reference_script.bytes} of
     * {@code 588695…#0} and {@code 047020cf…#0}, which is the same single-wrapped encoding. The script's
     * {@code cborHex} carries one MORE byte-string header (3 bytes, {@code 59xxxx}); it is that header the
     * probe stripped. Equality, not containment.
     */
    @Test
    void theAppliedCompoundScriptsAreThePublishedReferenceScriptsExactly() throws Exception {
        LoansContractRegistry registry = shipped();
        assertEquals(fixture("mainnet-compound-script-2026-10-26.hex"),
                HexUtil.encodeHexString(registry.getLmCompoundActionScript().serializeScriptBody()),
                "lm_compound_action applied != the reference script at 588695…#0");
        assertEquals(fixture("mainnet-pia-compound-script-2026-10-26.hex"),
                HexUtil.encodeHexString(registry.getLmLiquidatePayInAdvanceAndCompoundActionScript().serializeScriptBody()),
                "lm_liquidate_pay_in_advance_and_compound_action applied != the reference script at 047020cf…#0");
        // and the hash of those bytes is the field each is published under
        assertEquals("4713a925302692076b686eee3de80963c94e464108522afacf19039c",
                HexUtil.encodeHexString(registry.getLmCompoundActionScript().getScriptHash()));
        assertEquals("00e38efa912fa4e6906c9e8550c34ef15aaf7074973c1445642a0be4",
                HexUtil.encodeHexString(registry.getLmLiquidatePayInAdvanceAndCompoundActionScript().getScriptHash()));
    }

    /**
     * Per derivable field, the derived value equals the live one. Not derived, and so not here:
     * ConfigDatum[0] (configured: {@code loans.smart-tokens-spend-script-hash}), [1] (admin credential),
     * [7] (repaymentPolicyId), [16]/[17] (dutch auction), [18]..[21] (integers), LMConfigDatum[0] (admin
     * credential). ConfigDatum[14] is asserted separately: FluidTokens' pause hash, not a deployment.
     */
    @Test
    void everyDerivableFieldEqualsTheLiveDatum() throws Exception {
        LoansContractRegistry r = shipped();
        List<PlutusData> config = fields(CONFIG_DATUM);
        List<PlutusData> lm = fields(LM_CONFIG_DATUM);
        assertEquals(30, config.size(), "the 30-field ConfigDatum");
        assertEquals(8, lm.size(), "the 8-field LMConfigDatum");

        Map<Integer, String> cfg = new LinkedHashMap<>();
        cfg.put(2, r.getPoolPolicyId());
        cfg.put(3, r.getRequestPolicyId());
        cfg.put(4, r.getBorrowerBondPolicyId());
        cfg.put(5, r.getLenderBondPolicyId());
        cfg.put(6, r.getLoanPolicyId());
        cfg.put(8, r.getPoolSpendScriptHash());
        cfg.put(9, r.getRequestSpendScriptHash());
        cfg.put(10, r.getLoanSpendScriptHash());
        cfg.put(11, r.getLoanClaimActionScriptHash());
        cfg.put(12, r.getLoanRepayActionScriptHash());
        cfg.put(13, r.getLoanChangeCollateralActionScriptHash());
        cfg.put(15, r.getAssetManagerSpendScriptHash());
        cfg.put(22, r.getPoolCancelActionScriptHash());
        cfg.put(23, r.getPoolBorrowActionScriptHash());
        cfg.put(24, r.getPoolSellLenderPositionActionScriptHash());
        cfg.put(25, r.getPoolCompoundActionScriptHash());
        cfg.put(26, r.getPoolEditActionScriptHash());
        cfg.put(27, r.getPoolManagerSpendScriptHash());
        cfg.put(28, r.getPoolManagerPolicyId());
        cfg.put(29, r.getLockedBorrowerManagerSpendScriptHash());
        cfg.forEach((i, derived) -> assertEquals(hexAt(config, i), derived, "ConfigDatum[" + i + "]"));

        Map<Integer, String> lmc = new LinkedHashMap<>();
        lmc.put(1, r.getLmWithdrawBondsActionScriptHash());
        lmc.put(2, r.getLmLiquidateActionScriptHash());
        lmc.put(3, r.getLmCompoundActionScriptHash());
        lmc.put(4, r.getLmLiquidateAndPayInAdvanceActionScriptHash());
        lmc.put(5, r.getLmLiquidateAndConvertActionScriptHash());
        lmc.put(6, r.getLmLiquidatePayInAdvanceAndCompoundActionScriptHash());
        lmc.put(7, r.getLmLiquidateConvertAndCompoundActionScriptHash());
        lmc.forEach((i, derived) -> assertEquals(hexAt(lm, i), derived, "LMConfigDatum[" + i + "]"));

        assertEquals(SMART, hexAt(config, 0), "ConfigDatum[0] is the configured smart-tokens hash");
        assertEquals(RECAST_PAUSE, hexAt(config, 14), "ConfigDatum[14] is still FluidTokens' recast pause");
        // the three fields that moved, by value
        assertEquals("f43354cb9a5fd138398869ca76cd9fefadfa87e60cb0f47081376a0f", hexAt(config, 24));
        assertEquals("4713a925302692076b686eee3de80963c94e464108522afacf19039c", hexAt(lm, 3));
        assertEquals("00e38efa912fa4e6906c9e8550c34ef15aaf7074973c1445642a0be4", hexAt(lm, 6));
    }

    /**
     * ⛔ <b>Why eight upstream entries were NOT taken -- the counterfactual, measured.</b> The shipped file
     * with the a02d4e2 entries the recipe substituted out put back ({@code a02d4e2-not-deployed-entries.json},
     * written verbatim by {@code assemble-2026-10-26.py}): a02d4e2's repay does not derive the live
     * ConfigDatum[12], and a02d4e2's pool manager (still the 2026-09-18 change) does not derive [27], [28] or
     * LMConfigDatum[3]. A plain re-vendor of a02d4e2 would close the lending gate. Recast is kept for the same
     * minimal-change reason; its live field [14] is FluidTokens' pause hash, so the chain cannot arbitrate it.
     */
    @Test
    void theUpstreamEntriesThatWereNotKeptDoNotDeriveTheChain() throws Exception {
        List<PlutusData> config = fields(CONFIG_DATUM);
        List<PlutusData> lm = fields(LM_CONFIG_DATUM);
        LoansContractRegistry kept = shipped();
        assertEquals(hexAt(config, 12), kept.getLoanRepayActionScriptHash(), "the kept repay is the deployed one");

        Map<String, String> upstreamRepay = derivedWithUpstreamEntries("loan/loan_repay_action.");
        assertNotEquals(hexAt(config, 12), upstreamRepay.get("loanRepayActionScriptHash"),
                "a02d4e2's repay derives the live ConfigDatum[12] -- the reason to keep the old one is gone");
        assertEquals(REPAY_A02D4E2, upstreamRepay.get("loanRepayActionScriptHash"));

        Map<String, String> upstreamPoolManager = derivedWithUpstreamEntries("pool_manager/");
        assertNotEquals(hexAt(config, 27), upstreamPoolManager.get("poolManagerSpendScriptHash"), "ConfigDatum[27]");
        assertNotEquals(hexAt(config, 28), upstreamPoolManager.get("poolManagerPolicyId"), "ConfigDatum[28]");
        assertNotEquals(hexAt(lm, 3), upstreamPoolManager.get("lmCompoundActionScriptHash"), "LMConfigDatum[3]");
    }

    /** What upstream a02d4e2's own loan_repay_action derives for mainnet; the live ConfigDatum[12] is 41ac9c9f…. */
    private static final String REPAY_A02D4E2 = "779a92a8032c77167b2d172c0c35aea18f6b47bf57667c696c769adb";

    /**
     * Derived hashes of the shipped artefact with every a02d4e2 entry whose title starts with {@code prefix}
     * put back, written beside the test resources for the duration of the derivation.
     */
    private static Map<String, String> derivedWithUpstreamEntries(String prefix) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode upstream = mapper.readTree(resource("loans-v4/blueprint/a02d4e2-not-deployed-entries.json"));
        assertEquals(8, upstream.size(), "the recipe substitutes out exactly eight entries");
        JsonNode root = mapper.readTree(resource("loans-v4.plutus.json"));
        ArrayNode validators = (ArrayNode) root.get("validators");
        int replaced = 0;
        for (int i = 0; i < validators.size(); i++) {
            String title = validators.get(i).get("title").asText();
            if (title.startsWith(prefix) && upstream.has(title)) {
                validators.set(i, upstream.get(title).deepCopy());
                replaced++;
            }
        }
        assertTrue(replaced > 0, "no entry under " + prefix + " was replaced -- the variant would be the shipped file");
        java.nio.file.Path dir = java.nio.file.Path.of(new ClassPathResource(BEFORE).getURL().toURI()).getParent();
        java.nio.file.Path variant = dir.resolve("loans-v4-a02d4e2-" + prefix.replaceAll("[^a-z]", "") + ".plutus.json");
        java.nio.file.Files.write(variant, mapper.writeValueAsBytes(root));
        try {
            return new LoansContractRegistry(variant.getFileName().toString(),
                    CONFIG, LM_CONFIG, ASSET, SMART, MS_POLICY, MS_POOL, MS_ORDER).derivedHashes();
        } finally {
            java.nio.file.Files.deleteIfExists(variant);
        }
    }

    /** The derivation follows the artefact's declared arity: sell went back to two parameters at a02d4e2. */
    @Test
    void theSellArityFollowsTheArtefact() {
        assertEquals(2, shipped().appliedParameterCounts()
                .get("pool/pool_sell_lender_position.pool_sell_lender_position_action"));
        assertEquals(3, before().appliedParameterCounts()
                .get("pool/pool_sell_lender_position.pool_sell_lender_position_action"));
        assertEquals(6, shipped().appliedParameterCounts().get("loan/loan_claim_action.loan_claim_action"));
    }
}
