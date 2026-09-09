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
 * A decision is a <em>record of a judgement</em>, and only ever a promise when it says
 * {@link Outcome#SUBMITTED}: {@link Outcome#WOULD_SUBMIT} means the transaction was built, priced
 * and found profitable, and then deliberately dropped because one of the submit vetoes fired —
 * which one is in {@link #submitVeto}.
 *
 * @param decidedAt              epoch millis at which the decision was taken
 * @param loanUtxoRef            {@code txHash#index} of the loan UTxO
 * @param bondUtxoRef            {@code txHash#index} of the lender-bond UTxO
 * @param variant                which action this decision is about — {@link #VARIANT} or
 *                               {@link #VARIANT_CONVERT}. A field rather than an implicit fact so a
 *                               second action can be added without every stored decision becoming
 *                               ambiguous. ⚠ <b>It was hardcoded to the plain value for the whole
 *                               time the convert path was live</b>, so every convert decision would
 *                               have recorded itself as a plain Liquidate — the exact ambiguity this
 *                               field exists to prevent, in the field that exists to prevent it
 * @param reason                 the machine-readable half: a {@code Refusal} name, an exception
 *                               class name, or the outcome's own name
 * @param detail                 the human half
 * @param submitVeto             which of the seven submit vetoes stopped this candidate being
 *                               submitted, or null — either because the candidate never reached the
 *                               veto chain, or because every veto passed and a submission was
 *                               attempted
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
                                  Integer redeemers,
                                  String submitVeto) {

    /** The plain {@code Liquidate} of design §8: the bot EARNS a fee slice and keeps the collateral. */
    public static final String VARIANT = "Liquidate";

    /**
     * {@code LiquidateAndPayInAdvance}: the bot <b>PAYS the lender in ada out of its own wallet</b>
     * and acquires the collateral token. <b>The opposite sign to {@link #VARIANT}</b> — a purchase,
     * not a fee — which is why the two must never share a label in the durable record.
     */
    public static final String VARIANT_CONVERT = "LiquidateAndPayInAdvance";

    /**
     * {@code LiquidateAndConvert}: the bot builds a Minswap swap order inside the liquidation and
     * <b>fronts nothing</b> — the batcher supplies the principal, and both the proceeds and any refund
     * land in the LENDER's asset manager (findings §25.2).
     *
     * <p>⚑ A third label, not a reuse of {@link #VARIANT_CONVERT}. That one is named "convert" for
     * historical reasons and actually means <em>pay-in-advance</em>, where the bot PAYS the lender out
     * of its own wallet and acquires the collateral — <b>the opposite sign</b>. Two paths that a
     * decision log cannot tell apart are two paths an operator cannot reason about, and the older name
     * is exactly the confusion this must not inherit.
     */
    public static final String VARIANT_CONVERT_MINSWAP = "LiquidateAndConvert";

    /**
     * <b>Temporary back-compatibility. Remove at T-010.</b>
     * <p>
     * The 24-argument shape this record had before {@link #submitVeto} existed. It is here for one
     * reason only: {@code LiquidationDecisionsEndpointTest} constructs decisions positionally and was
     * outside the file allowlist of the slice that added the field, so changing the canonical
     * constructor's arity would have meant editing a file that slice was not permitted to touch.
     * <p>
     * It has no production caller. When T-010 opens that test file, delete this constructor and let
     * every caller state its veto — {@code null} included — explicitly.
     */
    public LiquidationDecision(long decidedAt, String loanId, String loanUtxoRef, String bondUtxoRef,
                               String variant, Outcome outcome, String reason, String detail,
                               Boolean late, BigInteger remainingDebt, BigInteger equity,
                               BigInteger liquidationFee, String collateralUnit,
                               BigInteger expectedFeeLovelace, BigInteger txFeeLovelace,
                               BigInteger marginLovelace, BigInteger expectedProfitLovelace,
                               String txHash, Integer txSizeBytes, String txCborHex, Integer inputs,
                               Integer outputs, Integer referenceInputs, Integer redeemers) {
        this(decidedAt, loanId, loanUtxoRef, bondUtxoRef, variant, outcome, reason, detail, late,
                remainingDebt, equity, liquidationFee, collateralUnit, expectedFeeLovelace,
                txFeeLovelace, marginLovelace, expectedProfitLovelace, txHash, txSizeBytes, txCborHex,
                inputs, outputs, referenceInputs, redeemers, null);
    }

    /**
     * How far a candidate got.
     * <p>
     * {@link #SUBMITTED} and {@link #SUBMIT_FAILED} are the only two states that imply the node
     * transmitted anything, and reaching either takes all eight vetoes of
     * {@code LiquidationExecutor} passing. Everything else is a record of a judgement.
     */
    public enum Outcome {
        /** One of the two UTxOs was no longer unspent; nothing was built. */
        NO_UTXO,
        /** {@link com.fluidtokens.aquarium.offchain.service.loans.LiquidateTransactionBuilder}
         *  refused, or building threw. */
        REFUSED,
        /** Built, but the fee slice does not cover the transaction's own fee plus the margin. */
        UNPROFITABLE,
        /**
         * Built and profitable, and not submitted because the bot is not armed for it — the mode is
         * not live, the arming flag is off, or the network is not preview. {@link #submitVeto} names
         * which. This is the shadow verdict: what an armed bot would have done.
         */
        WOULD_SUBMIT,
        /**
         * Built, profitable, armed — and still not submitted, because a veto about <em>this
         * candidate at this instant</em> fired: the transaction is over maxTxSize, an oracle window
         * is about to close, a UTxO moved, or one of those could not be established at all.
         * {@link #submitVeto} names which. Every ambiguous case lands here.
         */
        SUBMIT_VETOED,
        /** Signed and accepted by the backend. {@code txHash} is the hash that was submitted. */
        SUBMITTED,
        /** Signed and transmitted, and the backend rejected it. {@code detail} is its response. */
        SUBMIT_FAILED,
        /**
         * Skipped because an earlier failure quarantined this loan UTxO and the quarantine has not
         * yet lapsed. {@code detail} carries the remaining hold.
         *
         * <p>⚠ <b>This is the one outcome that says nothing about the candidate.</b> The other seven
         * are judgements about the loan; this is a statement about the bot's own recent history, and
         * an operator reading it as "not liquidatable" would be reading it wrong.
         *
         * <p>It exists because the quarantine skip was the <b>only silent exit</b> from
         * {@code consider()} — five of the six paths recorded a decision and this one returned with a
         * debug line. A held loan therefore looked exactly like a loan that was never a candidate,
         * which is the same ambiguity a quiet market has with a dead deployment: <b>absence of a
         * record is not a record of absence.</b> Confirmed 2026-08-25 by waiting out a real 30-minute
         * quarantine and observing the first decision land at the first cycle past expiry.
         */
        QUARANTINED
    }
}
