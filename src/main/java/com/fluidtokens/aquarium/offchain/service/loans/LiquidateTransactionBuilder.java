package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.MinAdaCalculator;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.function.TxBuilder;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
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
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
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
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
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
 * <h2>Which numbers come from the assessment, and which do not</h2>
 * The {@link LiquidationAssessment} decides <em>whether</em> a loan is liquidated — it is the
 * admission ticket (V1), it supplies the {@code liquidationFee}, and its figures are what the caller
 * prices profitability and files decision rows with. It does <b>not</b> supply the redeemer's two
 * time-dependent figures.
 * <p>
 * {@code remainingDebt} and {@code equity} are derived here, at the transaction's {@code validFrom},
 * because that is the only instant at which they can be right: {@code loan_claim_action.ak:212-222,229}
 * ({@code ff005fb}) recomputes {@code get_remaining_debt(.., validFrom - datum.lendDate)} and demands
 * exact equality. The assessment's copies were taken at <em>scan</em> time, minutes earlier and
 * before {@code LiquidationExecutor}'s 30-second {@code validFrom} backdate, so on any
 * interest-bearing loan they are a few lovelace off — an on-chain refusal, observed live at
 * {@code interestRate = 459} as a two-lovelace divergence. The two figures differing is therefore
 * <em>expected</em>, not an anomaly.
 * <p>
 * {@code liquidationFee} is still the assessment's: it is time-independent
 * ({@code collateralAmount * liquidationFeePerMille / 1000}), it never reaches a redeemer field, and
 * V4 recomputes and compares it rather than substituting.
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
 *   <li><b>V4</b> — the assessment's {@code liquidationFee} must be the one the bond's per-mille rate
 *       produces, and {@code late || can_liquidate} must hold at <em>both</em> ends of the window,
 *       because that leg consumes the oracle feeds and the feeds must span the interval (see
 *       {@code assertLiquidatableOverWindow}). The debt and the equity are no longer compared here:
 *       they are derived at {@code validFrom} rather than supplied, and what checks them is V5.</li>
 *   <li><b>V5</b> — structural assertions re-read off the <em>built</em> transaction body and its
 *       witness set: index uniqueness and counts (D5), byte-identical bond echo (D6), asset-manager
 *       output shape (D7), that every index in every redeemer points at what it claims, and — see
 *       {@code assertRedeemerFiguresMatchTheBodysValidFrom} — that the {@code remainingDebt} and
 *       {@code equity} <em>decoded back out of the emitted redeemer</em> are the ones the loan datum
 *       produces at the validity start slot the <em>emitted body</em> carries.</li>
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
 * </ul>
 * <b>There is no V8.</b> It used to refuse {@code equity > 0} outright, on the belief that no output
 * layout satisfied both validators at once. That belief was wrong — see "Positive equity: rule R"
 * below — and the veto was deleted rather than relaxed.
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
 * {@code lenderBondOutputIndex} is COMPUTED from the emission order rather than observed: outputs are
 * a list and the ledger preserves builder order exactly, so the only unknown is how many outputs the
 * library put in front ({@link OutputLayout#CCL_PREPENDED_OUTPUTS}). The transaction is therefore
 * built ONCE (T-051). V5 re-derives all of it from the finished body anyway, and finds the
 * asset-manager outputs by their datum rather than by an offset — so a layout mistake is a
 * build-time refusal, never a chain failure.
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
 *
 * <h2>Positive equity: rule R</h2>
 * A partially liquidated loan owes the borrower the residual value of the collateral. The deployed
 * validators demand <em>two</em> asset-manager outputs for such a loan, and they locate them
 * differently:
 * <ul>
 *   <li>{@code loan_claim_action.ak:271-295} reads the borrower's compensation output as
 *       {@code safe_list_at(get_outputs_to_smart_credential(..), index)} — the <b>bare loan index</b>
 *       into the asset-manager-credential-filtered output list, with no redeemer indirection
 *       available to move it.</li>
 *   <li>{@code lm_liquidate_action.ak:87-91} reads the lender's claimed-collateral output as
 *       {@code safe_list_at(assetOutputs, safe_list_at(redeemer.assetOutputIndexes, index))} — the
 *       same filtered list, but through a redeemer field this builder chooses.</li>
 * </ul>
 * Only one of the two positions is forced, so the layout is decided by the forced one. With the loans
 * in canonical loan-input order, {@code N} of them and {@code K} carrying a positive equity, the
 * filtered asset-manager output list is emitted as:
 * <pre>
 *   slots 0 .. N-1     loan i's EQUITY output if equity(i) &gt; 0, else loan i's COLLATERAL output
 *   slots N .. N+K-1   the COLLATERAL outputs of the positive-equity loans, in loan order
 * </pre>
 * That is <b>rule R</b>. It puts every compensation output exactly where {@code loan_claim_action}
 * insists on finding it, and leaves {@code assetOutputIndexes} — which is free — to point
 * {@code lm_liquidate_action} at wherever the matching collateral output ended up. Verified against
 * the deployed validators at N=1 and N=2 in both shapes, and on real chain data
 * ({@code RealEquityLoanDryEvalTest}).
 * <p>
 * <b>Rule R degenerates to the previous emission order when {@code K = 0}</b>: every loan takes the
 * "else" branch, slot {@code i} holds loan {@code i}'s collateral output, and the second row is
 * empty. The zero-equity path — the only one that has ever been submitted — is therefore
 * byte-identical, which is why this is a widening rather than a change.
 * <p>
 * {@code assetOutputIndexes} is computed straight from rule R's emission order
 * ({@link #assetOutputIndexesForEquities}), which needs no library offset at all: the index is into
 * the asset-manager-FILTERED list, and neither the prepended dummy nor the appended change survives
 * that filter. V5 still re-derives it off the finished body by collateral-datum match
 * ({@link #assetOutputIndexes}) and refuses if the two disagree — two provenances, so the
 * disagreement is a real signal.
 *
 * <h2>Where the compensation output is sent</h2>
 * {@code equity_sent_to_borrower}'s {@code and{}} block opens with the comment
 * {@code //No staking check here}, so the validator constrains the compensation output's payment
 * credential and nothing else — the stake part is the transaction builder's free choice. This builder
 * emits an <b>enterprise address</b>: the asset-manager spend credential with no stake part at all.
 * See {@link #equityAddress()} for why that is the only neutral answer, and note that it is asserted
 * off the finished body precisely <em>because</em> the validator will not assert it for us.
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
        /**
         * V5 — the equity decoded out of the emitted redeemer is not the one the loan and the feeds
         * produce at the emitted body's {@code validFrom}. Same instant as
         * {@link #REMAINING_DEBT_NOT_INVARIANT}, and for the same reason:
         * {@code get_equity}/{@code get_equity_in_collateral_currency} take no time argument at all,
         * so every bit of time dependence they have arrives through the {@code validFrom} debt they
         * are handed.
         */
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
        /**
         * V5 — the debt decoded out of the <em>emitted</em> loan-claim redeemer is not the one the
         * loan datum produces at the <em>emitted</em> body's validity start, which is the only instant
         * the validator computes it at ({@code loan_claim_action.ak:212-222,229} at {@code ff005fb}).
         * <p>
         * The name says "NOT_INVARIANT" for history's sake, and the check has now moved twice: it was
         * a both-ends comparison (a false premise), then a {@code validFrom} comparison against the
         * assessment (which refused every interest-bearing loan, because the assessment is taken at
         * scan time and {@code validFrom} is 30 s earlier). It is now an artefact-level check — see
         * {@code assertRedeemerFiguresMatchTheBodysValidFrom} — and both of its sides are read back
         * off the built transaction rather than carried down from the code that built it.
         */
        REMAINING_DEBT_NOT_INVARIANT,
        /** V4 — D9's {@code late || can_liquidate} does not hold across the whole window. */
        NOT_LIQUIDATABLE_OVER_WINDOW,
        /** V4/V5 — the finance re-computation raised an arithmetic failure the chain would too. */
        HEALTH_NOT_COMPUTABLE,
        /** V6 — a pointer stake credential cannot be turned into an output address here. */
        POINTER_STAKE_CREDENTIAL,
        /** The stake credential field is not an {@code Option<StakeCredential>}. */
        UNDECODABLE_STAKE_CREDENTIAL,
        /**
         * V6 — a repayment-receipt NFT would have to be minted, and that mint is not modelled.
         * <p>
         * <b>This is now reachable from a scanned batch</b>, and its {@code @UnreachableFromScannedBatch}
         * marking was removed with V8. The marking claimed the scanner filtered it out via
         * {@code LiquidationExclusion.POSITIVE_EQUITY_UNSUPPORTED}; that exclusion is gone, because
         * positive-equity liquidations are now built rather than refused. A
         * {@code repaymentReceipts = True} loan with a positive equity therefore reaches this check
         * for real, and this is the loud refusal of the one branch of {@code equity_sent_to_borrower}
         * this repo still cannot satisfy: {@code loan_claim_action.ak:433-438} requires exactly one
         * NFT named {@code hash_output_ref(loanInputOutputReference)} under {@code repaymentPolicyId}
         * on the compensation output, and nothing here mints it.
         */
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
         * <p>
         * <b>This is no longer unreachable from a scanned batch</b>, and its
         * {@code @UnreachableFromScannedBatch} marking was removed with A3. The marking claimed the
         * scanner filtered it out via {@code LiquidationExclusion.CONVERSION_TO_PRINCIPAL_REQUIRED}; A3
         * lifted that exclusion, so a convert loan is now scanned. What keeps it away from <em>this</em>
         * plain builder is {@code LiquidationExecutor}'s routing, which sends a convert candidate to the
         * pay-in-advance seam ({@code PayInAdvanceLiquidationRouter}) rather than here (A2). The V7 guard
         * below stays as a hard last line: if a convert assessment ever does reach this builder — an
         * assessment that did not come from the routing of the UTxOs actually being spent — it still
         * refuses rather than emitting a transaction the {@code lm_liquidate_action} validator rejects.
         */
        CONVERSION_TO_PRINCIPAL_REQUIRED,
        /** D6 needs the bond datum echoed byte for byte; this one does not survive a round trip. */
        BOND_DATUM_NOT_BYTE_IDENTICAL,
        /** The requested window does not contain at least one whole slot. */
        VALIDITY_WINDOW_INVALID,
        /** V5 — the finished body does not match what the redeemers claim about it. */
        /**
         * The pure-ada collateral this transaction can offer is less than {@code fee ×
         * collateral_percent}, so the ledger's required collateral cannot be met.
         *
         * <p>⛔ <b>This is the one failure mode that never becomes a transaction at all.</b> Measured
         * 2026-08-25: with a 1,000,000 lovelace collateral input and a 1,113,523 fee, the required
         * collateral was 1,670,285 and cardano-client-lib emitted a collateral return of
         * <b>−670,285</b>. A negative {@code MaryValue} is unrepresentable, so every era decoder
         * rejected the CBOR — {@code DeserialiseFailure 1227 "expected array or int, got TypeNInt"}
         * — <b>before any validation ran</b>. Not phase 2, not phase 1: the node could not parse it.
         *
         * <p>Distinct from {@link #WALLET_UTXO_NOT_ADA_ONLY}, which asks whether the utxo has the
         * right <em>shape</em>. This asks whether it is <em>large enough</em>, and the 2026-08-25
         * artefact passed the first and failed the second.
         */
        INSUFFICIENT_COLLATERAL,
        /**
         * The loan UTxO carries an asset that is neither its declared collateral nor its loan NFT.
         *
         * <p>⛔ <b>This is a griefing vector, and refusing is a blast-radius reduction rather than a
         * fix.</b> Nothing pays out an undeclared asset — {@code assetManagerAmounts} emits only
         * {@code loan.datum().collateral().assetType()} — so it would flow to the bot's change output,
         * which {@code adaOnlyWalletUtxo()} then correctly refuses. <b>The wallet's only fresh output
         * would be token-bearing and the WHOLE BOT would stop</b>, which is the 2026-08-25 outage
         * reproduced by a stranger for the price of one min-UTxO and a fee.
         *
         * <p><b>After this refusal that one loan is skipped and the bot keeps running.</b> It is still
         * unliquidatable by us either way — <b>whether a griefed loan is recoverable at all is a
         * CONTRACT question</b>, recorded in the findings as an open question for the protocol.
         *
         * <p>The detail names the offending unit, because <em>"something is wrong with that loan"</em>
         * and <em>"someone sent this token to that address"</em> lead to different actions.
         */
        LOAN_UTXO_CARRIES_UNDECLARED_ASSET,
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
                                   TransactionInput assetManager,
                                   TransactionInput lmLiquidateAndPayInAdvanceAction,
                                   TransactionInput lmLiquidateAndConvertAction) {

        /**
         * The seven-slot form, for callers that predate {@code lmLiquidateAndPayInAdvanceAction}.
         * Defaults it to unpublished, which is what every such caller meant.
         */
        public ReferenceScripts(TransactionInput loan,
                                TransactionInput loanSpend,
                                TransactionInput lenderManager,
                                TransactionInput lenderManagerSpend,
                                TransactionInput loanClaimAction,
                                TransactionInput lmLiquidateAction,
                                TransactionInput assetManager) {
            this(loan, loanSpend, lenderManager, lenderManagerSpend, loanClaimAction,
                    lmLiquidateAction, assetManager, null, null);
        }

        /** The eight-slot form, for callers that predate {@code lmLiquidateAndConvertAction}. */
        public ReferenceScripts(TransactionInput loan,
                                TransactionInput loanSpend,
                                TransactionInput lenderManager,
                                TransactionInput lenderManagerSpend,
                                TransactionInput loanClaimAction,
                                TransactionInput lmLiquidateAction,
                                TransactionInput assetManager,
                                TransactionInput lmLiquidateAndPayInAdvanceAction) {
            this(loan, loanSpend, lenderManager, lenderManagerSpend, loanClaimAction,
                    lmLiquidateAction, assetManager, lmLiquidateAndPayInAdvanceAction, null);
        }

        /** Nothing published: every script travels in the witness set. */
        public static ReferenceScripts none() {
            return new ReferenceScripts(null, null, null, null, null, null, null, null, null);
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
     * The backend cardano-client-lib builds against in production, or {@code null} for the offline
     * rigs. When present, {@code QuickTxBuilder} is constructed from it directly — the one-argument
     * constructor the library documents — which gives it the utxo supplier, protocol params,
     * <b>script supplier</b> and transaction processor in one object.
     * <p>
     * <b>The safety property is now a stated decision, not a constructor trick.</b> For a long time
     * this class handed {@code QuickTxBuilder} a {@code null} processor so that it was structurally
     * incapable of submitting. That same {@code null} nulled the evaluator (T-014, placeholder
     * ex-units), and later hid the script supplier that a reference-script transaction needs to be
     * priced at all. It cost twice. So: <b>this builder can technically reach a submitter and does
     * not use it — nothing in this class calls {@code submit}, {@code build()} returns an unsigned
     * {@link Transaction}, and arming lives in {@code LiquidationExecutor} behind two independent
     * flags.</b> A property enforced by a hole in the wiring was a property that could be violated by
     * accident in the other direction, and was.
     */
    private final com.bloxbean.cardano.client.backend.api.BackendService backendService;

    /**
     * The offline builder: no evaluator, so redeemers keep cardano-client-lib's placeholder ex-units.
     * For the test rigs, which evaluate separately. Production goes through the {@code BackendService}
     * constructor.
     */
    public LiquidateTransactionBuilder(LoansContractRegistry registry,
                                       Network network,
                                       CardanoConverters converters,
                                       UtxoSupplier utxoSupplier,
                                       ProtocolParamsSupplier protocolParamsSupplier) {
        this(registry, network, converters, utxoSupplier, protocolParamsSupplier, null, null);
    }

    /**
     * Offline builder with an evaluator — what the dry-eval rigs use to prove the priced path against
     * the deployed validators without a network.
     */
    public LiquidateTransactionBuilder(LoansContractRegistry registry,
                                       Network network,
                                       CardanoConverters converters,
                                       UtxoSupplier utxoSupplier,
                                       ProtocolParamsSupplier protocolParamsSupplier,
                                       TransactionEvaluator scriptCostEvaluator) {
        this(registry, network, converters, utxoSupplier, protocolParamsSupplier, null, scriptCostEvaluator);
    }

    /**
     * The production constructor. {@code QuickTxBuilder} is built from the {@code BackendService}
     * exactly as the library documents, so it has a utxo supplier, protocol params, a script supplier
     * that can fetch a validator travelling as a reference script, and a transaction processor. The
     * evaluator is passed separately because the processor's own evaluator would price against a
     * remote node's view; Blockfrost's {@code /utils/txs/evaluate} is what we want, and it is what
     * {@code YaciConfig} supplies. See the field javadoc for why the processor is no longer nulled.
     */
    public LiquidateTransactionBuilder(LoansContractRegistry registry,
                                       Network network,
                                       CardanoConverters converters,
                                       com.bloxbean.cardano.client.backend.api.BackendService backendService,
                                       TransactionEvaluator scriptCostEvaluator) {
        this(registry, network, converters,
                new com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier(backendService.getUtxoService()),
                new com.bloxbean.cardano.client.backend.api.DefaultProtocolParamsSupplier(backendService.getEpochService()),
                Objects.requireNonNull(backendService, "backendService"), scriptCostEvaluator);
    }

    private LiquidateTransactionBuilder(LoansContractRegistry registry,
                                        Network network,
                                        CardanoConverters converters,
                                        UtxoSupplier utxoSupplier,
                                        ProtocolParamsSupplier protocolParamsSupplier,
                                        com.bloxbean.cardano.client.backend.api.BackendService backendService,
                                        TransactionEvaluator scriptCostEvaluator) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.network = Objects.requireNonNull(network, "network");
        this.converters = Objects.requireNonNull(converters, "converters");
        this.utxoSupplier = Objects.requireNonNull(utxoSupplier, "utxoSupplier");
        this.protocolParamsSupplier = Objects.requireNonNull(protocolParamsSupplier, "protocolParamsSupplier");
        this.backendService = backendService;
        this.scriptCostEvaluator = scriptCostEvaluator;
    }

    // ---- the one entry point ---------------------------------------------------------------

    /**
     * Builds the unsigned {@code Liquidate} transaction for this batch, or refuses.
     *
     * @throws RefusedException whenever anything about the batch is not certain
     */
    public Transaction build(Request request) {
        checkRequestShape(request, registry.getLoanPolicyId());

        // The instant every time-dependent redeemer figure is derived from is chosen FIRST, and
        // everything below is derived from it — because `validFrom` is not an observation, it is a
        // number this builder picks and the ledger then enforces.
        //
        // It is the SLOT-derived millisecond, not the caller's: `validitySlots` clamps the requested
        // window inwards to whole slots, and what `loan_claim_action` destructures out of
        // `self.validity_range` is the POSIX time of the slot that ends up in the body. Computing the
        // redeemer at request.validFromMillis() while the body carries slotToTime(slotFrom) would be
        // the same class of mistake as computing it at the scan instant, only a second smaller.
        long[] slots = validitySlots(request);
        long validFrom = millisOf(converters.slot().slotToTime(slots[0]));
        long validTo = millisOf(converters.slot().slotToTime(slots[1]));

        // Pass 1 — vet every loan on its own. Nothing about the transaction shape yet.
        List<VettedLoan> vetted = new ArrayList<>();
        for (LoanLiquidation liquidation : request.liquidations()) {
            vetted.add(vet(liquidation, request, validFrom, validTo));
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

        // Pass 4 — the output indexes, COMPUTED from the emission order this builder controls.
        //
        // ⚠ This replaced a two-pass layout probe on 2026-08-26 (T-051). The measurement that made it
        // safe is docs/ledger-index-ordering.md; the two facts it turns on:
        //
        //   1. OUTPUTS ARE A LIST, NOT A SET. The ledger preserves builder order exactly — unlike
        //      inputs and reference inputs, which are sets re-sorted lexicographically by
        //      (txhash, ix) before a validator sees them. So there is no sort order to compute here:
        //      an output's position IS the order it was added in. (Giovanni's "sort alphabetically"
        //      rule is right for inputs, reference inputs, withdrawals and mint policies, and does
        //      not apply to outputs — which is the only place this builder ever needed a probe.)
        //   2. cardano-client-lib prepends exactly ONE dummy output when a transaction carries
        //      withdrawals — StakeTx:292-295 is `if (withdrawalContexts.size() > 0)`, one per
        //      TRANSACTION, not one per withdrawal — and appends change last. Measured on
        //      49743a1e…: 5 withdrawals, 1 dummy at index 0, change at index 4.
        //
        // The probe was never the guarantee and its removal does not weaken one: V5 re-derives BOTH
        // index families from the FINISHED body — lenderBondOutputIndex by address, value and datum
        // bytes, assetOutputIndexes by collateral datum — and refuses if either points at anything
        // other than what its redeemer claims. Computing rather than observing therefore turns a
        // layout mistake into a build-time refusal: loud, free, nothing on chain.
        List<Long> bondOutputIndexes = bondOutputIndexes(lenderBondInputIndexes);
        List<ClaimData> claims = claims(loanOrder, refInputs, bondOutputIndexes);
        List<Long> assetOutputIndexes = assetOutputIndexes(loanOrder);

        Transaction transaction = complete(request, assemble(request, loanOrder, bondOrder, claims,
                lenderBondInputIndexes, assetOutputIndexes, oracles, refInputs, configRefIndex,
                lmConfigRefIndex),
                // V5 — everything above is re-derived from the finished body and compared, INSIDE the
                // build pipeline. There is no separate call for a future path to forget.
                (ctx, txn) -> assertStructure(txn, request, loanOrder, bondOrder, claims,
                        lenderBondInputIndexes, assetOutputIndexes, refInputs, configRefIndex,
                        lmConfigRefIndex, oracles));

        assertCollateralIsCoverable(protocolParamsSupplier.getProtocolParams(), transaction,
                request.walletUtxo());

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
                    v.redeemerEquity(),
                    v.loan().loanId(),
                    v.redeemerRemainingDebt()));
        }
        return claims;
    }

    /**
     * <b>Where each loan's bond echo will land, computed from the emission order.</b>
     * <p>
     * {@code assemble} emits one echo per bond in <em>bond-input</em> order, as the first outputs it
     * adds, so the echo for the k-th bond input sits at {@code outputBase + k}. A loan's bond is the
     * {@code lenderBondInputIndexes[i]}-th bond input — the pairing V5 independently re-asserts — so
     * that index doubles as the echo's offset. Nothing here re-derives the pairing; it reuses it.
     * <p>
     * {@code outputBase} is the one thing cardano-client-lib contributes: see
     * {@link OutputLayout#CCL_PREPENDED_OUTPUTS}.
     */
    static List<Long> bondOutputIndexes(List<Long> lenderBondInputIndexes) {
        return lenderBondInputIndexes.stream()
                .map(bondInputIndex -> bondInputIndex + OutputLayout.CCL_PREPENDED_OUTPUTS)
                .toList();
    }

    /**
     * <b>Where each loan's claimed-collateral output will land in the asset-manager-filtered list,
     * computed from rule R's emission order.</b>
     * <p>
     * This index is into the <em>filtered</em> list, not the body — see the overload below — which is
     * why it needs no {@code outputBase}: the dummy and change outputs sit at the change address and
     * the bond echoes at the LenderManager credential, so none of them survives the asset-manager
     * filter. <b>The only outputs in that list are the two rows this builder emits</b>, in the order
     * it emits them:
     * <pre>
     *   filtered[0 .. L-1]      rule R first row — one per loan, loan order: the loan's COMPENSATION
     *                           output when its equity is positive, otherwise its COLLATERAL output
     *   filtered[L .. L+K-1]    rule R second row — the collateral outputs displaced out of the first
     *                           row by a positive equity, in loan order (K = how many)
     * </pre>
     * So a loan with no equity finds its collateral at its own index, and a loan with equity finds it
     * at {@code L} plus its rank among the loans that have one.
     */
    private static List<Long> assetOutputIndexes(List<VettedLoan> loanOrder) {
        return assetOutputIndexesForEquities(
                loanOrder.stream().map(VettedLoan::redeemerEquity).toList());
    }

    /**
     * Rule R's positional arithmetic, over nothing but the equities — so it is testable without a
     * chain fixture, which the builders it serves are not.
     */
    static List<Long> assetOutputIndexesForEquities(List<BigInteger> equityInLoanOrder) {
        int firstRow = equityInLoanOrder.size();
        List<Long> indexes = new ArrayList<>();
        long displaced = 0;
        for (BigInteger equity : equityInLoanOrder) {
            if (equity.signum() > 0) {
                indexes.add(firstRow + displaced);
                displaced++;
            } else {
                indexes.add((long) indexes.size());
            }
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

    /**
     * <b>Rule R's first row: every borrower-compensation output sits at its own loan's index in the
     * asset-manager-filtered output list.</b>
     * <p>
     * This is the one position in a {@code Liquidate} that no redeemer can move.
     * {@code loan_claim_action.ak:275-284} reads the compensation output as
     * {@code safe_list_at(get_outputs_to_smart_credential(self.outputs, ..), index)}, where
     * {@code index} is the loan's own position among the loan inputs — there is no
     * {@code assetOutputIndexes}-style indirection on that side. Emit the compensation output
     * anywhere else and the validator reads whatever output happens to occupy slot {@code index},
     * {@code equals_data} fails on the datum, and the transaction dies in phase-2 evaluation with the
     * fee already spent. {@link #assetOutputIndexes} then does the yielding for the <em>other</em>
     * validator, which does have an indirection.
     * <p>
     * Separate from {@link #assemble} on purpose. {@code assemble} decides the layout; this decides
     * whether the layout that actually reached the body is the one the chain will accept, and it is
     * handed the finished body's own filtered list rather than anything the emission path carried
     * down. Package-private and static so it can be exercised directly on a synthetic filtered list —
     * the same treatment {@link #assetOutputIndexes} gets, and for the same reason: a guard whose only
     * exercise is the happy path is a guard nobody has watched fail.
     *
     * <h3>Why this <em>returns</em> the outputs rather than only asserting on them</h3>
     * Because a guard whose call can be deleted without anything going red is not a guard. An earlier
     * shape of this method was {@code void}, and deleting its single call site left the whole suite
     * green — every downstream assertion located the compensation output by scanning the body for its
     * datum, so it went on checking an output that carried the right bytes at the wrong slot. Handing
     * the located outputs back, and making {@code assertStructure} do all of its amount, shape and
     * address checks on <em>those</em>, means the position is what the rest of V5 is talking about.
     * Removing the call now fails to compile.
     *
     * @param assetManagerOutputs               the body's outputs filtered by the asset-manager spend
     *                                          credential, in body order — the list
     *                                          {@code get_outputs_to_smart_credential} produces
     * @param compensationDatumHexByLoanIndex   one entry per loan, in loan-input order: the
     *                                          serialized compensation datum that loan requires at its
     *                                          own slot, or {@code null} for a loan with no equity —
     *                                          which emits no compensation output and whose slot
     *                                          {@code loan_claim_action} never reads, because
     *                                          {@code or { inputAction.equity == 0, .. }}
     *                                          short-circuits first
     * @param loanIdsByLoanIndex                the loan ids, in the same order, for the refusal detail
     * @return one entry per loan, in the same order: the compensation output found at that loan's own
     *         slot, or {@code null} for a loan that requires none. Never a wrong-slot output — those
     *         refuse rather than return.
     */
    static List<TransactionOutput> compensationOutputsAtTheirLoanIndex(
            List<TransactionOutput> assetManagerOutputs,
            List<String> compensationDatumHexByLoanIndex,
            List<String> loanIdsByLoanIndex) {
        List<TransactionOutput> located = new ArrayList<>();
        for (int i = 0; i < compensationDatumHexByLoanIndex.size(); i++) {
            String expected = compensationDatumHexByLoanIndex.get(i);
            if (expected == null) {
                located.add(null);
                continue;
            }
            String loanId = loanIdsByLoanIndex.get(i);
            structural(i < assetManagerOutputs.size(),
                    ("loan %s has a compensation output but loan index %d is outside the %d "
                            + "asset-manager outputs, so loan_claim_action has no slot to read")
                            .formatted(loanId, i, assetManagerOutputs.size()));
            TransactionOutput atLoanSlot = assetManagerOutputs.get(i);
            PlutusData datum = atLoanSlot.getInlineDatum();
            structural(datum != null && datum.serializeToHex().equalsIgnoreCase(expected),
                    ("loan %s's compensation output is not at filtered asset-manager slot %d; "
                            + "loan_claim_action reads it at the bare loan index and cannot be "
                            + "redirected").formatted(loanId, i));
            located.add(atLoanSlot);
        }
        return located;
    }

    // ---- request shape ---------------------------------------------------------------------

    private static void checkRequestShape(Request request, String loanPolicyId) {
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

        for (LoanLiquidation liquidation : request.liquidations()) {
            refuseIfLoanCarriesAnUndeclaredAsset(liquidation, loanPolicyId);
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

    /**
     * What survives vetting: the assessment, plus everything derived that is now fixed.
     *
     * @param redeemerRemainingDebt {@code LoanFinance.remainingDebt} at the transaction's
     *                              {@code validFrom} — the figure that goes in the redeemer, which is
     *                              <em>not</em> {@code assessment.remainingDebt()} (that one was taken
     *                              at scan time; see {@code vet})
     * @param redeemerEquity        {@code LoanFinance.redeemerEquity} from that same debt, likewise
     *                              not the assessment's
     * @param assetManagerAddress   where the lender's claimed collateral goes: the asset-manager spend
     *                              credential carrying the <em>lender's</em> stake part
     * @param equityAddress         where the borrower's compensation goes: the same spend credential
     *                              with <em>no</em> stake part. Deliberately a separate field rather
     *                              than a reuse of {@code assetManagerAddress} — see
     *                              {@link #equityAddress()}. On a bond whose
     *                              {@code lenderStakeCredential} is {@code None} the two happen to be
     *                              the same string; that coincidence is not the reason either is
     *                              correct.
     */
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
                              String equityAddress,
                              BigInteger redeemerRemainingDebt,
                              BigInteger redeemerEquity,
                              BigInteger recomputedFee) {
    }

    /** One priced leg: the feed that goes in a redeemer, and the oracle it came from (null for ada). */
    private record Leg(OraclePriceFeed feed, OracleEntry entry) {

        boolean isOracle() {
            return entry != null;
        }
    }

    private VettedLoan vet(LoanLiquidation liquidation, Request request, long validFrom, long validTo) {
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

        Leg principal = leg(datum.principalAsset().isAda(), datum.principalOracleAsset(), request,
                loan.loanId(), "principal", validFrom, validTo);
        Leg collateral = leg(datum.collateral().isAda(), datum.collateral().oracleTokenAsset(), request,
                loan.loanId(), "collateral", validFrom, validTo);

        // The two time-dependent figures the redeemer carries, computed HERE, at validFrom.
        //
        // They are deliberately NOT the assessment's. LiquidationCandidateScanner computes its own at
        // scan time, and the transaction's validFrom is a different instant — LiquidationExecutor
        // backdates it by VALID_FROM_BACKDATE_MILLIS for clock skew, and the slot clamp moves it again
        // — so on any interest-bearing loan the two differ by a few lovelace. loan_claim_action
        // recomputes at validFrom and compares exactly (`remainingDebt == inputAction.remainingDebt`,
        // loan_claim_action.ak:229 at ff005fb), so the assessment's figure is the wrong one to carry:
        // it is the profitability and decision-log number, and this is the chain's.
        //
        // equity follows the debt rather than being computed independently: get_equity and
        // get_equity_in_collateral_currency (lib/fluidtokens/finance.ak:348,381) take no time argument
        // at all, so all of their time dependence arrives through the remainingDebt they are handed.
        // liquidationFee is time-independent in both directions (collateralAmount and the bond's
        // per-mille rate) and is still the assessment's, recomputed and compared below.
        BigInteger remainingDebt;
        BigInteger equity;
        try {
            remainingDebt = LoanFinance.remainingDebt(datum, validFrom);
            equity = LoanFinance.redeemerEquity(liquidationMode, Rational.fromInt(loan.collateralAmount()),
                    Rational.fromInt(remainingDebt), principal.feed(), collateral.feed());
        } catch (ArithmeticException e) {
            throw refuse(Refusal.HEALTH_NOT_COMPUTABLE,
                    "loan %s at %d: %s".formatted(loan.loanId(), validFrom, e.getMessage()), e);
        }
        BigInteger liquidationFee = assessment.liquidationFee();

        // V2 — the arithmetic the chain will have to satisfy.
        if (remainingDebt.signum() <= 0) {
            throw refuse(Refusal.NON_POSITIVE_REMAINING_DEBT,
                    "loan %s has remainingDebt %s".formatted(loan.loanId(), remainingDebt));
        }
        // Defence in depth rather than a reachable case: LoanFinance.redeemerEquity mirrors
        // loan_claim_action.ak:241-268 and clamps at zero, so nothing it returns is negative. Kept
        // because the clamp is a property of that method, not of this one, and a redeemer field this
        // builder never checks is exactly the kind of thing a later edit to LoanFinance would break
        // silently.
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

        // V4 — D9's `late || can_liquidate`, still demanded at both ends of the window.
        assertLiquidatableOverWindow(loan, principal, collateral, validFrom, validTo);

        // V6 — the collateral outputs carry the lender's stake part, so it has to be decodable.
        String assetManagerAddress = assetManagerAddress(bond);

        PlutusData bondDatum = roundTrippableBondDatum(bond);

        return new VettedLoan(assessment, loanUtxo, bondUtxo, loan, bond, datum.liquidationMode(),
                principal, collateral, collateralPayout, bondDatum, assetManagerAddress,
                equityAddress(), remainingDebt, equity, recomputedFee);
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
            // ⛔ SAY WHICH END FAILED AND HOW OLD THE FEED ALREADY WAS.
            //
            // "does not cover" alone cannot tell an operator apart two very different worlds: the
            // window genuinely lapsed, or OUR SUPPLIER IS BEHIND. Measured 2026-08-27: the registry
            // API serves entries whose window opened ~5 minutes earlier, against a 600s width
            // published every ~300s — so roughly a third of each feed's usable life is gone before
            // we ever see it, and no arithmetic on our side can recover it.
            // Same distinction as LOAN_NOT_FOUND: one reading is nothing to do, the other is a fault
            // somebody else has to fix, and they were indistinguishable in the same string.
            long ageAtBuild = validFrom - feed.validFrom();
            throw refuse(Refusal.ORACLE_FEED_NOT_USABLE_OVER_WINDOW,
                    ("loan %s %s leg: feed window [%d,%d] does not cover tx window [%d,%d] — %s. "
                            + "The feed was already %ds old when this transaction was built; if that "
                            + "is a large fraction of its width, the registry API is serving us a "
                            + "stale window and the shortfall is UPSTREAM, not ours.")
                            .formatted(loanId, which, feed.validFrom(), feed.validTo(),
                                    validFrom, validTo,
                                    validFrom < feed.validFrom()
                                            ? "the tx opens BEFORE the feed does"
                                            : "the tx runs PAST the feed's end by "
                                                    + (validTo - feed.validTo()) + "ms",
                                    ageAtBuild / 1000L));
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
     * V4's window leg: D9's {@code late || can_liquidate}, at <em>both</em> ends of the validity
     * interval.
     *
     * <h3>Why this one is not narrowed to {@code validFrom} the way the debt and the equity are</h3>
     * {@code late || can_liquidate} ({@code loan_claim_action.ak:230-239}) consumes the two oracle
     * price feeds, and {@code retrieve_oracle_data} ({@code lib/fluidtokens/oracle.ak}) is passed
     * <em>both</em> {@code validFrom} and {@code validTo} and requires the feed's own window to span
     * the interval — so a feed genuinely has to hold across the whole window for this leg, and being
     * conservative about the interval it is read over costs nothing.
     *
     * <h3>Where the debt and the equity went</h3>
     * They are no longer compared here at all, because there is nothing left to compare them against:
     * {@code vet} now <em>derives</em> both from {@code validFrom} rather than taking them from the
     * assessment, so a comparison at this point would be a value against itself. What checks them is
     * {@link #assertRedeemerFiguresMatchTheBodysValidFrom}, which reads the figures back out of the
     * finished witness set and the instant back off the finished body — an artefact-level check that
     * can fail, and a self-comparison that cannot.
     */
    private static void assertLiquidatableOverWindow(Loan loan, Leg principal, Leg collateral,
                                                     long validFrom, long validTo) {
        for (long at : new long[]{validFrom, validTo}) {
            boolean liquidatable;
            try {
                Rational debt = Rational.fromInt(LoanFinance.remainingDebt(loan.datum(), at));
                Rational collateralAmount = Rational.fromInt(loan.collateralAmount());
                boolean late = LoanFinance.isRepaymentLate(loan.datum(), at);
                // D9 / loan_claim_action.ak:230 — `or { isRepaymentLate, can_liquidate }`.
                liquidatable = late || LoanFinance.canLiquidate(debt, collateralAmount,
                        LoanFinance.liquidationLtv(liquidationOf(loan)), principal.feed(), collateral.feed());
            } catch (ArithmeticException e) {
                throw refuse(Refusal.HEALTH_NOT_COMPUTABLE,
                        "loan %s at %d: %s".formatted(loan.loanId(), at, e.getMessage()), e);
            }
            if (!liquidatable) {
                throw refuse(Refusal.NOT_LIQUIDATABLE_OVER_WINDOW,
                        "loan %s is neither late nor over its liquidation ltv at %d"
                                .formatted(loan.loanId(), at));
            }
        }
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
     * The address the borrower's partial-liquidation compensation goes to: the asset-manager spend
     * credential as an <b>enterprise address</b> — no stake credential at all.
     *
     * <h3>Why the validator does not decide this, and we must</h3>
     * {@code equity_sent_to_borrower}'s {@code and{}} block opens with the comment
     * {@code //No staking check here} ({@code loan_claim_action.ak:454}) and then checks the datum,
     * the amount, the receipt condition and the flatten count — never the address beyond the payment
     * credential the filter already imposed. So every choice of stake part evaluates identically on
     * chain. That is exactly why the choice has to be made deliberately here: the chain will not
     * catch a wrong one, and staking rewards on a compensation output are real money accruing to
     * whoever the stake credential names.
     *
     * <h3>Why enterprise, and not one of the alternatives</h3>
     * We are a third-party liquidator moving someone else's money. The neutral choice is the only one
     * that is not a decision about whose money it is.
     * <ul>
     *   <li><b>The lender's stake credential</b> — which is what this builder used to emit, by reusing
     *       {@link #assetManagerAddress(LenderBond)} for both outputs. That was an accident, not a
     *       decision, and it is the one option that is affirmatively wrong: it would have the
     *       <em>borrower's</em> refund earn staking rewards for the <em>lender</em>.</li>
     *   <li><b>The bot's own stake credential</b> — self-dealing. This is operator-distributed
     *       software; an operator's node quietly staking other people's refunds to the operator is not
     *       something to ship by default.</li>
     *   <li><b>The borrower's stake credential</b> — the option we would prefer, and it does not
     *       exist. Lending v4 carries no borrower address anywhere: {@code LoanDatum} has no address
     *       field, and the protocol's only datum-carried stake credential is the bond's
     *       {@code lenderStakeCredential}. There is nothing to read.</li>
     *   <li><b>Enterprise</b> — no stake part, so no one is credited. Nothing is earned rather than
     *       the wrong party earning it.</li>
     * </ul>
     * <b>Reversible.</b> If FluidTokens ever adds a borrower stake credential to the loan datum, this
     * method is the single place that changes, and the assertion in {@code assertStructure} is the
     * single place that has to agree.
     */
    private String equityAddress() {
        return AddressProvider.getEntAddress(
                Credential.fromScript(registry.getAssetManagerSpendScriptHash()), network).getAddress();
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

        // Outputs: every bond echo, then the asset-manager outputs in RULE R order (see the class
        // javadoc, "Positive equity: rule R").
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
        // RULE R, first row — filtered slots 0..N-1, one per loan in loan-input order.
        //
        // loan_claim_action.ak:275-284 reads the borrower's compensation output as the BARE LOAN
        // INDEX into the asset-manager-credential-filtered output list. It has no redeemer
        // indirection, so that position is not negotiable: for a loan with a positive equity, slot i
        // must be its compensation output. lm_liquidate_action, which wants the claimed-collateral
        // output, reaches its slot through assetOutputIndexes[index] — a field this builder chooses —
        // so it is the one that yields.
        //
        // A loan with no equity emits no compensation output at all (loan_claim_action.ak:273's
        // `or { inputAction.equity == 0, .. }` short-circuits before looking), so its slot i holds its
        // collateral output — which is what makes the K=0 case byte-identical to the emission order
        // this branch replaced.
        for (VettedLoan loan : loanOrder) {
            if (loan.redeemerEquity().signum() > 0) {
                tx.payToContract(loan.equityAddress(),
                        assetManagerAmounts(loan, loan.redeemerEquity()), equityDatum(loan));
            } else {
                tx.payToContract(loan.assetManagerAddress(),
                        assetManagerAmounts(loan, loan.collateralPayout()), collateralDatum(loan));
            }
        }
        // RULE R, second row — filtered slots N..N+K-1: the collateral outputs displaced out of the
        // first row, in loan order. Nothing reads these by a forced position; assetOutputIndexes
        // names them, and it is computed from this very emission order — see
        // assetOutputIndexesForEquities, which V5 then re-derives off the finished body.
        for (VettedLoan loan : loanOrder) {
            if (loan.redeemerEquity().signum() > 0) {
                tx.payToContract(loan.assetManagerAddress(),
                        assetManagerAmounts(loan, loan.collateralPayout()), collateralDatum(loan));
            }
        }

        // THE LIQUIDATION FEE, PAID OUT BY NAME (T-056).
        //
        // The bot's share used to be whatever the balancer had left over — collateral minus equity
        // minus payout — and it arrived as a token-bearing CHANGE output that adaOnlyWalletUtxo()
        // correctly refuses, disabling the wallet after a SUCCESSFUL liquidation. That was repaired
        // afterwards by a postBalanceTx split, because the residual was UNEXPLAINED.
        //
        // It is explained: measured 2026-08-26, it is the liquidation fee. liquidationFeePerMille = 50
        // on all seven live bonds, read from the datum and corroborated by 49743a1e…'s split
        // (2,410,366 equity / 92,589,634 lender / 5,000,000 bot, summing to the collateral exactly).
        // A COMPUTED FEE CAN BE PAID OUT BY NAME; AN UNEXPLAINED RESIDUAL CANNOT.
        //
        // Naming it before balancing lets cardano-client-lib balance correctly instead of us repairing
        // afterwards, so change comes back ada-only BY CONSTRUCTION. Every other route by which a token
        // could reach change is closed by construction too — see docs/change-output-enumeration.md,
        // whose route 8 is the one that survives and is refused at the door instead.
        //
        // Skipped when the collateral is ada (the "fee" is then lovelace and change is ada-only
        // anyway) and when the fee is zero (a lender may set liquidationFeePerMille = 0, and an empty
        // output is not a payment).
        for (VettedLoan loan : loanOrder) {
            BigInteger fee = loan.assessment().liquidationFee();
            if (!loan.loan().datum().collateral().isAda() && fee.signum() > 0) {
                tx.payToAddress(request.changeAddress(), assetManagerAmounts(loan, fee));
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

        attachValidators(tx, request.referenceScripts());

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
     * <b>A script that travels by REFERENCE is not attached here.</b> This used to attach all six
     * unconditionally and rely on {@code removeDuplicateScriptWitnesses(true)} to strip the witness
     * copy — but that call sat inside {@code if (backendService == null && ...)}, alongside
     * {@code withReferenceScripts}, which genuinely does need that guard for fee reasons. <b>The
     * strip does not, and in production {@code backendService} is never null, so it never ran.</b>
     * <p>
     * Measured on preview 2026-08-25: a liquidation carried {@code loan_claim_action} as BOTH a
     * reference input AND an 8,662-byte witness, so publishing the script made the transaction
     * <em>larger</em> rather than smaller — 20,548 bytes against a 16,384 limit, where 20,342 was the
     * all-inline estimate. Two coordinates verified clean at boot and the bytes never came off,
     * because verification proves configuration and says nothing about use.
     * <p>
     * Skipping the attach, rather than stripping it afterwards, is the sibling
     * {@code LiquidatePayInAdvanceTransactionBuilder}'s design (since {@code e11ccca}) and is the
     * better one: the strip runs AFTER balancing, so a remote evaluator is still shown the oversized
     * body. And a script both witnessed and referenced is {@code ExtraneousScriptWitnessesUTXOW},
     * so this was a second, independent ledger rejection waiting behind the size one.
     */
    private void attachValidators(ScriptTx tx, ReferenceScripts scripts) {
        if (scripts.loanSpend() == null) {
            tx.attachSpendingValidator(registry.getLoanSpendScript());
        }
        if (scripts.lenderManagerSpend() == null) {
            tx.attachSpendingValidator(registry.getLenderManagerSpendScript());
        }
        if (scripts.loan() == null) {
            tx.attachRewardValidator(registry.getLoanScript());
        }
        if (scripts.loanClaimAction() == null) {
            tx.attachRewardValidator(registry.getLoanClaimActionScript());
        }
        if (scripts.lenderManager() == null) {
            tx.attachRewardValidator(registry.getLenderManagerScript());
        }
        if (scripts.lmLiquidateAction() == null) {
            tx.attachRewardValidator(registry.getLmLiquidateActionScript());
        }
    }


    private static final String LOVELACE = "lovelace";

    /**
     * Removes from the witness set every script that travels as a REFERENCE INPUT (rule 3, T-048).
     *
     * <h2>Why a hook is required at all</h2>
     * {@code attachValidators} already declines to attach a referenced script — but
     * {@code ScriptTx.mintAsset(script, …)} calls a <b>private</b> {@code attachMintValidator(script)}
     * unconditionally (v0.7.2 {@code ScriptTx:309}), and <b>every</b> {@code mintAsset} overload at this
     * version takes a {@code PlutusScript}. There is no policy-id form until 0.8.0. So the one script
     * this transaction both <em>mints with</em> and <em>may reference</em> — {@code loan.loan}, burned
     * to retire the loan NFT — re-enters the witness set behind the attach-skip's back.
     *
     * <h2>⚠ Why {@code preBalanceTx} and not {@code removeDuplicateScriptWitnesses}</h2>
     * The pipeline order, read from {@code QuickTxBuilder} v0.7.2:
     * <pre>
     *   :401  preBalanceTrasformer          ⇐ THIS HOOK
     *   :455  ScriptCostEvaluators.evaluateScriptCost()
     *   :470  ScriptBalanceTxProviders.balanceTx(...)
     *   :474  DuplicateScriptWitnessChecker.removeDuplicateScriptWitnesses()
     *   :478  postBalanceTrasformer
     * </pre>
     * {@code removeDuplicateScriptWitnesses} runs at <b>:474 — after evaluation AND after balancing</b>.
     * It would strip the copy from the submitted body while the <em>evaluator</em> still priced the
     * bloated one. Measured on preview 2026-08-24: an attached-and-referenced script left the body
     * <b>8,665 bytes larger at evaluation time</b> — 23,459 against a 16,384 {@code maxTxSize} — and
     * Blockfrost answered {@code EvaluationFailure} with an EMPTY {@code ScriptFailures} map.
     * <b>:401 is the only seam that removes it before anything reads it.</b>
     *
     * <h2>Both directions matter</h2>
     * Rule 3 is <em>"liquidation should be possible with a combination of reference script or attached
     * scripts (ensure only required scripts are attached)"</em> — <b>both modes must work</b>. Referenced
     * and also attached is {@code ExtraneousScriptWitnessesUTXOW}; not referenced and not attached is a
     * missing-script failure. This removes only what is provably supplied elsewhere.
     */
    static TxBuilder stripReferencedScriptsFromWitnessSet(List<PlutusScript> referenced) {
        return (ctx, txn) -> {
            if (referenced.isEmpty() || txn.getWitnessSet() == null
                    || txn.getWitnessSet().getPlutusV3Scripts() == null) {
                return;
            }
            Set<String> referencedHashes = referenced.stream()
                    .map(LiquidateTransactionBuilder::scriptHashHex)
                    .collect(java.util.stream.Collectors.toSet());
            txn.getWitnessSet().getPlutusV3Scripts()
                    .removeIf(script -> referencedHashes.contains(scriptHashHex(script)));
        };
    }

    /** A script's hash as hex. Identity here is the hash, never object equality. */
    private static String scriptHashHex(PlutusScript script) {
        try {
            return HexUtil.encodeHexString(script.getScriptHash());
        } catch (Exception e) {
            throw new IllegalStateException("cannot hash a script while stripping referenced witnesses", e);
        }
    }

    /**
     * The collateral inputs, chosen by <b>our own</b> selection rather than nominated as the spend
     * input (T-050).
     *
     * <h2>⛔ Why not CCL's auto-selection, despite the "use auto features" guideline</h2>
     * {@code QuickTxBuilder.buildCollateralOutput} (v0.7.2 {@code :507}) constructs its <b>own</b>
     * {@code DefaultUtxoSelectionStrategyImpl} from the raw {@code utxoSupplier}. It never reads
     * {@code txBuilderContext.getUtxoSelectionStrategy()}, so {@code withUtxoSelectionStrategy} and
     * the {@code preBalanceTx} selector are <b>both invisible to it</b>, and that strategy has no
     * reference-script exclusion — its {@code accept()} is {@code return true}. The paying address is
     * the bot's own, which is where a published reference script can sit. <b>One did, and an
     * unguarded builder consumed it on 2026-08-25.</b> Measured, not reasoned: with both selection
     * guards installed and this method absent, a build still pledged the published
     * {@code loan_claim_action} as collateral — <b>and collateral is what a PHASE-2 failure
     * consumes.</b>
     *
     * <p>⚠ It also <b>assumes</b> the amount rather than deriving it: {@code DEFAULT_COLLATERAL_AMT}
     * is a hardcoded {@code Amount.ada(5.0)} at {@code QuickTxBuilder:65}.
     *
     * <h2>What this does instead</h2>
     * <ul>
     *   <li><b>Not the spend input.</b> Un-welding the two roles is the whole point: the ada-only rule
     *       the spend input must satisfy was written for spending, and it was silently governing
     *       collateral capacity.</li>
     *   <li><b>Native assets are allowed.</b> CIP-0040: collateral may carry tokens <em>provided a
     *       collateral output is specified</em>, and {@code collateralPayer(...)} specifies one.
     *       Measured 2026-08-26 against CCL's real {@code CollateralBuilders}: the tokens come back in
     *       {@code collateral_return} and min-ada is satisfied.</li>
     *   <li><b>Reference scripts are excluded</b>, which is the guard CCL cannot apply.</li>
     *   <li><b>The amount is DERIVED, never assumed</b> — see {@link #maxPossibleCollateral}.</li>
     * </ul>
     */
    private TransactionInput[] collateralInputsFor(Request request) {
        return collateralInputsFor(utxoSupplier, protocolParamsSupplier,
                request.changeAddress(), request.walletUtxo());
    }

    /**
     * Shared by both liquidation builders, and <b>it takes the SUPPLIER, not the params</b>.
     *
     * <p>⚠ That signature is load-bearing. This runs before {@code complete()}'s try/catch, so any
     * remote read here escapes as a raw exception instead of the builder's named refusal — measured:
     * introducing this method turned T-040's cause-chain test from {@code TRANSACTION_NOT_BUILDABLE}
     * into {@code SocketTimeoutException}, because a throwing protocol-params supplier is <em>the</em>
     * natural way into that branch. Taking the supplier lets both remote reads — params and wallet —
     * be guarded in one place. Handing in already-fetched params would move the hazard to every caller.
     */
    static TransactionInput[] collateralInputsFor(UtxoSupplier utxoSupplier,
                                                  ProtocolParamsSupplier protocolParamsSupplier,
                                                  String changeAddress, Utxo fallback) {
        ProtocolParams params;
        try {
            params = protocolParamsSupplier.getProtocolParams();
        } catch (Exception e) {
            throw refuse(Refusal.TRANSACTION_NOT_BUILDABLE,
                    "could not read protocol parameters to size the collateral", e);
        }
        return collateralInputsFor(utxoSupplier, params, changeAddress, fallback);
    }

    /** Shared by both liquidation builders — one shape, not two copies (the T-043 lesson). */
    static TransactionInput[] collateralInputsFor(UtxoSupplier utxoSupplier, ProtocolParams params,
                                                  String changeAddress, Utxo fallback) {
        BigInteger required = maxPossibleCollateral(params);
        // ⚠ The supplier call sits OUTSIDE complete()'s try/catch, so a transport failure here would
        // escape as a raw exception name instead of the builder's named refusal — measured: the T-037
        // cause-chain test went from TRANSACTION_NOT_BUILDABLE to SocketTimeoutException the moment
        // this method was introduced. Wrapped so the error taxonomy is unchanged, and NOT silently
        // degraded to the nominated utxo: a transient network fault must not quietly reinstate the
        // welded behaviour this ticket exists to remove.
        List<Utxo> wallet;
        try {
            wallet = utxoSupplier.getAll(changeAddress);
        } catch (Exception e) {
            throw refuse(Refusal.TRANSACTION_NOT_BUILDABLE,
                    "could not read the wallet to choose collateral inputs", e);
        }
        List<Utxo> candidates = wallet.stream()
                .filter(ReferenceScriptSafeUtxoSelection::spendable)
                .filter(utxo -> utxo.getInlineDatum() == null && utxo.getDataHash() == null)
                .sorted(java.util.Comparator.comparing(LiquidateTransactionBuilder::adaOnly).reversed())
                .toList();

        int limit = Math.max(1, params.getMaxCollateralInputs());
        List<TransactionInput> chosen = new ArrayList<>();
        BigInteger capacity = BigInteger.ZERO;
        for (Utxo utxo : candidates) {
            if (chosen.size() >= limit || capacity.compareTo(required) >= 0) {
                break;
            }
            chosen.add(inputOf(utxo));
            capacity = capacity.add(adaOnly(utxo));
        }

        if (chosen.isEmpty()) {
            // Fall back to the nominated wallet utxo rather than hand CCL an empty array, which would
            // silently re-enable its own unguarded selector — the exact defect this method exists for.
            return new TransactionInput[]{inputOf(fallback)};
        }
        return chosen.toArray(TransactionInput[]::new);
    }

    /**
     * The most collateral any transaction this ledger accepts could ever require — <b>derived from the
     * protocol parameters, never assumed.</b>
     *
     * <p>The requirement is {@code fee × collateral_percent}, and the fee is not known until balancing
     * has run — so selection, which happens before, needs an upper bound rather than a guess. The
     * ledger supplies one: a transaction cannot exceed {@code maxTxSize} bytes, nor
     * {@code maxTxExMem}/{@code maxTxExSteps} of execution budget, so
     * {@code minFeeA·maxTxSize + minFeeB + priceMem·maxTxExMem + priceStep·maxTxExSteps} is a ceiling
     * on any fee it will accept.
     *
     * <p><b>This is the difference between this method and CCL's hardcoded 5 ADA:</b> the number moves
     * when the chain's parameters move, and it is provably sufficient rather than conventionally so.
     * On preview 2026-08 it lands near 3.6 ADA — below CCL's constant, and for a reason.
     *
     * <p>The exact requirement is still checked after building, against the real fee, by
     * {@code assertCollateralIsCoverable}. <b>Select generously; assert exactly.</b>
     */
    static BigInteger maxPossibleCollateral(ProtocolParams params) {
        return com.fluidtokens.aquarium.offchain.util.LedgerCeilings.maxPossibleCollateral(params);
    }

    /**
     * Refuses a loan whose UTxO carries anything beyond lovelace, its declared collateral and its
     * loan NFT (T-056, route 8 of {@code docs/change-output-enumeration.md}).
     *
     * <p>Nothing in this builder pays out an undeclared asset, so it would reach the change output and
     * make the bot's own wallet unusable. <b>Refusing narrows the blast radius from every liquidation
     * to this one.</b>
     */
    private static void refuseIfLoanCarriesAnUndeclaredAsset(LoanLiquidation liquidation,
                                                             String loanPolicyId) {
        Utxo loanUtxo = liquidation.loanUtxo();
        if (loanUtxo == null || loanUtxo.getAmount() == null) {
            return;
        }
        var loan = liquidation.assessment().loan();
        AssetType collateral = loan.datum().collateral().assetType();
        refuseUndeclaredAssets(loanUtxo.getAmount(),
                collateral.isAda() ? AssetType.LOVELACE : unitOf(collateral),
                loanPolicyId + loan.loanId(), loan.loanId(), utxoRef(loanUtxo));
    }

    /**
     * The predicate itself, taking only what it uses so it is testable without a whole request.
     *
     * <p>Permitted: lovelace, the declared collateral, and the loan NFT. <b>Anything else is paid out
     * by no output</b> — {@code assetManagerAmounts} emits only the declared collateral — so it would
     * reach the bot's change and disable the wallet.
     */
    static void refuseUndeclaredAssets(List<Amount> amounts, String collateralUnit,
                                       String loanNftUnit, String loanId, String loanUtxoRef) {
        for (Amount amount : amounts) {
            String unit = amount.getUnit();
            if (AssetType.LOVELACE.equals(unit) || collateralUnit.equalsIgnoreCase(unit)
                    || loanNftUnit.equalsIgnoreCase(unit)) {
                continue;
            }
            throw refuse(Refusal.LOAN_UTXO_CARRIES_UNDECLARED_ASSET,
                    ("loan %s at %s carries %s, which is neither its declared collateral (%s) nor its "
                            + "loan NFT (%s). Nothing pays it out, so it would land in the bot's change "
                            + "and disable the wallet. Someone sent this token to that address.")
                            .formatted(loanId, loanUtxoRef, unit, collateralUnit, loanNftUnit));
        }
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
     * Assembles and balances one body — <b>once</b>.
     * <p>
     * Since T-051 there is exactly one assembly per build: the layout probe is gone, because the
     * output indexes are computed from the emission order instead of observed off a throwaway body
     * (see "Pass 4" in {@code build()} and docs/ledger-index-ordering.md). That assembly is always
     * priced, so every redeemer carries measured ex-units — and it means exactly one evaluation, i.e.
     * one remote round trip in production, per build. Giovanni's outcome, in his words: <i>"we
     * shouldn't build tx twice."</i>
     *
     * @param verify V5, installed as a {@code postBalanceTx} hook so it runs INSIDE the library's
     *               build pipeline (v0.7.2 {@code QuickTxBuilder:478}) rather than after
     *               {@code build()} returns. A path that forgets to call it cannot exist, because
     *               there is no separate call to forget (T-054).
     *               <p>⛔ <b>Not {@code withVerifier}</b>, despite the name: that hook is consulted at
     *               {@code QuickTxBuilder:567-571}, inside {@code complete()} — <b>the SUBMIT path</b>
     *               — and {@code build()}/{@code buildAndSign()} reference it nowhere. These builders
     *               are deliberately submit-incapable and call {@code build()}, so
     *               {@code withVerifier} is dead code here. It works on the tank, which submits.
     */
    private Transaction complete(Request request, ScriptTx tx, TxBuilder verify) {
        TransactionEvaluator evaluator =
                scriptCostEvaluator != null ? reporting(scriptCostEvaluator) : null;
        long[] slots = validitySlots(request);
        // Production: the one-argument constructor the library documents, which wires the utxo
        // supplier, protocol params, SCRIPT SUPPLIER and transaction processor from one backend.
        // The script supplier is what lets it fetch a validator that only exists on chain as a
        // reference script; without it a reference-script transaction cannot be priced. Offline:
        // the three-argument form with no processor and no supplier, because the rigs hand every
        // script in explicitly and evaluate for themselves.
        QuickTxBuilder quickTxBuilder = backendService != null
                ? new QuickTxBuilder(backendService)
                : new QuickTxBuilder(utxoSupplier, protocolParamsSupplier, null);
        QuickTxBuilder.TxContext context = quickTxBuilder
                        .compose(tx)
                        .feePayer(request.changeAddress())
                        .collateralPayer(request.changeAddress())
                        .validFrom(slots[0])
                        .validTo(slots[1])
                        .mergeOutputs(false)
                        // With an evaluator, a failed evaluation must stop the build: the default
                        // (true) turns it into a log.warn and hands back a transaction whose redeemers
                        // still carry placeholder ex-units, which is a phase-2 failure waiting to be
                        // submitted. Without one, the flag stays true because there is nothing to
                        // evaluate with and the offline rigs price the transaction themselves.
                        .ignoreScriptCostEvaluationError(evaluator == null)
                        // Same guard as the pay-in-advance builder: cardano-client-lib's default
                        // coin selection has no reference-script exclusion, and the bot's published
                        // scripts live at its own address. See ReferenceScriptSafeUtxoSelection.
                        .withUtxoSelectionStrategy(
                                ReferenceScriptSafeUtxoSelection.strategy(utxoSupplier))
                        // The strategy above guards only the path ChangeOutputAdjustments tries SECOND. The
                        // UtxoSelector it tries FIRST has no withUtxoSelector on TxContext, so it is installed
                        // here — preBalanceTx hands over the TxBuilderContext itself and runs before balancing.
                        //
                        // ⛔ AND IT IS COMPOSED, NOT ADDED. QuickTxBuilder.preBalanceTx is a SETTER
                        // (:262-263, `this.preBalanceTrasformer = txBuilder`), not an adder: a second
                        // call SILENTLY REPLACES the first. Calling it again for the witness strip
                        // below would have deleted this selector — the guard written after an
                        // unguarded builder consumed a published reference script (6c5ee75).
                        .preBalanceTx(((TxBuilder) (ctx, txn) ->
                                ctx.setUtxoSelector(ReferenceScriptSafeUtxoSelection.selector(utxoSupplier)))
                                .andThen(stripReferencedScriptsFromWitnessSet(
                                        publishedScripts(request.referenceScripts()))))
                        // COLLATERAL is chosen by neither of the above: QuickTxBuilder.buildCollateralOutput
                        // (:507) builds its OWN DefaultUtxoSelectionStrategyImpl rather than reading the
                        // context's, so withUtxoSelectionStrategy and the selector alike are invisible to it.
                        // Measured, not reasoned: with both guards installed and this line absent, a build
                        // still pledged the published loan_claim_action script as collateral — and collateral
                        // is what a PHASE-2 failure consumes, which makes it the worse of the two paths.
                        //
                        // Pinning it to the wallet utxo is safe and costs no extra funding: that utxo is
                        // already an explicit input (collectFrom above), the executor's adaOnlyWalletUtxo()
                        // guarantees it is ada-only with no datum and no reference script, and one utxo may
                        // serve as both spend input and collateral (CCL trap 12 — a collateral return is
                        // emitted). Pinning also excludes it from ordinary coin selection, which changes
                        // nothing here precisely because it was never selected: it is handed in.
                        .withCollateralInputs(collateralInputsFor(request));


        if (backendService == null) {
            // Offline: cardano-client-lib would otherwise walk every reference input looking for a
            // script to fetch and NPE on the missing supplier. The rigs hand scripts in explicitly.
            context = context.withScriptSupplier(scriptHash -> Optional.empty());
        }
        if (evaluator != null) {
            // With an evaluator present, ignoreScriptCostEvaluationError above is false, so a failed
            // evaluation stops the build rather than shipping placeholder ex-units (CCL trap 8).
            context = context.withTxEvaluator(evaluator);
        }

        // Same trap as the pay-in-advance builder, mirrored deliberately: withReferenceScripts with
        // a PARTIAL list makes FeeCalculators price only what it was handed and skip the supplier
        // that would have priced every reference script. With a backend, declare nothing and let the
        // supplier do it. See LiquidatePayInAdvanceTransactionBuilder.complete for the measurement.
        if (verify != null) {
            context = context.postBalanceTx(verify);
        }

        List<PlutusScript> published = publishedScripts(request.referenceScripts());
        if (backendService == null && !published.isEmpty()) {
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
     *
     * <p>⚠ <b>The {@code slotFrom} clamp is now INERT on the common production path, and that is
     * correct rather than dead.</b> Since T-064 anchored the window
     * ({@code LiquidationExecutor.windowAnchorMillis}), {@code validFromMillis} is
     * {@code min(now, tipMillis) - 30_000}. Whenever the chain is in a block gap — the case the
     * anchor exists for — that is {@code tipMillis}, which is <b>slot-aligned</b>, minus a whole
     * number of slots, so the guard condition is simply false and no correction is needed. It still
     * fires when {@code now < tipMillis}, and a mutant deleting it still fails one test.
     * <b>Do not delete it as unreachable, and do not assume it stays inert:</b> change the backdate
     * to anything that is not a whole multiple of the slot length and it starts mattering again,
     * silently. (officina {@code ccl-transaction-building-traps} trap 20, closing paragraph — <i>"a
     * conditional correction that silently stops firing is indistinguishable from one that was never
     * needed — expect it to vanish, and notice that it did."</i>)
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

    /**
     * Refuses a transaction whose collateral input cannot cover {@code fee × collateral_percent}.
     *
     * <h2>⚠ Why this is checked on the BUILT body and not before building</h2>
     * The required collateral is a function of the <b>fee</b>, and the fee is not known until
     * balancing has run. A pre-build version would have to estimate it, and estimating it would mean
     * either being wrong or reimplementing the balancer. The only sound pre-build bound is
     * {@code minFeeB × collateral_percent} — for the measured failure that is 233,072 against a
     * 1,000,000 capacity, so <b>it would have passed and caught nothing</b>. Building is free: no
     * signature, no submission, nothing on chain. Refusing here costs a build and is exact.
     *
     * <h2>⛔ Why this refuses instead of clamping the return to zero</h2>
     * Clamping produces a transaction that <em>parses</em> and is still wrong — it would silently
     * over-collateralise, and the failure would move somewhere quieter. The shortfall is real: the
     * wallet does not hold enough pure ada to back this script transaction, and that is a fact about
     * the wallet, not a number to round.
     *
     * <h2>Re-examined under T-050 — KEPT, with its role restated</h2>
     * Selection now covers {@link #maxPossibleCollateral}, the ledger's own ceiling on any fee it will
     * accept, so this should never fire because of a <em>builder</em> mistake. <b>That is exactly why
     * it stays.</b> What it now reports is the one thing selection cannot fix: <b>the wallet does not
     * hold enough pure-ada-equivalent collateral to fund a liquidation at all.</b> It converts that
     * from an unparseable artefact into a named refusal with a recorded decision.
     * <b>Select generously (before the build, from a derived ceiling); assert exactly (after, against
     * the real fee).</b> A guard that no longer fires for the original reason is not dead — it is the
     * one that catches the case the new mechanism cannot.
     *
     * <p>Both readings are asserted, and <b>both now come from the artefact</b>: its own
     * {@code collateral_return} must not be negative, and its declared {@code total_collateral} must
     * reach what the ledger demands. They can disagree — cardano-client-lib chooses the collateral
     * set, and if it ever chooses differently from what this builder nominated, the artefact is the
     * one telling the truth.
     *
     * <p>⚠ <b>The second reading used to measure the NOMINATED WALLET UTXO, and that was wrong after
     * T-050.</b> Collateral inputs are chosen separately now, so the wallet utxo is not the quantity
     * this guard is about; T-052 then shrank it further. See the comment at that line — it is the
     * half of T-050's re-examination that did not happen, and it could have produced FALSE
     * {@code INSUFFICIENT_COLLATERAL} refusals on good candidates.
     */
    static void assertCollateralIsCoverable(ProtocolParams params, Transaction transaction,
                                            Utxo walletUtxo) {
        var body = transaction.getBody();
        BigInteger fee = body.getFee();

        // The artefact first: a negative return is the unparseable case, whatever produced it.
        if (body.getCollateralReturn() != null) {
            BigInteger returned = body.getCollateralReturn().getValue().getCoin();
            if (returned.signum() < 0) {
                throw refuse(Refusal.INSUFFICIENT_COLLATERAL,
                        ("collateral return is NEGATIVE (%s lovelace) — a negative MaryValue cannot be "
                                + "encoded, so no node could parse this transaction. fee %s, total "
                                + "collateral %s, collateral input %s")
                                .formatted(returned, fee, body.getTotalCollateral(),
                                        utxoRef(walletUtxo)));
            }
        }

        BigInteger percent = params.getCollateralPercent().toBigInteger();
        // Ceiling division: the ledger requires AT LEAST this much, so rounding down would let a
        // one-lovelace shortfall through the very guard that exists to stop it.
        BigInteger required = fee.multiply(percent)
                .add(BigInteger.valueOf(99)).divide(BigInteger.valueOf(100));

        // ⚠ THIS TERM READ `adaOnly(walletUtxo)` UNTIL 2026-08-26, AND THAT WAS T-050's UNFINISHED HALF.
        //
        // It was correct while the NOMINATED WALLET UTXO WAS THE COLLATERAL SOURCE. T-050 made the
        // builder choose collateral inputs SEPARATELY (withCollateralInputs, sized to
        // maxPossibleCollateral, possibly several utxos), so the wallet utxo stopped being the
        // quantity this check is about — and T-052 then made it SYSTEMATICALLY SMALLER by nominating
        // the smallest input that covers the FEE ceiling rather than the largest available.
        //
        // Measured: it could fire. capacity was >= maxPossibleFee (2,607,027 from LIVE preview
        // parameters) by T-052's rule, and required is fee x 150%, so a FALSE refusal needed only
        // fee > 1,738,018 — against an observed liquidation fee of 1,113,523. A 56% margin is not a
        // proof, and reference-script fees (which maxPossibleFee does not model at all) and batching
        // both eat into it.
        //
        // ⚠ Those figures were first written as 2,405,077 / 1,603,385 / "44%", all derived from
        // LoanFixtures' pinned maxTxExMem of 14,000,000 — cardano-client-lib's built-in value, not
        // preview's 17,500,000 (officina CCL trap 7). The margin was WIDER than stated, and the
        // conclusion is unchanged: a margin is not a proof, and this guard was measuring the wrong
        // quantity regardless of how much room it happened to have.
        //
        // ⇒ The right quantity is what the TRANSACTION DECLARES, not what some utxo holds. Read off
        // the artefact, so it needs no extra remote read — twice today a moved remote read has
        // changed a failure mode.
        BigInteger declared = body.getTotalCollateral();
        if (declared == null) {
            throw refuse(Refusal.INSUFFICIENT_COLLATERAL,
                    ("the transaction declares no total_collateral, so its collateral coverage cannot "
                            + "be verified from the artefact at all. fee %s, collateral_percent %s%%, "
                            + "required %s").formatted(fee, percent, required));
        }
        if (declared.compareTo(required) < 0) {
            throw refuse(Refusal.INSUFFICIENT_COLLATERAL,
                    ("the transaction declares %s lovelace of total_collateral but the ledger requires "
                            + "%s (fee %s × collateral_percent %s%%), so it would be rejected. "
                            + "nominated wallet input %s")
                            .formatted(declared, required, fee, percent, utxoRef(walletUtxo)));
        }
    }

    /** The lovelace an ada-only utxo holds. The caller has already vetted its shape. */
    private static BigInteger adaOnly(Utxo utxo) {
        return utxo.getAmount().stream()
                .filter(amount -> AssetType.LOVELACE.equals(amount.getUnit()))
                .map(com.bloxbean.cardano.client.api.model.Amount::getQuantity)
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

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

        // ⇒ AND THE WHOLE LIST, RE-DERIVED INDEPENDENTLY. The emitted list was COMPUTED from rule R
        // and loan order (assetOutputIndexes(loanOrder)); this one is OBSERVED off the finished body
        // by collateral-datum match. Two provenances, so a disagreement is a real signal — which is
        // what the deleted layout probe used to provide for free, and what CCL_PREPENDED_OUTPUTS is
        // exposed to if a library upgrade moves the prepended-output count.
        structural(assetOutputIndexes.equals(assetOutputIndexes(outputs,
                        registry.getAssetManagerSpendScriptHash(),
                        loanOrder.stream().map(v -> collateralDatum(v).serializeToHex()).toList())),
                "assetOutputIndexes computed from the emission order disagree with the finished body");

        // The redeemer's two time-dependent figures, taken back off the artefact and re-derived from
        // the artefact's own instant. Returns the emitted equities, in loan order.
        List<BigInteger> emittedEquity = assertRedeemerFiguresMatchTheBodysValidFrom(transaction, loanOrder);

        for (int i = 0; i < loanOrder.size(); i++) {
            VettedLoan loan = loanOrder.get(i);
            ClaimData claim = claims.get(i);
            structural(claim.loanId().equals(loan.loan().loanId()),
                    "actionsForEachInput[%d] is loan %s, expected %s"
                            .formatted(i, claim.loanId(), loan.loan().loanId()));
            structural(loan.collateralPayout().equals(loan.loan().collateralAmount()
                            .subtract(emittedEquity.get(i)).subtract(loan.recomputedFee())),
                    ("claim %d: the collateral payout %s is not collateral %s - the emitted redeemer's "
                            + "equity %s - the recomputed fee %s")
                            .formatted(i, loan.collateralPayout(), loan.loan().collateralAmount(),
                                    emittedEquity.get(i), loan.recomputedFee()));

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
            String collateralUnit = unitOf(loan.loan().datum().collateral().assetType());
            TransactionOutput collateralOutput = onlyOutputWithDatum(outputs, loan.assetManagerAddress(),
                    collateralDatum(loan), "collateral output for loan " + loan.loan().loanId());
            List<Amount> collateralAmounts = assertAssetManagerOutput(collateralOutput,
                    loan.assetManagerAddress(), adaCollateral,
                    "collateral output for loan " + loan.loan().loanId());
            structural(quantityIn(collateralAmounts, collateralUnit).equals(loan.collateralPayout()),
                    "collateral output for loan %s carries %s, expected collateral - equity - fee = %s"
                            .formatted(loan.loan().loanId(),
                                    quantityIn(collateralAmounts, collateralUnit),
                                    loan.collateralPayout()));
        }

        // RULE R's first row, located off the FINISHED body's own filtered list. The expectation is
        // built from the equity the EMITTED redeemer carries, not from the builder's own variable, so
        // a loan whose redeemer says "no equity" is expected to have no compensation output.
        //
        // This runs BEFORE the equity assertions below and hands them the outputs they check, which is
        // deliberate: everything V5 says about a compensation output is then a statement about the
        // output at the slot loan_claim_action will actually read. Locating it by scanning the body
        // for its datum instead — the shape this replaced — checks the right bytes at whatever slot
        // they happen to occupy, and passes just as happily when the layout is wrong.
        List<String> compensationDatums = new ArrayList<>();
        for (int i = 0; i < loanOrder.size(); i++) {
            compensationDatums.add(emittedEquity.get(i).signum() > 0
                    ? equityDatum(loanOrder.get(i)).serializeToHex()
                    : null);
        }
        List<TransactionOutput> compensationOutputs = compensationOutputsAtTheirLoanIndex(
                assetManagerFilteredOutputs, compensationDatums,
                loanOrder.stream().map(loan -> loan.loan().loanId()).toList());

        // Equity outputs — one per loan with a positive equity, and none for the others.
        //
        // The min-ada calculator is built once and only if this batch has a compensation output at
        // all: a zero-equity batch reaches the protocol params exactly as often as it did before the
        // subsidy bound existed, which is what keeps that path unchanged end to end.
        MinAdaCalculator minAdaCalculator = null;
        long expectedEquityOutputs = 0;
        for (int i = 0; i < loanOrder.size(); i++) {
            VettedLoan loan = loanOrder.get(i);
            BigInteger equity = emittedEquity.get(i);
            if (equity.signum() <= 0) {
                continue;
            }
            expectedEquityOutputs++;
            String what = "equity output for loan " + loan.loan().loanId();

            // The output at the forced slot, and the only output in the body carrying this datum,
            // must be one and the same. Reference equality is exact here — both lists hold entries of
            // transaction.getBody().getOutputs() itself — and it is what rules out a second output
            // with the same datum sitting somewhere the validator would never look.
            TransactionOutput equityOutput = compensationOutputs.get(i);
            structural(equityOutput == onlyOutputWithDatum(outputs, loan.equityAddress(),
                            equityDatum(loan), what),
                    ("%s at filtered asset-manager slot %d is not the body's only output carrying "
                            + "that datum").formatted(what, i));
            List<Amount> equityAmounts = assertAssetManagerOutput(equityOutput, loan.equityAddress(),
                    loan.loan().datum().collateral().isAda(), what);
            // loan_claim_action checks the compensation with `>=`, so a min-ada top-up is legal here
            // where it would not be on the collateral output. Legal is not free: the top-up comes out
            // of the bot's inputs, so the rider is bounded to one min-ada before the `>=` is checked
            // on the value that bound vetted.
            if (minAdaCalculator == null) {
                minAdaCalculator = new MinAdaCalculator(protocolParamsSupplier.getProtocolParams());
            }
            BigInteger emittedCompensation = assertCompensationSubsidyBounded(equityOutput, equityAmounts,
                    unitOf(loan.loan().datum().collateral().assetType()), equity,
                    minAdaCalculator.calculateMinAda(equityOutput), what);
            structural(emittedCompensation.compareTo(equity) >= 0,
                    "%s carries %s, less than the redeemer's equity %s"
                            .formatted(what, emittedCompensation, equity));

            // The stake part the validator explicitly does not check — see equityAddress(). Asserted
            // in two independent ways off the emitted address: it must decompose to the asset-manager
            // spend credential with NO delegation part, and it must be exactly the enterprise address
            // that credential derives on this network.
            Address emitted = new Address(equityOutput.getAddress());
            structural(registry.getAssetManagerSpendScriptHash()
                            .equals(paymentCredentialOf(equityOutput.getAddress())),
                    "%s is not at the asset-manager spend credential".formatted(what));
            structural(emitted.getDelegationCredential().isEmpty(),
                    ("%s carries a stake credential; the borrower's compensation goes to an enterprise "
                            + "address so that no third party earns staking rewards on it")
                            .formatted(what));
            structural(equityOutput.getAddress().equals(equityAddress()),
                    "%s is at %s, expected the enterprise address %s"
                            .formatted(what, equityOutput.getAddress(), equityAddress()));
        }

        // Nothing else may sit at an asset-manager address: exactly one collateral output per loan
        // plus the equity outputs just accounted for. Both addresses count — the collateral outputs'
        // (which carry the lender's stake part) and the equity outputs' enterprise one.
        Set<String> assetManagerAddresses = Stream.concat(
                        loanOrder.stream().map(VettedLoan::assetManagerAddress),
                        loanOrder.stream().map(VettedLoan::equityAddress))
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

    /**
     * V5's money check: the two time-dependent redeemer figures, taken back out of the
     * <em>artefact</em> and compared against a recomputation from the artefact's own instant.
     *
     * <h3>Why this is a check and not a tautology</h3>
     * {@code vet} derives {@code remainingDebt} and {@code equity} at {@code validFrom}, and
     * {@code loan_claim_action} recomputes them at {@code validFrom} and compares
     * ({@code loan_claim_action.ak:212-222,229} at {@code ff005fb}). Asserting the builder's own
     * variable against the same formula would compare a value to itself and could never fail. So
     * neither side of the comparisons below is a variable this class carried down from {@code vet}:
     * <ul>
     *   <li>the <b>figures</b> come from the {@code loan_claim_action} withdrawal's redeemer, decoded
     *       back out of {@code transaction.getWitnessSet()} — the bytes that will be submitted, after
     *       the second assembly, the balancing and the script costing;</li>
     *   <li>the <b>instant</b> comes from {@code transaction.getBody().getValidityStartInterval()} —
     *       the slot that will be submitted — converted to POSIX millis the way the ledger will.</li>
     * </ul>
     * It therefore fails, loudly and before anything is returned, if the body ends up carrying a
     * validity range other than the one the figures were computed against (a changed clamp, a
     * different slot conversion, an assembly that dropped the range), or if a redeemer reaches the
     * witness set carrying figures from any other instant — the scan instant being the one that
     * actually happened: {@code LiquidationCandidateScanner} assesses at scan time and
     * {@code LiquidationExecutor} backdates {@code validFrom} by 30 s, which on a loan at
     * {@code interestRate = 459} is a real two-lovelace divergence and a refused transaction.
     * <p>
     * The claim rows are joined to loans by the redeemer's own {@code loanId} field, not by position,
     * so a reordering cannot make a row match the wrong loan's arithmetic.
     *
     * @return each loan's equity <em>as emitted</em>, in loan order, for the output-value assertions
     */
    private List<BigInteger> assertRedeemerFiguresMatchTheBodysValidFrom(Transaction transaction,
                                                                        List<VettedLoan> loanOrder) {
        long slot = transaction.getBody().getValidityStartInterval();
        long validFrom = millisOf(converters.slot().slotToTime(slot));

        List<List<PlutusData>> rows = emittedClaimRows(transaction);
        structural(rows.size() == loanOrder.size(),
                "the emitted loan-claim redeemer carries %d actions for %d loan inputs"
                        .formatted(rows.size(), loanOrder.size()));

        List<BigInteger> emittedEquity = new ArrayList<>();
        for (int i = 0; i < loanOrder.size(); i++) {
            VettedLoan loan = loanOrder.get(i);
            List<PlutusData> row = rows.get(i);
            structural(row.size() == ClaimData.FIELD_COUNT,
                    "emitted ClaimData %d has %d fields, expected %d"
                            .formatted(i, row.size(), ClaimData.FIELD_COUNT));

            String emittedLoanId = HexUtil.encodeHexString(((BytesPlutusData) row.get(6)).getValue());
            structural(emittedLoanId.equalsIgnoreCase(loan.loan().loanId()),
                    "emitted ClaimData %d is loan %s, expected %s"
                            .formatted(i, emittedLoanId, loan.loan().loanId()));

            BigInteger emittedDebt = ((BigIntPlutusData) row.get(7)).getValue();
            BigInteger equity = ((BigIntPlutusData) row.get(5)).getValue();

            BigInteger debtAtBodyValidFrom;
            BigInteger equityAtBodyValidFrom;
            try {
                debtAtBodyValidFrom = LoanFinance.remainingDebt(loan.loan().datum(), validFrom);
                equityAtBodyValidFrom = LoanFinance.redeemerEquity(liquidationOf(loan.loan()),
                        Rational.fromInt(loan.loan().collateralAmount()),
                        Rational.fromInt(debtAtBodyValidFrom),
                        loan.principal().feed(), loan.collateral().feed());
            } catch (ArithmeticException e) {
                throw refuse(Refusal.HEALTH_NOT_COMPUTABLE,
                        "loan %s at the body's validFrom %d (slot %d): %s"
                                .formatted(loan.loan().loanId(), validFrom, slot, e.getMessage()), e);
            }

            if (!debtAtBodyValidFrom.equals(emittedDebt)) {
                throw refuse(Refusal.REMAINING_DEBT_NOT_INVARIANT,
                        ("loan %s: remainingDebt is %s at the built body's validFrom %d (slot %d) but "
                                + "the emitted redeemer carries %s")
                                .formatted(loan.loan().loanId(), debtAtBodyValidFrom, validFrom, slot,
                                        emittedDebt));
            }
            if (!equityAtBodyValidFrom.equals(equity)) {
                throw refuse(Refusal.EQUITY_NOT_REPRODUCIBLE,
                        ("loan %s: equity is %s at the built body's validFrom %d (slot %d) but the "
                                + "emitted redeemer carries %s")
                                .formatted(loan.loan().loanId(), equityAtBodyValidFrom, validFrom, slot,
                                        equity));
            }
            emittedEquity.add(equity);
        }
        return emittedEquity;
    }

    /**
     * {@code actionsForEachInput} — field 1 of the {@code loan_claim_action} withdrawal's redeemer —
     * as it sits in the finished witness set, one field list per claim.
     */
    private List<List<PlutusData>> emittedClaimRows(Transaction transaction) {
        String reward = rewardAddress(registry.getLoanClaimActionScriptHash());
        List<Withdrawal> withdrawals = transaction.getBody().getWithdrawals();
        int index = -1;
        for (int i = 0; withdrawals != null && i < withdrawals.size(); i++) {
            if (withdrawals.get(i).getRewardAddress().equals(reward)) {
                index = i;
            }
        }
        structural(index >= 0, "the finished body has no withdrawal at " + reward);

        PlutusData data = null;
        for (Redeemer redeemer : transaction.getWitnessSet().getRedeemers()) {
            if (redeemer.getTag() == RedeemerTag.Reward && redeemer.getIndex().intValue() == index) {
                data = redeemer.getData();
            }
        }
        structural(data != null,
                "the finished witness set has no Reward redeemer %d, the loan_claim_action withdrawal"
                        .formatted(index));

        List<PlutusData> fields = ((ConstrPlutusData) data).getData().getPlutusDataList();
        structural(fields.size() == 2,
                "the emitted LoanClaimActionWithdrawRedeemer has %d fields, expected 2"
                        .formatted(fields.size()));
        return ((ListPlutusData) fields.get(1)).getPlutusDataList().stream()
                .map(action -> ((ConstrPlutusData) action).getData().getPlutusDataList())
                .toList();
    }

    /**
     * D7's shape check on one asset-manager output: it sits at exactly {@code expectedAddress}, and
     * its value flattens to exactly one entry for ada collateral or two otherwise — the
     * {@code dosProtection} conjunct both {@code validate_repayment_output} and
     * {@code equity_sent_to_borrower} impose. The address is a parameter rather than
     * {@code loan.assetManagerAddress()} because the two outputs no longer share one: the collateral
     * output carries the lender's stake part, the compensation output is an enterprise address.
     *
     * <h3>Why this <em>returns</em> the flattened amounts rather than only asserting on them</h3>
     * Same reason {@link #compensationOutputsAtTheirLoanIndex} does. As a {@code void} guard it was
     * optional: either of its two call sites could be deleted and the whole suite stayed green,
     * because the amount checks that follow each of them re-flattened the output for themselves.
     * Handing the flattened list back, and making those amount checks read it, means a deleted call
     * no longer compiles. Package-private and static for the same reason again — a guard whose only
     * exercise is the happy path is a guard nobody has watched fail, and neither call site can be
     * made to emit a wrong flatten count from a legitimate build, so the violating bodies have to be
     * handed to it directly.
     *
     * @return {@code output}'s value, flattened — one {@link Amount} per unit, lovelace included
     */
    static List<Amount> assertAssetManagerOutput(TransactionOutput output, String expectedAddress,
                                                 boolean adaCollateral, String what) {
        structural(output.getAddress().equals(expectedAddress),
                what + " is at " + output.getAddress() + ", expected " + expectedAddress);
        List<Amount> amounts = ValueUtil.toAmountList(output.getValue());
        int flattened = amounts.size();
        structural(flattened == (adaCollateral ? 1 : 2),
                what + " flattens to " + flattened + " assets, expected " + (adaCollateral ? 1 : 2));
        return amounts;
    }

    /**
     * <b>The min-ada the bot funds on a borrower-compensation output is bounded to one min-ada.</b>
     * <p>
     * {@code equity_sent_to_borrower} checks the compensation with {@code >=}, not the {@code ==} the
     * claimed-collateral output gets, so a lovelace rider on it is legal on chain and nothing upstream
     * refuses one. That rider is real money and it is the <em>bot's</em>: the output is emitted
     * carrying only the equity quantity, and cardano-client-lib tops it to min-ada out of the bot's
     * own inputs during balancing. On token collateral the whole coin is that subsidy — the equity is
     * denominated in the collateral token and the lovelace is pure rider. On ada collateral it is the
     * shortfall between a sub-min-ada equity and the floor: an equity of one lovelace still emits an
     * output of a full min-ada.
     * <p>
     * A min-ada rider is unavoidable — the ledger will not accept the output without one — so this
     * does not refuse it. It refuses anything <em>larger</em>, which is the part no validator, no
     * balancer and no profitability check would ever object to and which would therefore grow
     * silently. The ceiling is computed from the caller's protocol params against this very output,
     * so it moves with the params rather than being pinned to a number.
     * <p>
     * <b>This is a bound, not an accounting fix.</b> {@code LiquidationExecutor}'s expected-profit
     * arithmetic still does not subtract this subsidy — see its javadoc at the calculation. Bounding
     * it caps how wrong that arithmetic can be; it does not make it right.
     *
     * @param amounts        {@code output}'s flattened value, as {@link #assertAssetManagerOutput}
     *                       returned it
     * @param collateralUnit the unit the equity is denominated in — the collateral asset's
     * @param equity         the equity the emitted redeemer carries, in {@code collateralUnit}
     * @param minAda         the min-ada {@code output} itself requires under the caller's protocol
     *                       params
     * @return the {@code collateralUnit} quantity {@code output} carries, so the caller's {@code >=}
     *         check is made on the value this method vetted and deleting the call stops compiling
     */
    static BigInteger assertCompensationSubsidyBounded(TransactionOutput output, List<Amount> amounts,
                                                       String collateralUnit, BigInteger equity,
                                                       BigInteger minAda, String what) {
        // Only an ada-denominated equity is payable in the coin field; a token equity rides on top of
        // a coin that is subsidy end to end.
        BigInteger equityInLovelace = AssetType.LOVELACE.equals(collateralUnit) ? equity : BigInteger.ZERO;
        BigInteger ceiling = equityInLovelace.add(minAda);
        BigInteger coin = output.getValue().getCoin();
        structural(coin.compareTo(ceiling) <= 0,
                ("%s carries %s lovelace against %s of ada-denominated equity, so the bot subsidises "
                        + "%s — more than the %s min-ada this output requires")
                        .formatted(what, coin, equityInLovelace, coin.subtract(equityInLovelace), minAda));
        return quantityIn(amounts, collateralUnit);
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

    /**
     * The {@code unit} quantity in an already-flattened value. It takes the flattened list rather than
     * the output on purpose: the list is what {@link #assertAssetManagerOutput} hands back, so every
     * amount check in {@code assertStructure} is made on a value that guard has vetted.
     */
    private static BigInteger quantityIn(List<Amount> amounts, String unit) {
        return amounts.stream()
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
