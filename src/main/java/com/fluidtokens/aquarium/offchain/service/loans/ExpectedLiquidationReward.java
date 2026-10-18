package com.fluidtokens.aquarium.offchain.service.loans;

import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.model.loans.Loan;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.model.loans.Rational;

import java.math.BigInteger;
import java.util.Optional;

/**
 * ⛔ <b>The readiness page's "Expected reward" AND the bot's liquidation order — one function for
 * both</b> (FAB-144).
 *
 * <p>The liquidator's fee slice, {@link ConvertEconomics#liquidationFee}, priced into lovelace through
 * the collateral oracle the loan's own datum names. {@code LiquidationReadinessController} shows it
 * per row and {@code LiquidationExecutor} walks each cycle's buildable candidates in descending order
 * of it. Both call THIS class, so the page and the bot can never disagree about which loan pays most:
 * a second copy of the arithmetic would be a second number the first time the two drifted.
 *
 * <p>⚠ <b>A ROUGH estimate, and only an ordering key.</b> No transaction fee, Minswap order cost or
 * slippage is subtracted (Giovanni, 2026-10-15: a rough estimate is enough). Every profitability gate,
 * refusal and submit path stays where it was; an estimate that is wrong costs ordering, never funds.
 */
public final class ExpectedLiquidationReward {

    private ExpectedLiquidationReward() {
    }

    /**
     * The fee slice in the collateral token's base units, exactly as the readiness page has always
     * called it: {@code collateralAmount * liquidationFeePerMille / 1000}, truncated.
     */
    public static BigInteger feeTokens(Loan loan, LenderBond bond) {
        return ConvertEconomics.liquidationFee(loan.collateralAmount(),
                bond.datum().liquidationFeePerMille().longValueExact());
    }

    /**
     * {@code feeTokens} in lovelace through {@code feed}, floored. Throws whatever the feed's price
     * throws (a pooled feed, a zero denominator) — the page reports that as an unpriceable feed.
     */
    public static BigInteger lovelace(BigInteger feeTokens, OraclePriceFeed feed) {
        return LoanFinance.toLovelace(Rational.fromInt(feeTokens), feed).floor();
    }

    /**
     * The ranking key: {@link #lovelace} of {@link #feeTokens}, or empty when anything it needs is
     * missing or cannot be computed — no loan, no bond, no feed, a null field, a feed that throws.
     * <b>Never throws</b>: a candidate that cannot be priced sorts last, it is never lost.
     *
     * @param collateralFeed the feed for the collateral leg the loan's datum names, as
     *                       {@code FluidOracleClient.findFeedForLeg} returns it (the 1:1 unit feed
     *                       for ada collateral)
     */
    public static Optional<BigInteger> estimate(Loan loan, LenderBond bond,
                                                Optional<OraclePriceFeed> collateralFeed) {
        if (loan == null || bond == null || collateralFeed == null || collateralFeed.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(lovelace(feeTokens(loan, bond), collateralFeed.get()));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
