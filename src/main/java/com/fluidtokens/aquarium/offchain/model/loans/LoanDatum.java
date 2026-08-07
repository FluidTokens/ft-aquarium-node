package com.fluidtokens.aquarium.offchain.model.loans;

import com.fluidtokens.aquarium.offchain.model.AssetType;

import java.math.BigInteger;

/**
 * {@code LoanDatum} from {@code lib/fluidtokens/types/loan.ak} — the inline datum on every
 * UTxO at the loan {@code general_spend} address.
 * <p>
 * Hand-written on purpose. {@code LoanDatum} is <b>not</b> in {@code loans-v4.plutus.json}:
 * loan UTxOs sit at a {@code general_spend} address whose handler takes
 * {@code datumOpt: Option<Data>}, so Aiken never emits a schema for it and the
 * {@code @Blueprint} processor cannot generate it. Field <em>order</em> below is the
 * on-chain constructor order and must not be reordered.
 * <p>
 * All integers are {@link BigInteger} because on-chain {@code Int} is unbounded and this
 * decodes untrusted chain data.
 *
 * @param principalAmount   in the smallest unit of {@link #principalAsset}
 * @param lendDate          POSIX milliseconds
 * @param interestRate      rate/10000, so 0.01% is 1. For perpetuals this is {@code c} in {@code APY = mx + c}
 * @param installmentPeriod hours between installments
 * @param initialGracePeriod hours before repayment starts
 * @param repaymentTimeWindow hours of lateness tolerated before liquidation
 * @param originId          the pool id or request id the loan came from
 */
public record LoanDatum(BigInteger doneRecasts,
                        BigInteger principalAmount,
                        BigInteger lendDate,
                        BigInteger repaidInstallments,
                        BigInteger interestRate,
                        BigInteger totalInstallments,
                        AssetType principalAsset,
                        AssetType principalOracleAsset,
                        BigInteger installmentPeriod,
                        BigInteger initialGracePeriod,
                        LiquidationMode liquidationMode,
                        RepaymentMode repaymentMode,
                        BigInteger repaymentTimeWindow,
                        BigInteger penaltyFeeForLateRepayment,
                        boolean repaymentReceipts,
                        String originId,
                        CollateralAsset collateral) {

    /** Number of fields in the on-chain constructor; a mismatch means the contract type changed. */
    public static final int FIELD_COUNT = 17;
}
