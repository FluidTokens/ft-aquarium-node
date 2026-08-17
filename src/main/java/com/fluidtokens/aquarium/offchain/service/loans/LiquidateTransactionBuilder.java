package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.api.util.ValueUtil;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.AssetManagerDatumWithToken;
import com.fluidtokens.aquarium.offchain.model.loans.ClaimData;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationMode;
import com.fluidtokens.aquarium.offchain.model.loans.Loan;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.model.loans.Rational;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import com.fluidtokens.aquarium.offchain.service.TransactionInputComparator;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.conversions.CardanoConverters;

import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.stream.Stream;

/**
 * Assembles the T-008 Phase-1 {@code Liquidate} transaction (design §8) from N already-assessed
 * loans and the UTxOs the caller has resolved for them.
 *
 * <h2>What this class is, and is not</h2>
 * It is <b>deterministic</b>: every input, output, reference input and index is computed from the
 * arguments, the {@link LoansContractRegistry} derivation, and the caller's
 * {@link UtxoSupplier}/{@link ProtocolParamsSupplier}. It never takes a {@code BackendService},
 * never submits, and never signs.
 * <p>
 * The one thing it may reach the network for is <em>script cost evaluation</em>: the optional
 * {@link TransactionEvaluator} described under "Ex-units are measured, not guessed" below. That
 * interface has a single operation, {@code evaluateTx(cbor, inputUtxos)}, and no submit method of any
 * kind, so granting it does not grant a submission path — {@code transactionProcessor} stays
 * {@code null} in {@link #complete}, which is what makes this class structurally incapable of
 * submitting rather than merely disinclined to.
 * <p>
 * It is <b>not</b> a health-factor engine. Every number that reaches a redeemer —
 * {@code remainingDebt}, {@code equity}, {@code liquidationFee} — is copied verbatim from the
 * {@link LiquidationAssessment} (findings §7 D8: those are bot-supplied inputs the validator
 * checks, so recomputing them here and using the new value would be a silent divergence from what
 * the scanner decided). {@link LoanFinance} is re-run only as a <em>guard</em> (V4 below): if the
 * recomputation disagrees with the assessment, the batch is refused, never rewritten.
 *
 * <h2>Refusal is the default</h2>
 * A wrongly built liquidation moves real value and burns an NFT. Every ambiguous case therefore
 * throws {@link RefusedException} rather than producing a transaction. The named pre-flight
 * checks are:
 * <ul>
 *   <li><b>V1</b> — the assessment must be {@link LiquidationAssessment#buildable()}.</li>
 *   <li><b>V2</b> — {@code remainingDebt > 0}, {@code equity >= 0}, and
 *       {@code collateralAmount - equity - liquidationFee >= 0}; a negative payout is
 *       chain-unsatisfiable.</li>
 *   <li><b>V3</b> — every non-unit feed must satisfy {@link OraclePriceFeed#usableOver} for the
 *       <em>whole</em> transaction window (never {@code usableAt}: the chain checks containment of
 *       the interval, not of an instant), with at least the caller's margin of window left after
 *       {@code validTo}.</li>
 *   <li><b>V4</b> — {@code LoanFinance.remainingDebt} must equal the assessment's number at both
 *       ends of the window, and {@code late || can_liquidate} must hold at both ends. The chain's
 *       evaluation bound is not knowable off chain, so invariance across the interval is demanded
 *       rather than agreement at one point.</li>
 *   <li><b>V5</b> — structural assertions re-read off the <em>built</em> transaction body: index
 *       uniqueness and counts (D5), byte-identical bond echo (D6), asset-manager output shape
 *       (D7), and that every index in every redeemer points at what it claims.</li>
 *   <li><b>V6</b> — modelling gaps that must never be guessed at: a Charli3 feed with no provider
 *       reference input, a {@code Pooled}/{@code Orcfax} feed, a pointer stake credential, and
 *       {@code repaymentReceipts} together with a non-zero equity (the receipt NFT mint is not
 *       modelled anywhere in this repo).</li>
 *   <li><b>V7</b> — action scope: the two datum fields the plain {@code Liquidate} path
 *       <em>requires</em> to be false. {@code lm_liquidate_action.ak:122} is a hard
 *       {@code expect equityInPrincipalCurrency == False}, and {@code :143} makes
 *       {@code shouldLiquidationConvertToPrincipal == False} a conjunct of the same check. The
 *       scanner already excludes both (D2 and §7.5), so this is the builder's own last line rather
 *       than the only one.</li>
 *   <li><b>V8</b> — deployment scope: {@code equity > 0} is refused, because no output layout
 *       <em>this builder emits</em> satisfies both validators (see
 *       {@link Refusal#POSITIVE_EQUITY_UNSUPPORTED}). This is the only veto here that is a statement
 *       about <em>this deployment</em> rather than about the design, so it is checked last of the
 *       per-loan vetoes: a batch that is wrong for a permanent reason reports that permanent
 *       reason.</li>
 * </ul>
 *
 * <h2>Ex-units are measured, not guessed</h2>
 * A redeemer's declared ex-units are not checked by the mempool: a transaction that under-declares is
 * accepted, lands on chain, and then exhausts its budget during on-chain evaluation — phase 2, fee and
 * collateral forfeit. cardano-client-lib fills every redeemer with a placeholder (10000 mem, and
 * 10000 or 1000 steps) and only overwrites it from a {@link TransactionEvaluator}; with no evaluator
 * set, {@code ScriptCostEvaluators} throws "Transaction evaluator is not set" and {@code QuickTxBuilder}
 * swallows it, because {@code ignoreScriptCostEvaluationError} defaults to {@code true}. The measured
 * cost of one ada/ada liquidation is ~2.26M mem / ~778M steps, so the placeholders under-declare by
 * two to five orders of magnitude.
 * <p>
 * So the evaluator is <em>optional but load-bearing</em>:
 * <ul>
 *   <li><b>Supplied</b> (the production wiring in {@code YaciConfig}) — it is set with
 *       {@code withTxEvaluator}, and {@code ignoreScriptCostEvaluationError(false)} makes a failed
 *       evaluation a {@link Refusal#SCRIPT_COST_EVALUATION_FAILED} instead of a {@code log.warn}
 *       followed by a transaction that would burn collateral. Refusing is the safe direction: this
 *       class already refuses everything it is not certain about, and the scheduled loop catches
 *       {@link RefusedException} per candidate.</li>
 *   <li><b>Absent</b> — the offline test rigs, which have no network and evaluate separately against
 *       the real PlutusV3 machine ({@code LiquidateDryEvalTest}). Behaviour is then exactly as it was:
 *       placeholder ex-units, no throw. Nothing built this way may be submitted, which is a property of
 *       the wiring: the only caller that submits is {@code LiquidationExecutor}, and the only builder
 *       Spring gives it is the one with the evaluator.</li>
 * </ul>
 *
 * <h2>Index resolution</h2>
 * Cardano orders transaction inputs and reference inputs canonically by {@code (txHash, index)}
 * before a script ever sees them, so every index is computed from the canonically sorted sets
 * (the {@link TransactionInputComparator} / {@code resolveRefIndexes} pattern
 * {@code ScheduledTransactionService} already uses), never from the order things were added.
 * <p>
 * Outputs keep body order, but the body is not only this builder's: cardano-client-lib prepends a
 * dummy output whenever a transaction has withdrawals and appends the change output. So
 * {@code lenderBondOutputIndex} is not predicted at all — the transaction is assembled twice, the
 * first time only to observe where the bond echoes landed. V5 re-derives all of it from the
 * finished body, and finds the asset-manager outputs by their datum rather than by an offset.
 *
 * <h2>What on-chain evaluation settled</h2>
 * Claims made here are structural, not validator-correctness. {@code LiquidateDryEvalTest} runs the
 * transaction this class builds through the real PlutusV3 machine against the deployed validators,
 * and that is what settled the four things this javadoc used to leave open:
 * <ul>
 *   <li>{@code LoanMintRedeemer.isPoolOrigin}/{@code originWithdrawRedeemerIndex} — accepted.
 *       {@code loan.ak}'s {@code check_mint} only reads them when something is <em>minted</em>
 *       ({@code quantity > 0}); a burn-only mint field short-circuits to {@code True}.</li>
 *   <li>The two asset-manager action byte strings — accepted (see {@link LiquidationTxEncoder}).</li>
 *   <li>Writing zero for an ada leg's oracle reference index — accepted:
 *       {@code retrieve_oracle_data} returns the synthesised 1:1 feed from its
 *       {@code expectedTokenPolicyId == ""} branch without reading the index.</li>
 *   <li>The output layout — <b>corrected</b>. The bond echoes have to go out in bond-input order,
 *       not loan-input order; see the comment at their emission site.</li>
 * </ul>
 * One thing evaluation settled the other way: a batch containing a loan with a <em>positive
 * equity</em> is not satisfiable in <b>either output layout this builder can emit</b>.
 * {@code loan_claim_action} still reads the index-th entry of the asset-manager-filtered output list
 * directly, while {@code lm_liquidate_action} now reaches it through
 * {@code assetOutputIndexes[index]} — so the two no longer <em>structurally</em> collide, but this
 * builder emits identity indexes, and at the identity both layouts still fail. That is V8 above: such
 * a batch is refused rather than built, and the caller no longer has to remember not to schedule one.
 * <b>Whether some other layout — a non-identity {@code assetOutputIndexes} paired with a reordered
 * output list — satisfies both validators is an open question that has not been tested.</b> V8 is
 * therefore a statement about what this builder emits, not a proof of impossibility; it is the veto
 * to lift if that question is ever answered yes.
 */
@Slf4j
public final class LiquidateTransactionBuilder {

    /** The reason a batch was refused. Every value is produced by exactly one named check. */
    public enum Refusal {
        /** V1 — the scanner excluded this bond; only a buildable assessment may be built. */
        NOT_BUILDABLE,
        /** The caller handed an empty batch. */
        EMPTY_BATCH,
        /** Two entries in the batch share a loan id; the loan↔bond pairing would be ambiguous. */
        DUPLICATE_LOAN,
        /** The supplied UTxO is not the one the assessment was made against. */
        UTXO_DOES_NOT_MATCH_ASSESSMENT,
        /** The fee/collateral wallet UTxO must hold ada only and carry no datum or script. */
        WALLET_UTXO_NOT_ADA_ONLY,
        /** V2 — a loan with no debt left cannot be liquidated. */
        NON_POSITIVE_REMAINING_DEBT,
        /** V2 — negative equity would be an underpayment to the borrower, not a refund. */
        NEGATIVE_EQUITY,
        /** V2 — collateral cannot cover equity plus the liquidation fee. */
        COLLATERAL_CANNOT_COVER_EQUITY_AND_FEE,
        /** V2 — a negative fee is not a discount, it inflates the payout past the collateral. */
        NEGATIVE_LIQUIDATION_FEE,
        /** V4 — the fee in the assessment is not the one the bond's per-mille rate produces. */
        LIQUIDATION_FEE_NOT_REPRODUCIBLE,
        /** V4 — the equity in the assessment is not the one the loan and the feeds produce. */
        EQUITY_NOT_REPRODUCIBLE,
        /** No oracle entry was supplied for a non-ada leg. */
        ORACLE_ENTRY_MISSING,
        /**
         * The oracle validator <em>is</em> in the bundled blueprint
         * ({@code oracle.oracle}, unapplied hash {@code 642597518a03f07dabc45af7bd6658622fc6c27254ec67c18984c7c0}),
         * but it takes eight parameters — {@code verification_keys}, {@code threshold},
         * {@code charlie_specs}, {@code orcfax_specs} and the asset identifiers — whose deployed
         * values FluidTokens does not publish. Without them the applied script cannot be
         * reconstructed, and the unapplied one hashes to a different credential than the withdrawal
         * is made from. So there is no witness fallback for an oracle, only its published reference
         * script.
         */
        ORACLE_REFERENCE_SCRIPT_MISSING,
        /** The registry's reward address is not the one this network derives from its credential. */
        ORACLE_REWARD_ADDRESS_MISMATCH,
        /** V6 — a Charli3 feed is validated against a provider UTxO the registry did not publish. */
        @UnreachableFromScannedBatch(
                scannerFilter = "OracleEntry.usableForLiquidation",
                reason = "usableForLiquidation() requires charlieProviderReferenceInput != null for a "
                        + "PRICE_DATA_CHARLIE feed")
        CHARLIE_PROVIDER_REFERENCE_INPUT_MISSING,
        /** V6 — {@code Pooled} and {@code PriceDataOrcfax} feeds are not modelled. */
        @UnreachableFromScannedBatch(
                scannerFilter = "OracleEntry.usableForLiquidation",
                reason = "usableForLiquidation() is false for POOLED and PRICE_DATA_ORCFAX")
        UNSUPPORTED_ORACLE_VARIANT,
        /** V3 — the feed does not cover the whole transaction validity interval. */
        ORACLE_FEED_NOT_USABLE_OVER_WINDOW,
        /** V3 — too little of the feed's window is left after {@code validTo}. */
        ORACLE_WINDOW_MARGIN_TOO_SMALL,
        /** V4 — the debt is not the same at both ends of the window, or differs from the assessment. */
        REMAINING_DEBT_NOT_INVARIANT,
        /** V4 — D9's {@code late || can_liquidate} does not hold across the whole window. */
        NOT_LIQUIDATABLE_OVER_WINDOW,
        /** V4 — the finance re-computation raised an arithmetic failure the chain would too. */
        HEALTH_NOT_COMPUTABLE,
        /** V6 — a pointer stake credential cannot be turned into an output address here. */
        POINTER_STAKE_CREDENTIAL,
        /** The stake credential field is not an {@code Option<StakeCredential>}. */
        UNDECODABLE_STAKE_CREDENTIAL,
        /** V6 — a repayment-receipt NFT would have to be minted, and that mint is not modelled. */
        @UnreachableFromScannedBatch(
                scannerFilter = "LiquidationExclusion.POSITIVE_EQUITY_UNSUPPORTED",
                reason = "reachable only with equity > 0, which the scanner already excludes on; "
                        + "checked BEFORE V8 in vet(), so V8's own POSITIVE_EQUITY_UNSUPPORTED veto "
                        + "does not mask it")
        REPAYMENT_RECEIPTS_WITH_EQUITY,
        /**
         * V7 — findings §7.1 D2: the loan's {@code Liquidation.equityInPrincipalCurrency} is true, and
         * {@code lm_liquidate_action.ak:122} is a hard {@code expect equityInPrincipalCurrency == False}.
         * Nothing this builder can emit satisfies it.
         */
        @UnreachableFromScannedBatch(
                scannerFilter = "LiquidationExclusion.EQUITY_IN_PRINCIPAL_CURRENCY",
                reason = "the scanner already excludes on D2 (LiquidationExclusion.EQUITY_IN_PRINCIPAL_CURRENCY)")
        EQUITY_IN_PRINCIPAL_CURRENCY,
        /**
         * V7 — findings §7.5: the lender bond's {@code shouldLiquidationConvertToPrincipal} is true, and
         * {@code lm_liquidate_action.ak:143} makes {@code shouldLiquidationConvertToPrincipal == False}
         * a conjunct of the check the plain {@code Liquidate} path runs. Converting proceeds to the
         * principal currency is a different action, not this one.
         */
        @UnreachableFromScannedBatch(
                scannerFilter = "LiquidationExclusion.CONVERSION_TO_PRINCIPAL_REQUIRED",
                reason = "the scanner already excludes on §7.5 (LiquidationExclusion.CONVERSION_TO_PRINCIPAL_REQUIRED)")
        CONVERSION_TO_PRINCIPAL_REQUIRED,
        /**
         * V8 — the assessment's {@code equity} is positive, and <b>no output layout this builder
         * emits</b> satisfies both validators of the <em>currently deployed</em> pin {@code ff005fb}.
         * <p>
         * The two validators reach into the same list — the outputs filtered by
         * {@code get_outputs_to_smart_credential(..)} — but no longer at the same position:
         * {@code lm_liquidate_action.ak:87-91} reads
         * {@code safe_list_at(assetOutputs, safe_list_at(redeemer.assetOutputIndexes, index))} and
         * requires {@code constants.action_claimed_collateral} in it ({@code :160}), while
         * {@code loan_claim_action.ak:275-284} still reads {@code index} of that list directly
         * (unchanged at {@code ff005fb}) and requires
         * {@code constants.action_partial_liquidation_compensation}. So the redeemer indirection means
         * the collision is no longer structural. This builder, however, emits identity
         * {@code assetOutputIndexes} — which puts both validators back on the same slot, wanting
         * mutually exclusive datums, and {@code loan_claim_action.ak:273}'s
         * {@code or { inputAction.equity == 0, .. }} is then the only way through. Pinned by
         * {@code LiquidateDryEvalTest}'s
         * {@code positiveEquityIsRefusedInBothLayoutsThisBuilderCanEmit}, which runs both layouts
         * against the deployed scripts.
         * <p>
         * <b>Not a proof of impossibility.</b> Whether a non-identity {@code assetOutputIndexes} over
         * a reordered output list satisfies both validators is untested and deliberately left open;
         * this veto records what this builder emits. It is the veto to lift if that question is
         * answered yes, or if a redeploy removes the constraint another way.
         */
        @UnreachableFromScannedBatch(
                scannerFilter = "LiquidationExclusion.POSITIVE_EQUITY_UNSUPPORTED",
                reason = "the scanner already excludes on positive equity (LiquidationExclusion.POSITIVE_EQUITY_UNSUPPORTED)")
        POSITIVE_EQUITY_UNSUPPORTED,
        /** D6 needs the bond datum echoed byte for byte; this one does not survive a round trip. */
        BOND_DATUM_NOT_BYTE_IDENTICAL,
        /** The requested window does not contain at least one whole slot. */
        VALIDITY_WINDOW_INVALID,
        /** V5 — the finished body does not match what the redeemers claim about it. */
        STRUCTURAL_ASSERTION_FAILED,
        /**
         * The script-cost evaluator could not price the transaction, so its redeemers would have kept
         * placeholder ex-units. Distinct from {@link #TRANSACTION_NOT_BUILDABLE} because the two mean
         * opposite things to an operator: this one says the <em>evaluator</em> failed — Blockfrost down,
         * rate-limited, or rejecting the request — and is usually transient and affects every candidate
         * at once, while {@code TRANSACTION_NOT_BUILDABLE} says <em>this candidate</em> could not be
         * assembled. The detail carries the evaluator's own root-cause text, which cardano-client-lib
         * otherwise flattens into a bare "Error while evaluating script cost".
         * <p>
         * Refusals are deliberately not quarantined, so during an evaluator outage every candidate
         * re-attempts a remote evaluation every cycle with no backoff. That is a T-010 question, not
         * this one's: the direction is already safe (nothing is built, nothing is submitted), it is only
         * wasteful.
         */
        SCRIPT_COST_EVALUATION_FAILED,
        /** cardano-client-lib could not balance or assemble the transaction. */
        TRANSACTION_NOT_BUILDABLE
    }

    /** Thrown instead of returning a transaction whose correctness is not certain. */
    @Getter
    public static final class RefusedException extends RuntimeException {

        private final Refusal reason;

        RefusedException(Refusal reason, String detail) {
            super(reason + ": " + detail);
            this.reason = reason;
        }

        RefusedException(Refusal reason, String detail, Throwable cause) {
            super(reason + ": " + detail, cause);
            this.reason = reason;
        }
    }

    /**
     * One loan to liquidate: the scanner's verdict plus the two UTxOs that will be spent for it.
     *
     * @param assessment must be {@link LiquidationAssessment#buildable()}; its numbers are what the
     *                   redeemers carry
     * @param loanUtxo   the loan UTxO named by {@code assessment.loan()}
     * @param bondUtxo   the lender-bond UTxO named by {@code assessment.bond()}
     */
    public record LoanLiquidation(LiquidationAssessment assessment, Utxo loanUtxo, Utxo bondUtxo) {
    }

    /**
     * Published reference-script UTxOs, one per invoked validator. A {@code null} field means "not
     * published — carry this script in the witness set instead". Both paths are supported because
     * FluidTokens has not confirmed the v4 reference-script coordinates (design §10) and a bot that
     * can only do one of the two cannot run until they do.
     *
     * @param assetManager the asset-manager script is <em>not</em> invoked by a plain
     *                     {@code Liquidate} — it only guards the outputs — so this is accepted (it
     *                     is listed in design §8) and referenced for fee accuracy, never attached
     */
    public record ReferenceScripts(TransactionInput loan,
                                   TransactionInput loanSpend,
                                   TransactionInput lenderManager,
                                   TransactionInput lenderManagerSpend,
                                   TransactionInput loanClaimAction,
                                   TransactionInput lmLiquidateAction,
                                   TransactionInput assetManager) {

        /** Nothing published: every script travels in the witness set. */
        public static ReferenceScripts none() {
            return new ReferenceScripts(null, null, null, null, null, null, null);
        }
    }

    /**
     * Everything one {@code Liquidate} transaction needs. Nothing here is looked up: the caller
     * (T-009's scheduler) resolves it all, which is what keeps this class deterministic.
     *
     * @param oraclesByOracleTokenUnit  keyed by {@link OracleEntry#oracleToken()}{@code .toUnit()},
     *                                  because that — not the priced asset — is what a loan datum
     *                                  points at and what {@code retrieve_oracle_data} matches the
     *                                  reference input against
     * @param walletUtxo                an ada-only bot UTxO, for fees and to carry the fee slice of
     *                                  the collateral out as change
     * @param oracleWindowMarginMillis  how much of each feed's window must still be unused after
     *                                  {@code validToMillis}; the caller owns this policy, this
     *                                  class only enforces it
     */
    public record Request(List<LoanLiquidation> liquidations,
                          Utxo configUtxo,
                          Utxo lmConfigUtxo,
                          Map<String, OracleEntry> oraclesByOracleTokenUnit,
                          Utxo walletUtxo,
                          String changeAddress,
                          long validFromMillis,
                          long validToMillis,
                          long oracleWindowMarginMillis,
                          ReferenceScripts referenceScripts) {
    }

    /** The unit redeemer {@code Constr 0 []} the {@code general_spend} handlers take. */
    static final PlutusData GENERAL_SPEND_REDEEMER =
            ConstrPlutusData.builder().alternative(0).data(ListPlutusData.of()).build();

    private final LoansContractRegistry registry;
    private final Network network;
    private final CardanoConverters converters;
    private final UtxoSupplier utxoSupplier;
    private final ProtocolParamsSupplier protocolParamsSupplier;

    /**
     * Where the redeemers' ex-units come from, or {@code null} for "nowhere" — see "Ex-units are
     * measured, not guessed" in the class javadoc. Nullable rather than optional because the two
     * states are not a preference: with it, the transaction is priced and a failed evaluation refuses;
     * without it, the transaction carries placeholders and must not be submitted.
     */
    private final TransactionEvaluator scriptCostEvaluator;

    /**
     * The offline builder: no evaluator, so redeemers keep cardano-client-lib's placeholder ex-units.
     * For the test rigs, which evaluate separately. Production goes through the six-argument
     * constructor.
     */
    public LiquidateTransactionBuilder(LoansContractRegistry registry,
                                       Network network,
                                       CardanoConverters converters,
                                       UtxoSupplier utxoSupplier,
                                       ProtocolParamsSupplier protocolParamsSupplier) {
        this(registry, network, converters, utxoSupplier, protocolParamsSupplier, null);
    }

    /**
     * @param scriptCostEvaluator may be {@code null}; when it is not, every redeemer's ex-units are
     *                            the evaluator's numbers and a failed evaluation is a
     *                            {@link Refusal#SCRIPT_COST_EVALUATION_FAILED}. A
     *                            {@link TransactionEvaluator} cannot submit — that is the whole reason
     *                            this parameter is that type and not a {@code TransactionProcessor} or
     *                            a {@code BackendService}.
     */
    public LiquidateTransactionBuilder(LoansContractRegistry registry,
                                       Network network,
                                       CardanoConverters converters,
                                       UtxoSupplier utxoSupplier,
                                       ProtocolParamsSupplier protocolParamsSupplier,
                                       TransactionEvaluator scriptCostEvaluator) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.network = Objects.requireNonNull(network, "network");
        this.converters = Objects.requireNonNull(converters, "converters");
        this.utxoSupplier = Objects.requireNonNull(utxoSupplier, "utxoSupplier");
        this.protocolParamsSupplier = Objects.requireNonNull(protocolParamsSupplier, "protocolParamsSupplier");
        this.scriptCostEvaluator = scriptCostEvaluator;
    }

    // ---- the one entry point ---------------------------------------------------------------

    /**
     * Builds the unsigned {@code Liquidate} transaction for this batch, or refuses.
     *
     * @throws RefusedException whenever anything about the batch is not certain
     */
    public Transaction build(Request request) {
        return build(request, true);
    }

    /**
     * The same build with V8 — and only V8 — disabled. Package-private, no production caller, and
     * deliberately not part of the public surface.
     * <p>
     * V8 refuses a positive equity because no output layout this builder emits satisfies both
     * validators of the deployed pin ({@link Refusal#POSITIVE_EQUITY_UNSUPPORTED}); the transaction it
     * would have built is still the structurally correct one, and becomes submittable the day the
     * constraint lifts. Two kinds of test need that transaction to exist:
     * {@code LiquidateDryEvalTest}, which is the <em>evidence</em> for V8 — it runs both output layouts
     * this builder can emit through the real PlutusV3 machine and shows each validator refusing the
     * other's — and the structural anatomy tests in
     * {@code LiquidateTransactionBuilderTest}, which pin the equity output's datum, value and place in
     * the body. Routing those through this seam keeps them proving what they proved before V8 existed.
     * <p>
     * Every other veto still applies. In particular V7's two {@code expect}-backed refusals are
     * permanent facts about the validators rather than about this deployment, so nothing bypasses them.
     */
    Transaction buildIgnoringPositiveEquityVeto(Request request) {
        return build(request, false);
    }

    private Transaction build(Request request, boolean vetoPositiveEquity) {
        checkRequestShape(request);

        long validFrom = request.validFromMillis();
        long validTo = request.validToMillis();

        // Pass 1 — vet every loan on its own. Nothing about the transaction shape yet.
        List<VettedLoan> vetted = new ArrayList<>();
        for (LoanLiquidation liquidation : request.liquidations()) {
            vetted.add(vet(liquidation, request, validFrom, validTo, vetoPositiveEquity));
        }

        // Pass 2 — canonical ordering. The loan inputs sorted alone are in the same relative order
        // as they will be inside the ledger's sorted input list, so filtering that list by the loan
        // credential yields exactly this sequence.
        List<VettedLoan> loanOrder = vetted.stream()
                .sorted(Comparator.comparing(v -> inputOf(v.loanUtxo()), new TransactionInputComparator()))
                .toList();
        List<VettedLoan> bondOrder = vetted.stream()
                .sorted(Comparator.comparing(v -> inputOf(v.bondUtxo()), new TransactionInputComparator()))
                .toList();
        List<Long> lenderBondInputIndexes = loanOrder.stream()
                .map(v -> (long) bondOrder.indexOf(v))
                .toList();

        // Pass 3 — reference inputs, again canonically ordered before any index is read off them.
        List<OracleEntry> oracles = distinctOracles(loanOrder);
        List<TransactionInput> refInputs = referenceInputs(request, oracles);

        int configRefIndex = refIndex(refInputs, inputOf(request.configUtxo()), "main config");
        int lmConfigRefIndex = refIndex(refInputs, inputOf(request.lmConfigUtxo()), "lm config");

        // Pass 4 — the output indexes, taken off a finished body rather than predicted.
        //
        // This builder controls the order it adds outputs in, but not where they end up:
        // cardano-client-lib prepends a dummy output of its own whenever a transaction has
        // withdrawals (to trigger input selection) and appends the change output. Rather than
        // encode that offset — a library implementation detail that could move under us and would
        // silently mis-aim `lenderBondOutputIndex` at real money if it did — the transaction is
        // assembled once with placeholder indexes purely to observe the layout, the real indexes
        // are read off that body, and it is assembled again.
        //
        // The asset-output indexes are observed from the same probe, for the same reason: they too
        // are positions in the finished body's output sequence (filtered by the asset-manager spend
        // credential — see assetOutputIndexes), and nothing here may predict where cardano-client-lib
        // put things. As it happens this builder emits every collateral output before every equity
        // output, so the answer is currently the identity [0..n-1] — which is exactly why it is
        // observed rather than written as a literal: an identity that is only true by today's
        // emission order must not be encoded as if it were a rule.
        //
        // The two bodies are not identical — only the probe's redeemers hold placeholder indexes, and
        // only the second one is script-costed, so they differ in ex-units, fee and size. So the
        // reason the indexes read off the first are safe to use in the second is NOT that the two
        // bodies are the same: it is V5, which re-derives every index from the FINISHED body and
        // refuses (STRUCTURAL_ASSERTION_FAILED) if any of them points at something other than what
        // its redeemer claims. That holds for the asset-output indexes as much as for the bond-output
        // ones: V5, not the probe, is the guarantee.
        List<Long> placeholders = LongStream.range(0, loanOrder.size()).boxed().toList();
        // The probe is deliberately not script-costed: its claim redeemers name output indexes that
        // are not the real ones yet, so it is a transaction the validators refuse. See complete().
        Transaction probe = complete(request, assemble(request, loanOrder, bondOrder,
                claims(loanOrder, refInputs, placeholders), lenderBondInputIndexes, placeholders,
                oracles, refInputs, configRefIndex, lmConfigRefIndex), false);

        List<Long> bondOutputIndexes = locateBondOutputs(probe, loanOrder);
        List<ClaimData> claims = claims(loanOrder, refInputs, bondOutputIndexes);
        List<Long> assetOutputIndexes = assetOutputIndexes(probe.getBody().getOutputs(),
                registry.getAssetManagerSpendScriptHash(),
                loanOrder.stream().map(v -> collateralDatum(v).serializeToHex()).toList());

        Transaction transaction = complete(request, assemble(request, loanOrder, bondOrder, claims,
                lenderBondInputIndexes, assetOutputIndexes, oracles, refInputs, configRefIndex,
                lmConfigRefIndex), true);

        // V5 — everything above is re-derived from the finished body and compared.
        assertStructure(transaction, request, loanOrder, bondOrder, claims, lenderBondInputIndexes,
                assetOutputIndexes, refInputs, configRefIndex, lmConfigRefIndex, oracles);

        return transaction;
    }

    /** One {@link ClaimData} per loan, in final loan-input order. */
    private static List<ClaimData> claims(List<VettedLoan> loanOrder, List<TransactionInput> refInputs,
                                          List<Long> bondOutputIndexes) {
        List<ClaimData> claims = new ArrayList<>();
        for (int i = 0; i < loanOrder.size(); i++) {
            VettedLoan v = loanOrder.get(i);
            claims.add(new ClaimData(
                    v.liquidation(),
                    BigInteger.valueOf(bondOutputIndexes.get(i)),
                    BigInteger.valueOf(oracleRefIndex(refInputs, v.collateral())),
                    BigInteger.valueOf(oracleRefIndex(refInputs, v.principal())),
                    v.bond().datum().lenderAuth(),
                    v.assessment().equity(),
                    v.loan().loanId(),
                    v.assessment().remainingDebt()));
        }
        return claims;
    }

    /**
     * Where each loan's bond echo actually landed, matched on the three things D6 makes identical —
     * address, datum bytes and value — with the bond NFT inside that value making the match unique
     * even between two otherwise identical bonds.
     */
    private static List<Long> locateBondOutputs(Transaction probe, List<VettedLoan> loanOrder) {
        List<TransactionOutput> outputs = probe.getBody().getOutputs();
        List<Long> indexes = new ArrayList<>();
        for (VettedLoan loan : loanOrder) {
            List<Integer> matches = new ArrayList<>();
            for (int i = 0; i < outputs.size(); i++) {
                TransactionOutput output = outputs.get(i);
                if (output.getAddress().equals(loan.bondUtxo().getAddress())
                        && output.getInlineDatum() != null
                        && output.getInlineDatum().serializeToHex()
                        .equalsIgnoreCase(loan.bondUtxo().getInlineDatum())
                        && sameValue(output, loan.bondUtxo())) {
                    matches.add(i);
                }
            }
            if (matches.size() != 1) {
                throw refuse(Refusal.STRUCTURAL_ASSERTION_FAILED,
                        "bond %s matches %d outputs, expected exactly one"
                                .formatted(loan.bond().loanId(), matches.size()));
            }
            indexes.add(matches.getFirst().longValue());
        }
        return indexes;
    }

    /**
     * Where each loan's claimed-collateral output landed <em>within the asset-manager-filtered
     * output list</em>, in loan-input order — the fourth field of
     * {@code LMLiquidateWithdrawRedeemer} at the deployed commit {@code ff005fb}.
     * <p>
     * <b>The index is into the filtered list, not into the body.</b> {@code lm_liquidate_action}
     * reads the output for loan {@code index} as
     * {@code safe_list_at(assetOutputs, safe_list_at(redeemer.assetOutputIndexes, index))}, where
     * {@code assetOutputs} is {@code get_outputs_to_smart_credential(self.outputs, ..)} — a
     * {@code list.filter} over the body that keeps the outputs whose payment credential is the
     * asset-manager spend script, preserving body order. So this is a position in that filtered
     * sequence. That is exactly what {@code ClaimData.lenderBondOutputIndex} is <em>not</em>: that one
     * is an absolute body index. Confusing the two produces a plausible number that aims at the wrong
     * output, which is why {@code LiquidateTransactionBuilderTest} unit-tests this method on a
     * synthetic output list where the two answers differ.
     * <p>
     * Each loan is located by its collateral datum bytes and nothing else: only the datum says which
     * loan an asset-manager output descends from, so a match on it is what proves the right loan got
     * the right slot. Anything ambiguous refuses — a datum matching no filtered output or more than
     * one, and a result carrying a duplicate. The duplicate case is refused rather than emitted on
     * purpose: {@code list.unique(redeemer.assetOutputIndexes) == redeemer.assetOutputIndexes} is a
     * top-level conjunct of the validator precisely because a repeated index is a
     * double-satisfaction shape, so emitting one would hand the chain the attack it guards against.
     * Refusing costs a skipped candidate; emitting costs a fee.
     *
     * @param collateralDatumHexInLoanOrder one serialized collateral datum per loan, in loan-input
     *                                      order — the same ordering {@code lenderBondInputIndexes}
     *                                      and {@code actionsForEachInput} use
     */
    static List<Long> assetOutputIndexes(List<TransactionOutput> outputs,
                                         String assetManagerSpendScriptHash,
                                         List<String> collateralDatumHexInLoanOrder) {
        List<TransactionOutput> filtered = outputs.stream()
                .filter(output -> assetManagerSpendScriptHash.equals(
                        paymentCredentialOf(output.getAddress())))
                .toList();
        List<Long> indexes = new ArrayList<>();
        for (String datumHex : collateralDatumHexInLoanOrder) {
            List<Integer> matches = new ArrayList<>();
            for (int i = 0; i < filtered.size(); i++) {
                PlutusData datum = filtered.get(i).getInlineDatum();
                if (datum != null && datum.serializeToHex().equalsIgnoreCase(datumHex)) {
                    matches.add(i);
                }
            }
            if (matches.size() != 1) {
                throw refuse(Refusal.STRUCTURAL_ASSERTION_FAILED,
                        ("collateral datum %s matches %d of the %d asset-manager outputs, expected "
                                + "exactly one").formatted(datumHex, matches.size(), filtered.size()));
            }
            indexes.add(matches.getFirst().longValue());
        }
        if (new HashSet<>(indexes).size() != indexes.size()) {
            throw refuse(Refusal.STRUCTURAL_ASSERTION_FAILED,
                    ("assetOutputIndexes %s carry a duplicate; lm_liquidate_action's "
                            + "list.unique(..) == .. conjunct rejects that as a double-satisfaction "
                            + "shape, so it is refused here rather than emitted").formatted(indexes));
        }
        return indexes;
    }

    // ---- request shape ---------------------------------------------------------------------

    private static void checkRequestShape(Request request) {
        Objects.requireNonNull(request, "request");
        if (request.liquidations() == null || request.liquidations().isEmpty()) {
            throw refuse(Refusal.EMPTY_BATCH, "no liquidations supplied");
        }
        Objects.requireNonNull(request.configUtxo(), "configUtxo");
        Objects.requireNonNull(request.lmConfigUtxo(), "lmConfigUtxo");
        Objects.requireNonNull(request.referenceScripts(), "referenceScripts");
        if (request.changeAddress() == null || request.changeAddress().isBlank()) {
            throw refuse(Refusal.TRANSACTION_NOT_BUILDABLE, "no change address");
        }
        if (request.oracleWindowMarginMillis() < 0) {
            throw refuse(Refusal.ORACLE_WINDOW_MARGIN_TOO_SMALL,
                    "a negative margin is not a policy: " + request.oracleWindowMarginMillis());
        }
        if (request.validToMillis() <= request.validFromMillis()) {
            throw refuse(Refusal.VALIDITY_WINDOW_INVALID,
                    "validTo %d must be after validFrom %d"
                            .formatted(request.validToMillis(), request.validFromMillis()));
        }

        Utxo wallet = Objects.requireNonNull(request.walletUtxo(), "walletUtxo");
        boolean adaOnly = wallet.getAmount() != null
                && wallet.getAmount().size() == 1
                && AssetType.LOVELACE.equals(wallet.getAmount().getFirst().getUnit());
        if (!adaOnly || wallet.getInlineDatum() != null || wallet.getDataHash() != null
                || wallet.getReferenceScriptHash() != null) {
            throw refuse(Refusal.WALLET_UTXO_NOT_ADA_ONLY,
                    "wallet utxo " + utxoRef(wallet) + " must hold ada only, with no datum or script");
        }

        Set<String> loanIds = new HashSet<>();
        for (LoanLiquidation liquidation : request.liquidations()) {
            LiquidationAssessment assessment = Objects.requireNonNull(liquidation, "liquidation").assessment();
            Objects.requireNonNull(assessment, "assessment");
            String loanId = assessment.bond().loanId();
            if (!loanIds.add(loanId)) {
                throw refuse(Refusal.DUPLICATE_LOAN, "loan id " + loanId + " appears twice in one batch");
            }
        }
    }

    // ---- V1..V4, V6: vetting one loan ------------------------------------------------------

    /** What survives vetting: the assessment plus everything derived from it that is now fixed. */
    private record VettedLoan(LiquidationAssessment assessment,
                              Utxo loanUtxo,
                              Utxo bondUtxo,
                              Loan loan,
                              LenderBond bond,
                              LiquidationMode liquidation,
                              Leg principal,
                              Leg collateral,
                              BigInteger collateralPayout,
                              PlutusData bondDatum,
                              String assetManagerAddress,
                              BigInteger recomputedEquity,
                              BigInteger recomputedFee) {
    }

    /** One priced leg: the feed that goes in a redeemer, and the oracle it came from (null for ada). */
    private record Leg(OraclePriceFeed feed, OracleEntry entry) {

        boolean isOracle() {
            return entry != null;
        }
    }

    private VettedLoan vet(LoanLiquidation liquidation, Request request, long validFrom, long validTo,
                           boolean vetoPositiveEquity) {
        LiquidationAssessment assessment = liquidation.assessment();

        // V1 — the scanner's verdict is the only admission ticket.
        if (!assessment.buildable()) {
            throw refuse(Refusal.NOT_BUILDABLE,
                    "bond %s excluded by the scanner: %s (%s)"
                            .formatted(assessment.bond().loanId(), assessment.exclusion(), assessment.detail()));
        }

        Loan loan = assessment.loan();
        LenderBond bond = assessment.bond();
        LoanDatum datum = loan.datum();
        Utxo loanUtxo = Objects.requireNonNull(liquidation.loanUtxo(), "loanUtxo");
        Utxo bondUtxo = Objects.requireNonNull(liquidation.bondUtxo(), "bondUtxo");

        // The assessment and the UTxOs must be about the same thing; a mismatch here is how a
        // liquidation gets built against a UTxO nobody assessed.
        requireSameUtxo(loanUtxo, loan.txHash(), loan.outputIndex(), loan.address(), "loan");
        requireSameUtxo(bondUtxo, bond.txHash(), bond.outputIndex(), bond.address(), "lender bond");
        requireQuantity(loanUtxo, registry.getLoanPolicyId() + loan.loanId(), BigInteger.ONE, "loan NFT");
        requireQuantity(bondUtxo, registry.getLenderBondPolicyId() + bond.loanId(), BigInteger.ONE,
                "lender bond NFT");
        AssetType collateralAsset = datum.collateral().assetType();
        requireQuantity(loanUtxo, unitOf(collateralAsset), loan.collateralAmount(), "collateral");

        if (!(datum.liquidationMode() instanceof LiquidationMode.Liquidation liquidationMode)) {
            // Unreachable through a buildable assessment, but the ClaimData below copies the mode
            // straight into a redeemer, so it is asserted rather than assumed.
            throw refuse(Refusal.NOT_BUILDABLE,
                    "loan %s is %s, not Liquidation".formatted(loan.loanId(), datum.liquidationMode()));
        }

        // V7 — the two datum fields the plain Liquidate path requires to be false. The scanner
        // excludes both already (D2 and §7.5), so reaching either of these means the assessment did
        // not come from a scan of the UTxOs actually being spent; that is precisely when the builder
        // must not take the scanner's word for it.
        if (liquidationMode.equityInPrincipalCurrency()) {
            throw refuse(Refusal.EQUITY_IN_PRINCIPAL_CURRENCY,
                    ("loan %s denominates equity in the principal currency; lm_liquidate_action "
                            + "expects equityInPrincipalCurrency == False").formatted(loan.loanId()));
        }
        if (bond.datum().shouldLiquidationConvertToPrincipal()) {
            throw refuse(Refusal.CONVERSION_TO_PRINCIPAL_REQUIRED,
                    ("bond %s requires converting liquidation proceeds to principal; the plain "
                            + "Liquidate path requires shouldLiquidationConvertToPrincipal == False")
                            .formatted(bond.loanId()));
        }

        BigInteger remainingDebt = assessment.remainingDebt();
        BigInteger equity = assessment.equity();
        BigInteger liquidationFee = assessment.liquidationFee();

        // V2 — the arithmetic the chain will have to satisfy.
        if (remainingDebt.signum() <= 0) {
            throw refuse(Refusal.NON_POSITIVE_REMAINING_DEBT,
                    "loan %s has remainingDebt %s".formatted(loan.loanId(), remainingDebt));
        }
        if (equity.signum() < 0) {
            throw refuse(Refusal.NEGATIVE_EQUITY,
                    "loan %s has equity %s".formatted(loan.loanId(), equity));
        }
        // A negative fee does not shrink the bot's take, it *inflates* the asset-manager payout past
        // the collateral that is actually there. liquidationFeePerMille is a lender-authored bond
        // field with no on-chain non-negativity constraint, so this is reachable from chain data.
        if (liquidationFee.signum() < 0) {
            throw refuse(Refusal.NEGATIVE_LIQUIDATION_FEE,
                    "loan %s has liquidationFee %s".formatted(loan.loanId(), liquidationFee));
        }
        BigInteger collateralPayout = loan.collateralAmount().subtract(equity).subtract(liquidationFee);
        if (collateralPayout.signum() < 0) {
            throw refuse(Refusal.COLLATERAL_CANNOT_COVER_EQUITY_AND_FEE,
                    "loan %s: collateral %s - equity %s - fee %s = %s".formatted(loan.loanId(),
                            loan.collateralAmount(), equity, liquidationFee, collateralPayout));
        }

        // V4 — the fee the redeemer's arithmetic rests on must be the one the bond actually
        // authorises. `liquidationFee` never reaches a redeemer field, but it decides the split
        // between the asset-manager payout and the bot's change, and `lm_liquidate_action.ak:118-124`
        // recomputes it on chain: an inflated fee underpays the lender and the transaction dies in
        // script evaluation after the fee is already spent. §7.5's formula, floored through
        // Rational exactly as LiquidationCandidateScanner does — the comparison is against the value
        // this method is about to use, so nothing can quietly substitute a recomputed one for it.
        BigInteger recomputedFee = Rational.required(
                        loan.collateralAmount().multiply(bond.datum().liquidationFeePerMille()),
                        BigInteger.valueOf(1000))
                .floor();
        if (!recomputedFee.equals(liquidationFee)) {
            throw refuse(Refusal.LIQUIDATION_FEE_NOT_REPRODUCIBLE,
                    "loan %s: collateral %s at %s per mille is a fee of %s, the assessment says %s"
                            .formatted(loan.loanId(), loan.collateralAmount(),
                                    bond.datum().liquidationFeePerMille(), recomputedFee, liquidationFee));
        }

        // V6 — the receipt NFT a repayment-receipt loan expects on the compensation output is not
        // modelled anywhere in this repo, so a partial liquidation of one cannot be built.
        if (datum.repaymentReceipts() && equity.signum() > 0) {
            throw refuse(Refusal.REPAYMENT_RECEIPTS_WITH_EQUITY,
                    "loan %s wants repayment receipts and has equity %s; the receipt mint is unmodelled"
                            .formatted(loan.loanId(), equity));
        }

        Leg principal = leg(datum.principalAsset().isAda(), datum.principalOracleAsset(), request,
                loan.loanId(), "principal", validFrom, validTo);
        Leg collateral = leg(datum.collateral().isAda(), datum.collateral().oracleTokenAsset(), request,
                loan.loanId(), "collateral", validFrom, validTo);

        // V4 — the guards. These recomputations never replace the assessment's numbers (D8);
        // disagreement refuses the batch.
        BigInteger recomputedEquity = assertHealthInvariantOverWindow(loan, remainingDebt, equity,
                principal, collateral, validFrom, validTo);

        // V6 — the collateral outputs carry the lender's stake part, so it has to be decodable.
        String assetManagerAddress = assetManagerAddress(bond);

        PlutusData bondDatum = roundTrippableBondDatum(bond);

        // V8 — last of the per-loan vetoes, and the only one that is a statement about *this
        // deployment* rather than about the design: loan_claim_action reads the loan-index slot of the
        // asset-manager-filtered output list directly while lm_liquidate_action reaches it through
        // assetOutputIndexes[index], and this builder emits identity indexes — which puts both on the
        // same slot wanting mutually exclusive datums, so no layout this builder emits carries a
        // positive equity through both. Whether some other layout would is untested. See
        // Refusal.POSITIVE_EQUITY_UNSUPPORTED for the file:line references and the dry-eval test that
        // pins it. Kept last so a batch that is wrong for a permanent reason reports that permanent
        // reason instead of this transient one.
        if (vetoPositiveEquity && equity.signum() > 0) {
            throw refuse(Refusal.POSITIVE_EQUITY_UNSUPPORTED,
                    ("loan %s has equity %s; no output layout this builder emits satisfies both "
                            + "lm_liquidate_action and loan_claim_action at a positive equity")
                            .formatted(loan.loanId(), equity));
        }

        return new VettedLoan(assessment, loanUtxo, bondUtxo, loan, bond, datum.liquidationMode(),
                principal, collateral, collateralPayout, bondDatum, assetManagerAddress,
                recomputedEquity, recomputedFee);
    }

    /**
     * Resolves one leg exactly as {@code retrieve_oracle_data} would: an empty policy id is the
     * synthesised 1:1 feed with no oracle consulted, and anything else is looked up by the oracle
     * NFT the datum names.
     */
    private Leg leg(boolean isAda, AssetType oracleToken, Request request, String loanId, String which,
                    long validFrom, long validTo) {
        if (isAda) {
            return new Leg(OraclePriceFeed.unit(), null);
        }
        Map<String, OracleEntry> oracles = request.oraclesByOracleTokenUnit();
        OracleEntry entry = oracles == null ? null : oracles.get(oracleToken.toUnit());
        if (entry == null) {
            throw refuse(Refusal.ORACLE_ENTRY_MISSING,
                    "loan %s %s leg: no oracle entry for %s".formatted(loanId, which, oracleToken.toUnit()));
        }

        // V6 — variants whose redeemer this repo cannot build, and the c3 gap.
        switch (entry.feed().variant()) {
            case POOLED, PRICE_DATA_ORCFAX -> throw refuse(Refusal.UNSUPPORTED_ORACLE_VARIANT,
                    "loan %s %s leg: %s feeds are not modelled".formatted(loanId, which,
                            entry.feed().variant()));
            case PRICE_DATA_CHARLIE -> {
                if (entry.charlieProviderReferenceInput() == null) {
                    throw refuse(Refusal.CHARLIE_PROVIDER_REFERENCE_INPUT_MISSING,
                            "loan %s %s leg: a Charli3 feed is validated against a provider utxo the "
                                    + "registry did not publish".formatted(loanId, which));
                }
            }
            case AGGREGATED, DEDICATED -> {
                // signed variants: the registry's own signatures travel in the redeemer
            }
        }
        if (entry.referenceScript() == null) {
            throw refuse(Refusal.ORACLE_REFERENCE_SCRIPT_MISSING,
                    ("loan %s %s leg: the registry published no reference script for this oracle, and "
                            + "there is no witness fallback — the bundled blueprint's oracle.oracle is "
                            + "unapplied, and the eight parameter values the deployed one was applied "
                            + "with are not published, so attaching it would witness a different "
                            + "credential than the withdrawal is made from").formatted(loanId, which));
        }

        // The reward address decides which redeemer the validator picks up, so it is derived here
        // on the app network and only accepted if the registry agrees.
        String derived = rewardAddress(entry.withdrawCredentialHash());
        if (!derived.equals(entry.rewardAddress())) {
            throw refuse(Refusal.ORACLE_REWARD_ADDRESS_MISMATCH,
                    "loan %s %s leg: registry publishes %s, credential %s derives %s on this network"
                            .formatted(loanId, which, entry.rewardAddress(), entry.withdrawCredentialHash(),
                                    derived));
        }

        // V3 — tx-grade window checks. usableOver, never usableAt.
        OraclePriceFeed feed = entry.feed();
        if (!feed.usableOver(validFrom, validTo)) {
            throw refuse(Refusal.ORACLE_FEED_NOT_USABLE_OVER_WINDOW,
                    "loan %s %s leg: feed window [%d,%d] does not cover tx window [%d,%d]"
                            .formatted(loanId, which, feed.validFrom(), feed.validTo(), validFrom, validTo));
        }
        long remainingWindow = feed.validTo() - validTo;
        if (remainingWindow < request.oracleWindowMarginMillis()) {
            throw refuse(Refusal.ORACLE_WINDOW_MARGIN_TOO_SMALL,
                    "loan %s %s leg: only %dms of feed window left after validTo, %dms required"
                            .formatted(loanId, which, remainingWindow, request.oracleWindowMarginMillis()));
        }
        return new Leg(feed, entry);
    }

    /**
     * V4. Recomputes the debt, the equity and D9's liquidatability from the loan datum and the
     * redeemer's own feeds, and refuses if any of them disagrees with what the assessment carries.
     * <p>
     * Two separate reasons for demanding agreement at <em>both</em> ends of the window: the chain
     * evaluates the loan's finance at some point of the validity interval and this side cannot know
     * which, so a number that moves in between makes the assessment unusable; and an assessment that
     * simply does not reproduce — a stale one, or one whose numbers were tampered with between the
     * scan and the build — must never be turned into a transaction. {@code equity} and
     * {@code remainingDebt} go into the redeemer <em>verbatim</em> either way (D8); this method
     * never substitutes its own values for them, it only refuses. Both comparisons are against the
     * values the caller is about to use, so nothing can quietly swap a recomputed number in.
     *
     * @return the recomputed equity, for the post-assembly assertions to compare against
     */
    private static BigInteger assertHealthInvariantOverWindow(Loan loan, BigInteger remainingDebt,
                                                              BigInteger equity, Leg principal,
                                                              Leg collateral, long validFrom, long validTo) {
        BigInteger recomputedEquityAtValidFrom = null;
        for (long at : new long[]{validFrom, validTo}) {
            BigInteger recomputed;
            BigInteger recomputedEquity;
            boolean liquidatable;
            try {
                recomputed = LoanFinance.remainingDebt(loan.datum(), at);
                boolean late = LoanFinance.isRepaymentLate(loan.datum(), at);
                Rational debt = Rational.fromInt(recomputed);
                Rational collateralAmount = Rational.fromInt(loan.collateralAmount());
                // D9 / loan_claim_action.ak:230 — `or { isRepaymentLate, can_liquidate }`.
                liquidatable = late || LoanFinance.canLiquidate(debt, collateralAmount,
                        LoanFinance.liquidationLtv(liquidationOf(loan)), principal.feed(), collateral.feed());
                recomputedEquity = LoanFinance.redeemerEquity(liquidationOf(loan), collateralAmount, debt,
                        principal.feed(), collateral.feed());
            } catch (ArithmeticException e) {
                throw refuse(Refusal.HEALTH_NOT_COMPUTABLE,
                        "loan %s at %d: %s".formatted(loan.loanId(), at, e.getMessage()), e);
            }
            if (!recomputed.equals(remainingDebt)) {
                throw refuse(Refusal.REMAINING_DEBT_NOT_INVARIANT,
                        "loan %s: remainingDebt is %s at %d but the redeemer carries %s"
                                .formatted(loan.loanId(), recomputed, at, remainingDebt));
            }
            if (!recomputedEquity.equals(equity)) {
                throw refuse(Refusal.EQUITY_NOT_REPRODUCIBLE,
                        "loan %s: equity is %s at %d but the redeemer carries %s"
                                .formatted(loan.loanId(), recomputedEquity, at, equity));
            }
            if (!liquidatable) {
                throw refuse(Refusal.NOT_LIQUIDATABLE_OVER_WINDOW,
                        "loan %s is neither late nor over its liquidation ltv at %d"
                                .formatted(loan.loanId(), at));
            }
            if (recomputedEquityAtValidFrom == null) {
                recomputedEquityAtValidFrom = recomputedEquity;
            }
        }
        return recomputedEquityAtValidFrom;
    }

    private static LiquidationMode.Liquidation liquidationOf(Loan loan) {
        return (LiquidationMode.Liquidation) loan.datum().liquidationMode();
    }

    // ---- addresses ---------------------------------------------------------------------------

    /**
     * The address the claimed collateral goes to: the asset-manager spend credential, carrying the
     * lender's stake part from {@code LenderManagerDatum.lenderStakeCredential} (findings §7 D6 —
     * that field is for the bot-created asset outputs, not for the bond echo).
     */
    private String assetManagerAddress(LenderBond bond) {
        Credential payment = Credential.fromScript(registry.getAssetManagerSpendScriptHash());
        Credential stake = stakeCredential(bond);
        return stake == null
                ? AddressProvider.getEntAddress(payment, network).getAddress()
                : AddressProvider.getBaseAddress(payment, stake, network).getAddress();
    }

    /**
     * Decodes {@code Option<StakeCredential>}: {@code None} means an enterprise address,
     * {@code Some(Inline(credential))} contributes the stake part, and {@code Some(Pointer{..})}
     * is refused — cardano-client-lib can build a pointer address, but nothing in this workstream
     * has ever seen one and guessing its bytes onto an output that holds real collateral is not a
     * trade this builder makes.
     */
    private static Credential stakeCredential(LenderBond bond) {
        PlutusData data = bond.datum().lenderStakeCredential();
        ConstrPlutusData option = asConstr(data, bond, "lenderStakeCredential");
        if (option.getAlternative() == 1) {
            return null;
        }
        if (option.getAlternative() != 0) {
            throw refuse(Refusal.UNDECODABLE_STAKE_CREDENTIAL,
                    "bond %s: Option constructor %d".formatted(bond.loanId(), option.getAlternative()));
        }
        ConstrPlutusData referenced = asConstr(first(option, bond), bond, "StakeCredential");
        if (referenced.getAlternative() == 1) {
            throw refuse(Refusal.POINTER_STAKE_CREDENTIAL,
                    "bond %s carries a pointer stake credential".formatted(bond.loanId()));
        }
        if (referenced.getAlternative() != 0) {
            throw refuse(Refusal.UNDECODABLE_STAKE_CREDENTIAL,
                    "bond %s: StakeCredential constructor %d"
                            .formatted(bond.loanId(), referenced.getAlternative()));
        }
        ConstrPlutusData credential = asConstr(first(referenced, bond), bond, "Credential");
        PlutusData hashData = first(credential, bond);
        if (!(hashData instanceof BytesPlutusData hash)) {
            throw refuse(Refusal.UNDECODABLE_STAKE_CREDENTIAL,
                    "bond %s: credential hash is not a ByteArray".formatted(bond.loanId()));
        }
        return switch ((int) credential.getAlternative()) {
            case 0 -> Credential.fromKey(hash.getValue());
            case 1 -> Credential.fromScript(hash.getValue());
            default -> throw refuse(Refusal.UNDECODABLE_STAKE_CREDENTIAL,
                    "bond %s: Credential constructor %d".formatted(bond.loanId(), credential.getAlternative()));
        };
    }

    private static ConstrPlutusData asConstr(PlutusData data, LenderBond bond, String what) {
        if (data instanceof ConstrPlutusData constr) {
            return constr;
        }
        throw refuse(Refusal.UNDECODABLE_STAKE_CREDENTIAL,
                "bond %s: %s is not a constructor".formatted(bond.loanId(), what));
    }

    private static PlutusData first(ConstrPlutusData constr, LenderBond bond) {
        List<PlutusData> fields = constr.getData() == null ? List.of() : constr.getData().getPlutusDataList();
        if (fields.isEmpty()) {
            throw refuse(Refusal.UNDECODABLE_STAKE_CREDENTIAL,
                    "bond %s: a stake credential constructor has no fields".formatted(bond.loanId()));
        }
        return fields.getFirst();
    }

    private String rewardAddress(String scriptHash) {
        return AddressProvider.getRewardAddress(Credential.fromScript(scriptHash), network).getAddress();
    }

    /**
     * D6 requires the bond output to be a byte-identical echo of its input, and cardano-client-lib
     * re-serialises whatever {@code PlutusData} it is handed. So the input's datum is decoded and
     * re-encoded here, and the batch is refused unless the bytes come back identical — the failure
     * {@code LenderManagerDatum#lenderStakeCredential}'s javadoc warns about, caught before it can
     * cost a fee.
     */
    private static PlutusData roundTrippableBondDatum(LenderBond bond) {
        String original = bond.inlineDatum();
        try {
            PlutusData decoded = ConstrPlutusData.deserialize(
                    CborSerializationUtil.deserialize(HexUtil.decodeHexString(original)));
            String reencoded = decoded.serializeToHex();
            if (!original.equalsIgnoreCase(reencoded)) {
                throw refuse(Refusal.BOND_DATUM_NOT_BYTE_IDENTICAL,
                        "bond %s: datum re-encodes to different bytes".formatted(bond.loanId()));
            }
            return decoded;
        } catch (RefusedException e) {
            throw e;
        } catch (Exception e) {
            throw refuse(Refusal.BOND_DATUM_NOT_BYTE_IDENTICAL,
                    "bond %s: datum could not be decoded".formatted(bond.loanId()), e);
        }
    }

    // ---- reference inputs --------------------------------------------------------------------

    /** One withdrawal per oracle credential — {@code pairs.get_first} finds exactly one (design §6.3). */
    private static List<OracleEntry> distinctOracles(List<VettedLoan> loans) {
        Map<String, OracleEntry> byCredential = new LinkedHashMap<>();
        for (VettedLoan loan : loans) {
            for (Leg leg : List.of(loan.principal(), loan.collateral())) {
                if (leg.isOracle()) {
                    byCredential.putIfAbsent(leg.entry().withdrawCredentialHash(), leg.entry());
                }
            }
        }
        return List.copyOf(byCredential.values());
    }

    /** Every reference input the finished body will hold, deduplicated and canonically sorted. */
    private static List<TransactionInput> referenceInputs(Request request, List<OracleEntry> oracles) {
        Set<TransactionInput> inputs = new LinkedHashSet<>();
        inputs.add(inputOf(request.configUtxo()));
        inputs.add(inputOf(request.lmConfigUtxo()));
        for (OracleEntry oracle : oracles) {
            inputs.add(oracle.referenceInput());
            inputs.add(oracle.referenceScript());
            if (oracle.charlieProviderReferenceInput() != null) {
                inputs.add(oracle.charlieProviderReferenceInput());
            }
        }
        ReferenceScripts scripts = request.referenceScripts();
        Stream.of(scripts.loan(), scripts.loanSpend(), scripts.lenderManager(),
                        scripts.lenderManagerSpend(), scripts.loanClaimAction(), scripts.lmLiquidateAction(),
                        scripts.assetManager())
                .filter(Objects::nonNull)
                .forEach(inputs::add);
        return inputs.stream().sorted(new TransactionInputComparator()).toList();
    }

    private static int refIndex(List<TransactionInput> refInputs, TransactionInput input, String what) {
        if (input == null) {
            // Reachable only if one of the vetoes above stopped guarding; refusing here keeps a
            // missing coordinate a refusal rather than a NullPointerException out of indexOf.
            throw refuse(Refusal.STRUCTURAL_ASSERTION_FAILED,
                    "%s reference input is null — no coordinate to resolve an index against"
                            .formatted(what));
        }
        int index = refInputs.indexOf(input);
        if (index < 0) {
            throw refuse(Refusal.STRUCTURAL_ASSERTION_FAILED,
                    "%s reference input %s#%d is not in the reference input set"
                            .formatted(what, input.getTransactionId(), input.getIndex()));
        }
        return index;
    }

    /**
     * An ada leg has no oracle and no reference input: {@code retrieve_oracle_data} returns the
     * synthesised 1:1 feed from its {@code expectedTokenPolicyId == ""} branch before it ever looks
     * at the index, so zero is written and never read. Every other leg resolves against the final
     * reference-input order.
     */
    private static int oracleRefIndex(List<TransactionInput> refInputs, Leg leg) {
        return leg.isOracle() ? refIndex(refInputs, leg.entry().referenceInput(), "oracle") : 0;
    }

    // ---- assembly ------------------------------------------------------------------------------

    private ScriptTx assemble(Request request,
                              List<VettedLoan> loanOrder,
                              List<VettedLoan> bondOrder,
                              List<ClaimData> claims,
                              List<Long> lenderBondInputIndexes,
                              List<Long> assetOutputIndexes,
                              List<OracleEntry> oracles,
                              List<TransactionInput> refInputs,
                              int configRefIndex,
                              int lmConfigRefIndex) {
        ScriptTx tx = new ScriptTx();

        // Inputs. The general_spend handlers take a unit redeemer; the real authorisation is the
        // withdraw-0 invocation of the validator they wrap.
        for (VettedLoan loan : loanOrder) {
            tx.collectFrom(loan.loanUtxo(), GENERAL_SPEND_REDEEMER);
        }
        for (VettedLoan bond : bondOrder) {
            tx.collectFrom(bond.bondUtxo(), GENERAL_SPEND_REDEEMER);
        }
        tx.collectFrom(request.walletUtxo());

        // Burn every loan NFT under one mint redeemer: a policy id may appear only once in the
        // mint field, so one policy means exactly one Mint redeemer.
        //
        // isPoolOrigin=false / originWithdrawRedeemerIndex=0 are the plain non-pool liquidation
        // case. On-chain evaluation accepts them, and loan.ak's check_mint explains why they are
        // inert here: it only reads either field once something is minted (quantity > 0), and a
        // Liquidate mints nothing — the mint field holds burns only.
        List<Asset> burns = loanOrder.stream()
                .map(loan -> new Asset("0x" + loan.loan().loanId(), BigInteger.ONE.negate()))
                .toList();
        tx.mintAsset(registry.getLoanScript(), burns,
                LiquidationTxEncoder.loanMintRedeemer(configRefIndex, false, 0));

        // Outputs: every bond echo, then every collateral output, then the equity outputs of the
        // loans that have one.
        //
        // The bond echoes go out in *bond-input* order, not loan-input order. lm_liquidate_action
        // reads the echo as `safe_list_at(lenderBondOutputs, lenderBondIndex)` where
        // lenderBondOutputs is the outputs filtered by the LenderManager spend credential and
        // lenderBondIndex is the index into the identically filtered *inputs* — so the k-th bond
        // output must be the echo of the k-th bond input. Emitting them in loan order passes every
        // off-chain structural check and is rejected on chain the moment the two orders differ
        // (slice C, N=2: Withdraw redeemer 2 / lm_liquidate_action, EvaluationFailure).
        for (VettedLoan bond : bondOrder) {
            tx.payToContract(bond.bondUtxo().getAddress(), List.copyOf(bond.bondUtxo().getAmount()),
                    bond.bondDatum());
        }
        for (VettedLoan loan : loanOrder) {
            tx.payToContract(loan.assetManagerAddress(),
                    assetManagerAmounts(loan, loan.collateralPayout()), collateralDatum(loan));
        }
        for (VettedLoan loan : loanOrder) {
            if (loan.assessment().equity().signum() > 0) {
                tx.payToContract(loan.assetManagerAddress(),
                        assetManagerAmounts(loan, loan.assessment().equity()), equityDatum(loan));
            }
        }

        // Withdraw-0 invocations. The main config authorises loan/claim/lm-liquidate; the
        // LenderManager validator reads the LM config instead.
        List<String> bondAssetNames = loanOrder.stream().map(loan -> loan.loan().loanId()).toList();
        tx.withdraw(rewardAddress(registry.getLoanPolicyId()), BigInteger.ZERO,
                LiquidationTxEncoder.loanWithdrawRedeemer(configRefIndex), request.changeAddress());
        tx.withdraw(rewardAddress(registry.getLoanClaimActionScriptHash()), BigInteger.ZERO,
                LiquidationTxEncoder.loanClaimActionWithdrawRedeemer(configRefIndex, claims),
                request.changeAddress());
        tx.withdraw(rewardAddress(registry.getLenderManagerWithdrawScriptHash()), BigInteger.ZERO,
                LiquidationTxEncoder.lenderManagerWithdrawRedeemer(lmConfigRefIndex), request.changeAddress());
        tx.withdraw(rewardAddress(registry.getLmLiquidateActionScriptHash()), BigInteger.ZERO,
                LiquidationTxEncoder.lmLiquidateWithdrawRedeemer(configRefIndex, lenderBondInputIndexes,
                        bondAssetNames, assetOutputIndexes),
                request.changeAddress());
        for (OracleEntry oracle : oracles) {
            tx.withdraw(oracle.rewardAddress(), BigInteger.ZERO, oracleRedeemer(oracle, refInputs),
                    request.changeAddress());
        }

        // Reference inputs. readFrom deduplicates, and the body's order is irrelevant because every
        // index was taken off the canonically sorted list.
        tx.readFrom(refInputs.toArray(TransactionInput[]::new));

        attachValidators(tx);

        return tx.withChangeAddress(request.changeAddress());
    }

    /** The claimed-collateral datum: owned by the lender bond, descending from the loan input. */
    private PlutusData collateralDatum(VettedLoan loan) {
        return LiquidationTxEncoder.assetManagerDatumWithToken(new AssetManagerDatumWithToken(
                loan.loanUtxo().getTxHash(), loan.loanUtxo().getOutputIndex(),
                LiquidationTxEncoder.CLAIMED_COLLATERAL_ACTION_HEX,
                new AssetType(registry.getLenderBondPolicyId(), loan.loan().loanId())));
    }

    /** The partial-liquidation compensation datum: owned by the <em>borrower</em> bond. */
    private PlutusData equityDatum(VettedLoan loan) {
        return LiquidationTxEncoder.assetManagerDatumWithToken(new AssetManagerDatumWithToken(
                loan.loanUtxo().getTxHash(), loan.loanUtxo().getOutputIndex(),
                LiquidationTxEncoder.PARTIAL_LIQUIDATION_ACTION_HEX,
                new AssetType(registry.getBorrowerBondPolicyId(), loan.loan().loanId())));
    }

    /**
     * The redeemer for one oracle. A Charli3 feed is unsigned and carries the index of the provider
     * UTxO instead, resolved — like every other index here — against the final reference-input
     * order. The window is exactly the registry-published one for every variant, signed or not
     * (see {@link OraclePriceFeed#priceDataCharlie}).
     */
    private static PlutusData oracleRedeemer(OracleEntry oracle, List<TransactionInput> refInputs) {
        if (oracle.feed().variant() == OraclePriceFeed.Variant.PRICE_DATA_CHARLIE) {
            int providerIndex = refIndex(refInputs, oracle.charlieProviderReferenceInput(),
                    "charli3 provider");
            return LiquidationTxEncoder.oracleRedeemer(oracle.feed(), providerIndex, List.of());
        }
        return LiquidationTxEncoder.oracleRedeemer(oracle.feed(), oracle.signatures());
    }

    /**
     * The value of one asset-manager output. For token collateral the quantity is exact and the
     * lovelace rider is left to cardano-client-lib's min-ada top-up, which uses the caller's
     * protocol params rather than a second copy of the same formula here.
     */
    private static List<Amount> assetManagerAmounts(VettedLoan loan, BigInteger quantity) {
        AssetType collateral = loan.loan().datum().collateral().assetType();
        return collateral.isAda()
                ? List.of(Amount.lovelace(quantity))
                : List.of(Amount.asset(unitOf(collateral), quantity));
    }

    /**
     * The six validators a plain {@code Liquidate} invokes, attached to the witness set — the
     * asset-manager script is not among them, because this transaction only <em>creates</em>
     * asset-manager outputs and never spends one.
     * <p>
     * They are attached unconditionally, including when the caller published reference scripts:
     * those are read from in {@link #referenceInputs} and declared to {@code withReferenceScripts},
     * and {@code removeDuplicateScriptWitnesses} then strips the witness copy of each one. Carrying
     * a script that is also reachable by reference is {@code ExtraneousScriptWitnessesUTXOW}, so
     * the strip is not an optimisation.
     */
    private void attachValidators(ScriptTx tx) {
        tx.attachSpendingValidator(registry.getLoanSpendScript());
        tx.attachSpendingValidator(registry.getLenderManagerSpendScript());
        tx.attachRewardValidator(registry.getLoanScript());
        tx.attachRewardValidator(registry.getLoanClaimActionScript());
        tx.attachRewardValidator(registry.getLenderManagerScript());
        tx.attachRewardValidator(registry.getLmLiquidateActionScript());
    }

    /** The scripts the caller says are published, paired with the registry object for each. */
    private List<PlutusScript> publishedScripts(ReferenceScripts scripts) {
        List<PlutusScript> published = new ArrayList<>();
        if (scripts.loan() != null) {
            published.add(registry.getLoanScript());
        }
        if (scripts.loanSpend() != null) {
            published.add(registry.getLoanSpendScript());
        }
        if (scripts.lenderManager() != null) {
            published.add(registry.getLenderManagerScript());
        }
        if (scripts.lenderManagerSpend() != null) {
            published.add(registry.getLenderManagerSpendScript());
        }
        if (scripts.loanClaimAction() != null) {
            published.add(registry.getLoanClaimActionScript());
        }
        if (scripts.lmLiquidateAction() != null) {
            published.add(registry.getLmLiquidateActionScript());
        }
        if (scripts.assetManager() != null) {
            published.add(registry.getAssetManagerScript());
        }
        return published;
    }

    /**
     * Assembles and balances one body.
     *
     * @param priceScripts whether this assembly is the one whose redeemers must carry measured
     *                     ex-units. Only the second one is: the first is the layout probe, and its
     *                     claim redeemers hold placeholder {@code lenderBondOutputIndex} values that
     *                     no validator can accept, so a real evaluator run against it fails by
     *                     construction — measured, by costing the probe on purpose:
     *                     {@code RedeemerError { tag: "Withdraw", index: 0 }}, the withdrawal whose
     *                     validator reads the claim's output index. Evaluating it would refuse every
     *                     batch; not evaluating it costs nothing, because the probe is thrown away
     *                     after {@code locateBondOutputs} reads the output layout off it, and V5
     *                     re-derives that layout from the finished body anyway. It also means exactly
     *                     one evaluation — one remote round trip in production — per build.
     */
    private Transaction complete(Request request, ScriptTx tx, boolean priceScripts) {
        TransactionEvaluator evaluator =
                priceScripts && scriptCostEvaluator != null ? reporting(scriptCostEvaluator) : null;
        long[] slots = validitySlots(request);
        QuickTxBuilder.TxContext context =
                // The third argument is the TransactionProcessor, and it stays null: a processor can
                // submit, and nothing in this class may be able to. Script cost evaluation is granted
                // separately below through withTxEvaluator, whose interface has no submit method.
                new QuickTxBuilder(utxoSupplier, protocolParamsSupplier, null)
                        .compose(tx)
                        .feePayer(request.changeAddress())
                        .collateralPayer(request.changeAddress())
                        .validFrom(slots[0])
                        .validTo(slots[1])
                        // A script supplier that resolves nothing. cardano-client-lib otherwise walks
                        // every reference input looking for a script to fetch, and this builder has no
                        // remote source to fetch one from — the caller either points at a published
                        // reference script or the validator travels in the witness set.
                        .withScriptSupplier(scriptHash -> Optional.empty())
                        .mergeOutputs(false)
                        // With an evaluator, a failed evaluation must stop the build: the default
                        // (true) turns it into a log.warn and hands back a transaction whose redeemers
                        // still carry placeholder ex-units, which is a phase-2 failure waiting to be
                        // submitted. Without one, the flag stays true because there is nothing to
                        // evaluate with and the offline rigs price the transaction themselves.
                        .ignoreScriptCostEvaluationError(evaluator == null);

        if (evaluator != null) {
            context = context.withTxEvaluator(evaluator);
        }

        List<PlutusScript> published = publishedScripts(request.referenceScripts());
        if (!published.isEmpty()) {
            context = context.withReferenceScripts(published.toArray(PlutusScript[]::new))
                    .removeDuplicateScriptWitnesses(true);
        }

        try {
            return context.build();
        } catch (RefusedException e) {
            throw e;
        } catch (Exception e) {
            // Everything cardano-client-lib can fail with arrives here as a named refusal — the
            // scheduled loop catches RefusedException per candidate, so nothing thrown in here can
            // take a cycle down. An evaluator failure is told apart from an unbuildable candidate by
            // the marker reporting() plants in the cause chain, rather than by matching on
            // cardano-client-lib's "Error while evaluating script cost" wording.
            ScriptCostEvaluationException evaluation = evaluationFailureIn(e);
            if (evaluation != null) {
                throw refuse(Refusal.SCRIPT_COST_EVALUATION_FAILED, evaluation.getMessage(), e);
            }
            throw refuse(Refusal.TRANSACTION_NOT_BUILDABLE, e.getMessage(), e);
        }
    }

    /**
     * Thrown by {@link #reporting} when the evaluator itself fails, so that
     * {@link Refusal#SCRIPT_COST_EVALUATION_FAILED} is decided by a type rather than by a string, and
     * so the operator-facing detail is the evaluator's root cause rather than cardano-client-lib's
     * two-layer wrapping of it.
     */
    private static final class ScriptCostEvaluationException extends RuntimeException {

        ScriptCostEvaluationException(String detail, Throwable cause) {
            super(detail, cause);
        }
    }

    /**
     * The caller's evaluator, with every way it can fail to price the transaction turned into one
     * {@link ScriptCostEvaluationException}. Unchecked on purpose: {@code ScriptCostEvaluators} only
     * catches {@code CborSerializationException} and {@code ApiException}, so a {@link RuntimeException}
     * reaches {@link #complete}'s catch with the marker still at the head of the chain.
     * <p>
     * Three failure shapes, not two. A thrown exception and an unsuccessful {@link Result} are the
     * obvious ones; the third is a <b>successful result that does not cost every redeemer</b>, and it is
     * the dangerous one precisely because it looks like success. Blockfrost answering HTTP 200 with an
     * incomplete array — an upstream bug, an API shape change, a proxy in the way — would otherwise pass
     * straight through: {@code ScriptCostEvaluators} writes back only the costings it was given, the
     * redeemers it was not given keep their 10000-mem placeholders, the build succeeds, and in live mode
     * that transaction is submitted and forfeits collateral in phase 2. Checking the envelope and not
     * the payload would leave exactly the defect this class exists to close, one layer in.
     */
    private static TransactionEvaluator reporting(TransactionEvaluator delegate) {
        return (cbor, inputUtxos) -> {
            Result<List<EvaluationResult>> result;
            try {
                result = delegate.evaluateTx(cbor, inputUtxos);
            } catch (Exception e) {
                throw new ScriptCostEvaluationException(causeChain(e), e);
            }
            if (result == null) {
                throw new ScriptCostEvaluationException("the evaluator returned no result", null);
            }
            if (!result.isSuccessful()) {
                throw new ScriptCostEvaluationException(String.valueOf(result.getResponse()), null);
            }
            requireEveryRedeemerCosted(cbor, result.getValue());
            return result;
        };
    }

    /**
     * Every redeemer in the transaction that was sent for evaluation must come back with a costing of
     * its own. Coverage is checked per {@code (tag, index)} pair — the same key
     * {@code ScriptCostEvaluators} writes back on — rather than by count, because N results for N
     * redeemers can still leave one redeemer uncosted and one costing unused.
     */
    private static void requireEveryRedeemerCosted(byte[] cbor, List<EvaluationResult> results) {
        List<Redeemer> redeemers;
        try {
            redeemers = Transaction.deserialize(cbor).getWitnessSet().getRedeemers();
        } catch (Exception e) {
            throw new ScriptCostEvaluationException(
                    "the transaction sent for evaluation could not be read back", e);
        }
        if (redeemers == null || redeemers.isEmpty()) {
            // No redeemers means cardano-client-lib skipped evaluation entirely; nothing to cover.
            return;
        }
        Set<String> costed = new HashSet<>();
        if (results != null) {
            for (EvaluationResult costing : results) {
                costed.add(redeemerKey(costing.getRedeemerTag(), costing.getIndex()));
            }
        }
        List<String> uncosted = redeemers.stream()
                .map(redeemer -> redeemerKey(redeemer.getTag(), redeemer.getIndex().intValue()))
                .filter(key -> !costed.contains(key))
                .toList();
        if (!uncosted.isEmpty()) {
            throw new ScriptCostEvaluationException(
                    ("the evaluator costed %d of %d redeemers; %s would have kept placeholder ex-units")
                            .formatted(redeemers.size() - uncosted.size(), redeemers.size(), uncosted),
                    null);
        }
    }

    private static String redeemerKey(RedeemerTag tag, int index) {
        return tag + "#" + index;
    }

    private static ScriptCostEvaluationException evaluationFailureIn(Throwable thrown) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof ScriptCostEvaluationException marker) {
                return marker;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return null;
    }

    /** Every link of the chain, so the reason a remote evaluator refused is not lost to wrapping. */
    private static String causeChain(Throwable thrown) {
        StringBuilder detail = new StringBuilder();
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (!detail.isEmpty()) {
                detail.append(" <- ");
            }
            detail.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
            if (t.getCause() == t) {
                break;
            }
        }
        return detail.toString();
    }

    /**
     * Slots for the requested millisecond window, clamped <em>inwards</em>. A slot boundary rarely
     * lands on the requested instant, and rounding outwards would hand the chain a wider interval
     * than the one V3 and V4 were checked against — the interval the guards proved safe must
     * contain the interval the transaction actually claims, not the other way round.
     */
    private long[] validitySlots(Request request) {
        long from = request.validFromMillis();
        long to = request.validToMillis();
        long slotFrom = converters.time().toSlot(utc(from));
        if (millisOf(converters.slot().slotToTime(slotFrom)) < from) {
            slotFrom += 1;
        }
        long slotTo = converters.time().toSlot(utc(to));
        if (millisOf(converters.slot().slotToTime(slotTo)) > to) {
            slotTo -= 1;
        }
        if (slotFrom > slotTo) {
            throw refuse(Refusal.VALIDITY_WINDOW_INVALID,
                    "window [%d,%d] does not contain a whole slot".formatted(from, to));
        }
        return new long[]{slotFrom, slotTo};
    }

    private static LocalDateTime utc(long millis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC);
    }

    private static long millisOf(LocalDateTime time) {
        return time.toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    // ---- V5: structural assertions on the finished body ----------------------------------------

    private void assertStructure(Transaction transaction,
                                 Request request,
                                 List<VettedLoan> loanOrder,
                                 List<VettedLoan> bondOrder,
                                 List<ClaimData> claims,
                                 List<Long> lenderBondInputIndexes,
                                 List<Long> assetOutputIndexes,
                                 List<TransactionInput> refInputs,
                                 int configRefIndex,
                                 int lmConfigRefIndex,
                                 List<OracleEntry> oracles) {
        List<TransactionInput> sortedInputs = transaction.getBody().getInputs().stream()
                .sorted(new TransactionInputComparator())
                .toList();
        List<TransactionInput> sortedRefInputs = transaction.getBody().getReferenceInputs().stream()
                .sorted(new TransactionInputComparator())
                .toList();
        List<TransactionOutput> outputs = transaction.getBody().getOutputs();
        String lenderManagerCredential = registry.getLenderManagerSpendScriptHash();

        // The reference-input set every index above was computed from must be the one that ended up
        // in the body — nothing may have been added or dropped along the way.
        structural(sortedRefInputs.equals(refInputs),
                "reference inputs in the body differ from the set the indexes were resolved against");
        structural(refInputs.get(configRefIndex).equals(inputOf(request.configUtxo())),
                "configRefInputIndex " + configRefIndex + " does not point at the main config utxo");
        structural(refInputs.get(lmConfigRefIndex).equals(inputOf(request.lmConfigUtxo())),
                "lenderManager configRefInputIndex " + lmConfigRefIndex
                        + " does not point at the lm config utxo");
        for (OracleEntry oracle : oracles) {
            structural(sortedRefInputs.contains(oracle.referenceInput()),
                    "oracle " + oracle.oracleToken().toUnit() + " reference input is missing");
            if (oracle.charlieProviderReferenceInput() != null) {
                structural(sortedRefInputs.contains(oracle.charlieProviderReferenceInput()),
                        "charli3 provider reference input is missing for " + oracle.oracleToken().toUnit());
            }
        }

        // D5 — one bond input per loan input, indexes unique.
        structural(lenderBondInputIndexes.size() == loanOrder.size(),
                "lenderBondInputIndexes has %d entries for %d loan inputs"
                        .formatted(lenderBondInputIndexes.size(), loanOrder.size()));
        structural(new HashSet<>(lenderBondInputIndexes).size() == lenderBondInputIndexes.size(),
                "lenderBondInputIndexes are not unique: " + lenderBondInputIndexes);
        for (int i = 0; i < loanOrder.size(); i++) {
            VettedLoan loan = loanOrder.get(i);
            VettedLoan pairedBond = bondOrder.get(lenderBondInputIndexes.get(i).intValue());
            structural(pairedBond.loan().loanId().equals(loan.loan().loanId()),
                    "lenderBondInputIndexes[%d] pairs loan %s with bond %s"
                            .formatted(i, loan.loan().loanId(), pairedBond.bond().loanId()));
            structural(sortedInputs.contains(inputOf(loan.loanUtxo())),
                    "loan input " + utxoRef(loan.loanUtxo()) + " is not in the body");
            structural(sortedInputs.contains(inputOf(loan.bondUtxo())),
                    "bond input " + utxoRef(loan.bondUtxo()) + " is not in the body");
        }
        // The filtered orders must be the ones the ledger will see, i.e. the sorted body order.
        structural(filteredOrderMatches(sortedInputs, loanOrder, VettedLoan::loanUtxo),
                "loan inputs are not in canonical order in the body");
        structural(filteredOrderMatches(sortedInputs, bondOrder, VettedLoan::bondUtxo),
                "bond inputs are not in canonical order in the body");

        // lm_liquidate_action pairs the two LenderManager-credential lists positionally: the echo it
        // reads for a loan is `lenderBondOutputs[lenderBondIndex]`, with the same index it used to
        // pick `lenderBondInputs[lenderBondIndex]`. So the k-th bond output in body order must be
        // the echo of the k-th bond input in canonical order, for every k.
        List<TransactionOutput> bondOutputs = outputs.stream()
                .filter(output -> lenderManagerCredential.equals(paymentCredentialOf(output.getAddress())))
                .toList();
        structural(bondOutputs.size() == bondOrder.size(),
                "%d outputs at the LenderManager credential for %d bond inputs"
                        .formatted(bondOutputs.size(), bondOrder.size()));
        for (int i = 0; i < bondOrder.size(); i++) {
            Utxo bondInput = bondOrder.get(i).bondUtxo();
            TransactionOutput bondOutput = bondOutputs.get(i);
            structural(bondOutput.getAddress().equals(bondInput.getAddress())
                            && sameValue(bondOutput, bondInput)
                            && bondOutput.getInlineDatum() != null
                            && bondOutput.getInlineDatum().serializeToHex()
                            .equalsIgnoreCase(bondInput.getInlineDatum()),
                    "bond output %d is not the echo of bond input %s".formatted(i, utxoRef(bondInput)));
        }

        // ff005fb's fourth LMLiquidateWithdrawRedeemer field. lm_liquidate_action reads the collateral
        // output for loan `index` as assetOutputs[assetOutputIndexes[index]], where assetOutputs is
        // the body's outputs filtered by the asset-manager SPEND credential in body order. Everything
        // below is re-derived from `outputs` — the finished body — and never from the list that was
        // emitted: comparing the emitted list against itself would be an assertion structurally
        // incapable of failing, which is the defect class this block exists to avoid.
        List<TransactionOutput> assetManagerFilteredOutputs = outputs.stream()
                .filter(output -> registry.getAssetManagerSpendScriptHash()
                        .equals(paymentCredentialOf(output.getAddress())))
                .toList();
        structural(assetOutputIndexes.size() == loanOrder.size(),
                "assetOutputIndexes has %d entries for %d loan inputs"
                        .formatted(assetOutputIndexes.size(), loanOrder.size()));
        structural(new HashSet<>(assetOutputIndexes).size() == assetOutputIndexes.size(),
                "assetOutputIndexes are not unique: " + assetOutputIndexes);
        for (int i = 0; i < loanOrder.size(); i++) {
            VettedLoan loan = loanOrder.get(i);
            long index = assetOutputIndexes.get(i);
            structural(index >= 0 && index < assetManagerFilteredOutputs.size(),
                    "assetOutputIndexes[%d] = %d is outside the %d asset-manager outputs"
                            .formatted(i, index, assetManagerFilteredOutputs.size()));
            // Matched on the datum bytes and nothing else: address and value are shared by every
            // asset-manager output of the batch, so only the datum identifies which loan's collateral
            // this slot really is.
            PlutusData datum = assetManagerFilteredOutputs.get((int) index).getInlineDatum();
            structural(datum != null
                            && datum.serializeToHex()
                            .equalsIgnoreCase(collateralDatum(loan).serializeToHex()),
                    ("assetOutputIndexes[%d] = %d points at an asset-manager output that does not "
                            + "carry loan %s's claimed-collateral datum")
                            .formatted(i, index, loan.loan().loanId()));
        }

        for (int i = 0; i < loanOrder.size(); i++) {
            VettedLoan loan = loanOrder.get(i);
            ClaimData claim = claims.get(i);
            structural(claim.loanId().equals(loan.loan().loanId()),
                    "actionsForEachInput[%d] is loan %s, expected %s"
                            .formatted(i, claim.loanId(), loan.loan().loanId()));
            // D8 — the redeemer carries the assessment's numbers verbatim. The equality with the
            // independently recomputed equity is what makes that meaningful rather than circular:
            // V4 already refused every batch where the two disagree, so a number reaching a
            // redeemer here has been produced twice, from two directions.
            structural(claim.remainingDebt().equals(loan.assessment().remainingDebt())
                            && claim.equity().equals(loan.assessment().equity()),
                    "claim %d does not carry the assessment's numbers verbatim".formatted(i));
            structural(claim.equity().equals(loan.recomputedEquity()),
                    "claim %d carries equity %s, the loan recomputes %s"
                            .formatted(i, claim.equity(), loan.recomputedEquity()));
            structural(loan.collateralPayout().equals(loan.loan().collateralAmount()
                            .subtract(loan.recomputedEquity()).subtract(loan.recomputedFee())),
                    "claim %d: the collateral payout is not collateral - equity - fee at the "
                            .formatted(i) + "recomputed numbers");

            // D6 — the bond output is a byte-identical echo of the bond input.
            int bondOutputIndex = claim.lenderBondOutputIndex().intValueExact();
            structural(bondOutputIndex >= 0 && bondOutputIndex < outputs.size(),
                    "lenderBondOutputIndex " + bondOutputIndex + " is out of range");
            TransactionOutput bondOutput = outputs.get(bondOutputIndex);
            structural(bondOutput.getAddress().equals(loan.bondUtxo().getAddress()),
                    "bond output %d is at %s, expected %s"
                            .formatted(bondOutputIndex, bondOutput.getAddress(), loan.bondUtxo().getAddress()));
            structural(sameValue(bondOutput, loan.bondUtxo()),
                    "bond output %d does not hold exactly the bond input's value".formatted(bondOutputIndex));
            structural(bondOutput.getInlineDatum() != null
                            && bondOutput.getInlineDatum().serializeToHex()
                            .equalsIgnoreCase(loan.bondUtxo().getInlineDatum()),
                    "bond output %d does not echo the bond input's datum bytes".formatted(bondOutputIndex));

            // D7 — the asset outputs flatten to exactly one entry for ada collateral, two otherwise.
            // The collateral output is found by its datum rather than by an offset: only the datum
            // says which loan an asset-manager output descends from, so matching on it is what
            // proves the right loan got the right payout.
            boolean adaCollateral = loan.loan().datum().collateral().isAda();
            TransactionOutput collateralOutput = onlyOutputWithDatum(outputs, loan.assetManagerAddress(),
                    collateralDatum(loan), "collateral output for loan " + loan.loan().loanId());
            assertAssetManagerOutput(collateralOutput, loan, adaCollateral,
                    "collateral output for loan " + loan.loan().loanId());
            structural(quantityIn(collateralOutput, loan).equals(loan.collateralPayout()),
                    "collateral output for loan %s carries %s, expected collateral - equity - fee = %s"
                            .formatted(loan.loan().loanId(), quantityIn(collateralOutput, loan),
                                    loan.collateralPayout()));
        }

        // Equity outputs — one per loan with a positive equity, and none for the others.
        long expectedEquityOutputs = 0;
        for (VettedLoan loan : loanOrder) {
            if (loan.assessment().equity().signum() <= 0) {
                continue;
            }
            expectedEquityOutputs++;
            String what = "equity output for loan " + loan.loan().loanId();
            TransactionOutput equityOutput =
                    onlyOutputWithDatum(outputs, loan.assetManagerAddress(), equityDatum(loan), what);
            assertAssetManagerOutput(equityOutput, loan, loan.loan().datum().collateral().isAda(), what);
            // loan_claim_action checks the compensation with `>=`, so a min-ada top-up is legal here
            // where it would not be on the collateral output.
            structural(quantityIn(equityOutput, loan).compareTo(loan.assessment().equity()) >= 0,
                    "%s carries %s, less than the redeemer's equity %s".formatted(what,
                            quantityIn(equityOutput, loan), loan.assessment().equity()));
        }

        // Nothing else may sit at an asset-manager address: exactly one collateral output per loan
        // plus the equity outputs just accounted for.
        Set<String> assetManagerAddresses = loanOrder.stream()
                .map(VettedLoan::assetManagerAddress)
                .collect(Collectors.toSet());
        long assetManagerOutputs = outputs.stream()
                .filter(output -> assetManagerAddresses.contains(output.getAddress()))
                .count();
        structural(assetManagerOutputs == loanOrder.size() + expectedEquityOutputs,
                "%d asset-manager outputs for %d loans and %d equity refunds"
                        .formatted(assetManagerOutputs, loanOrder.size(), expectedEquityOutputs));

        // Every loan NFT, and only those, must be burned.
        Map<String, BigInteger> burned = new LinkedHashMap<>();
        if (transaction.getBody().getMint() != null) {
            transaction.getBody().getMint().stream()
                    .filter(multiAsset -> multiAsset.getPolicyId().equals(registry.getLoanPolicyId()))
                    .flatMap(multiAsset -> multiAsset.getAssets().stream())
                    .forEach(asset -> burned.put(stripHexPrefix(asset.getNameAsHex()), asset.getValue()));
        }
        structural(burned.size() == loanOrder.size(),
                "%d loan NFTs burned for %d loans".formatted(burned.size(), loanOrder.size()));
        for (VettedLoan loan : loanOrder) {
            structural(BigInteger.ONE.negate().equals(burned.get(loan.loan().loanId())),
                    "loan NFT " + loan.loan().loanId() + " is not burned exactly once");
        }
    }

    private void assertAssetManagerOutput(TransactionOutput output, VettedLoan loan, boolean adaCollateral,
                                          String what) {
        structural(output.getAddress().equals(loan.assetManagerAddress()),
                what + " is at " + output.getAddress() + ", expected " + loan.assetManagerAddress());
        int flattened = ValueUtil.toAmountList(output.getValue()).size();
        structural(flattened == (adaCollateral ? 1 : 2),
                what + " flattens to " + flattened + " assets, expected " + (adaCollateral ? 1 : 2));
    }

    /** The single output at {@code address} carrying {@code datum}, or a refusal. */
    private static TransactionOutput onlyOutputWithDatum(List<TransactionOutput> outputs, String address,
                                                         PlutusData datum, String what) {
        String expected = datum.serializeToHex();
        List<TransactionOutput> matches = outputs.stream()
                .filter(output -> output.getAddress().equals(address))
                .filter(output -> output.getInlineDatum() != null
                        && output.getInlineDatum().serializeToHex().equalsIgnoreCase(expected))
                .toList();
        if (matches.size() != 1) {
            throw refuse(Refusal.STRUCTURAL_ASSERTION_FAILED,
                    "%s matches %d outputs, expected exactly one".formatted(what, matches.size()));
        }
        return matches.getFirst();
    }

    private static BigInteger quantityIn(TransactionOutput output, VettedLoan loan) {
        AssetType collateral = loan.loan().datum().collateral().assetType();
        String unit = unitOf(collateral);
        return ValueUtil.toAmountList(output.getValue()).stream()
                .filter(amount -> unit.equals(amount.getUnit()))
                .map(Amount::getQuantity)
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    private static boolean filteredOrderMatches(List<TransactionInput> sortedInputs, List<VettedLoan> order,
                                                Function<VettedLoan, Utxo> which) {
        List<TransactionInput> expected = order.stream().map(which).map(LiquidateTransactionBuilder::inputOf)
                .toList();
        List<TransactionInput> actual = sortedInputs.stream().filter(expected::contains).toList();
        return actual.equals(expected);
    }

    private static boolean sameValue(TransactionOutput output, Utxo utxo) {
        return normalise(ValueUtil.toAmountList(output.getValue())).equals(normalise(utxo.getAmount()));
    }

    private static Map<String, BigInteger> normalise(List<Amount> amounts) {
        Map<String, BigInteger> byUnit = new LinkedHashMap<>();
        for (Amount amount : amounts) {
            byUnit.merge(amount.getUnit(), amount.getQuantity(), BigInteger::add);
        }
        byUnit.values().removeIf(quantity -> quantity.signum() == 0);
        return byUnit;
    }

    private static String stripHexPrefix(String hex) {
        return hex != null && hex.startsWith("0x") ? hex.substring(2) : hex;
    }

    private static void structural(boolean condition, String detail) {
        if (!condition) {
            throw refuse(Refusal.STRUCTURAL_ASSERTION_FAILED, detail);
        }
    }

    // ---- small helpers -------------------------------------------------------------------------

    /** The payment credential of a bech32 address, as the hex hash the registry speaks in. */
    private static String paymentCredentialOf(String address) {
        return new Address(address).getPaymentCredentialHash().map(HexUtil::encodeHexString).orElse(null);
    }

    private static TransactionInput inputOf(Utxo utxo) {
        return new TransactionInput(utxo.getTxHash(), utxo.getOutputIndex());
    }

    private static String utxoRef(Utxo utxo) {
        return utxo.getTxHash() + "#" + utxo.getOutputIndex();
    }

    /** {@code quantity_of(value, "", "")} is the lovelace quantity on chain, and so is this. */
    private static String unitOf(AssetType asset) {
        return asset.isAda() ? AssetType.LOVELACE : asset.toUnit();
    }

    private static void requireSameUtxo(Utxo utxo, String txHash, int outputIndex, String address,
                                        String what) {
        if (!utxo.getTxHash().equals(txHash) || utxo.getOutputIndex() != outputIndex
                || !utxo.getAddress().equals(address)) {
            throw refuse(Refusal.UTXO_DOES_NOT_MATCH_ASSESSMENT,
                    "%s utxo %s at %s is not the assessed %s#%d at %s"
                            .formatted(what, utxoRef(utxo), utxo.getAddress(), txHash, outputIndex, address));
        }
    }

    private static void requireQuantity(Utxo utxo, String unit, BigInteger expected, String what) {
        BigInteger actual = utxo.getAmount().stream()
                .filter(amount -> unit.equals(amount.getUnit()))
                .map(Amount::getQuantity)
                .reduce(BigInteger.ZERO, BigInteger::add);
        if (!actual.equals(expected)) {
            throw refuse(Refusal.UTXO_DOES_NOT_MATCH_ASSESSMENT,
                    "%s in %s is %s, the assessment says %s".formatted(what, utxoRef(utxo), actual, expected));
        }
    }

    private static RefusedException refuse(Refusal reason, String detail) {
        log.debug("refusing to build a Liquidate transaction — {}: {}", reason, detail);
        return new RefusedException(reason, detail);
    }

    private static RefusedException refuse(Refusal reason, String detail, Throwable cause) {
        log.debug("refusing to build a Liquidate transaction — {}: {}", reason, detail, cause);
        return new RefusedException(reason, detail, cause);
    }
}
