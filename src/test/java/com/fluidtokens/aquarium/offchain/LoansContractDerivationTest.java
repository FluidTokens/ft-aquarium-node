package com.fluidtokens.aquarium.offchain;

import com.bloxbean.cardano.client.plutus.spec.Language;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Proves that {@link LoansContractRegistry} — the code the node actually runs — derives
 * the ft-cardano-loans-v4 script hashes that are really deployed on preview, starting from
 * nothing but the two config NFT policy ids.
 * <p>
 * Ground truth is tx {@code 6de7b7ec…094} on preview: output 0 holds the main config NFT
 * ({@code ConfigDatum}), output 1 holds the LenderManager config NFT ({@code LMConfigDatum}).
 * Every constant below was read off those two datums. See docs/lending-v4-findings.md.
 * <p>
 * If this test is green, the bundled blueprint matches the deployed contracts and no v4
 * address is ever hardcoded. If it goes red, either our clone is the wrong commit or the
 * contracts were redeployed — nothing built on top of it can be trusted until it is fixed.
 */
class LoansContractDerivationTest {

    // ---- Derivation inputs (preview) ----------------------------------------------------

    private static final String CONFIG_POLICY_ID = "f1a475ea8cccc1e0b7a59b10e79ad171452dc057ffb1cda0df92835c";
    private static final String LM_CONFIG_POLICY_ID = "d0998754ddc3e9cfe80356d7e12db163d03cecc5b6b438dad4f4a3e3";
    /** "parameters" — hardcoded in lib/fluidtokens/constants.ak, not a free choice. */
    private static final String CONFIG_ASSET_NAME = "706172616d6574657273";
    /**
     * Read from the ConfigDatum, not derived: the blueprint ships no smart_tokens validator.
     * Supplying it unlocks the pool-manager branch of the derivation.
     */
    private static final String SMART_TOKENS_SPEND = "fca77bcce1e5e73c97a0bfa8c90f7cd2faff6fd6ed5b6fec1c04eefa";

    // ---- Ground truth: ConfigDatum, output 0 of 6de7b7ec…094 -----------------------------

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

    // ---- Not published anywhere on chain -------------------------------------------------
    //
    // Neither config datum carries the LenderManager hashes, so they can only be derived.
    // lm_withdraw_bonds_action is parameterised by the spend hash, which is what makes the
    // LM_WITHDRAW_BONDS_ACTION assertion below an indirect on-chain proof of both values.
    // Pinned here so an accidental change to the derivation is caught explicitly.

    private static final String LENDER_MANAGER_WITHDRAW = "d628e1eb4f4c7ff6af341ae8d6af81c7477b1f12eb49978529e45cbb";
    private static final String LENDER_MANAGER_SPEND = "b2b99ad8c1e5c9f2c341d86a9b7268adf394dff27d20e2824e88ec64";

    private static LoansContractRegistry registry(String smartTokensSpendScriptHash) {
        return new LoansContractRegistry(CONFIG_POLICY_ID, LM_CONFIG_POLICY_ID,
                CONFIG_ASSET_NAME, smartTokensSpendScriptHash);
    }

    // ---- The proof ----------------------------------------------------------------------

    @Test
    void registryDerivesTheDeployedPreviewHashes() {
        LoansContractRegistry r = registry(SMART_TOKENS_SPEND);
        r.derivedHashes().forEach((k, v) -> System.out.printf("%-52s %s%n", k, v));

        assertEquals(LOAN_POLICY_ID, r.getLoanPolicyId(), "loanPolicyId");
        assertEquals(POOL_POLICY_ID, r.getPoolPolicyId(), "poolPolicyId");
        assertEquals(REQUEST_POLICY_ID, r.getRequestPolicyId(), "requestPolicyId");

        assertEquals(LOAN_SPEND, r.getLoanSpendScriptHash(), "loanSpendScriptHash");
        assertEquals(POOL_SPEND, r.getPoolSpendScriptHash(), "poolSpendScriptHash");
        assertEquals(REQUEST_SPEND, r.getRequestSpendScriptHash(), "requestSpendScriptHash");
        assertEquals(ASSET_MANAGER_SPEND, r.getAssetManagerSpendScriptHash(), "assetManagerSpendScriptHash");
        assertEquals(LOCKED_BORROWER_MANAGER_SPEND, r.getLockedBorrowerManagerSpendScriptHash(),
                "lockedBorrowerManagerSpendScriptHash");

        assertEquals(LOAN_CLAIM_ACTION, r.getLoanClaimActionScriptHash(), "loanClaimActionScriptHash");
        assertEquals(LOAN_REPAY_ACTION, r.getLoanRepayActionScriptHash(), "loanRepayActionScriptHash");
        assertEquals(LOAN_RECAST_ACTION, r.getLoanRecastActionScriptHash(), "loanRecastActionScriptHash");
        assertEquals(LOAN_CHANGE_COLLATERAL_ACTION, r.getLoanChangeCollateralActionScriptHash(),
                "loanChangeCollateralActionScriptHash");

        assertEquals(POOL_CANCEL_ACTION, r.getPoolCancelActionScriptHash(), "poolCancelActionScriptHash");
        assertEquals(POOL_BORROW_ACTION, r.getPoolBorrowActionScriptHash(), "poolBorrowActionScriptHash");
        assertEquals(POOL_SELL_LENDER_POSITION_ACTION, r.getPoolSellLenderPositionActionScriptHash(),
                "poolSellLenderPositionActionScriptHash");
        assertEquals(POOL_COMPOUND_ACTION, r.getPoolCompoundActionScriptHash(), "poolCompoundActionScriptHash");

        assertEquals(LENDER_MANAGER_WITHDRAW, r.getLenderManagerWithdrawScriptHash(),
                "lenderManagerWithdrawScriptHash");
        assertEquals(LENDER_MANAGER_SPEND, r.getLenderManagerSpendScriptHash(), "lenderManagerSpendScriptHash");
        assertEquals(LM_WITHDRAW_BONDS_ACTION, r.getLmWithdrawBondsActionScriptHash(),
                "lmWithdrawBondsActionScriptHash — also proves the derived LenderManager spend hash");
        assertEquals(LM_LIQUIDATE_ACTION, r.getLmLiquidateActionScriptHash(), "lmLiquidateActionScriptHash");
        assertEquals(LM_LIQUIDATE_AND_PAY_IN_ADVANCE_ACTION, r.getLmLiquidateAndPayInAdvanceActionScriptHash(),
                "lmLiquidateAndPayInAdvanceActionScriptHash");
        assertEquals(LM_LIQUIDATE_CONVERT_AND_COMPOUND_ACTION, r.getLmLiquidateConvertAndCompoundActionScriptHash(),
                "lmLiquidateConvertAndCompoundActionScriptHash — the parameterless stub");

        assertEquals(POOL_MANAGER_POLICY_ID, r.getPoolManagerPolicyId(), "poolManagerPolicyId");
        assertEquals(POOL_MANAGER_SPEND, r.getPoolManagerSpendScriptHash(), "poolManagerSpendScriptHash");
        assertEquals(LM_COMPOUND_ACTION, r.getLmCompoundActionScriptHash(), "lmCompoundActionScriptHash");
        assertEquals(LM_LIQUIDATE_PAY_IN_ADVANCE_AND_COMPOUND_ACTION,
                r.getLmLiquidatePayInAdvanceAndCompoundActionScriptHash(),
                "lmLiquidatePayInAdvanceAndCompoundActionScriptHash");
    }

    /**
     * The liquidation path must come up without smartTokensSpendScriptHash, since that value
     * is only available from the on-chain ConfigDatum. Only the pool-manager branch degrades.
     */
    @Test
    void derivesLiquidationHashesWithoutSmartTokens() {
        LoansContractRegistry r = registry(null);

        assertEquals(LOAN_SPEND, r.getLoanSpendScriptHash(), "loanSpendScriptHash");
        assertEquals(LM_LIQUIDATE_ACTION, r.getLmLiquidateActionScriptHash(), "lmLiquidateActionScriptHash");
        assertEquals(LM_LIQUIDATE_AND_PAY_IN_ADVANCE_ACTION, r.getLmLiquidateAndPayInAdvanceActionScriptHash(),
                "lmLiquidateAndPayInAdvanceActionScriptHash");
        assertEquals(LENDER_MANAGER_SPEND, r.getLenderManagerSpendScriptHash(), "lenderManagerSpendScriptHash");

        assertNull(r.getPoolManagerPolicyId(), "poolManagerPolicyId without smart tokens");
        assertNull(r.getLmCompoundActionScriptHash(), "lmCompoundActionScriptHash without smart tokens");
    }

    /**
     * The registry hands out applied {@code PlutusScript} objects for the seven validators a T-008
     * {@code Liquidate} transaction invokes, so the bot can put the script bytes in the witness set
     * when no reference script is published.
     * <p>
     * A script whose hash is not the one derived above would be attached to a transaction that
     * spends real collateral and fail — or worse, be the <em>wrong</em> validator. The two are
     * built from the same applied compiled code, and this is what proves it: every exposed script
     * hashes back to its own published hash, and all of them are PlutusV3.
     */
    @Test
    void everyExposedScriptHashesToItsDerivedHash() {
        LoansContractRegistry r = registry(SMART_TOKENS_SPEND);

        assertScript(r.getLoanScript(), r.getLoanPolicyId(), "loan.loan");
        assertScript(r.getLoanSpendScript(), r.getLoanSpendScriptHash(), "loan general_spend");
        assertScript(r.getLenderManagerScript(), r.getLenderManagerWithdrawScriptHash(),
                "lender_manager.lenderManager");
        assertScript(r.getLenderManagerSpendScript(), r.getLenderManagerSpendScriptHash(),
                "lender_manager general_spend");
        assertScript(r.getLoanClaimActionScript(), r.getLoanClaimActionScriptHash(),
                "loan/loan_claim_action");
        assertScript(r.getLmLiquidateActionScript(), r.getLmLiquidateActionScriptHash(),
                "lender_manager/lm_liquidate_action");
        assertScript(r.getAssetManagerScript(), r.getAssetManagerWithdrawScriptHash(),
                "asset_manager.assetManager");
    }

    /** The scripts must also come up without smartTokensSpendScriptHash, like the hashes do. */
    @Test
    void exposesScriptsWithoutSmartTokens() {
        LoansContractRegistry r = registry(null);

        assertScript(r.getLoanScript(), r.getLoanPolicyId(), "loan.loan");
        assertScript(r.getLmLiquidateActionScript(), r.getLmLiquidateActionScriptHash(),
                "lender_manager/lm_liquidate_action");
    }

    @SneakyThrows
    private static void assertScript(PlutusScript script, String expectedHash, String what) {
        assertEquals(Language.PLUTUS_V3, script.getLanguage(), what + " plutus version");
        assertEquals(expectedHash, HexUtil.encodeHexString(script.getScriptHash()), what + " script hash");
    }
}
