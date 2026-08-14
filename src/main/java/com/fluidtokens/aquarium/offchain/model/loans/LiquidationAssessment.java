package com.fluidtokens.aquarium.offchain.model.loans;

import java.math.BigInteger;

/**
 * What {@link LiquidationCandidateScanner} decided about one lender bond: either a buildable
 * liquidation with the numbers a redeemer needs, or a single {@link LiquidationExclusion} reason.
 * <p>
 * This is the shape a T-009 shadow-mode "would-have-liquidated" record carries: one row per
 * scanned bond, always explaining itself, never silently dropped.
 *
 * @param bond            the scanned lender bond
 * @param loan            the joined loan, or {@code null} — only possible when
 *                        {@link #exclusion} is {@link LiquidationExclusion#LOAN_NOT_FOUND}
 * @param exclusion       {@code null} exactly when this assessment is {@link #buildable()}
 * @param detail          human context — which feed and why, which validator check failed, etc.
 * @param remainingDebt   non-null only when buildable; {@code LoanFinance.remainingDebt}
 * @param equity          non-null only when buildable; {@code LoanFinance.redeemerEquity}
 * @param late            non-null only when buildable; {@code LoanFinance.isRepaymentLate}
 * @param liquidationFee  non-null only when buildable; {@code collateralAmount * liquidationFeePerMille / 1000}, floored
 */
public record LiquidationAssessment(LenderBond bond,
                                    Loan loan,
                                    LiquidationExclusion exclusion,
                                    String detail,
                                    BigInteger remainingDebt,
                                    BigInteger equity,
                                    Boolean late,
                                    BigInteger liquidationFee) {

    /** A buildable liquidation, with every number a T-008 redeemer needs. */
    public static LiquidationAssessment buildable(LenderBond bond, Loan loan, String detail,
                                                  BigInteger remainingDebt, BigInteger equity, boolean late,
                                                  BigInteger liquidationFee) {
        return new LiquidationAssessment(bond, loan, null, detail, remainingDebt, equity, late, liquidationFee);
    }

    /** A bond the scanner will not build a liquidation for, and why. */
    public static LiquidationAssessment excluded(LenderBond bond, Loan loan, LiquidationExclusion exclusion,
                                                 String detail) {
        return new LiquidationAssessment(bond, loan, exclusion, detail, null, null, null, null);
    }

    public boolean buildable() {
        return exclusion == null;
    }
}
