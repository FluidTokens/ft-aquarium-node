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
        NOTHING_LEFT_TO_SWAP,
        /**
         * The pool sets {@code allow_dynamic_fee}, so its two base numerators are not the whole fee and
         * a quote built from them would UNDERSTATE the cost — the direction that lets a futile order
         * through. Refused rather than quoted at a price the pool does not charge.
         */
        POOL_HAS_DYNAMIC_FEE,
        /**
         * The pool cannot deliver the debt. {@code minimum_receive} is {@code remainingDebt} and the
         * validator fixes it, so an order this pool cannot fill does not fill BADLY — it is batched,
         * refunded, and the operator pays the batcher fee and the transaction fee for nothing
         * (findings §25.2). Measured on the live FLDT/USDM pool 2026-09-09: 22,003,200,000 FLDT in
         * would have returned ~824 M USDM against a 980,001,429 requirement, a 155 M shortfall.
         *
         * <p>⚠ This is arithmetic on the pool's own declared reserves and fee, not a policy or a
         * slippage model — there is no knob, and there is nothing for an operator to tune.
         */
        POOL_TOO_THIN
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

        // ⛔ QUOTE THE SWAP BEFORE ANYTHING IS BUILT — a thin pool REFUNDS, it does not fill badly.
        //
        // `minimum_receive` is `remainingDebt` and the validator fixes it, so there is no price at
        // which a pool too shallow to deliver the debt produces a partial fill: Minswap batches the
        // order, fails the minimum, and returns the collateral. The bot pays the transaction fee and
        // the batcher fee for a no-op, and the loan is still liquidatable next cycle — so the same
        // futile order is built again, every cycle, until the pool moves. Refusing here costs nothing
        // and is reproducible; the alternative is billed to the operator.
        //
        // ⚠ The fee is the POOL's, read from its own datum, and it is direction-dependent. No constant
        // works: the two live mainnet pools declare 80 and 70, and both differ from the 24/10000 and
        // 30/10000 the DEX literature quotes. Over-quoting is the failure that matters — it is what
        // lets the futile order through — and a constant over-quotes the real batch by ~0.5%.
        if (pool.allowDynamicFee()) {
            throw new RefusedException(Refusal.POOL_HAS_DYNAMIC_FEE,
                    ("pool %s/%s sets allow_dynamic_fee, so its base numerators (a %s, b %s) are not "
                            + "the whole fee; refusing to quote a price the pool does not charge")
                            .formatted(pool.assetA().toUnit(), pool.assetB().toUnit(),
                                    pool.baseFeeANumerator(), pool.baseFeeBNumerator()));
        }
        BigInteger reserveIn = aToB ? pool.reserveA() : pool.reserveB();
        BigInteger reserveOut = aToB ? pool.reserveB() : pool.reserveA();
        BigInteger feeNumerator = feeNumeratorFor(pool, aToB);
        BigInteger quotedOut = constantProductOut(swappable, reserveIn, reserveOut, feeNumerator);
        if (quotedOut.compareTo(remainingDebt) < 0) {
            throw new RefusedException(Refusal.POOL_TOO_THIN,
                    ("swapping %s %s would return about %s %s, but minimum_receive is the debt, %s — "
                            + "a shortfall of %s. The order would be batched and REFUNDED, at the "
                            + "operator's expense. Pool reserves %s in / %s out, fee %s/%s (%s)")
                            .formatted(swappable, collateral.toUnit(), quotedOut,
                                    principal.toUnit(), remainingDebt,
                                    remainingDebt.subtract(quotedOut), reserveIn, reserveOut,
                                    feeNumerator, MinswapPoolDatum.FEE_DENOMINATOR,
                                    aToB ? "base_fee_a, aToB" : "base_fee_b, bToA"));
        }

        // The lp asset name is computed from the POOL's declared order, not from ours: the pair is
        // the same set either way, and the hash is not.
        String lpAssetName = ConvertTxEncoder.computeLpAssetName(pool.assetA(), pool.assetB());

        // ⛔ The order's ada is the validator's, and BOTH branches carry the Minswap overhead now.
        // Until db5069e the ada branch held exactly the swappable amount and nothing else, leaving no
        // room for a batcher fee at all — which is why no convert order was ever batchable
        // (findings §57.9). The validator now reads `>=`, so this is a floor we meet exactly.
        BigInteger orderLovelace = collateral.isAda()
                ? swappable.add(ConvertEconomics.MINSWAP_ORDER_OVERHEAD)
                : ConvertEconomics.MINSWAP_ORDER_OVERHEAD;

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

    /**
     * The pool's fee numerator <b>for this swap's direction</b>: {@code aToB} sells {@code asset_a} and
     * pays {@code base_fee_a_numerator} on the input, {@code bToA} pays {@code base_fee_b_numerator}.
     *
     * <p>⚠ <b>They are equal on both live mainnet pools</b> (ADA/FLDT 80/80, FLDT/USDM 70/70, measured
     * 2026-09-10) — <b>which is precisely why picking the wrong one would be invisible</b> on every pool
     * this node actually trades against, and why the test for this uses a fixture with UNEQUAL
     * numerators. A direction bug here does not fail; it quotes a price at the other side's fee.
     */
    static BigInteger feeNumeratorFor(MinswapPoolDatum pool, boolean aToB) {
        return aToB ? pool.baseFeeANumerator() : pool.baseFeeBNumerator();
    }

    /**
     * Minswap V2's constant-product output, fee taken on the INPUT — the same arithmetic its batcher
     * runs, so this answers "what would this order actually receive" rather than "what would be fair".
     *
     * <pre>
     * effectiveIn = amountIn × (10000 − feeNumerator)
     * out         = reserveOut × effectiveIn ÷ (reserveIn × 10000 + effectiveIn)
     * </pre>
     *
     * <p>⛔ <b>Integer division truncates DOWNWARD, and that is the direction to keep.</b> The quote is
     * a pre-check that refuses; under-stating the output can only make it refuse an order that would
     * marginally have filled, while over-stating lets one through to be batched and refunded at the
     * operator's expense. Calibrated against the real mainnet batch {@code 5f0572b2…} (2026-09-08):
     * 86,279,718 FLDT against reserves 1,667,895,724,071 / 7,689,154,296,039 at the datum's fee of 80
     * quotes <b>18,565,466</b> where the batch paid <b>18,565,738</b> — 272 lovelace under, on the safe
     * side. The residual is consistent with that batch carrying several orders, so the pool input it
     * settled against predates them all.
     */
    static BigInteger constantProductOut(BigInteger amountIn, BigInteger reserveIn,
                                         BigInteger reserveOut, BigInteger feeNumerator) {
        BigInteger effectiveIn = amountIn.multiply(
                MinswapPoolDatum.FEE_DENOMINATOR.subtract(feeNumerator));
        return reserveOut.multiply(effectiveIn)
                .divide(reserveIn.multiply(MinswapPoolDatum.FEE_DENOMINATOR).add(effectiveIn));
    }
}
