package com.fluidtokens.aquarium.offchain;

import com.bloxbean.cardano.aiken.AikenScriptUtil;
import com.bloxbean.cardano.client.plutus.blueprint.PlutusBlueprintUtil;
import com.bloxbean.cardano.client.plutus.blueprint.model.PlutusVersion;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves that the bundled ft-cardano-loans-v4 blueprint is the one actually deployed
 * on preview, by re-deriving every script hash from the two config NFT policy ids and
 * comparing against the values published in the live config UTxO datums.
 * <p>
 * Ground truth is tx {@code 6de7b7ec…094} on preview:
 * output 0 holds the main config NFT ({@code ConfigDatum}), output 1 holds the
 * LenderManager config NFT ({@code LMConfigDatum}). See docs/lending-v4-findings.md.
 * <p>
 * If this test is green, the blueprint matches the deployed contracts and we can derive
 * loan addresses instead of hardcoding them. If it goes red, our clone is the wrong
 * commit and nothing built on top of it can be trusted.
 */
class LoansContractDerivationTest {

    // ---- Derivation inputs (preview) ----------------------------------------------------

    private static final String CONFIG_POLICY_ID = "f1a475ea8cccc1e0b7a59b10e79ad171452dc057ffb1cda0df92835c";
    private static final String LM_CONFIG_POLICY_ID = "d0998754ddc3e9cfe80356d7e12db163d03cecc5b6b438dad4f4a3e3";
    /** "parameters" — hardcoded in lib/fluidtokens/constants.ak, not a free choice. */
    private static final String CONFIG_ASSET_NAME = "706172616d6574657273";

    // ---- Ground truth: ConfigDatum, output 0 of 6de7b7ec…094 -----------------------------

    private static final String SMART_TOKENS_SPEND = "fca77bcce1e5e73c97a0bfa8c90f7cd2faff6fd6ed5b6fec1c04eefa";
    private static final String POOL_POLICY_ID = "c02ec05d2806339fd752c1370a28ab613c8603f071fa077a03ea55a0";
    private static final String REQUEST_POLICY_ID = "4519ff8d1d17660b60ee98c80cd48dd1c81d720d1ea3280cecfc5b46";
    private static final String LOAN_POLICY_ID = "79d911c0beb40fae0a26ff802b1bc017d62b3bd07eca4766b6660dad";
    private static final String POOL_SPEND = "9434006c1e8365c3afedc69332a3eee5d5779eb1a1d44ed37c7cb6f3";
    private static final String REQUEST_SPEND = "e8b4765ebe61377d3f32ea56f37974335ebfe0aa2417e19611cfeed1";
    private static final String LOAN_SPEND = "b8569d71e5a918f79ba2b6899f53c534631f73db92207582a15c414a";
    private static final String LOAN_CLAIM_ACTION = "2ec873803f7b44688edbbe98671bf1f79c921143bb39b10f274b9b79";
    private static final String LOAN_REPAY_ACTION = "7e36682e0366ecbc38e3875fdb4f44f33c2ce0bdcdc555a4f3015245";
    private static final String LOAN_CHANGE_COLLATERAL_ACTION = "28dbdb0620901f7b43feb4d8cbbc8b9e4c6499ffbb5537e152546a47";
    private static final String LOAN_RECAST_ACTION = "66b3b28fea964bf0b15b42015cee2c09cca2347a640f30a4d7d43289";
    private static final String ASSET_MANAGER_SPEND = "3f9068ec82efa5c87537fe626a65037f868a86c0191e99a4decf56dd";
    private static final String POOL_CANCEL_ACTION = "85ba63cdb595df5eb3ecbcb10620fd106b3af2e745308f18b01aaf66";
    private static final String POOL_BORROW_ACTION = "991a88e0bd9f0fd925e3e7f4b52c2da48e47474b055c1a5e1610980c";
    private static final String POOL_SELL_LENDER_POSITION_ACTION = "dc8332cb9d29423f21ca0fa578860cb412d276747f0c486595e41d65";
    private static final String POOL_COMPOUND_ACTION = "0626cf5a62522eede017006c1144f29fa9446e11cb352f67c2910803";
    private static final String POOL_MANAGER_SPEND = "e67abf27731ce422ab8918a1ef88dab0b25a2fe86cb3c8123b09fe49";
    private static final String POOL_MANAGER_POLICY_ID = "e4aee9c6a86cfa3ed2bcdc9b2089a7f259167e0a10f866650d6ca296";
    private static final String LOCKED_BORROWER_MANAGER_SPEND = "3b10fe08db2516218b2a1c6efe3ff6dd5008dd8898da408e1116274c";

    // ---- Ground truth: LMConfigDatum, output 1 of 6de7b7ec…094 ---------------------------

    private static final String LM_WITHDRAW_BONDS_ACTION = "4aa7f99f9bd697071162cce2c8edb92e40a31edc48141db94ab74584";
    private static final String LM_LIQUIDATE_ACTION = "c67e53e2b8d9c9a305e8dc281ddb1b79a2741976fd5657e8eadf3bf9";
    private static final String LM_COMPOUND_ACTION = "36835ebd9a76dc22595782be75da7fcec8560562462ea6e35e01efaa";
    private static final String LM_LIQUIDATE_AND_PAY_IN_ADVANCE_ACTION = "25c2475d888702af5872dafa032fff7b5853686f48290cd3eee5f1fa";
    private static final String LM_LIQUIDATE_PAY_IN_ADVANCE_AND_COMPOUND_ACTION = "732e7ef5745919b9b5e0ff5cb20aee09c199706fc54e45392bcac54a";
    private static final String LM_LIQUIDATE_CONVERT_AND_COMPOUND_ACTION = "435b42cc200719c3868dfe01689ee07e2eeff5f5809f25408cbe4e7d";

    // ---- Machinery ----------------------------------------------------------------------

    private static Map<String, String> unappliedCode() throws Exception {
        Map<String, String> m = new HashMap<>();
        try (InputStream is = LoansContractDerivationTest.class.getResourceAsStream("/loans-v4.plutus.json")) {
            JsonNode root = new ObjectMapper().readTree(is);
            for (JsonNode v : root.get("validators")) {
                // All handlers of a validator share one compiled code / hash; first wins.
                m.putIfAbsent(v.get("title").asText().replaceAll("\\.[^.]+$", ""), v.get("compiledCode").asText());
            }
        }
        return m;
    }

    private static String hashOf(String compiledCode) {
        try {
            return HexUtil.encodeHexString(
                    PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(compiledCode, PlutusVersion.v3).getScriptHash());
        } catch (Exception e) {
            throw new IllegalStateException("cannot hash compiled code", e);
        }
    }

    private static BytesPlutusData b(String hex) {
        return BytesPlutusData.of(HexUtil.decodeHexString(hex));
    }

    /**
     * A {@code cardano/address.Credential} holding a script hash. {@code Script} is the
     * second constructor of that type, hence alternative 1 — passing raw bytes here instead
     * yields a valid-looking but wrong script hash.
     */
    private static ConstrPlutusData scriptCredential(String scriptHash) {
        return ConstrPlutusData.of(1, b(scriptHash));
    }

    private final Map<String, String> code;

    LoansContractDerivationTest() throws Exception {
        this.code = unappliedCode();
    }

    /** Applies params to the named validator and returns the resulting script hash. */
    private String derive(String validator, PlutusData... params) {
        String unapplied = code.get(validator);
        if (unapplied == null) {
            throw new IllegalArgumentException("no such validator in blueprint: " + validator);
        }
        ListPlutusData list = ListPlutusData.builder().build();
        for (PlutusData p : params) {
            list.add(p);
        }
        return hashOf(AikenScriptUtil.applyParamToScript(list, unapplied));
    }

    /**
     * Wraps a withdraw-script hash in general_spend — this is how every "…SpendScriptHash"
     * in the config datum is built. Spending is delegated to the withdraw validator, so the
     * address holding the UTxOs is general_spend, not the logic validator itself.
     */
    private String generalSpend(String withdrawScriptHash, String configPolicyId) {
        return derive("general_spend.general_spend",
                b(withdrawScriptHash), b(configPolicyId), b(CONFIG_ASSET_NAME));
    }

    // ---- The proof ----------------------------------------------------------------------

    @Test
    void bundledBlueprintMatchesDeployedPreviewConfig() {
        PlutusData cfg = b(CONFIG_POLICY_ID);
        PlutusData name = b(CONFIG_ASSET_NAME);
        Map<String, String> derived = new LinkedHashMap<>();

        // Tier 1 — validators parameterised directly by the main config NFT.
        // A validator's hash serves as both its minting policy id and its withdraw script hash.
        String loan = derive("loan.loan", cfg, name);
        String pool = derive("pool.pool", cfg, name);
        String request = derive("request.request", cfg, name);
        String assetManager = derive("asset_manager.assetManager", cfg, name);
        String lockedBorrowerManager = derive("locked_borrower_manager.lockedBorrowerManager", cfg, name);
        derived.put("loanPolicyId", loan);
        derived.put("poolPolicyId", pool);
        derived.put("requestPolicyId", request);

        // Tier 2 — the addresses UTxOs actually sit at.
        String assetManagerSpend = generalSpend(assetManager, CONFIG_POLICY_ID);
        derived.put("loanSpendScriptHash", generalSpend(loan, CONFIG_POLICY_ID));
        derived.put("poolSpendScriptHash", generalSpend(pool, CONFIG_POLICY_ID));
        derived.put("requestSpendScriptHash", generalSpend(request, CONFIG_POLICY_ID));
        derived.put("assetManagerSpendScriptHash", assetManagerSpend);
        derived.put("lockedBorrowerManagerSpendScriptHash", generalSpend(lockedBorrowerManager, CONFIG_POLICY_ID));

        // Tier 3 — loan actions (the ones needing the asset manager pair).
        PlutusData amSpend = b(assetManagerSpend);
        PlutusData amWithdraw = b(assetManager);
        derived.put("loanClaimActionScriptHash",
                derive("loan/loan_claim_action.loan_claim_action", cfg, name, amSpend, amWithdraw));
        derived.put("loanRepayActionScriptHash",
                derive("loan/loan_repay_action.loan_repay_action", cfg, name, amSpend, amWithdraw));
        derived.put("loanRecastActionScriptHash",
                derive("loan/loan_recast_action.loan_recast_action", cfg, name, amSpend, amWithdraw));
        derived.put("loanChangeCollateralActionScriptHash",
                derive("loan/loan_change_collateral_action.loan_change_collateral_action", cfg, name));

        // Tier 4 — pool actions.
        derived.put("poolCancelActionScriptHash",
                derive("pool/pool_cancel_action.pool_cancel_action", cfg, name));
        derived.put("poolBorrowActionScriptHash",
                derive("pool/pool_borrow_action.pool_borrow_action", cfg, name));
        derived.put("poolSellLenderPositionActionScriptHash",
                derive("pool/pool_sell_lender_position.pool_sell_lender_position_action", cfg, name));
        derived.put("poolCompoundActionScriptHash",
                derive("pool/pool_compound_action.pool_compound_action", cfg, name));

        // Tier 5 — LenderManager. Its spend hash is published nowhere on chain, so it has to
        // be derived; lm_withdraw_bonds_action is parameterised by it, which lets the
        // LMConfigDatum confirm we derived it right.
        //
        // Note the asymmetry: the LenderManager subtree wraps with the *LM* config policy,
        // whereas loan/pool/request wrap with the main config policy. Getting this wrong
        // still produces a plausible-looking hash, so the assertion below is load-bearing.
        String lenderManager = derive("lender_manager.lenderManager", b(LM_CONFIG_POLICY_ID), name);
        String lenderManagerSpend = generalSpend(lenderManager, LM_CONFIG_POLICY_ID);
        derived.put("lenderManagerWithdrawScriptHash (not on chain)", lenderManager);
        derived.put("lenderManagerSpendScriptHash (not on chain)", lenderManagerSpend);
        derived.put("lmWithdrawBondsActionScriptHash", derive(
                "lender_manager/lm_withdraw_bonds_action.actionValidator", b(lenderManagerSpend)));
        derived.put("lmLiquidateActionScriptHash", derive(
                "lender_manager/lm_liquidate_action.actionValidator",
                cfg, name, b(lenderManagerSpend), amSpend, amWithdraw, scriptCredential(LOAN_CLAIM_ACTION)));
        derived.put("lmLiquidateAndPayInAdvanceActionScriptHash", derive(
                "lender_manager/lm_liquidate_and_pay_in_advance_action.actionValidator",
                cfg, name, b(lenderManagerSpend), amSpend, amWithdraw, scriptCredential(LOAN_CLAIM_ACTION)));
        derived.put("lmLiquidateConvertAndCompoundActionScriptHash",
                derive("lender_manager/lm_liquidate_convert_and_compound_action.actionValidator"));

        // Tier 6 — pool manager. Its own params fold in the pool chain, the LenderManager
        // withdraw hash and smartTokensSpendScriptHash, so getting this right cross-checks
        // nearly everything above at once.
        PlutusData poolSpend = b(POOL_SPEND);
        PlutusData poolPolicy = b(POOL_POLICY_ID);
        PlutusData smartTokens = b(SMART_TOKENS_SPEND);
        String pmCancel = derive("pool_manager/pm_cancel_pool_manager.poolManager",
                cfg, name, poolSpend, poolPolicy, smartTokens);
        String pmUpdate = derive("pool_manager/pm_update_pool_manager.poolManager",
                cfg, name, poolSpend, poolPolicy, smartTokens);
        String pmCompound = derive("pool_manager/pm_compound_liquidity.poolManager",
                b(lenderManager), b(pool));
        String poolManager = derive("pool_manager.poolManager",
                cfg, name, poolSpend, b(pool), b(pmCancel), b(pmUpdate), b(pmCompound));
        derived.put("poolManagerPolicyId", poolManager);
        String poolManagerSpend = generalSpend(poolManager, CONFIG_POLICY_ID);
        derived.put("poolManagerSpendScriptHash", poolManagerSpend);

        // Tier 7 — the two LM actions that also need the pool manager, now that it is known.
        // lmLiquidateAndConvert is deliberately left out: it takes five Minswap parameters we
        // do not have, so it cannot be derived until FT supplies them.
        derived.put("lmCompoundActionScriptHash", derive(
                "lender_manager/lm_compound_action.actionValidator",
                cfg, name, b(lenderManagerSpend), b(lenderManager), amSpend, amWithdraw,
                b(poolManagerSpend), b(poolManager)));
        derived.put("lmLiquidatePayInAdvanceAndCompoundActionScriptHash", derive(
                "lender_manager/lm_liquidate_pay_in_advance_and_compound_action.actionValidator",
                cfg, name, b(lenderManagerSpend), amSpend, amWithdraw,
                b(poolManagerSpend), b(poolManager), scriptCredential(LOAN_CLAIM_ACTION)));

        derived.forEach((k, v) -> System.out.printf("%-52s %s%n", k, v));

        // ---- Assertions against the live config datums ----
        assertEquals(LOAN_POLICY_ID, derived.get("loanPolicyId"), "loanPolicyId");
        assertEquals(POOL_POLICY_ID, derived.get("poolPolicyId"), "poolPolicyId");
        assertEquals(REQUEST_POLICY_ID, derived.get("requestPolicyId"), "requestPolicyId");

        assertEquals(LOAN_SPEND, derived.get("loanSpendScriptHash"), "loanSpendScriptHash");
        assertEquals(POOL_SPEND, derived.get("poolSpendScriptHash"), "poolSpendScriptHash");
        assertEquals(REQUEST_SPEND, derived.get("requestSpendScriptHash"), "requestSpendScriptHash");
        assertEquals(ASSET_MANAGER_SPEND, derived.get("assetManagerSpendScriptHash"), "assetManagerSpendScriptHash");
        assertEquals(LOCKED_BORROWER_MANAGER_SPEND, derived.get("lockedBorrowerManagerSpendScriptHash"),
                "lockedBorrowerManagerSpendScriptHash");

        assertEquals(LOAN_CLAIM_ACTION, derived.get("loanClaimActionScriptHash"), "loanClaimActionScriptHash");
        assertEquals(LOAN_REPAY_ACTION, derived.get("loanRepayActionScriptHash"), "loanRepayActionScriptHash");
        assertEquals(LOAN_RECAST_ACTION, derived.get("loanRecastActionScriptHash"), "loanRecastActionScriptHash");
        assertEquals(LOAN_CHANGE_COLLATERAL_ACTION, derived.get("loanChangeCollateralActionScriptHash"),
                "loanChangeCollateralActionScriptHash");

        assertEquals(POOL_CANCEL_ACTION, derived.get("poolCancelActionScriptHash"), "poolCancelActionScriptHash");
        assertEquals(POOL_BORROW_ACTION, derived.get("poolBorrowActionScriptHash"), "poolBorrowActionScriptHash");
        assertEquals(POOL_SELL_LENDER_POSITION_ACTION, derived.get("poolSellLenderPositionActionScriptHash"),
                "poolSellLenderPositionActionScriptHash");
        assertEquals(POOL_COMPOUND_ACTION, derived.get("poolCompoundActionScriptHash"), "poolCompoundActionScriptHash");

        assertEquals(LM_WITHDRAW_BONDS_ACTION, derived.get("lmWithdrawBondsActionScriptHash"),
                "lmWithdrawBondsActionScriptHash — also proves the derived LenderManager spend hash");
        assertEquals(LM_LIQUIDATE_ACTION, derived.get("lmLiquidateActionScriptHash"), "lmLiquidateActionScriptHash");
        assertEquals(LM_LIQUIDATE_AND_PAY_IN_ADVANCE_ACTION, derived.get("lmLiquidateAndPayInAdvanceActionScriptHash"),
                "lmLiquidateAndPayInAdvanceActionScriptHash");
        assertEquals(LM_LIQUIDATE_CONVERT_AND_COMPOUND_ACTION,
                derived.get("lmLiquidateConvertAndCompoundActionScriptHash"),
                "lmLiquidateConvertAndCompoundActionScriptHash — the parameterless stub");

        assertEquals(POOL_MANAGER_POLICY_ID, derived.get("poolManagerPolicyId"), "poolManagerPolicyId");
        assertEquals(POOL_MANAGER_SPEND, derived.get("poolManagerSpendScriptHash"), "poolManagerSpendScriptHash");

        assertEquals(LM_COMPOUND_ACTION, derived.get("lmCompoundActionScriptHash"), "lmCompoundActionScriptHash");
        assertEquals(LM_LIQUIDATE_PAY_IN_ADVANCE_AND_COMPOUND_ACTION,
                derived.get("lmLiquidatePayInAdvanceAndCompoundActionScriptHash"),
                "lmLiquidatePayInAdvanceAndCompoundActionScriptHash");
    }
}
