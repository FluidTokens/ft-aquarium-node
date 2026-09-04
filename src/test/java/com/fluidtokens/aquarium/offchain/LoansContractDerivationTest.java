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
 * Ground truth is tx {@code 8dd38e97…091c} on preview — the FOURTH deployment: output 0 holds the
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

    private static final String CONFIG_POLICY_ID = "d46f626fc11750409cf44f3d202f48d1b5df41ad35d62a7364b8e22e";
    private static final String LM_CONFIG_POLICY_ID = "a7d4b762c5a6197ab3b169c2ff1945fdcd4c21cc5f4c180e75441a13";
    /** "parameters" — hardcoded in lib/fluidtokens/constants.ak, not a free choice. */
    private static final String CONFIG_ASSET_NAME = "706172616d6574657273";
    /**
     * Read from the ConfigDatum, not derived: the blueprint ships no smart_tokens validator.
     * Supplying it unlocks the pool-manager branch of the derivation. Unchanged across the third
     * redeploy — smart_tokens is not parameterised by the config NFT policy ids.
     */
    private static final String SMART_TOKENS_SPEND = "fca77bcce1e5e73c97a0bfa8c90f7cd2faff6fd6ed5b6fec1c04eefa";

    // ---- Ground truth: ConfigDatum, output 0 of 8dd38e97…091c (FOURTH deployment) ----------
    //
    // ⛔ Moved from the THIRD deployment (7374a985…e781) on 2026-09-04, with LoanFixtures.
    // These are what the LIVE datum publishes; the vendored blueprint derives exactly them,
    // which is the pairing ShippedRegistryMatchesPinnedConfigTest asserts independently.

    private static final String POOL_POLICY_ID = "a33aee4034165f1772e57af5fb975f26c35f7e9080b7e44b4634f227";
    private static final String REQUEST_POLICY_ID = "39bef32eb5f696f6d0b1cc0446903311f04fe008797c0e349a672acb";
    private static final String LOAN_POLICY_ID = "2f1aa941f437e351e3870f7247d735b2bc2952f1c7977426e8960d17";
    private static final String POOL_SPEND = "bf8c4378bab7de15baddbb5d8805255d89174c08bf36179c21cad685";
    private static final String REQUEST_SPEND = "978934c46206696e0d44e56a43ceeaf0a607b1232fcd5b4ec64b0f73";
    private static final String LOAN_SPEND = "31e0dc1d75076e4f7795b24c4cc4b5515791bb4eff4af7961e404f3e";
    private static final String LOAN_CLAIM_ACTION = "c6e0c4395cf22e08f918ca996d7db49faba793dbd6b647160168ff39";
    private static final String LOAN_REPAY_ACTION = "c0f7e513e81f7eb1abf8429c614cc0ab2acde02189bade0dd65c15be";
    private static final String LOAN_CHANGE_COLLATERAL_ACTION = "899f9bf3ff89d48537a283a38e5c8d868a97c22200f8c66695f38e7b";
    private static final String LOAN_RECAST_ACTION = "1628910a5fbdba415c3b1bf7304672106659ac527442f10701472753";
    private static final String ASSET_MANAGER_SPEND = "de8f81868054fe87019230b9c33e1d18d668689ee201f3f57fbfa69c";
    private static final String POOL_CANCEL_ACTION = "a4f2d030b2348582335038135cb7b59bbe7145d44d2dab9a409dde50";
    private static final String POOL_BORROW_ACTION = "344755c30db0617ff43cb41e5212379b729985352a213371b15c90cd";
    private static final String POOL_SELL_LENDER_POSITION_ACTION = "db9a5bf043f37e744bbb43b96ec89a3e175f7c5523d02dd563ed9c56";
    private static final String POOL_COMPOUND_ACTION = "33128ca352b5472f593104d5884ced5cba5e980b3177353eb2116c62";
    private static final String POOL_MANAGER_SPEND = "b4ad9a6f2710d68067177e0de5a4378ebe4fcdfdc929c7488479c313";
    private static final String POOL_MANAGER_POLICY_ID = "45ce890c9bcf70f6eed629b5db7c0622e44ca1003e001a2cf951518f";
    /**
     * <b>Not ground truth from the datum</b> — the {@code ConfigDatum} does not publish the pool-manager
     * <em>action</em> hashes, they are baked into {@code pool_manager.ak}'s parameters instead. It is
     * pinned <em>transitively</em> and that pin is not weak: {@code poolManagerPolicyId} is derived by
     * applying this hash (with {@code pmUpdate} and {@code pmCompound}) to {@code pool_manager.poolManager},
     * and {@link #POOL_MANAGER_POLICY_ID} above <em>is</em> ground truth off the live datum. A wrong value
     * here cannot produce the right value there.
     */
    private static final String PM_CANCEL_POOL_MANAGER = "72bdf30225529881c6e6de5f6576b0ec5f55a8e515019b3412d7bce1";
    private static final String LOCKED_BORROWER_MANAGER_SPEND = "d815766d61c1241742ff78164cdf8edaef1746a99a242a7fb7938aa6";

    /**
     * Bond policy ids, read from the {@code ConfigDatum} (output 0 of {@code 8dd38e97…091c}): the
     * borrower bond sits at datum index 4, the lender bond at index 5 (see
     * {@code LoansConfigVerifier}'s {@code CFG_BORROWER_BOND_POLICY_ID} / {@code CFG_LENDER_BOND_POLICY_ID}).
     * {@code bond.ak} is parameterised only by the borrower/lender discriminator Int, so neither hash
     * moves across a config-NFT redeploy. Transcribed from the live datum, not from the derivation.
     */
    private static final String BORROWER_BOND_POLICY_ID = "eadc69a5d2d1357acc9b9d49ec5390fcdf6e080c7a40139917223dcb";
    private static final String LENDER_BOND_POLICY_ID = "bcd713bb7858d4b08738bed90ee7068d8f9b38d02e0cae0b45ac7a9b";

    // ---- Ground truth: LMConfigDatum, output 1 of 8dd38e97…091c --------------------------

    private static final String LM_WITHDRAW_BONDS_ACTION = "42c4a0d6f33f21ccc694c6620d6485331df6528da8b8d16acf1589fe";
    private static final String LM_LIQUIDATE_ACTION = "e0a13838d176cea9de466afe2075f38f682603013604021a3959700f";
    private static final String LM_COMPOUND_ACTION = "dd4709091734af2dc36321e774cf496222a1f92377ad6c5bef100457";
    private static final String LM_LIQUIDATE_AND_PAY_IN_ADVANCE_ACTION = "00b8a30bd2f18962e527d7c03712e86077a688bfce7e2934ef70034d";
    private static final String LM_LIQUIDATE_PAY_IN_ADVANCE_AND_COMPOUND_ACTION = "70b149e7c84a4cf47fb273d87ed2fe97562f0148bfce0b4681afa480";
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

    private static final String LENDER_MANAGER_WITHDRAW = "777aa0f117733d2c504c8ae56618b4196aa322fb75f9e2d67a6b85e6";
    private static final String LENDER_MANAGER_SPEND = "dd2d7f3fdd0ca7ea68e94912c3d332f18299b6ec8854577492d006eb";

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
        assertEquals(PM_CANCEL_POOL_MANAGER, r.getPmCancelPoolManagerScriptHash(),
                "pmCancelPoolManagerScriptHash — transitively pinned through poolManagerPolicyId");
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
        assertNull(r.getPmCancelPoolManagerScriptHash(),
                "pmCancelPoolManagerScriptHash without smart tokens");
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
        assertEquals(LM_LIQUIDATE_AND_PAY_IN_ADVANCE_ACTION, r.getLmLiquidateAndPayInAdvanceActionScriptHash(),
                "lmLiquidateAndPayInAdvanceActionScriptHash");
        assertScript(r.getLmLiquidateAndPayInAdvanceActionScript(),
                r.getLmLiquidateAndPayInAdvanceActionScriptHash(),
                "lender_manager/lm_liquidate_and_pay_in_advance_action");
        assertScript(r.getAssetManagerScript(), r.getAssetManagerWithdrawScriptHash(),
                "asset_manager.assetManager");
    }

    /**
     * The six pool/bond accessors added for T-016 S1 hand out the applied {@code PlutusScript} the
     * pool-origination fixtures attach to a transaction. Each is the same applied compiled code its
     * pinned hash was taken from, so — exactly as {@link #everyExposedScriptHashesToItsDerivedHash} —
     * the script must hash back to its own published hash, and all must be PlutusV3. The bond hashes
     * come off the {@code ConfigDatum}, the four pool hashes off the same datum's pool fields; none is
     * a new derivation.
     */
    @Test
    void everyPoolAndBondScriptHashesToItsDerivedHash() {
        LoansContractRegistry r = registry(SMART_TOKENS_SPEND);

        assertScript(r.getPoolScript(), POOL_POLICY_ID, "pool.pool");
        assertScript(r.getPoolSpendScript(), POOL_SPEND, "pool general_spend");
        assertScript(r.getPoolBorrowActionScript(), POOL_BORROW_ACTION, "pool/pool_borrow_action");
        assertScript(r.getPoolCancelActionScript(), POOL_CANCEL_ACTION, "pool/pool_cancel_action");
        assertScript(r.getLenderBondScript(), LENDER_BOND_POLICY_ID, "bond.bond(1)");
        assertScript(r.getBorrowerBondScript(), BORROWER_BOND_POLICY_ID, "bond.bond(0)");
    }

    /**
     * The three pool-manager accessors added for T-024 hand out the applied {@code PlutusScript} the
     * pool-create transaction mints under and the pool-cancel transaction spends, withdraws and burns
     * with. Same rule as the two tests above: each must hash back to its own derived hash and be
     * PlutusV3, so an accessor can never serve a plausible-looking wrong validator.
     * <p>
     * Two of the three hashes ({@code b2324fbd…}, {@code 720deaf9…}) are ground truth off the live
     * {@code ConfigDatum}; the third is pinned transitively — see {@link #PM_CANCEL_POOL_MANAGER}.
     */
    @Test
    void everyPoolManagerScriptHashesToItsDerivedHash() {
        LoansContractRegistry r = registry(SMART_TOKENS_SPEND);

        assertScript(r.getPoolManagerScript(), POOL_MANAGER_POLICY_ID, "pool_manager.poolManager");
        assertScript(r.getPoolManagerSpendScript(), POOL_MANAGER_SPEND, "pool_manager general_spend");
        assertScript(r.getPmCancelPoolManagerScript(), PM_CANCEL_POOL_MANAGER,
                "pool_manager/pm_cancel_pool_manager");
        assertScript(r.getPmCompoundLiquidityScript(), r.getPmCompoundLiquidityScriptHash(),
                "pm_compound_liquidity — transitively pinned through poolManagerPolicyId");
        assertScript(r.getPoolCompoundActionScript(), POOL_COMPOUND_ACTION, "pool_compound_action");
        assertScript(r.getLmLiquidatePayInAdvanceAndCompoundActionScript(),
                LM_LIQUIDATE_PAY_IN_ADVANCE_AND_COMPOUND_ACTION,
                "lm_liquidate_pay_in_advance_and_compound_action");
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
