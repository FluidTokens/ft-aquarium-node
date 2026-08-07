package com.fluidtokens.aquarium.offchain.model.loans;

import java.math.BigInteger;

/**
 * An indexed Lending v4 loan: the on-chain {@link LoanDatum} plus the UTxO facts that are
 * not in the datum — where it lives and how much collateral is actually sitting there.
 * <p>
 * {@code collateralAmount} is read from the UTxO value rather than the datum because the
 * datum has no collateral quantity; {@code lm_liquidate_action.ak} likewise computes it with
 * {@code quantity_of(loanInput.output.value, ..)}.
 *
 * @param loanId asset name of the loan NFT — also the lender bond's asset name, which is the
 *               join key between a loan and its bond ({@code lm_liquidate_action.ak:131-135})
 */
public record Loan(String txHash,
                   int outputIndex,
                   String address,
                   String loanId,
                   BigInteger collateralAmount,
                   BigInteger lovelace,
                   LoanDatum datum) {

    public String utxoRef() {
        return txHash + "#" + outputIndex;
    }

    /**
     * Whether the LenderManager liquidation actions could ever accept this loan. This is a
     * <em>type</em> filter, not a health check — an eligible loan still has to be unhealthy.
     * <p>
     * All four checks come from {@code lm_liquidate_action.ak} and are enforced identically by
     * the other three liquidate actions (docs/lending-v4-findings.md §7):
     * <ul>
     *   <li>D1 {@code expect Liquidation {..}} — dutch-auction and full-claim modes are out</li>
     *   <li>D2 {@code expect equityInPrincipalCurrency == False}</li>
     *   <li>D3 {@code expect Some(collateralAssetName)} — no NFT collections (:108)</li>
     *   <li>D3 {@code expect loanCollateralAmount > 1} — no single NFTs (:116)</li>
     * </ul>
     */
    public boolean botLiquidatable() {
        return datum.liquidationMode().allowsBotLiquidation()
                && datum.collateral().assetName().isPresent()
                && collateralAmount.compareTo(BigInteger.ONE) > 0;
    }
}
