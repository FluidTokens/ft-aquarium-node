package com.fluidtokens.aquarium.offchain.service.loans;

import java.math.BigInteger;
import java.util.List;

import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.MinswapPoolDatum;

/**
 * What an operator would net by <b>anticipating the principal and then selling the collateral
 * themselves</b>, instead of letting the validator convert it atomically.
 *
 * <h2>⛔ Why this is a different question from {@link PoolUsability}, not a softer one</h2>
 * A {@code CONVERT} is <b>all or nothing</b>: the validator fixes {@code minimum_receive} at the
 * remaining debt, so a pool that cannot deliver the whole debt does not fill badly — Minswap
 * batches the order, the minimum fails, and the collateral is refunded with both fees paid. That is
 * a cliff, and {@link PoolUsability} reports which side of it a loan is on.
 *
 * <p>An <b>anticipate</b> has no such minimum. The operator pays the lender from their own wallet
 * and <b>owns the collateral outright</b>; selling it is an ordinary swap they may make at any
 * price, in one go or in slices over days. So the binary "this pool is too thin" becomes a
 * continuous "this is what the slippage costs" — which is why a loan {@code CONVERT} refuses can
 * still be worth doing.
 *
 * <h2>The two tests are priced against different benchmarks</h2>
 * <pre>
 *   CONVERT            quote(swappable)        >=  remainingDebt
 *   ANTICIPATE + sell  quote(swappable + fee)  >   oracleValue(swappable) + costs
 * </pre>
 * The convert path is measured against the <b>debt</b>; this one against the <b>oracle</b>, because
 * {@code convertedLoanCollateralToPrincipalAmount} — what the lender is paid — is the oracle's
 * valuation of the same collateral slice. The {@code liquidationFee} slice is the cushion: the
 * operator sells it too, having paid nothing for it, so the trade can clear even when Minswap is
 * somewhat below the oracle.
 *
 * <h2>⚠ Three honesties this number carries, and the page must say so</h2>
 * <ol>
 *   <li><b>It is not atomic.</b> Two transactions, with price risk between them. A convert is one.
 *       This is an estimate of an outcome the operator still has to go and get.</li>
 *   <li><b>It is a LOWER BOUND if the collateral would be sold gradually.</b> The quote prices the
 *       whole slice into the pool at once, which is the worst case for slippage. Erring low is the
 *       right direction for a number someone commits capital against.</li>
 *   <li><b>Capital is tied up</b> between the legs, and nothing here knows the operator's
 *       opportunity cost. The figure is a gross margin, not a return.</li>
 * </ol>
 *
 * <p>Like {@link PoolUsability}, this re-uses {@link ConvertOrderPlan}'s own arithmetic rather than
 * restating it, so the page cannot drift from the builder. It computes nothing the builder does not,
 * and it builds nothing.
 */
public record AnticipateAndSell(BigInteger netInPrincipal, String detail) {

    /** Whether a number could be produced at all. {@code null} net means the reason is all there is. */
    public boolean known() {
        return netInPrincipal != null;
    }

    /** True only when the estimate is strictly positive — the operator would come out ahead. */
    public boolean profitable() {
        return netInPrincipal != null && netInPrincipal.signum() > 0;
    }

    public static AnticipateAndSell unknown(String why) {
        return new AnticipateAndSell(null, why);
    }

    /**
     * @param swappable      {@code collateral − equity − liquidationFee} — the slice the lender would
     *                       have received, and which the operator buys at the oracle's price
     * @param liquidationFee the operator's own slice, sold alongside it at no cost
     * @param advanceCost    {@code convertedLoanCollateralToPrincipalAmount} — what the lender is paid
     * @param orderCost      the fixed outlay of the Minswap order the operator would place. Null
     *                       falls back to {@link ConvertEconomics#MINSWAP_ORDER_OVERHEAD}, which the
     *                       validator forces the order to carry — an operator may believe the cost
     *                       is HIGHER, but never lower, because the chain spends it regardless
     */
    public static AnticipateAndSell estimate(AssetType collateral, AssetType principal,
                                             BigInteger swappable, BigInteger liquidationFee,
                                             BigInteger advanceCost, BigInteger orderCost,
                                             List<MinswapPoolDatum> pools) {
        if (swappable == null || liquidationFee == null || advanceCost == null) {
            return unknown("this loan's figures could not be priced, so the estimate is not known");
        }
        if (pools == null || pools.isEmpty()) {
            return unknown("no Minswap pool for this pair, so the collateral could not be sold there");
        }
        BigInteger toSell = swappable.add(liquidationFee);
        if (toSell.signum() <= 0) {
            return unknown("after the borrower's equity there is no collateral left to sell");
        }

        // ⛔ BEST PROCEEDS ACROSS EVERY POOL, not the deepest. The output turns on price and fee as
        // well as depth, so the deepest pool is not reliably the one that pays most -- the same
        // reasoning as bestVerdict(), and the same reason depth() alone cannot answer it.
        BigInteger best = null;
        String cannotQuote = null;
        for (MinswapPoolDatum pool : pools) {
            boolean aToB = pool.assetA().equals(collateral) && pool.assetB().equals(principal);
            boolean bToA = pool.assetB().equals(collateral) && pool.assetA().equals(principal);
            if (!aToB && !bToA) {
                continue;
            }
            if (pool.allowDynamicFee()) {
                // ⚠ Same refusal as ConvertOrderPlan: the published numerators are not the whole fee,
                // and a quote built from them UNDERSTATES the cost -- the direction that flatters this
                // number into looking profitable when it is not.
                cannotQuote = "a pool for this pair sets a dynamic fee, so its return cannot be quoted "
                        + "honestly";
                continue;
            }
            BigInteger reserveIn = aToB ? pool.reserveA() : pool.reserveB();
            BigInteger reserveOut = aToB ? pool.reserveB() : pool.reserveA();
            BigInteger out = ConvertOrderPlan.constantProductOut(toSell, reserveIn, reserveOut,
                    ConvertOrderPlan.feeNumeratorFor(pool, aToB));
            if (best == null || out.compareTo(best) > 0) {
                best = out;
            }
        }
        if (best == null) {
            return unknown(cannotQuote != null ? cannotQuote
                    : "no pool for this loan's collateral/principal pair could be quoted");
        }

        BigInteger costs = orderCost == null ? ConvertEconomics.MINSWAP_ORDER_OVERHEAD : orderCost;
        BigInteger net = best.subtract(advanceCost).subtract(costs);
        return new AnticipateAndSell(net,
                "selling " + toSell + " collateral would return about " + best + ", against " + advanceCost
                        + " paid to the lender and " + costs + " of order cost"
                        + (net.signum() > 0 ? " — net positive" : " — net NEGATIVE")
                        + ". Two transactions, so the price may move between them; and this prices the "
                        + "whole slice into the pool at once, so selling gradually would do better");
    }
}
