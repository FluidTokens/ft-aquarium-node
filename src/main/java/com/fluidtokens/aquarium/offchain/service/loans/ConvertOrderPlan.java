package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.AuthorizationMethod;
import com.fluidtokens.aquarium.offchain.model.loans.MinswapPoolDatum;

import java.math.BigInteger;

/**
 * Everything {@code lm_liquidate_and_convert_action} dictates about the Minswap order, computed once
 * and refusing rather than guessing — <b>before</b> any transaction is assembled.
 *
 * <h2>Why this is its own object</h2>
 * Every value here is fixed by the validator, not chosen by us: the swap direction comes from the
 * pool's own datum, {@code minimum_receive} is {@code remainingDebt}, the order's ada is a literal,
 * and the two datum hashes are constructions the validator reproduces byte for byte. Computing them
 * in the middle of a {@code QuickTxBuilder} chain would mix decisions that can be checked against the
 * contract with decisions that can only be checked against a node.
 *
 * <h2>⛔ The pool is resolved by its NFT at RUN TIME, never by a pinned coordinate</h2>
 * A Minswap pool UTxO is spent and re-created on <em>every swap</em>, so its output reference is stale
 * within minutes (findings §32.3). The caller must locate it by {@code minswapPoolPolicyId} +
 * {@link ConvertTxEncoder#POOL_NFT_ASSET_NAME}, and {@link Planner} takes the datum it found rather
 * than a reference it remembered.
 */
public record ConvertOrderPlan(boolean aToBDirection,
                               String lpAssetName,
                               BigInteger liquidationFee,
                               BigInteger swappableCollateralAmount,
                               BigInteger orderLovelace,
                               BigInteger minimumReceive,
                               PlutusData successDatum,
                               PlutusData refundDatum,
                               String successDatumHash,
                               String refundDatumHash,
                               PlutusData orderDatum) {

    /** Why no order can be built for this candidate. Each is a fact about the chain, not a policy. */
    public enum Refusal {
        /** The lender bond's {@code shouldLiquidationConvertToPrincipal} is false. */
        BOND_FORBIDS_CONVERSION,
        /**
         * {@code expect equityInPrincipalCurrency == False} — the convert path requires equity NOT in
         * the principal currency, the mirror of findings §13.
         */
        EQUITY_IN_PRINCIPAL_CURRENCY,
        /**
         * The pool's {@code asset_a}/{@code asset_b} are not this loan's (collateral, principal) pair
         * in either order, so the validator's pair check fails. <b>A pool for the wrong pair is not a
         * worse price — it is an impossible transaction.</b>
         */
        POOL_IS_FOR_A_DIFFERENT_PAIR,
        /**
         * {@code collateral − equity − liquidationFee} is not positive, so there is nothing to swap.
         * Minswap would reject a zero-amount order and the validator's value check could not hold.
         */
        NOTHING_LEFT_TO_SWAP
    }

    /** Thrown rather than returned: a caller that ignored a refusal would build an invalid order. */
    public static final class RefusedException extends RuntimeException {
        private final transient Refusal refusal;

        RefusedException(Refusal refusal, String detail) {
            super(refusal + ": " + detail);
            this.refusal = refusal;
        }

        public Refusal refusal() {
            return refusal;
        }
    }

    /**
     * Builds a plan from the live pool datum and the loan's own numbers.
     *
     * @param collateral        the loan's collateral asset
     * @param principal         the loan's principal asset
     * @param collateralAmount  {@code loanCollateralAmount} — what the loan input actually holds
     * @param equity            the borrower's refund, from the loan-claim redeemer
     * @param remainingDebt     becomes {@code minimum_receive}; the validator fixes it, we do not
     * @param feePerMille       {@code liquidationFeePerMille} from the lender bond
     * @param bondAllowsConvert {@code shouldLiquidationConvertToPrincipal}
     * @param equityInPrincipalCurrency the loan-claim redeemer's flag; must be false here
     * @param pool              the datum of the pool UTxO the caller located BY ITS NFT
     * @param minswapPoolPolicyId the policy the lp asset is minted under
     * @param lenderBond        the bond NFT that owns the escrow the proceeds land in
     * @param lenderAuth        the LENDER's authorisation — the order's canceller is theirs, not ours
     * @param receiver          {@code get_smart_destination_address(...)} for the asset manager
     * @param loanTxHash        the loan input's transaction id, for the REFUND datum only
     * @param loanOutputIndex   the loan input's output index, for the REFUND datum only
     */
    public static ConvertOrderPlan plan(AssetType collateral,
                                        AssetType principal,
                                        BigInteger collateralAmount,
                                        BigInteger equity,
                                        BigInteger remainingDebt,
                                        long feePerMille,
                                        boolean bondAllowsConvert,
                                        boolean equityInPrincipalCurrency,
                                        MinswapPoolDatum pool,
                                        String minswapPoolPolicyId,
                                        AssetType lenderBond,
                                        AuthorizationMethod lenderAuth,
                                        PlutusData receiver,
                                        String loanTxHash,
                                        int loanOutputIndex) {
        if (!bondAllowsConvert) {
            throw new RefusedException(Refusal.BOND_FORBIDS_CONVERSION,
                    "the lender's bond does not permit conversion to principal");
        }
        if (equityInPrincipalCurrency) {
            throw new RefusedException(Refusal.EQUITY_IN_PRINCIPAL_CURRENCY,
                    "the convert action requires equityInPrincipalCurrency == False");
        }

        // ⛔ Direction is the POOL's answer, never ours. asset_a == collateral means we are selling A
        // for B; otherwise the validator demands asset_b == collateral AND asset_a == principal.
        boolean aToB = pool.assetA().equals(collateral);
        boolean pairMatches = aToB
                ? pool.assetB().equals(principal)
                : pool.assetB().equals(collateral) && pool.assetA().equals(principal);
        if (!pairMatches) {
            throw new RefusedException(Refusal.POOL_IS_FOR_A_DIFFERENT_PAIR,
                    ("pool is %s/%s but this loan is collateral %s against principal %s")
                            .formatted(pool.assetA().toUnit(), pool.assetB().toUnit(),
                                    collateral.toUnit(), principal.toUnit()));
        }

        BigInteger liquidationFee = ConvertEconomics.liquidationFee(collateralAmount, feePerMille);
        BigInteger swappable = collateralAmount.subtract(equity).subtract(liquidationFee);
        if (swappable.signum() <= 0) {
            throw new RefusedException(Refusal.NOTHING_LEFT_TO_SWAP,
                    ("collateral %s − equity %s − fee %s = %s")
                            .formatted(collateralAmount, equity, liquidationFee, swappable));
        }

        // The lp asset name is computed from the POOL's declared order, not from ours: the pair is
        // the same set either way, and the hash is not.
        String lpAssetName = ConvertTxEncoder.computeLpAssetName(pool.assetA(), pool.assetB());

        // ⚠ The order's ada is a validator literal, and it differs by collateral kind: an ADA
        // collateral's order holds exactly the swappable amount and NOTHING extra, while a token
        // collateral's must carry 2_800_000 lovelace alongside — which the bot funds (findings §30).
        BigInteger orderLovelace = collateral.isAda()
                ? swappable
                : ConvertEconomics.ORDER_ADA_FOR_TOKEN_COLLATERAL;

        PlutusData successDatum = ConvertTxEncoder.successDatum(collateral, lenderBond);
        PlutusData refundDatum = ConvertTxEncoder.refundDatum(loanTxHash, loanOutputIndex, lenderBond);
        String successHash = ConvertTxEncoder.datumHash(successDatum);
        String refundHash = ConvertTxEncoder.datumHash(refundDatum);

        PlutusData order = ConvertTxEncoder.orderDatum(lenderAuth, receiver, successHash, refundHash,
                new AssetType(minswapPoolPolicyId, lpAssetName),
                ConvertTxEncoder.swapExactIn(aToB, swappable, remainingDebt));

        return new ConvertOrderPlan(aToB, lpAssetName, liquidationFee, swappable, orderLovelace,
                remainingDebt, successDatum, refundDatum, successHash, refundHash, order);
    }
}
