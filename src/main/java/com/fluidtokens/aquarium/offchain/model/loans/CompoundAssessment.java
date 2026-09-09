package com.fluidtokens.aquarium.offchain.model.loans;

import java.math.BigInteger;

/**
 * The arithmetic behind one compound decision, kept whole so a log line or an operator can see the
 * numbers that produced it rather than only the verdict.
 *
 * @param approved      whether the candidate clears the operator's stated floor
 * @param exclusion     why not, or {@code null} when approved
 * @param escrow        the principal sitting in the asset manager, in the pool's principal unit
 * @param feePerMille   {@code compoudingFeePerMille} read from the live {@code PoolManagerDatum} —
 *                      <b>set by the pool owner, not by us and not by the protocol</b>
 * @param expectedFee   {@code escrow * feePerMille / 1000}, the on-chain formula, integer division
 * @param txFee         the measured transaction fee this compound would pay
 * @param net           {@code expectedFee - txFee}; negative means the bot pays to do the work
 * @param floor         {@code loans.compound.profit-margin-lovelace}, the operator's stated bound
 */
public record CompoundAssessment(boolean approved,
                                 CompoundExclusion exclusion,
                                 BigInteger escrow,
                                 long feePerMille,
                                 BigInteger expectedFee,
                                 BigInteger txFee,
                                 BigInteger net,
                                 BigInteger floor) {

    public static CompoundAssessment refused(CompoundExclusion why) {
        return new CompoundAssessment(false, why, null, 0L, null, null, null, null);
    }

    /** True when the pool owner has set no compounding fee, so this work pays nothing. */
    public boolean zeroFeePool() {
        return feePerMille == 0L;
    }
}
