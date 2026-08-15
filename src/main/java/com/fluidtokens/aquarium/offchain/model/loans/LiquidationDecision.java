package com.fluidtokens.aquarium.offchain.model.loans;

import java.math.BigInteger;

/**
 * What the liquidation loop decided about one buildable candidate in one cycle.
 * <p>
 * One record per candidate that got as far as being <em>acted on</em>. Bonds the scanner excluded
 * never appear here — they are counted in the run's exclusion histogram instead, because a node
 * watching a few hundred healthy loans would otherwise fill the whole log with
 * {@code NOT_LIQUIDATABLE} every cycle and evict the handful of rows an operator actually needs.
 * <p>
 * A decision is a <em>record of a judgement</em>, never a promise: {@link Outcome#WOULD_SUBMIT}
 * means the transaction was built, priced and found profitable, and then deliberately dropped.
 * Nothing in this workstream signs or submits one.
 *
 * @param decidedAt              epoch millis at which the decision was taken
 * @param loanUtxoRef            {@code txHash#index} of the loan UTxO
 * @param bondUtxoRef            {@code txHash#index} of the lender-bond UTxO
 * @param variant                always {@link #VARIANT} — the action this bot builds. A field
 *                               rather than an implicit fact so a second action (convert, compound)
 *                               can be added later without every stored decision becoming ambiguous
 * @param reason                 the machine-readable half: a {@code Refusal} name, an exception
 *                               class name, or the outcome's own name
 * @param detail                 the human half
 * @param expectedFeeLovelace    the liquidation fee slice priced through the collateral oracle;
 *                               null unless the transaction was built
 * @param txFeeLovelace          the fee the built body actually carries; null unless the
 *                               transaction was built
 * @param marginLovelace         the configured profit margin subtracted from the fee value; null
 *                               unless the transaction was built
 * @param expectedProfitLovelace {@code expectedFee - txFee - margin}; null unless the transaction
 *                               was built
 * @param txHash                 null unless the transaction was built
 * @param txSizeBytes            serialised body+witnesses size; null unless the transaction was built
 * @param txCborHex              the whole unsigned transaction; null unless the transaction was built
 * @param inputs                 null unless the transaction was built
 * @param outputs                null unless the transaction was built
 * @param referenceInputs        null unless the transaction was built
 * @param redeemers              null unless the transaction was built
 */
public record LiquidationDecision(long decidedAt,
                                  String loanId,
                                  String loanUtxoRef,
                                  String bondUtxoRef,
                                  String variant,
                                  Outcome outcome,
                                  String reason,
                                  String detail,
                                  Boolean late,
                                  BigInteger remainingDebt,
                                  BigInteger equity,
                                  BigInteger liquidationFee,
                                  String collateralUnit,
                                  BigInteger expectedFeeLovelace,
                                  BigInteger txFeeLovelace,
                                  BigInteger marginLovelace,
                                  BigInteger expectedProfitLovelace,
                                  String txHash,
                                  Integer txSizeBytes,
                                  String txCborHex,
                                  Integer inputs,
                                  Integer outputs,
                                  Integer referenceInputs,
                                  Integer redeemers) {

    /** The only action this bot builds: the plain {@code Liquidate} of design §8. */
    public static final String VARIANT = "Liquidate";

    /**
     * How far a candidate got. Deliberately has no {@code SUBMITTED}: this workstream cannot reach
     * such a state, and a value nothing can produce is an invitation to write code that does.
     */
    public enum Outcome {
        /** One of the two UTxOs was no longer unspent; nothing was built. */
        NO_UTXO,
        /** {@link com.fluidtokens.aquarium.offchain.service.loans.LiquidateTransactionBuilder}
         *  refused, or building threw. */
        REFUSED,
        /** Built, but the fee slice does not cover the transaction's own fee plus the margin. */
        UNPROFITABLE,
        /** Built and profitable. An armed bot would have submitted this one. */
        WOULD_SUBMIT
    }
}
