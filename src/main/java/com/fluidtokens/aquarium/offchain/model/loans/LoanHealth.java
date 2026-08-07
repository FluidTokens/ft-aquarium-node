package com.fluidtokens.aquarium.offchain.model.loans;

import java.math.BigInteger;

/**
 * The computed state of a loan at a point in time.
 * <p>
 * Split deliberately into two tiers, because they have different dependencies:
 * <ul>
 *   <li>{@link #remainingDebt} and {@link #repaymentLate} come from the datum and the clock alone,
 *       so they are <b>always</b> available.</li>
 *   <li>{@link #currentLtv}, {@link #equity} and {@link #liquidatable} need oracle prices for both
 *       legs. When a feed is missing they are null and {@link #unavailableReason} says why —
 *       never zero, never a guess. A fabricated health factor is worse than an absent one.</li>
 * </ul>
 */
public record LoanHealth(BigInteger remainingDebt,
                         boolean repaymentLate,
                         Rational currentLtv,
                         BigInteger equity,
                         Boolean liquidatable,
                         String unavailableReason) {

    public static LoanHealth debtOnly(BigInteger remainingDebt, boolean repaymentLate, String reason) {
        return new LoanHealth(remainingDebt, repaymentLate, null, null, null, reason);
    }

    public boolean hasPrices() {
        return currentLtv != null;
    }

    /** Current LTV as a percentage, for display only — the decision uses the exact rational. */
    public Double currentLtvPercent() {
        if (currentLtv == null) {
            return null;
        }
        return currentLtv.numerator().doubleValue() / currentLtv.denominator().doubleValue() * 100.0;
    }
}
