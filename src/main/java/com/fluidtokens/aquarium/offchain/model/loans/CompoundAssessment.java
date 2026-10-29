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
 * @param expectedFee   the validator's compounding fee, {@code CompoundEconomics.compoundingFee}:
 *                      {@code max(escrow * feePerMille / 1000, 1 ADA)} for an ADA principal since
 *                      FluidTokens' 2026-10-26 update, {@code escrow * feePerMille / 1000} for a token —
 *                      integer division, in the <b>principal's own unit</b>, exactly what the pool
 *                      gives up on chain
 * @param expectedFeeLovelace {@link #expectedFee} priced into lovelace via
 *                      {@code PricingService} — the identity for an ada principal, so this equals
 *                      {@link #expectedFee} exactly whenever the principal is ada
 * @param recoveredEscrowLovelace non-principal escrow ADA recovered by the bot ({@code E}); always
 *                      zero for an ADA principal because that coin is the principal itself
 * @param txFee         the exact final-body transaction fee ({@code L})
 * @param poolTopUpLovelace actual ADA added to keep a native-principal pool above minimum ADA
 *                      ({@code T}); always zero for an ADA principal
 * @param net           native: {@code expectedFeeLovelace + E - L - T}; ADA: {@code F - L}
 * @param floor         {@code loans.compound.profit-margin-lovelace}, the operator's stated bound
 * @param priceObservation exact native price observation used, or null for ADA/zero raw fee
 */
public record CompoundAssessment(boolean approved,
                                 CompoundExclusion exclusion,
                                 BigInteger escrow,
                                 long feePerMille,
                                 BigInteger expectedFee,
                                 BigInteger expectedFeeLovelace,
                                 BigInteger recoveredEscrowLovelace,
                                 BigInteger txFee,
                                 BigInteger poolTopUpLovelace,
                                 BigInteger net,
                                 BigInteger floor,
                                 OraclePriceFeed priceObservation) {

    /** Source-compatible constructor for callers that only know the legacy {@code pricedFee-L} view. */
    public CompoundAssessment(boolean approved,
                              CompoundExclusion exclusion,
                              BigInteger escrow,
                              long feePerMille,
                              BigInteger expectedFee,
                              BigInteger expectedFeeLovelace,
                              BigInteger txFee,
                              BigInteger net,
                              BigInteger floor) {
        this(approved, exclusion, escrow, feePerMille, expectedFee, expectedFeeLovelace,
                BigInteger.ZERO, txFee, BigInteger.ZERO, net, floor, null);
    }

    public static CompoundAssessment refused(CompoundExclusion why) {
        return new CompoundAssessment(false, why, null, 0L, null, null,
                null, null, null, null, null, null);
    }

    /**
     * True when the pool owner publishes {@code compoudingFeePerMille = 0}. That no longer means the work
     * pays nothing: since FluidTokens' 2026-10-26 update an ADA pool always pays at least 1 ADA; only a
     * token pool at rate 0 pays no fee (its net is then the escrow ADA the bot recovers).
     */
    public boolean zeroFeePool() {
        return feePerMille == 0L;
    }
}
