package com.fluidtokens.aquarium.offchain.model.loans;

import java.math.BigInteger;

/**
 * The arithmetic behind one LiquidateAndConvert decision, kept whole so a log line or an operator
 * can see the numbers that produced it rather than only the verdict.
 *
 * <p>⚠ <b>Two of these fields are in different currencies and that is the whole point.</b>
 * {@link #liquidationFee} is a quantity of the <em>collateral</em> asset; every other figure is
 * lovelace. {@link #feeValueLovelace} is the bridge between them and it is an <b>oracle valuation of
 * an unrealised position</b>, not cash received. A reader who forgets that will read
 * {@link #net} as realised profit, which it is not.
 *
 * @param approved         whether the candidate clears the operator's stated floor
 * @param exclusion        why not, or {@code null} when approved
 * @param liquidationFee   {@code loanCollateralAmount * liquidationFeePerMille / 1000}, floored —
 *                         <b>in the collateral asset</b>, the residue the validator leaves
 *                         unconstrained and the builder pays to the bot (findings §25.3)
 * @param feeValueLovelace {@link #liquidationFee} at the collateral oracle price, floored;
 *                         income the bot holds in tokens, not ada
 * @param txFee            the measured fee of the built transaction, in lovelace
 * @param orderAdaFunded   the {@code 2_800_000} lovelace {@code lm_liquidate_and_convert_action}
 *                         requires to accompany a NON-ada collateral in the Minswap order output,
 *                         and which leaves with the order; {@code 0} when the collateral is ada
 * @param outlay           {@code txFee + orderAdaFunded} — the ada the bot actually parts with
 * @param net              {@code feeValueLovelace - outlay}
 * @param floor            {@code loans.liquidation.convert.profit-margin-lovelace}
 */
public record ConvertAssessment(boolean approved,
                                ConvertExclusion exclusion,
                                BigInteger liquidationFee,
                                BigInteger feeValueLovelace,
                                BigInteger txFee,
                                BigInteger orderAdaFunded,
                                BigInteger outlay,
                                BigInteger net,
                                BigInteger floor) {

    public static ConvertAssessment refused(ConvertExclusion why) {
        return new ConvertAssessment(false, why, null, null, null, null, null, null, null);
    }

    /**
     * True when the lender set no liquidation fee, so this work pays the bot nothing at all. Not a
     * refusal in itself — {@link ConvertExclusion#NET_BELOW_FLOOR} is what refuses it — but worth
     * saying out loud in a decision log, because the operator can only ever authorise it by stating
     * a negative floor.
     */
    public boolean zeroFeeBond() {
        return liquidationFee != null && liquidationFee.signum() == 0;
    }
}
