package com.fluidtokens.aquarium.offchain.model.loans;

/**
 * Why {@link LiquidationCandidateScanner} would not build a liquidation transaction for a
 * lender bond, as a single machine-readable reason.
 * <p>
 * Every value below traces to a specific validator-verified filter documented in
 * {@code docs/lending-v4-findings.md} §7, plus one join precondition and one epic-scope
 * restriction. See {@link LiquidationCandidateScanner} for the exact order these are applied in.
 */
public enum LiquidationExclusion {

    /**
     * No loan under {@code loanPolicyId} shares the bond's asset name. The loan↔bond join key is
     * the asset name ({@code lm_liquidate_action.ak:131-135}, §7.5).
     */
    LOAN_NOT_FOUND,

    /**
     * D1 — {@code datum.liquidationMode()} is not {@link LiquidationMode.Liquidation}. Every
     * working LenderManager liquidation action {@code expect Liquidation {..}}, so
     * {@code NoLiquidationFullCollateralClaim} and {@code NoLiquidationDutchAuctionClaim} are
     * unreachable from the bot's path.
     */
    MODE_NOT_LIQUIDATION,

    /**
     * D2 — {@code equityInPrincipalCurrency == True}. Every working LenderManager liquidation
     * action also {@code expect}s this to be false; the bot's addressable set is exactly
     * {@code Liquidation { equityInPrincipalCurrency: False, .. }}.
     * <p>
     * Backed by {@code LiquidateTransactionBuilder.Refusal#EQUITY_IN_PRINCIPAL_CURRENCY}.
     */
    EQUITY_IN_PRINCIPAL_CURRENCY,

    /**
     * D3 — {@code collateral.maybeAssetName} is empty: NFT-collection collateral cannot be
     * liquidated ({@code lm_liquidate_action.ak:108}, {@code expect Some(collateralAssetName)}).
     */
    COLLATERAL_IS_COLLECTION,

    /**
     * D3 — {@code loanCollateralAmount <= 1}: single-NFT collateral cannot be liquidated
     * ({@code lm_liquidate_action.ak:116}, {@code expect loanCollateralAmount > 1}).
     */
    COLLATERAL_AMOUNT_TOO_SMALL,

    /**
     * Epic scope — {@code bond.datum().shouldLiquidationConvertToPrincipal() == true}. The plain
     * {@code Liquidate} action this scanner targets enforces
     * {@code shouldLiquidationConvertToPrincipal == False} (§7.5, {@code :143}); the convert
     * variant is a different action, out of scope here.
     * <p>
     * Backed by {@code LiquidateTransactionBuilder.Refusal#CONVERSION_TO_PRINCIPAL_REQUIRED}.
     */
    CONVERSION_TO_PRINCIPAL_REQUIRED,

    /**
     * The principal leg's oracle feed is missing, not usable for liquidation, or outside its
     * validity window at the assessment time — or the oracle client is disabled/absent.
     */
    PRINCIPAL_ORACLE_UNUSABLE,

    /**
     * The collateral leg's oracle feed is missing, not usable for liquidation, or outside its
     * validity window at the assessment time — or the oracle client is disabled/absent.
     */
    COLLATERAL_ORACLE_UNUSABLE,

    /**
     * {@link LoanFinance} threw {@link ArithmeticException} while computing debt, lateness,
     * liquidatability or equity — the on-chain path would fail the same evaluation.
     */
    HEALTH_NOT_COMPUTABLE,

    /**
     * D9 — not late and {@code currentLtv <= liquidationLtv}. {@code can_liquidate} is
     * strict-greater ({@code finance.ak:174-195}); equality does not liquidate.
     */
    NOT_LIQUIDATABLE
}
