package com.fluidtokens.aquarium.offchain.service.loans;

import com.fluidtokens.aquarium.offchain.model.loans.LiquidationMode;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.model.loans.Rational;
import com.fluidtokens.aquarium.offchain.model.loans.RepaymentMode;

import java.math.BigInteger;

/**
 * A line-by-line port of {@code lib/fluidtokens/finance.ak}.
 * <p>
 * These numbers are not advisory. {@code loan_claim_action.ak} recomputes every one of them
 * on chain and compares them to what the bot put in the redeemer
 * ({@code remainingDebt == inputAction.remainingDebt}, {@code inputAction.equity == max(computed, 0)}),
 * so an off-by-one here is a failed script evaluation and a wasted fee — not a slightly wrong
 * number on a dashboard. Everything runs through {@link Rational} for that reason: no doubles,
 * no early rounding, and the same floor/ceil asymmetry the stdlib has.
 * <p>
 * Scaling is applied by the <em>caller</em> on chain, and mirrored in {@link #remainingDebt(LoanDatum, long)}:
 * {@code interestRate} is {@code rational.new(datum.interestRate, 10000)} and the LTV is
 * {@code rational.new(lTV, lTVDivider)} — the raw datum ints are never used directly.
 */
public final class LoanFinance {

    /** {@code constants.hour_to_millis}. */
    public static final long HOUR_TO_MILLIS = 3_600_000L;

    private static final BigInteger HOURS_PER_YEAR = BigInteger.valueOf(8760);
    private static final BigInteger MILLIS_PER_HOUR = BigInteger.valueOf(HOUR_TO_MILLIS);
    /** {@code 3_600_000^2} — converts millis² straight to hours². */
    private static final BigInteger MILLIS_PER_HOUR_SQUARED = BigInteger.valueOf(12_960_000_000_000L);
    private static final BigInteger APY_COEFFICIENT_DIVIDER = BigInteger.valueOf(1_000_000);
    private static final BigInteger INTEREST_RATE_DIVIDER = BigInteger.valueOf(10_000);
    private static final BigInteger PER_MILLE = BigInteger.valueOf(1000);

    private LoanFinance() {
    }

    // ---- price conversion ---------------------------------------------------------------

    /** {@code get_token_amount_in_lovelace}. */
    public static Rational toLovelace(Rational tokenAmount, OraclePriceFeed feed) {
        return feed.price().mul(tokenAmount);
    }

    /** {@code get_lovelace_amount_in_token_currency}. Fails on a zero price, as the contract does. */
    public static Rational fromLovelace(Rational lovelaces, OraclePriceFeed feed) {
        return lovelaces.div(feed.price())
                .orElseThrow(() -> new ArithmeticException("oracle price is zero"));
    }

    // ---- debt ---------------------------------------------------------------------------

    /** {@code rational.new(datum.interestRate, 10000)} — the scaling {@code loan_claim_action.ak:83} applies. */
    public static Rational interestRate(LoanDatum datum) {
        return Rational.required(datum.interestRate(), INTEREST_RATE_DIVIDER);
    }

    /** {@code rational.new(lTV, lTVDivider)} — {@code loan_claim_action.ak:226}. */
    public static Rational liquidationLtv(LiquidationMode.Liquidation liquidation) {
        return Rational.required(liquidation.ltv(), liquidation.ltvDivider());
    }

    /**
     * {@code get_remaining_debt}, in principal currency, ignoring any late-repayment penalty.
     *
     * @param currentTimeMillis the transaction's {@code validFrom}; on chain the argument is
     *                          {@code validFrom - datum.lendDate}
     */
    public static BigInteger remainingDebt(LoanDatum datum, long currentTimeMillis) {
        return remainingDebt(
                datum.repaymentMode(),
                Rational.fromInt(datum.principalAmount()),
                interestRate(datum),
                datum.totalInstallments(),
                datum.repaidInstallments(),
                datum.installmentPeriod(),
                datum.initialGracePeriod(),
                BigInteger.valueOf(currentTimeMillis).subtract(datum.lendDate()));
    }

    /** {@code get_remaining_debt}. */
    public static BigInteger remainingDebt(RepaymentMode repaymentMode,
                                           Rational principal,
                                           Rational interestRate,
                                           BigInteger totalInstallments,
                                           BigInteger repaidInstallments,
                                           BigInteger installmentPeriod,
                                           BigInteger initialGracePeriod,
                                           BigInteger timeSinceLendDate) {
        if (repaymentMode instanceof RepaymentMode.PerpetualLoan perpetual) {
            // principal * (c * hoursSinceLastRepaidInstallment + m * hoursSoFar^2) / 8760
            Rational passedHoursSoFar = Rational.required(timeSinceLendDate, MILLIS_PER_HOUR);
            Rational passedHoursSoFarSquared =
                    Rational.required(Rational.pow(timeSinceLendDate, 2), MILLIS_PER_HOUR_SQUARED);
            Rational m = Rational.required(perpetual.apyIncreaseLinearCoefficient(), APY_COEFFICIENT_DIVIDER);

            Rational accumulatedHoursOfRepaidInstallments = Rational.fromInt(
                    repaidInstallments.signum() == 0
                            ? BigInteger.ZERO
                            : initialGracePeriod.add(installmentPeriod.multiply(repaidInstallments)));

            Rational passedHoursSinceLastRepaidInstallment =
                    passedHoursSoFar.sub(accumulatedHoursOfRepaidInstallments);

            Rational remainingInterestToPay = principal
                    .mul(interestRate.mul(passedHoursSinceLastRepaidInstallment)
                            .add(m.mul(passedHoursSoFarSquared)))
                    .divRequired(Rational.fromInt(HOURS_PER_YEAR));

            return principal.add(remainingInterestToPay).ceil();
        }

        return totalInstallments.subtract(repaidInstallments).multiply(
                nextInstallmentAmount(repaymentMode, principal, interestRate, totalInstallments,
                        repaidInstallments, installmentPeriod, initialGracePeriod, false, BigInteger.ZERO));
    }

    /** {@code get_next_installment_amount}, in principal currency. */
    public static BigInteger nextInstallmentAmount(RepaymentMode repaymentMode,
                                                   Rational principal,
                                                   Rational interestRate,
                                                   BigInteger totalInstallments,
                                                   BigInteger repaidInstallments,
                                                   BigInteger installmentPeriod,
                                                   BigInteger initialGracePeriod,
                                                   boolean isLate,
                                                   BigInteger penaltyFeeForLateRepayment) {
        Rational penaltyFee = Rational.fromInt(isLate ? penaltyFeeForLateRepayment : BigInteger.ZERO);

        return switch (repaymentMode) {
            case RepaymentMode.InterestOnRemainingPrincipal ignored -> {
                // amortization: P*i*(1+i)^n / ((1+i)^n - 1)
                Rational one = Rational.fromInt(BigInteger.ONE);
                Rational interestPerInstallment = interestRate.div(Rational.fromInt(totalInstallments))
                        .orElseThrow(() -> new ArithmeticException("totalInstallments is zero"));
                Rational compounded = one.add(interestPerInstallment).pow(toIntExact(totalInstallments));
                Rational numerator = principal.mul(interestPerInstallment).mul(compounded);
                Rational denominator = compounded.sub(one);
                Rational result = numerator.div(denominator)
                        .orElseThrow(() -> new ArithmeticException("zero denominator in amortization"));
                yield result.add(penaltyFee).ceil();
            }
            case RepaymentMode.PrincipalAndInterestOnInstallments ignored -> {
                Rational totalInterest = principal.mul(interestRate);
                Rational single = principal.add(totalInterest).div(Rational.fromInt(totalInstallments))
                        .orElseThrow(() -> new ArithmeticException("totalInstallments is zero"));
                yield single.add(penaltyFee).ceil();
            }
            case RepaymentMode.PerpetualLoan ignored -> {
                // interest only, over the period being paid for
                BigInteger periodToPayInHours = repaidInstallments.signum() == 0
                        ? initialGracePeriod.add(installmentPeriod)
                        : installmentPeriod;
                Rational hourlyInterestRate = interestRate.div(Rational.fromInt(HOURS_PER_YEAR))
                        .orElseThrow(() -> new ArithmeticException("unreachable: 8760 is not zero"));
                Rational interestRateInPeriod = Rational.fromInt(periodToPayInHours).mul(hourlyInterestRate);
                yield principal.mul(interestRateInPeriod).add(penaltyFee).ceil();
            }
        };
    }

    // ---- liquidation --------------------------------------------------------------------

    /**
     * {@code can_liquidate} — true when {@code currentLtv > liquidationLtv}. Note the strictness:
     * equality is <em>not</em> liquidatable.
     * <p>
     * The zero-collateral short circuit is a structural comparison against {@code rational.zero}
     * ({@code 0/1}), so it only fires when the collateral price denominator is 1. Otherwise a
     * zero-collateral loan falls through to a division by zero, which is a script failure on
     * chain — reproduced here as {@link ArithmeticException} rather than papered over.
     */
    public static boolean canLiquidate(Rational remainingDebt,
                                       Rational collateral,
                                       Rational liquidationLtv,
                                       OraclePriceFeed principalFeed,
                                       OraclePriceFeed collateralFeed) {
        Rational totalOutstandingDebt = toLovelace(remainingDebt, principalFeed);
        Rational collateralInLovelace = toLovelace(collateral, collateralFeed);

        if (collateralInLovelace.equals(Rational.ZERO)) {
            return true;
        }
        Rational currentLtv = totalOutstandingDebt.div(collateralInLovelace)
                .orElseThrow(() -> new ArithmeticException(
                        "collateral prices to zero — can_liquidate fails on chain here"));
        return liquidationLtv.compareTo(currentLtv) < 0;
    }

    /** Current LTV as a ratio of debt to collateral, both in lovelace. Not part of the contract. */
    public static Rational currentLtv(Rational remainingDebt,
                                      Rational collateral,
                                      OraclePriceFeed principalFeed,
                                      OraclePriceFeed collateralFeed) {
        return toLovelace(remainingDebt, principalFeed)
                .div(toLovelace(collateral, collateralFeed))
                .orElseThrow(() -> new ArithmeticException("collateral prices to zero"));
    }

    /** {@code get_equity} — the borrower's refund, expressed in <em>principal</em> currency. */
    public static BigInteger equityInPrincipalCurrency(Rational collateralAmount,
                                                       Rational remainingDebt,
                                                       OraclePriceFeed principalFeed,
                                                       OraclePriceFeed collateralFeed,
                                                       BigInteger partialLiquidationPenaltyPerMille) {
        return equity(collateralAmount, remainingDebt, principalFeed, collateralFeed,
                partialLiquidationPenaltyPerMille, principalFeed);
    }

    /** {@code get_equity_in_collateral_currency} — identical but converted back at the collateral price. */
    public static BigInteger equityInCollateralCurrency(Rational collateralAmount,
                                                        Rational remainingDebt,
                                                        OraclePriceFeed principalFeed,
                                                        OraclePriceFeed collateralFeed,
                                                        BigInteger partialLiquidationPenaltyPerMille) {
        return equity(collateralAmount, remainingDebt, principalFeed, collateralFeed,
                partialLiquidationPenaltyPerMille, collateralFeed);
    }

    private static BigInteger equity(Rational collateralAmount,
                                     Rational remainingDebt,
                                     OraclePriceFeed principalFeed,
                                     OraclePriceFeed collateralFeed,
                                     BigInteger partialLiquidationPenaltyPerMille,
                                     OraclePriceFeed resultFeed) {
        Rational remainingDebtInLovelace = toLovelace(remainingDebt, principalFeed);
        Rational collateralInLovelace = toLovelace(collateralAmount, collateralFeed);
        Rational penaltyRate = Rational.required(partialLiquidationPenaltyPerMille, PER_MILLE);
        Rational penalty = remainingDebtInLovelace.mul(penaltyRate);

        Rational equityInLovelace = collateralInLovelace.sub(remainingDebtInLovelace).sub(penalty);
        return fromLovelace(equityInLovelace, resultFeed).floor();
    }

    /**
     * The equity the bot must put in the redeemer: {@code max(computed, 0)}, or zero outright when
     * the penalty is negative — {@code loan_claim_action.ak:241-268}.
     */
    public static BigInteger redeemerEquity(LiquidationMode.Liquidation liquidation,
                                            Rational collateralAmount,
                                            Rational remainingDebt,
                                            OraclePriceFeed principalFeed,
                                            OraclePriceFeed collateralFeed) {
        if (liquidation.partialLiquidationPenaltyPerMille().signum() < 0) {
            return BigInteger.ZERO;
        }
        BigInteger computed = liquidation.equityInPrincipalCurrency()
                ? equityInPrincipalCurrency(collateralAmount, remainingDebt, principalFeed, collateralFeed,
                liquidation.partialLiquidationPenaltyPerMille())
                : equityInCollateralCurrency(collateralAmount, remainingDebt, principalFeed, collateralFeed,
                liquidation.partialLiquidationPenaltyPerMille());
        return computed.max(BigInteger.ZERO);
    }

    // ---- lateness -----------------------------------------------------------------------

    /** {@code is_repayment_late}. Perpetual loans with no installment period can never be late. */
    public static boolean isRepaymentLate(LoanDatum datum, long currentTimeMillis) {
        boolean isPerpetual = datum.repaymentMode() instanceof RepaymentMode.PerpetualLoan;
        if (isPerpetual && datum.installmentPeriod().signum() == 0) {
            return false;
        }
        BigInteger periodToLatestPaidInstallment =
                datum.repaidInstallments().add(BigInteger.ONE).multiply(datum.installmentPeriod());
        BigInteger deadline = datum.lendDate().add(
                datum.initialGracePeriod()
                        .add(periodToLatestPaidInstallment)
                        .add(datum.repaymentTimeWindow())
                        .multiply(MILLIS_PER_HOUR));
        return BigInteger.valueOf(currentTimeMillis).compareTo(deadline) > 0;
    }

    private static int toIntExact(BigInteger value) {
        try {
            return value.intValueExact();
        } catch (ArithmeticException e) {
            throw new ArithmeticException("installment count out of range: " + value);
        }
    }
}
