package com.fluidtokens.aquarium.offchain;

import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.model.loans.Rational;
import com.fluidtokens.aquarium.offchain.model.loans.RepaymentMode;
import com.fluidtokens.aquarium.offchain.service.loans.LoanFinance;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parity vectors for {@link LoanFinance} against {@code lib/fluidtokens/finance.ak}.
 * <p>
 * <b>Every expected value below was produced by the Aiken compiler, not by this code and not by
 * hand.</b> A temporary test module was added to a checkout of {@code ft-cardano-loans-v4} that
 * calls the real {@code finance.ak} functions and traces the results:
 * <pre>
 * // lib/fluidtokens/parity_vectors.ak
 * test vector_perpetual_real_loan() {
 *   expect Some(rate) = rational.new(459, 10000)
 *   let debt = get_remaining_debt(
 *     PerpetualLoan { apyIncreaseLinearCoefficient: 28, max_possible_recasts: 5 },
 *     rational.from_int(40000000), rate, 0, 0, 0, 0, 259200000)
 *   trace @"perpetual_real_loan": cbor.diagnostic(debt)
 *   False   // forces the trace to print
 * }
 *
 * aiken check -m "fluidtokens/parity_vectors" -t verbose
 * </pre>
 * That matters because these numbers are re-derived on chain and compared to the redeemer
 * ({@code loan_claim_action.ak:229,257,267}) — agreeing with my reading of the source is worth
 * nothing, agreeing with the compiler is the whole point.
 */
class LoanFinanceTest {

    private static final BigInteger ZERO = BigInteger.ZERO;

    /** The token and window are irrelevant here — these vectors exercise the price arithmetic only. */
    private static OraclePriceFeed feed(long price, long denominator) {
        return OraclePriceFeed.aggregated(AssetType.ada(), BigInteger.valueOf(price),
                BigInteger.valueOf(denominator), 0L, 0L);
    }

    private static Rational rate(long bps) {
        return Rational.required(bps, 10_000);
    }

    /** 40 ada principal, 4.59%, perpetual m=28, no installments, 3 days in. */
    @Test
    void perpetualRealLoan() {
        BigInteger debt = LoanFinance.remainingDebt(
                new RepaymentMode.PerpetualLoan(BigInteger.valueOf(28), BigInteger.valueOf(5)),
                Rational.fromInt(40_000_000), rate(459),
                ZERO, ZERO, ZERO, ZERO,
                BigInteger.valueOf(259_200_000L));
        assertEquals(BigInteger.valueOf(40_015_754L), debt);
    }

    /** Exercises the "hours since last repaid installment" term, which the simple case leaves at zero. */
    @Test
    void perpetualWithRepaidInstallments() {
        BigInteger debt = LoanFinance.remainingDebt(
                new RepaymentMode.PerpetualLoan(BigInteger.valueOf(500), BigInteger.valueOf(3)),
                Rational.fromInt(1_000_000_000), rate(1200),
                ZERO, BigInteger.TWO, BigInteger.valueOf(24), BigInteger.valueOf(12),
                BigInteger.valueOf(864_000_000L));
        assertEquals(BigInteger.valueOf(1_005_753_425L), debt);
    }

    @Test
    void principalAndInterestOnInstallments() {
        BigInteger debt = LoanFinance.remainingDebt(
                new RepaymentMode.PrincipalAndInterestOnInstallments(),
                Rational.fromInt(250_000_000), rate(1500),
                BigInteger.valueOf(12), BigInteger.valueOf(5), BigInteger.valueOf(720), ZERO,
                ZERO);
        assertEquals(BigInteger.valueOf(167_708_338L), debt);
    }

    /** The amortization branch — the one that leans on {@code rational_pow}. */
    @Test
    void interestOnRemainingPrincipal() {
        BigInteger debt = LoanFinance.remainingDebt(
                new RepaymentMode.InterestOnRemainingPrincipal(BigInteger.valueOf(4)),
                Rational.fromInt(500_000_000), rate(2000),
                BigInteger.valueOf(10), BigInteger.valueOf(3), BigInteger.valueOf(720), ZERO,
                ZERO);
        assertEquals(BigInteger.valueOf(389_642_848L), debt);
    }

    @Test
    void nextInstallmentPerpetualWithLatePenalty() {
        BigInteger amount = LoanFinance.nextInstallmentAmount(
                new RepaymentMode.PerpetualLoan(BigInteger.valueOf(28), BigInteger.valueOf(5)),
                Rational.fromInt(40_000_000), rate(459),
                ZERO, ZERO, BigInteger.valueOf(24), BigInteger.valueOf(12),
                true, BigInteger.valueOf(1_000_000));
        assertEquals(BigInteger.valueOf(1_007_546L), amount);
    }

    /**
     * The threshold is strict: at exactly the liquidation LTV the loan is <em>not</em>
     * liquidatable. Acting on the wrong side of this burns a fee on a script failure.
     */
    @Test
    void canLiquidateIsStrictlyGreaterThanTheThreshold() {
        Rational ltv = Rational.required(100, 125);
        var principalFeed = feed(1, 1);
        var collateralFeed = feed(3, 10);

        assertTrue(LoanFinance.canLiquidate(
                Rational.fromInt(30_000_000), Rational.fromInt(110_000_000),
                ltv, principalFeed, collateralFeed), "0.909 LTV is above the 0.8 threshold");

        assertFalse(LoanFinance.canLiquidate(
                Rational.fromInt(24_000_000), Rational.fromInt(110_000_000),
                ltv, principalFeed, collateralFeed), "exactly at the threshold is not liquidatable");
    }

    @Test
    void equityInBothCurrenciesAndUnderwater() {
        var principalFeed = feed(1, 1);
        var collateralFeed = feed(3, 10);

        assertEquals(BigInteger.valueOf(27_000_000L), LoanFinance.equityInPrincipalCurrency(
                Rational.fromInt(200_000_000), Rational.fromInt(30_000_000),
                principalFeed, collateralFeed, BigInteger.valueOf(100)));

        assertEquals(BigInteger.valueOf(90_000_000L), LoanFinance.equityInCollateralCurrency(
                Rational.fromInt(200_000_000), Rational.fromInt(30_000_000),
                principalFeed, collateralFeed, BigInteger.valueOf(100)));

        // underwater — negative equity, which is what makes floor()'s sign behaviour load-bearing
        assertEquals(BigInteger.valueOf(-18_000_000L), LoanFinance.equityInPrincipalCurrency(
                Rational.fromInt(50_000_000), Rational.fromInt(30_000_000),
                principalFeed, collateralFeed, BigInteger.valueOf(100)));
    }

    /** {@code rational.floor} floors toward negative infinity; {@code ceil} truncates. */
    @Test
    void rationalRoundingMatchesTheStdlib() {
        assertEquals(BigInteger.valueOf(2), Rational.required(5, 2).floor());
        assertEquals(BigInteger.valueOf(-3), Rational.required(-5, 2).floor(), "floor rounds down, not toward zero");
        assertEquals(BigInteger.valueOf(3), Rational.required(13, 5).ceil());
        assertEquals(BigInteger.valueOf(3), Rational.required(15, 5).ceil());
        assertEquals(BigInteger.valueOf(-2), Rational.required(-5, 2).ceil());
    }

    /**
     * Aiken compares records structurally, so {@code 0/5 != 0/1}. {@code can_liquidate}'s
     * zero-collateral short circuit therefore only fires when the price denominator is 1;
     * otherwise the division below it fails the script.
     */
    @Test
    void zeroCollateralShortCircuitIsStructural() {
        Rational ltv = Rational.required(100, 125);
        assertTrue(LoanFinance.canLiquidate(
                Rational.fromInt(30_000_000), Rational.ZERO, ltv, feed(1, 1), feed(1, 1)),
                "0 * (1/1) is structurally 0/1, so the short circuit fires");

        org.junit.jupiter.api.Assertions.assertThrows(ArithmeticException.class, () ->
                        LoanFinance.canLiquidate(
                                Rational.fromInt(30_000_000), Rational.ZERO, ltv, feed(1, 1), feed(3, 10)),
                "0 * (3/10) is 0/10, which is not structurally rational.zero — the contract fails here");
    }
}
