package com.fluidtokens.aquarium.offchain.service.loans;

import java.math.BigInteger;

import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.MinswapPoolDatum;

/**
 * Whether a pool could actually fill <b>this</b> loan's swap — not whether a pool exists.
 *
 * <h2>⛔ Existence is per PAIR; usability is per LOAN</h2>
 * What gets swapped is this loan's collateral, so a pool deep enough for a small loan is not deep
 * enough for a large one. Reporting one boolean for both questions is what made the readiness page
 * say "capital in advance" without ever saying whether that was the chain's doing or a setting.
 *
 * <h2>The thin-pool criterion is the validator's, not a threshold invented here</h2>
 * {@code minimum_receive} is the remaining debt and the validator fixes it, so there is no price at
 * which a pool too shallow to deliver the debt fills partially: Minswap batches the order, the
 * minimum fails, and the collateral is <b>refunded</b> — with the transaction fee and the batcher fee
 * paid for a no-op. So "too thin" means exactly {@code quotedOut < remainingDebt}, which is a fact
 * about this loan against this pool, and is the same test {@link ConvertOrderPlan} applies before it
 * builds anything.
 *
 * <p>This class re-uses {@link ConvertOrderPlan}'s own arithmetic rather than restating it, so the
 * page cannot drift from the builder. It computes nothing the builder does not, and it builds nothing.
 *
 * <h2>⚠ What it deliberately does NOT report</h2>
 * <b>No slippage percentage, and no price.</b> Both would read as precision this cannot honestly
 * claim: the reserves are a snapshot that moves between the render and any action taken on it. A
 * verdict and its binding reason are what a decision needs; a number that looks exact and is not is
 * worse on an operator page than no number at all.
 */
public record PoolUsability(Verdict verdict, String detail) {

    public enum Verdict {
        /** The pool exists, the pair matches, and it would deliver at least the debt. */
        USABLE,
        /** No pool for this pair, in either asset ordering. Permanent until someone creates one. */
        NO_POOL,
        /** The lookup itself failed — a transport or provider fault. <b>Not</b> the same as NO_POOL. */
        CHECK_FAILED,
        /** This node cannot convert at all: the Minswap configuration is absent for this network. */
        NOT_CONFIGURED,
        /** A pool exists but would return less than the debt, so the order would be refunded. */
        TOO_THIN,
        /** The pool sets {@code allow_dynamic_fee}, so its published numerators are not the whole fee. */
        CANNOT_QUOTE,
        /** The resolved pool is not this loan's pair — an impossible transaction, not a worse price. */
        WRONG_PAIR,
        /** Inputs the verdict needs could not be computed, typically because a feed is unusable. */
        UNKNOWN
    }

    public boolean usable() {
        return verdict == Verdict.USABLE;
    }

    /**
     * @param swappable     {@code collateralAmount − equity − liquidationFee}: what actually reaches
     *                      the pool, which is why this is per-loan
     * @param remainingDebt the validator's {@code minimum_receive}
     */
    public static PoolUsability assess(AssetType collateral, AssetType principal,
                                       BigInteger swappable, BigInteger remainingDebt,
                                       MinswapPoolDatum pool) {
        if (pool == null || swappable == null || remainingDebt == null) {
            return new PoolUsability(Verdict.UNKNOWN,
                    "the pool or this loan's figures could not be read, so usability is not known");
        }
        boolean aToB = pool.assetA().equals(collateral) && pool.assetB().equals(principal);
        boolean bToA = pool.assetB().equals(collateral) && pool.assetA().equals(principal);
        if (!aToB && !bToA) {
            return new PoolUsability(Verdict.WRONG_PAIR,
                    "the resolved pool is not this loan's collateral/principal pair");
        }
        if (swappable.signum() <= 0) {
            return new PoolUsability(Verdict.TOO_THIN,
                    "after the borrower's equity and the liquidation fee there is nothing left to swap");
        }
        if (pool.allowDynamicFee()) {
            return new PoolUsability(Verdict.CANNOT_QUOTE,
                    "this pool sets a dynamic fee, so its published fee is not the whole fee and the "
                            + "return cannot be quoted honestly");
        }
        BigInteger reserveIn = aToB ? pool.reserveA() : pool.reserveB();
        BigInteger reserveOut = aToB ? pool.reserveB() : pool.reserveA();
        BigInteger quotedOut = ConvertOrderPlan.constantProductOut(swappable, reserveIn, reserveOut,
                ConvertOrderPlan.feeNumeratorFor(pool, aToB));
        if (quotedOut.compareTo(remainingDebt) < 0) {
            // The shortfall is a real quantity in the principal's own units, not a percentage.
            return new PoolUsability(Verdict.TOO_THIN,
                    "the pool would return about " + quotedOut + " but the debt to clear is "
                            + remainingDebt + ", short by " + remainingDebt.subtract(quotedOut)
                            + " — the order would be refunded at the operator's expense");
        }
        return new PoolUsability(Verdict.USABLE,
                "a pool exists and is deep enough to clear this loan's debt");
    }

    public static PoolUsability noPool() {
        return new PoolUsability(Verdict.NO_POOL,
                "no Minswap pool exists for this pair, in either asset ordering");
    }

    public static PoolUsability checkFailed(String why) {
        return new PoolUsability(Verdict.CHECK_FAILED,
                "the pool lookup did not complete (" + why + ") — this is not evidence that no pool "
                        + "exists, and it is worth re-checking");
    }

    public static PoolUsability notConfigured() {
        return new PoolUsability(Verdict.NOT_CONFIGURED,
                "this node cannot convert — the Minswap configuration is unset or belongs to another "
                        + "network");
    }
}
