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
 * Ground truth is tx {@code 7374a985…e781} on preview — the THIRD deployment: output 0 holds the
 * main config NFT ({@code ConfigDatum}), output 1 holds the LenderManager config NFT
 * ({@code LMConfigDatum}). Every constant below was read off those two datums, transcribed from
 * the inline datum bytes the chain carries rather than produced by the derivation it checks. See
 * docs/lending-v4-findings.md.
 * <p>
 * If this test is green, the bundled blueprint matches the deployed contracts and no v4
 * address is ever hardcoded. If it goes red, either our clone is the wrong commit or the
 * contracts were redeployed — nothing built on top of it can be trusted until it is fixed.
 */
class LoansContractDerivationTest {

    // ---- Derivation inputs (preview) ----------------------------------------------------

    private static final String CONFIG_POLICY_ID = "c45d5306a7c0f7ba361af5fcdfa9bdbe0ba67f105caa2d2d4032aaa9";
    private static final String LM_CONFIG_POLICY_ID = "de1b8b40536f96c1084d73f838ebac6b228d891902d6234afc731484";
    /** "parameters" — hardcoded in lib/fluidtokens/constants.ak, not a free choice. */
    private static final String CONFIG_ASSET_NAME = "706172616d6574657273";
    /**
     * Read from the ConfigDatum, not derived: the blueprint ships no smart_tokens validator.
     * Supplying it unlocks the pool-manager branch of the derivation. Unchanged across the third
     * redeploy — smart_tokens is not parameterised by the config NFT policy ids.
     */
    private static final String SMART_TOKENS_SPEND = "fca77bcce1e5e73c97a0bfa8c90f7cd2faff6fd6ed5b6fec1c04eefa";

    // ---- Ground truth: ConfigDatum, output 0 of 7374a985…e781 ----------------------------

    private static final String POOL_POLICY_ID = "65a0bc5e6e5152fbe2bf3e1053f4020f6c7ee0a563beb0fe070a7b93";
    private static final String REQUEST_POLICY_ID = "88fa30dbb11dc0943d6fe2b6bae5b3ef3b80c60cd4e6421410cfba8c";
    private static final String LOAN_POLICY_ID = "4f84c6e3f4a7812d23a968e38dd22016abea07cdee48e80a17839476";
    private static final String POOL_SPEND = "c0be04e50016c124a9954b066cca5e76b19ab97b086666ad9f4c7c45";
    private static final String REQUEST_SPEND = "f50595e04ed417869360e4b9ff8da4e97777cd9e0da9af641a408783";
    private static final String LOAN_SPEND = "86356f7e64dc284e67c82004e94d424f580e2250c0a34e79a77db1bc";
    private static final String LOAN_CLAIM_ACTION = "9ae63b26c98d90024a45f9cdb57e4154f72144d44325f0a261b8bc1d";
    private static final String LOAN_REPAY_ACTION = "4309f0537f5a5ae3d13005b2c522fb829b58e90a233bef9e4da601f0";
    private static final String LOAN_CHANGE_COLLATERAL_ACTION = "029b10229f3b5c8b2abd4684b00b0d0e4d20e4d0ed452a368676d137";
    private static final String LOAN_RECAST_ACTION = "bd60c5f18113e0a132ffa48c859c0f3ee16d6533965ea37fd4296fbf";
    private static final String ASSET_MANAGER_SPEND = "f5b63a6b092b2dc9790d823f782801e2f1416783097e8cf989ac495b";
    private static final String POOL_CANCEL_ACTION = "4e4c5ed0d8c96fd158fa70ed619b93ec6bb9d0dfb425f3961b35d95b";
    private static final String POOL_BORROW_ACTION = "2fd32e80ffdc2435613f1977b4633b66d21f5ff4cf31d4fc7c6c64e1";
    private static final String POOL_SELL_LENDER_POSITION_ACTION = "56814009563fdcad029bdb8009d6252593ca6af233fac13a9b2aeeed";
    private static final String POOL_COMPOUND_ACTION = "a5e9ce2d7fa196b9bbbb2aac183a17540e7a5e52bece9ab7b23d7e38";
    private static final String POOL_MANAGER_SPEND = "720deaf94fecfd4ae2ff9510e562cdbfc4f86c17bc41950719b5de32";
    private static final String POOL_MANAGER_POLICY_ID = "b2324fbdcace499f6f1a9599daaebd707eb0ca70edbd6676fa20520b";
    private static final String LOCKED_BORROWER_MANAGER_SPEND = "b0e010c5d8cf28b20d7dfb14e109d19632c2590b0057e40f6a7433fa";

    // ---- Ground truth: LMConfigDatum, output 1 of 7374a985…e781 --------------------------

    private static final String LM_WITHDRAW_BONDS_ACTION = "353c844023c794304d28cfe882848df514ef20a40edf12d96a0fcff6";
    private static final String LM_LIQUIDATE_ACTION = "a39acb16a53422bfa690c6e74435f6edcb8d61114e1c468f332e78ce";
    private static final String LM_COMPOUND_ACTION = "67ff9e2df0b5d1f0ffc004864e4cb6529cbaefa6b79aaa04ae92a73e";
    private static final String LM_LIQUIDATE_AND_PAY_IN_ADVANCE_ACTION = "67b6c63bad731f763d9dc033d195a18b8799fd12ee174caf241ee84f";
    private static final String LM_LIQUIDATE_PAY_IN_ADVANCE_AND_COMPOUND_ACTION = "2219ee395196eeef5372e94641afa0f7d82d035378b94bbce9f704ce";
    /** Unchanged across the third redeploy: the parameterless stub takes no config policy id. */
    private static final String LM_LIQUIDATE_CONVERT_AND_COMPOUND_ACTION = "435b42cc200719c3868dfe01689ee07e2eeff5f5809f25408cbe4e7d";

    // ---- Not published anywhere on chain -------------------------------------------------
    //
    // Neither config datum carries the LenderManager hashes, so they can only be derived. These are
    // therefore the only two constants in this file NOT transcribed from a live datum — every other
    // one is. lm_withdraw_bonds_action is parameterised by the spend hash, so the
    // LM_WITHDRAW_BONDS_ACTION assertion below (whose expected value IS on chain) is an indirect
    // on-chain proof of both: it could not match the live LMConfigDatum if the derived spend hash
    // that went into it were wrong. Pinned here so an accidental change to the derivation is caught
    // explicitly rather than only through that one indirect route.

    private static final String LENDER_MANAGER_WITHDRAW = "695388d1f34e744cf7b015b85d294eeeeefbe6b425c890888d155516";
    private static final String LENDER_MANAGER_SPEND = "e302d1bee8142bee85f7e76f68399a170b2a1454ac19ffd9927332d3";

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
