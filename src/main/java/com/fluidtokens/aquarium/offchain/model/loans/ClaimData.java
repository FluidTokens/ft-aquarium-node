package com.fluidtokens.aquarium.offchain.model.loans;

import java.math.BigInteger;

/**
 * {@code ClaimData} from {@code lib/fluidtokens/types/loan.ak} — one element of the
 * {@code actionsForEachInput} list in a {@code LoanClaimActionWithdrawRedeemer}, describing how a
 * single loan input is being claimed (liquidated or repaid-at-maturity).
 * <p>
 * Field order below is the on-chain constructor order, taken from the blueprint definition
 * {@code fluidtokens/types/loan/ClaimData} in {@code loans-v4-alltypes.plutus.json} (see
 * {@code LiquidationTxEncoderSchemaTest}) and must not be reordered — {@link
 * com.fluidtokens.aquarium.offchain.service.loans.LiquidationTxEncoder} encodes these positionally.
 * <p>
 * All integers are {@link BigInteger} because on-chain {@code Int} is unbounded and, unlike the
 * decode-only models elsewhere in this package, {@code equity} and {@code remainingDebt} are
 * routinely negative here (a borrower can end up owing more than their collateral is worth).
 *
 * @param lenderBondOutputIndex        index of this input's lender-bond output in the transaction
 * @param collateralOracleRefInputIndex reference-input index of the collateral's oracle feed
 * @param principalOracleRefInputIndex  reference-input index of the principal's oracle feed
 * @param loanId                       hex; the loan's asset name, joining this claim to a specific
 *                                      loan input
 */
public record ClaimData(LiquidationMode liquidationMode,
                        BigInteger lenderBondOutputIndex,
                        BigInteger collateralOracleRefInputIndex,
                        BigInteger principalOracleRefInputIndex,
                        AuthorizationMethod lenderAuth,
                        BigInteger equity,
                        String loanId,
                        BigInteger remainingDebt) {

    /** Number of fields in the on-chain constructor; a mismatch means the contract type changed. */
    public static final int FIELD_COUNT = 8;
}
