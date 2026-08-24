package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.api.DefaultProtocolParamsSupplier;
import com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
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
import com.fluidtokens.aquarium.offchain.model.loans.Loan;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationMode;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.model.loans.Rational;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import com.fluidtokens.aquarium.offchain.service.TransactionInputComparator;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A <b>test-only</b> builder for a {@code LiquidateAndPayInAdvance} liquidation — the money-path
 * mode in which the bot pays the loan's principal in advance (in ADA) and takes the collateral,
 * for a lender bond whose {@code shouldLiquidationConvertToPrincipal == True}. It reproduces the
 * shape the deployed preview {@code lm_liquidate_and_pay_in_advance_action},
 * {@code lender_manager.lenderManager}, {@code loan_claim_action}, {@code loan.loan},
 * {@code general_spend} and the FluidTokens oracle accept under the real PlutusV3 machine, for
 * <b>one</b> loan.
 *
 * <h2>Ex-units are measured, not guessed</h2>
 * As in {@link LiquidateTransactionBuilder} (T-014), a redeemer's declared ex-units are not checked by
 * the mempool: a transaction that under-declares is accepted, lands on chain, and then exhausts its
 * budget during on-chain evaluation — phase 2, fee and collateral forfeit. cardano-client-lib fills
 * every redeemer with a placeholder (10000 mem, and 10000 or 1000 steps) and only overwrites it from a
 * {@link TransactionEvaluator}; with no evaluator set, {@code QuickTxBuilder} swallows the resulting
 * "Transaction evaluator is not set" because {@code ignoreScriptCostEvaluationError} defaults to
 * {@code true}. So the evaluator is <em>optional but load-bearing</em>, exactly as it is on the plain
 * path:
 * <ul>
 *   <li><b>Supplied</b> (the production wiring in {@code YaciConfig}) — it is set with
 *       {@code withTxEvaluator}, and {@code ignoreScriptCostEvaluationError(false)} turns a failed
 *       evaluation into a build failure the executor quarantines with its cause, instead of a
 *       {@code log.warn} followed by a transaction that would burn collateral. Only the final assembly
 *       is priced; the throwaway layout probe is not (its placeholder output indexes would make a real
 *       evaluator refuse every batch).</li>
 *   <li><b>Absent</b> — the offline test rigs, which have no network and evaluate separately against
 *       the real PlutusV3 machine ({@code LiquidatePayInAdvanceDryEvalTest}). Behaviour is then exactly
 *       as it was: placeholder ex-units, no throw.</li>
 * </ul>
 *
 * <h2>Holds a backend in production, and never submits</h2>
 * {@link #build} returns an <b>unsigned</b> {@link Transaction}; there is no signer, no key. In the
 * offline rigs the {@link QuickTxBuilder} is constructed with a <b>null</b> {@code TransactionProcessor}
 * and every script is handed in explicitly. In production it is constructed from a
 * {@code BackendService} — the one-argument constructor the library documents — so cardano-client-lib
 * can fetch a validator travelling as a reference script (the oracle script and, on preview,
 * {@code loan_claim_action}) to price and fee the transaction correctly; this mirrors
 * {@link LiquidateTransactionBuilder} exactly, which found on its first armed night that a
 * reference-script transaction cannot be priced without that supplier. The safety property is
 * therefore <b>a stated decision, not a constructor trick</b>: nothing in this class calls
 * {@code submit}, and arming and submission live only in {@code LiquidationExecutor} behind its two
 * independent flags. The evaluator ({@link TransactionEvaluator}) has one method and no way to submit,
 * so pricing the transaction does not grant submitting it.
 *
 * <h2>How it differs from the plain {@code Liquidate} builder</h2>
 * The transaction is the plain-liquidation shape with three changes the pay-in-advance validators
 * force:
 * <ul>
 *   <li>the parent {@code lender_manager.lenderManager} withdraw carries
 *       {@code LenderManagerAction.LiquidateAndPayInAdvance} (constructor index 3, verified against
 *       {@code lib/fluidtokens/types/lender_manager.ak} at {@code ff005fb}), not {@code Liquidate};</li>
 *   <li>the action withdraw is {@code lm_liquidate_and_pay_in_advance_action}, whose redeemer adds a
 *       {@code principalAndCollateralOracleRefInputIndexes} list to the four fields the plain one has;
 *       its script is <b>witness-attached</b> because {@code EvalFixtures#scriptSupplier} does not
 *       carry it;</li>
 *   <li>the lender is paid in ADA — {@code convertedLoanCollateralToPrincipalAmount}, an
 *       ADA-only output ({@code dosProtection} demands {@code flatten == 1}) carrying the
 *       {@code converted_to_liquidity} action — instead of the collateral itself.</li>
 * </ul>
 * The borrower's equity compensation (in the collateral currency, tFLDT) and the loan-NFT burn are
 * unchanged: {@code loan_claim_action} governs them exactly as in a plain liquidation, so on a
 * positive-equity loan the compensation output sits at the bare loan index in the asset-manager
 * filtered output list and the lender's converted output is reached through
 * {@code assetOutputIndexes}.
 *
 * <h2>Every index is derived from the finished body</h2>
 * {@code lenderBondOutputIndex} (an absolute output position) and {@code assetOutputIndexes} (a
 * position in the asset-manager-credential-filtered output list) are things cardano-client-lib
 * decides, not this builder. As {@code LiquidateTransactionBuilder} does, the transaction is
 * assembled once with placeholder indexes purely to observe the finished layout (the <em>probe</em>),
 * the real indexes are read off that body, and it is assembled again; then {@link #assertStructure}
 * re-derives them from the finished body and refuses on any mismatch.
 */
public final class LiquidatePayInAdvanceTransactionBuilder {

    /** The unit redeemer {@code Constr 0 []} the {@code general_spend} handler takes. */
    private static final PlutusData GENERAL_SPEND_REDEEMER =
            ConstrPlutusData.builder().alternative(0).data(ListPlutusData.of()).build();

    /**
     * ASCII {@code "converted_to_liquidity"} — {@code constants.converted_to_liquidity_action}
     * ({@code lib/fluidtokens/constants.ak} at {@code ff005fb}), the asset-manager action on the
     * lender's paid-in-advance output. Built from the ASCII literal rather than pinned as hex so it
     * cannot silently drift from the constant it stands for.
     */
    static final String CONVERTED_TO_LIQUIDITY_ACTION_HEX =
            HexUtil.encodeHexString("converted_to_liquidity".getBytes(StandardCharsets.US_ASCII));

    /** ASCII {@code "partial_liquidation"} — {@code constants.action_partial_liquidation_compensation}. */
    static final String PARTIAL_LIQUIDATION_ACTION_HEX =
            HexUtil.encodeHexString("partial_liquidation".getBytes(StandardCharsets.US_ASCII));

    private final LoansContractRegistry registry;
    private final Network network;
    private final UtxoSupplier utxoSupplier;
    private final ProtocolParamsSupplier protocolParamsSupplier;

    /**
     * Where the redeemers' ex-units come from, or {@code null} for "nowhere" — see "Ex-units are
     * measured, not guessed" in the class javadoc. Nullable rather than optional because the two states
     * are not a preference: with it, the transaction is priced and a failed evaluation fails the build;
     * without it, the transaction carries placeholders and must not be submitted.
     */
    private final TransactionEvaluator scriptCostEvaluator;

    /**
     * The backend cardano-client-lib builds against in production, or {@code null} for the offline rigs.
     * When present, {@code QuickTxBuilder} is constructed from it directly so it has the utxo supplier,
     * protocol params, <b>script supplier</b> and transaction processor in one object — the script
     * supplier is what lets it fetch a validator travelling as a reference script. See the class javadoc
     * for why holding this does not reopen the submission path.
     */
    private final BackendService backendService;

    /**
     * The offline builder: no evaluator, so redeemers keep cardano-client-lib's placeholder ex-units.
     * For the test rigs, which evaluate separately. Production goes through the {@code BackendService}
     * constructor.
     */
    public LiquidatePayInAdvanceTransactionBuilder(LoansContractRegistry registry,
                                                   Network network,
                                                   UtxoSupplier utxoSupplier,
                                                   ProtocolParamsSupplier protocolParamsSupplier) {
        this(registry, network, utxoSupplier, protocolParamsSupplier, null, null);
    }

    /**
     * Offline builder with an evaluator — what the dry-eval rig uses to prove the priced path against
     * the deployed validators without a network.
     */
    public LiquidatePayInAdvanceTransactionBuilder(LoansContractRegistry registry,
                                                   Network network,
                                                   UtxoSupplier utxoSupplier,
                                                   ProtocolParamsSupplier protocolParamsSupplier,
                                                   TransactionEvaluator scriptCostEvaluator) {
        this(registry, network, utxoSupplier, protocolParamsSupplier, null, scriptCostEvaluator);
    }

    /**
     * The production constructor. {@code QuickTxBuilder} is built from the {@code BackendService}
     * exactly as the library documents, so it has a script supplier that can fetch a validator
     * travelling as a reference script. The evaluator is passed separately (Blockfrost's
     * {@code /utils/txs/evaluate}, wired in {@code YaciConfig}) so its parameters and cost models are
     * the chain's by construction.
     */
    public LiquidatePayInAdvanceTransactionBuilder(LoansContractRegistry registry,
                                                   Network network,
                                                   BackendService backendService,
                                                   TransactionEvaluator scriptCostEvaluator) {
        this(registry, network,
                new DefaultUtxoSupplier(Objects.requireNonNull(backendService, "backendService").getUtxoService()),
                new DefaultProtocolParamsSupplier(backendService.getEpochService()),
                backendService, scriptCostEvaluator);
    }

    private LiquidatePayInAdvanceTransactionBuilder(LoansContractRegistry registry,
                                                    Network network,
                                                    UtxoSupplier utxoSupplier,
                                                    ProtocolParamsSupplier protocolParamsSupplier,
                                                    BackendService backendService,
                                                    TransactionEvaluator scriptCostEvaluator) {
        this.registry = registry;
        this.network = network;
        this.utxoSupplier = utxoSupplier;
        this.protocolParamsSupplier = protocolParamsSupplier;
        this.backendService = backendService;
        this.scriptCostEvaluator = scriptCostEvaluator;
    }

    /**
     * Everything the pay-in-advance transaction needs, all of it honest values — the adversarial
     * shape {@code LiquidatePayInAdvanceDryEvalTest} feeds the machine is produced by byte-surgery on
     * the finished body, not by mis-configuring this request.
     *
     * @param loan             the decoded loan, spent through {@code general_spend}
     * @param loanUtxo         the loan UTxO
     * @param bond             the decoded lender bond
     * @param bondUtxo         the lender-bond UTxO; its output is echoed byte-for-byte
     * @param walletUtxo       funds the ADA paid in advance to the lender, plus fees and min-ada
     * @param configUtxo       the main config reference input
     * @param lmConfigUtxo     the LenderManager config reference input
     * @param oracle           the Charli3-backed collateral oracle entry
     * @param validFromMillis  the instant the debt and equity are computed at — must be the POSIX time
     *                         {@code validFromSlot} converts back to, so the on-chain recomputation matches
     * @param validFromSlot    the validity range lower bound, in slots
     * @param validToSlot      the validity range upper bound, in slots
     * @param changeAddress    fee payer and change address (the bot); it keeps the collateral it took
     * @param referenceScripts published reference-script coordinates for the validators this builder
     *                         can shed to a reference input; a {@code null} field means "carry that
     *                         script in the witness set instead". {@link LiquidateTransactionBuilder.ReferenceScripts#none()}
     *                         is the all-inline shape. This builder only honours the subset it attaches
     *                         and that the shared record can name — see {@link #publishedScripts}.
     */
    public record Request(Loan loan,
                          Utxo loanUtxo,
                          LenderBond bond,
                          Utxo bondUtxo,
                          Utxo walletUtxo,
                          Utxo configUtxo,
                          Utxo lmConfigUtxo,
                          OracleEntry oracle,
                          long validFromMillis,
                          /**
                           * The validity upper bound in milliseconds, derived from {@code validToSlot}
                           * by the caller — that slot is what the ledger converts back into the
                           * {@code validTo} the validator reads out of {@code self.validity_range},
                           * so the window checked here is the window the chain will check.
                           */
                          long validToMillis,
                          long validFromSlot,
                          long validToSlot,
                          String changeAddress,
                          LiquidateTransactionBuilder.ReferenceScripts referenceScripts,
                          /**
                           * How much of the oracle feed's window must still be unused AFTER this
                           * transaction's {@code validTo}. Same meaning and same source as the plain
                           * path's field of this name — {@code loans.liquidation.oracle-window-margin-seconds}.
                           */
                          long oracleWindowMarginMillis) {
    }

    /** The five numbers the redeemer and the outputs rest on, all computed through {@link LoanFinance}. */
    public record Numbers(BigInteger remainingDebt,
                          BigInteger equity,
                          BigInteger liquidationFee,
                          BigInteger collateralLenderShouldReceive,
                          BigInteger convertedLoanCollateralToPrincipalAmount) {
    }

    /**
     * The five numbers, computed exactly as {@code LiquidateTransactionBuilder} and the on-chain
     * validators do: the debt and equity at {@code validFromMillis}, the fee from the bond's per-mille
     * rate, and — the pay-in-advance-specific one — the collateral the lender should receive converted
     * to the principal currency (ADA) through the two oracles. Since the principal is ADA its feed is
     * the 1:1 unit feed, so {@code convertFromAToBWithOracles} reduces to
     * {@code ceil(collateralReceive priced in lovelace)}.
     */
    public Numbers numbers(Request request) {
        LoanDatum datum = request.loan().datum();
        LiquidationMode.Liquidation mode = (LiquidationMode.Liquidation) datum.liquidationMode();
        BigInteger collateralAmount = request.loan().collateralAmount();

        BigInteger remainingDebt = LoanFinance.remainingDebt(datum, request.validFromMillis());
        BigInteger equity = LoanFinance.redeemerEquity(mode, Rational.fromInt(collateralAmount),
                Rational.fromInt(remainingDebt), OraclePriceFeed.unit(), request.oracle().feed());
        BigInteger liquidationFee = Rational.required(
                        collateralAmount.multiply(request.bond().datum().liquidationFeePerMille()),
                        BigInteger.valueOf(1000))
                .floor();
        BigInteger collateralLenderShouldReceive = collateralAmount.subtract(equity).subtract(liquidationFee);
        BigInteger converted = LoanFinance.toLovelace(
                Rational.fromInt(collateralLenderShouldReceive), request.oracle().feed()).ceil();
        return new Numbers(remainingDebt, equity, liquidationFee, collateralLenderShouldReceive, converted);
    }

    /**
     * Assembles and completes the transaction: probe for the layout, rebuild with the observed
     * indexes, then re-derive and assert them against the finished body.
     *
     * <h2>Reviewer's orientation — what this transaction IS</h2>
     * A pay-in-advance liquidation is not "sell the collateral". The bot <b>fronts the loan's
     * principal out of its own wallet</b>, pays the lender the principal-currency (ADA) value of the
     * collateral, takes the collateral tokens for itself, and keeps
     * {@code liquidationFeePerMille} of them as its profit. That is why a wallet UTxO is an input at
     * all: nothing in the loan pays for this, the bot advances the money.
     *
     * <h2>Why EIGHT redeemers — {@code Spend#1, Spend#2, Mint#0, Reward#0..#4}</h2>
     * <ul>
     *   <li><b>{@code Spend#1}</b> — the loan UTxO being spent, governed by {@code loan_spend}.</li>
     *   <li><b>{@code Spend#2}</b> — the lender bond UTxO, governed by {@code lender_manager_spend}.
     *       The bot's own wallet input needs no redeemer: it is a plain key input.</li>
     *   <li><b>{@code Mint#0}</b> — the loan NFT is <b>burned</b>. Closing a loan retires its NFT
     *       while the lender bond survives as the lender's separate claim ticket, withdrawn later
     *       through {@code lmWithdrawBondsAction}. (That asymmetry is also why the scanner reports so
     *       many {@code LOAN_NOT_FOUND} bonds — see that enum constant.)</li>
     *   <li><b>{@code Reward#0..#4}</b> — five <b>withdraw-0</b> invocations, explained next.</li>
     * </ul>
     *
     * <h2>The withdraw-0 pattern, which is genuinely non-obvious</h2>
     * Four of the five validators here are never "spent" — they are <b>staking</b> scripts, invoked by
     * placing a <b>withdrawal of zero lovelace</b> from their own reward account in the transaction.
     * The ledger must run the script to authorise the withdrawal, so a zero withdrawal is simply a way
     * to say "run this validator once for the whole transaction" without attaching it to any
     * particular input. It is how the contract system runs one global rule-check per transaction
     * instead of re-running it per input. The five are: the oracle, {@code loan.loan},
     * {@code lm_liquidate_and_pay_in_advance_action}, {@code lender_manager}, and
     * {@code loan_claim_action}. <b>Each one's stake credential must be registered on chain</b> or the
     * ledger cannot resolve the withdrawal — verified registered for all five on preview 2026-08-24.
     *
     * <h2>Why the transaction is built TWICE</h2>
     * Several redeemer fields name <b>absolute output positions</b> ({@code lenderBondOutputIndex},
     * {@code assetOutputIndex}). Those positions cannot be written down in advance: cardano-client-lib
     * inserts a dummy output for transactions carrying withdrawals and appends change afterwards, so
     * where our outputs land is only knowable once the body exists. So the first pass — the
     * <b>layout probe</b> — is assembled with placeholder indexes purely to be measured; the observed
     * positions are then fed into a second, real assembly. <b>The probe's body is discarded and never
     * returned.</b> Its redeemers name indexes no validator would accept, which is why it is
     * deliberately not script-costed (see {@link LayoutProbeEvaluator}).
     *
     * <h2>What the ledger sorts, and what must therefore never be assumed</h2>
     * The ledger orders <b>inputs</b> canonically (by transaction id, then index) and <b>withdrawals</b>
     * by reward address — <em>not</em> in the order they were added here. A {@code Spend#n} redeemer
     * points at position {@code n} of the SORTED input list, so changing which wallet UTxO is spent can
     * move every index. Never read an index off insertion order; measure it off the finished body.
     *
     * <h2>Reference inputs, and why</h2>
     * Six inputs are read but not spent: the two config UTxOs (protocol parameters the validators read
     * live), three oracle UTxOs (the price feed, its script, the Charli3 provider), and the published
     * {@code loan_claim_action} <b>reference script</b>. That last one exists purely for size: with
     * every validator inline the body is ~23.5 kB against a 16,384-byte {@code maxTxSize}, and
     * referencing the largest script brings it to ~14.8 kB. A script reached by reference must NOT
     * also be attached to the witness set — see {@link #attachValidators}.
     *
     * <h2>How this differs from the plain {@code Liquidate} path</h2>
     * The plain path requires {@code shouldLiquidationConvertToPrincipal == False} and pays the lender
     * in collateral tokens. This path requires it {@code True}, advances ADA, and converts through the
     * oracle. They are different on-chain actions with different script hashes; a convert bond is
     * refused outright by the plain builder's V7 guard.
     *
     * <h2>⚠ WHERE THE BUILD CURRENTLY DIES (2026-08-24) — read before re-running experiments</h2>
     * Against live preview this build fails inside {@link #complete}, at script-cost evaluation:
     * Blockfrost answers {@code EvaluationFailure} with an <b>empty</b> {@code ScriptFailures} map,
     * i.e. "the transaction could not be evaluated at all" — never "a validator said no". The
     * following have each been ELIMINATED BY MEASUREMENT against the live chain, so please do not
     * re-derive them:
     * <ol>
     *   <li><b>Funding</b> — the largest eligible wallet UTxO (58.4 ADA) is nominated; still fails.</li>
     *   <li><b>Spent or unresolvable inputs</b> — all 3 inputs and all 6 reference inputs verified
     *       unspent. A control transaction citing a genuinely non-existent input <em>succeeds</em>
     *       at this endpoint, so this failure is not an input-resolution problem.</li>
     *   <li><b>Transaction size</b> — was 23,459 bytes because a referenced script was also attached;
     *       fixed, now 14,794 under a 16,384 limit; failure unchanged.</li>
     *   <li><b>Serialisation era</b> — forcing Babbage instead of Conway changes nothing.</li>
     *   <li><b>Withdrawal stake credentials</b> — all five registered on chain.</li>
     *   <li><b>Mint purpose</b> — the burn policy {@code loan.loan} IS in the witness set.</li>
     *   <li><b>Redeemer indexes</b> — verified against the canonically sorted body: correct.</li>
     *   <li><b>Collateral</b> — the {@code collateralReturn}/{@code totalCollateral} triple is
     *       inconsistent at evaluation time, but correcting it three different ways changes
     *       nothing.</li>
     * </ol>
     * <b>Every offline test of this builder passes.</b> The offline rig does not enforce
     * {@code maxTxSize}, evaluates unbalanced intermediates, and serves every script by hash — so a
     * green suite here means "the deployed validators accept this shape", NOT "the chain will accept
     * this transaction". Three separate defects this day were invisible offline and immediate on
     * Blockfrost.
     */
    public Transaction build(Request request) {
        Numbers numbers = numbers(request);
        if (numbers.equity().signum() <= 0) {
            throw new IllegalStateException(
                    "this builder models the positive-equity pay-in-advance layout; equity was "
                            + numbers.equity());
        }
        if (!request.bond().datum().shouldLiquidationConvertToPrincipal()) {
            throw new IllegalStateException(
                    "lm_liquidate_and_pay_in_advance_action requires shouldLiquidationConvertToPrincipal == True");
        }
        // V3 — the oracle feed must cover this transaction's WHOLE validity window, with the
        // operator's margin still unused after it. This mirrors LiquidateTransactionBuilder's V3
        // (~:1136-1148) deliberately: the plain path has had this check since the beginning and this
        // path never did, and that divergence IS the defect. Do not invent a third shape here.
        //
        // Why it matters, measured against live preview 2026-08-24: the deployed
        // lm_liquidate_and_pay_in_advance_action reads validFrom/validTo out of
        // self.validity_range and passes BOTH into retrieve_oracle_data, which returns None unless
        // the feed covers that window. `expect Some(..)` on a None ABORTS the validator — and an
        // aborting expect produces a script failure with an EMPTY trace, which Blockfrost reports as
        // `{"ScriptFailures":{}}` with nothing named. Every convert liquidation built in the tail of
        // a feed's life failed exactly that way, and the empty report is why it took a day to find.
        //
        // Preview's Charli3 feeds are CONTIGUOUS 600s windows, not overlapping (measured: one feed's
        // validTo 1787576664408 against the next one's validFrom 1787576664367), so there is never a
        // fresher feed to pick instead. Refusing and rebuilding on a later cycle is the only correct
        // response, and it is exactly what the plain path already does.
        long feedRemainingAfterValidTo = request.oracle().feed().validTo() - request.validToMillis();
        if (!request.oracle().feed().usableOver(request.validFromMillis(), request.validToMillis())) {
            throw new PayInAdvanceLiquidationRouter.PayInAdvanceNotModelledException(
                    "oracle feed window [%d,%d] does not cover tx window [%d,%d]".formatted(
                            request.oracle().feed().validFrom(), request.oracle().feed().validTo(),
                            request.validFromMillis(), request.validToMillis()));
        }
        if (feedRemainingAfterValidTo < request.oracleWindowMarginMillis()) {
            throw new PayInAdvanceLiquidationRouter.PayInAdvanceNotModelledException(
                    "only %dms of oracle feed window left after validTo, %dms required".formatted(
                            feedRemainingAfterValidTo, request.oracleWindowMarginMillis()));
        }

        List<TransactionInput> refInputs = referenceInputs(request);
        int configRefIndex = refIndex(refInputs, inputOf(request.configUtxo()), "main config");
        int lmConfigRefIndex = refIndex(refInputs, inputOf(request.lmConfigUtxo()), "lm config");
        int collateralOracleRefIndex = refIndex(refInputs, request.oracle().referenceInput(), "collateral oracle");
        // Principal is ADA: retrieve_oracle_data short-circuits on policyId == "" and never reads the
        // reference input, but the index must still be in range. Index 0 of the canonically sorted
        // reference inputs always is — the same value LiquidateTransactionBuilder gives an ada leg.
        int principalOracleRefIndex = 0;
        int providerRefIndex = refIndex(refInputs, request.oracle().charlieProviderReferenceInput(),
                "charli3 provider");

        // Probe with placeholder output indexes purely to observe the finished layout. Never priced:
        // its claim redeemers carry placeholder output indexes no validator accepts, so a real evaluator
        // run against it would fail by construction and refuse every batch.
        Transaction probe = complete(request, assemble(request, numbers, configRefIndex, lmConfigRefIndex,
                collateralOracleRefIndex, principalOracleRefIndex, providerRefIndex, refInputs, 0L, 0L),
                false);

        long lenderBondOutputIndex = locateBondOutput(probe, request);
        long assetOutputIndex = locateLenderConvertedOutput(probe, request, numbers);

        Transaction transaction = complete(request, assemble(request, numbers, configRefIndex,
                lmConfigRefIndex, collateralOracleRefIndex, principalOracleRefIndex, providerRefIndex,
                refInputs, lenderBondOutputIndex, assetOutputIndex), true);

        assertStructure(transaction, request, numbers, lenderBondOutputIndex, assetOutputIndex);
        return transaction;
    }

    // ---- assembly ---------------------------------------------------------------------------------

    private ScriptTx assemble(Request request, Numbers numbers, int configRefIndex, int lmConfigRefIndex,
                              int collateralOracleRefIndex, int principalOracleRefIndex,
                              int providerRefIndex, List<TransactionInput> refInputs,
                              long lenderBondOutputIndex, long assetOutputIndex) {
        ScriptTx tx = new ScriptTx();
        String loanId = request.loan().loanId();

        // Inputs. The general_spend handlers take a unit redeemer; the real authorisation is the
        // withdraw-0 invocation of the validator they wrap. The wallet funds the paid-in-advance ada.
        tx.collectFrom(request.loanUtxo(), GENERAL_SPEND_REDEEMER);
        tx.collectFrom(request.bondUtxo(), GENERAL_SPEND_REDEEMER);
        tx.collectFrom(request.walletUtxo());

        // Burn the loan NFT (loan_claim_action.ak requires quantity_of(self.mint, loanPolicyId, loanId) == -1).
        tx.mintAsset(registry.getLoanScript(),
                List.of(new Asset("0x" + loanId, BigInteger.ONE.negate())),
                LiquidationTxEncoder.loanMintRedeemer(configRefIndex, false, 0));

        // Outputs. The bond echo first, then — in the filtered asset-manager list — the borrower's
        // equity compensation at slot 0 (loan_claim_action reads it at the bare loan index) and the
        // lender's paid-in-advance ada at slot 1 (lm_liquidate_and_pay_in_advance_action reaches it
        // through assetOutputIndexes).
        tx.payToContract(request.bondUtxo().getAddress(), List.copyOf(request.bondUtxo().getAmount()),
                bondEchoDatum(request));
        tx.payToContract(assetManagerAddress(), collateralEquityAmounts(request, numbers.equity()),
                borrowerCompensationDatum(request));
        tx.payToContract(assetManagerAddress(),
                List.of(Amount.lovelace(numbers.convertedLoanCollateralToPrincipalAmount())),
                lenderConvertedDatum(request));

        // Withdraw-0 invocations. The main config authorises loan / claim / pay-in-advance action; the
        // parent LenderManager reads the LM config and carries the LiquidateAndPayInAdvance action.
        tx.withdraw(rewardAddress(registry.getLoanPolicyId()), BigInteger.ZERO,
                LiquidationTxEncoder.loanWithdrawRedeemer(configRefIndex), request.changeAddress());
        tx.withdraw(rewardAddress(registry.getLoanClaimActionScriptHash()), BigInteger.ZERO,
                LiquidationTxEncoder.loanClaimActionWithdrawRedeemer(configRefIndex,
                        List.of(claimData(request, numbers, lenderBondOutputIndex,
                                collateralOracleRefIndex, principalOracleRefIndex))),
                request.changeAddress());
        tx.withdraw(rewardAddress(registry.getLenderManagerWithdrawScriptHash()), BigInteger.ZERO,
                lenderManagerPayInAdvanceRedeemer(lmConfigRefIndex), request.changeAddress());
        tx.withdraw(rewardAddress(registry.getLmLiquidateAndPayInAdvanceActionScriptHash()), BigInteger.ZERO,
                payInAdvanceActionRedeemer(configRefIndex, List.of(0L), List.of(request.loan().loanId()),
                        List.of(principalOracleRefIndex), List.of(collateralOracleRefIndex),
                        List.of(assetOutputIndex)),
                request.changeAddress());
        tx.withdraw(request.oracle().rewardAddress(), BigInteger.ZERO,
                LiquidationTxEncoder.oracleRedeemer(request.oracle().feed(), providerRefIndex, List.of()),
                request.changeAddress());

        tx.readFrom(refInputs.toArray(TransactionInput[]::new));

        attachValidators(tx, request.referenceScripts());
        return tx.withChangeAddress(request.changeAddress());
    }

    /**
     * The six validators this transaction invokes, attached to the witness set. The
     * pay-in-advance action script is the one {@code EvalFixtures#scriptSupplier} does not carry, so
     * attaching every one of them keeps the resolution self-contained (the oracle travels by reference
     * input, resolved from the {@code List.of(oracleScript())} extra at evaluation time).
     */
    private void attachValidators(ScriptTx tx, LiquidateTransactionBuilder.ReferenceScripts scripts) {
        // A script that travels by REFERENCE must not also be attached here. cardano-client-lib's
        // removeDuplicateScriptWitnesses(true) does strip the copy — but only AFTER balancing, and
        // script-cost evaluation runs BEFORE that. So an attached-and-referenced script is still in
        // the witness set when the transaction is handed to the evaluator, and a REMOTE evaluator is
        // shown a body 8,665 bytes larger than the one that would finally be submitted. Measured on
        // preview 2026-08-24: 23,459 bytes against a live maxTxSize of 16,384 — 43% over — and
        // Blockfrost answered EvaluationFailure with an EMPTY ScriptFailures map, i.e. "could not be
        // evaluated at all". The offline rig never saw this because it does not enforce maxTxSize.
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
        // No ReferenceScripts field names the pay-in-advance action, so it always travels inline.
        tx.attachRewardValidator(registry.getLmLiquidateAndPayInAdvanceActionScript());
    }

    /**
     * The scripts the caller says are published, paired with the registry object for each — only the
     * five this builder attaches AND that the shared {@link LiquidateTransactionBuilder.ReferenceScripts}
     * record can name. It deliberately IGNORES {@code lmLiquidateAction}: this builder attaches the
     * pay-in-advance action {@code getLmLiquidateAndPayInAdvanceActionScript}, which has no
     * {@code ReferenceScripts} field and so cannot be referenced. It also ignores {@code assetManager},
     * which this builder never attaches ({@link #attachValidators}). On preview only
     * {@code loanClaimAction} is set, which is the 8 665-byte biggest inline script and brings the
     * transaction under {@code maxTxSize}.
     */
    private List<PlutusScript> publishedScripts(LiquidateTransactionBuilder.ReferenceScripts scripts) {
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
        return published;
    }

    /**
     * Assembles and balances one body. Mirrors {@link LiquidateTransactionBuilder#complete}.
     *
     * @param priceScripts whether this assembly is the one whose redeemers must carry measured
     *                     ex-units. Only the final assembly is; the layout probe is not (see
     *                     {@link #build}).
     */
    private Transaction complete(Request request, ScriptTx tx, boolean priceScripts) {
        TransactionEvaluator evaluator =
                priceScripts && scriptCostEvaluator != null ? reporting(scriptCostEvaluator) : null;
        // The probe assembly is never priced — its claim redeemers carry placeholder output indexes
        // no validator accepts — but cardano-client-lib DEMANDS an evaluator the moment balancing
        // adds an input, and throws "Transaction evaluator is not set" on that path regardless of
        // ignoreScriptCostEvaluationError. Without this the build dies in the probe whenever the
        // nominated wallet utxo does not cover the whole transaction alone. See LayoutProbeEvaluator.
        TransactionEvaluator contextEvaluator = evaluator != null
                ? evaluator
                : (priceScripts ? null : LayoutProbeEvaluator.INSTANCE);
        try {
            // Production: the one-argument constructor the library documents, which wires the utxo
            // supplier, protocol params, SCRIPT SUPPLIER and transaction processor from one backend —
            // the script supplier is what lets it fetch a validator that only exists on chain as a
            // reference script. Offline: the three-argument form with no processor and no supplier,
            // because the rigs hand every script in explicitly and evaluate for themselves.
            QuickTxBuilder quickTxBuilder = backendService != null
                    ? new QuickTxBuilder(backendService)
                    : new QuickTxBuilder(utxoSupplier, protocolParamsSupplier, null);
            QuickTxBuilder.TxContext context = quickTxBuilder
                    .compose(tx)
                    .feePayer(request.changeAddress())
                    .collateralPayer(request.changeAddress())
                    .validFrom(request.validFromSlot())
                    .validTo(request.validToSlot())
                    .mergeOutputs(false)
                    // With an evaluator, a failed evaluation must stop the build: the default (true)
                    // turns it into a log.warn and hands back a transaction whose redeemers still carry
                    // placeholder ex-units — a phase-2 failure waiting to be submitted. Without one, the
                    // flag stays true because there is nothing to evaluate with and the offline rig
                    // prices the transaction itself.
                    .ignoreScriptCostEvaluationError(evaluator == null)
                    // The balancer must never reach for a UTxO carrying a published reference
                    // script: on preview the loan_claim_action script sits at the bot's OWN
                    // operational address, and cardano-client-lib's default selection has no
                    // reference-script exclusion at all (verified against the pinned v0.7.2 source).
                    // Spending it would refuse every later convert liquidation as TX_TOO_LARGE.
                    .withUtxoSelectionStrategy(ReferenceScriptSafeUtxoSelection.strategy(utxoSupplier))
                    // The strategy above guards only the path ChangeOutputAdjustments tries SECOND. The
                    // UtxoSelector it tries FIRST has no withUtxoSelector on TxContext, so it is installed
                    // here — preBalanceTx hands over the TxBuilderContext itself and runs before balancing.
                    .preBalanceTx((ctx, txn) ->
                            ctx.setUtxoSelector(ReferenceScriptSafeUtxoSelection.selector(utxoSupplier)))
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
                    .withCollateralInputs(inputOf(request.walletUtxo()));

            if (backendService == null) {
                // Offline: cardano-client-lib would otherwise walk every reference input looking for a
                // script to fetch and NPE on the missing supplier. The rig hands scripts in explicitly.
                context = context.withScriptSupplier(scriptHash -> Optional.empty());
            }
            if (contextEvaluator != null) {
                // Keyed on contextEvaluator, but ignoreScriptCostEvaluationError above stays keyed on
                // `evaluator`: a probe must never turn an evaluation failure into a build failure.
                context = context.withTxEvaluator(contextEvaluator);
            }

            // ⚠ CCL TRAP: withReferenceScripts with a PARTIAL list makes the fee LESS complete.
            // FeeCalculators (v0.7.2, ~:125-145) branches on context.getRefScripts(): when it is
            // EMPTY it uses the ScriptSupplier to fetch and price EVERY script reachable from the
            // reference inputs; when it is non-empty it prices ONLY the scripts it was handed and
            // never consults the supplier. So passing MORE information produces a SMALLER fee.
            //
            // We can only name the scripts in our own registry. The oracle's script is a third
            // party's, reachable by reference input but absent from publishedScripts(), so declaring
            // ours silently un-priced theirs. Measured on preview 2026-08-24: the ledger rejected the
            // first real liquidation with FeeTooSmallUTxO {supplied 1213011, expected 1275007}, short
            // by 61,996 -- and the oracle script is 4,138 bytes, ~62,070 at the reference-script rate.
            // That is not a plausible match, it is the thing itself.
            //
            // With a backend we therefore declare NOTHING and let its supplier price them all.
            // Verified before relying on it: Blockfrost serves BOTH by hash --
            // loan_claim_action 9ae63b26… (8,662 bytes) and the oracle 402c984d… (4,138 bytes).
            // Offline there is no supplier and no ledger to satisfy, so the declaration stays.
            //
            // removeDuplicateScriptWitnesses is no longer needed on either path: since e11ccca a
            // script that travels by reference is not attached in the first place, so there is no
            // duplicate to strip.
            List<PlutusScript> published = publishedScripts(request.referenceScripts());
            if (backendService == null && !published.isEmpty()) {
                context = context.withReferenceScripts(published.toArray(PlutusScript[]::new))
                        .removeDuplicateScriptWitnesses(true);
            }
            return context.build();
        } catch (Exception e) {
            // An evaluator failure arrives here with the ScriptCostEvaluationException marker at the
            // head of its cause chain; the executor's convert branch quarantines this candidate and
            // logs the whole cause chain at ERROR, so the evaluator's own words ("Blockfrost is down",
            // "costed 3 of 8 redeemers") reach the operator rather than a bare "Error while evaluating
            // script cost". See LiquidationExecutor's pay-in-advance catch.
            throw new IllegalStateException("cannot build the pay-in-advance transaction", e);
        }
    }

    /**
     * Thrown by {@link #reporting} when the evaluator itself fails to price the transaction, so the
     * operator-facing detail carries the evaluator's root cause rather than cardano-client-lib's
     * two-layer wrapping of it. Unchecked on purpose: {@code ScriptCostEvaluators} only catches
     * {@code CborSerializationException} and {@code ApiException}, so a {@link RuntimeException} reaches
     * {@link #complete}'s catch with the marker still in the chain.
     */
    private static final class ScriptCostEvaluationException extends RuntimeException {

        ScriptCostEvaluationException(String detail, Throwable cause) {
            super(detail, cause);
        }
    }

    /**
     * The caller's evaluator, with every way it can fail to price the transaction turned into one
     * {@link ScriptCostEvaluationException}. Three failure shapes, not two: a thrown exception, an
     * unsuccessful {@link Result}, and — the dangerous one because it looks like success — a
     * <b>successful result that does not cost every redeemer</b>, which would leave the uncosted
     * redeemers on their 10000-mem placeholders. Mirrors {@link LiquidateTransactionBuilder}'s wrapper.
     */
    private static TransactionEvaluator reporting(TransactionEvaluator delegate) {
        return (cbor, inputUtxos) -> {
            Result<List<EvaluationResult>> result;
            try {
                result = delegate.evaluateTx(cbor, inputUtxos);
            } catch (Exception e) {
                throw new ScriptCostEvaluationException(String.valueOf(e.getMessage()), e);
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
     * Every redeemer in the transaction sent for evaluation must come back with a costing of its own.
     * Coverage is checked per {@code (tag, index)} pair — the key {@code ScriptCostEvaluators} writes
     * back on — because N results for N redeemers can still leave one redeemer uncosted.
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

    // ---- redeemers --------------------------------------------------------------------------------

    /**
     * {@code LenderManagerWithdrawRedeemer { configRefInputIndex, LiquidateAndPayInAdvance } } —
     * {@code LenderManagerAction.LiquidateAndPayInAdvance} is constructor index 3 (WithdrawBonds 0,
     * Liquidate 1, Compound 2), verified against {@code lib/fluidtokens/types/lender_manager.ak} at
     * {@code ff005fb}. Built inline: {@link LiquidationTxEncoder#lenderManagerWithdrawRedeemer} hard-codes
     * the {@code Liquidate} action instead.
     */
    private static PlutusData lenderManagerPayInAdvanceRedeemer(long lmConfigRefIndex) {
        return constr(0, BigIntPlutusData.of(lmConfigRefIndex), constr(3));
    }

    /**
     * {@code LMLiquidateAndPayInAdvanceWithdrawRedeemer { configRefInputIndex, lenderBondInputIndexes,
     * lenderBondAssetNames, principalAndCollateralOracleRefInputIndexes, assetOutputIndexes } } —
     * constructor index 0. The oracle field is a list of {@code (Int, Int)} pairs, principal index
     * first, collateral second (the destructuring order in
     * {@code lm_liquidate_and_pay_in_advance_action.ak}). Built inline, per the schema at {@code ff005fb}.
     */
    private static PlutusData payInAdvanceActionRedeemer(long configRefInputIndex,
                                                         List<Long> lenderBondInputIndexes,
                                                         List<String> lenderBondAssetNamesHex,
                                                         List<Integer> principalOracleRefIndexes,
                                                         List<Integer> collateralOracleRefIndexes,
                                                         List<Long> assetOutputIndexes) {
        PlutusData bondIndexes = list(lenderBondInputIndexes.stream()
                .map(BigIntPlutusData::of).toArray(PlutusData[]::new));
        PlutusData bondNames = list(lenderBondAssetNamesHex.stream()
                .map(name -> (PlutusData) com.bloxbean.cardano.client.plutus.spec.BytesPlutusData
                        .of(HexUtil.decodeHexString(name)))
                .toArray(PlutusData[]::new));
        // An Aiken 2-tuple (Int, Int) serialises as a bare PlutusData list [a, b] — NOT a Constr.
        PlutusData[] pairs = new PlutusData[principalOracleRefIndexes.size()];
        for (int i = 0; i < pairs.length; i++) {
            pairs[i] = list(BigIntPlutusData.of(principalOracleRefIndexes.get(i)),
                    BigIntPlutusData.of(collateralOracleRefIndexes.get(i)));
        }
        PlutusData assetOutputs = list(assetOutputIndexes.stream()
                .map(BigIntPlutusData::of).toArray(PlutusData[]::new));
        return constr(0, BigIntPlutusData.of(configRefInputIndex), bondIndexes, bondNames,
                list(pairs), assetOutputs);
    }

    private ClaimData claimData(Request request, Numbers numbers, long lenderBondOutputIndex,
                               int collateralOracleRefIndex, int principalOracleRefIndex) {
        return new ClaimData(
                request.loan().datum().liquidationMode(),
                BigInteger.valueOf(lenderBondOutputIndex),
                BigInteger.valueOf(collateralOracleRefIndex),
                BigInteger.valueOf(principalOracleRefIndex),
                request.bond().datum().lenderAuth(),
                numbers.equity(),
                request.loan().loanId(),
                numbers.remainingDebt());
    }

    // ---- output datums ----------------------------------------------------------------------------

    /** The lender bond echo datum: the input's bytes, verbatim, so {@code equals_data} accepts it. */
    private static PlutusData bondEchoDatum(Request request) {
        try {
            return PlutusData.deserialize(
                    CborSerializationUtil.deserialize(HexUtil.decodeHexString(request.bondUtxo().getInlineDatum())));
        } catch (Exception e) {
            throw new IllegalStateException("cannot decode the lender bond input datum for its echo", e);
        }
    }

    /**
     * The borrower's equity compensation datum — {@code partial_liquidation}, owned by the borrower
     * bond, descending from the loan input. {@code loan_claim_action}'s {@code equity_sent_to_borrower}
     * compares against exactly this.
     */
    private PlutusData borrowerCompensationDatum(Request request) {
        return LiquidationTxEncoder.assetManagerDatumWithToken(new AssetManagerDatumWithToken(
                request.loanUtxo().getTxHash(), request.loanUtxo().getOutputIndex(),
                PARTIAL_LIQUIDATION_ACTION_HEX,
                new AssetType(registry.getBorrowerBondPolicyId(), request.loan().loanId())));
    }

    /**
     * The lender's paid-in-advance datum — {@code converted_to_liquidity}, owned by the lender bond.
     * {@code lm_liquidate_and_pay_in_advance_action}'s {@code validate_repayment_output} compares
     * against exactly this.
     */
    private PlutusData lenderConvertedDatum(Request request) {
        return LiquidationTxEncoder.assetManagerDatumWithToken(new AssetManagerDatumWithToken(
                request.loanUtxo().getTxHash(), request.loanUtxo().getOutputIndex(),
                CONVERTED_TO_LIQUIDITY_ACTION_HEX,
                new AssetType(registry.getLenderBondPolicyId(), request.loan().loanId())));
    }

    /** The borrower compensation value: {@code equity} collateral tokens, min-ada left to cardano-client-lib. */
    private List<Amount> collateralEquityAmounts(Request request, BigInteger equity) {
        AssetType collateral = request.loan().datum().collateral().assetType();
        return List.of(Amount.asset(collateral.policyId() + collateral.assetName(), equity));
    }

    // ---- addresses --------------------------------------------------------------------------------

    /**
     * The asset-manager spend credential as an enterprise address. {@code get_outputs_to_smart_credential}
     * filters on the payment credential alone in its native-token branch, so no stake part is needed and
     * both asset-manager outputs may share it.
     */
    private String assetManagerAddress() {
        return AddressProvider.getEntAddress(
                Credential.fromScript(registry.getAssetManagerSpendScriptHash()), network).getAddress();
    }

    private String rewardAddress(String scriptHash) {
        return AddressProvider.getRewardAddress(Credential.fromScript(scriptHash), network).getAddress();
    }

    // ---- reference inputs & derivations -----------------------------------------------------------

    /**
     * Config, LM config and the three oracle reference inputs, plus any published reference-script
     * coordinates this builder can shed — deduplicated (a coordinate that collides with a config or
     * oracle input is not added twice) and canonically sorted, mirroring
     * {@link LiquidateTransactionBuilder#referenceInputs}.
     */
    private List<TransactionInput> referenceInputs(Request request) {
        Set<TransactionInput> refInputs = new LinkedHashSet<>(List.of(
                inputOf(request.configUtxo()),
                inputOf(request.lmConfigUtxo()),
                request.oracle().referenceInput(),
                request.oracle().referenceScript(),
                request.oracle().charlieProviderReferenceInput()));
        // Only the subset this builder attaches AND the shared record can name: lmLiquidateAction and
        // assetManager are excluded here, exactly as in publishedScripts().
        LiquidateTransactionBuilder.ReferenceScripts scripts = request.referenceScripts();
        Stream.of(scripts.loan(), scripts.loanSpend(), scripts.lenderManager(),
                        scripts.lenderManagerSpend(), scripts.loanClaimAction())
                .filter(Objects::nonNull)
                .forEach(refInputs::add);
        return refInputs.stream().sorted(new TransactionInputComparator()).toList();
    }

    private static int refIndex(List<TransactionInput> refInputs, TransactionInput input, String what) {
        int index = refInputs.indexOf(input);
        if (index < 0) {
            throw new IllegalStateException(what + " reference input " + input + " is not among the body's");
        }
        return index;
    }

    /** The absolute output position of the lender-bond echo — matched on address, datum and NFT. */
    private long locateBondOutput(Transaction probe, Request request) {
        List<TransactionOutput> outputs = probe.getBody().getOutputs();
        List<Integer> matches = new ArrayList<>();
        AssetType bondNft = new AssetType(registry.getLenderBondPolicyId(), request.loan().loanId());
        for (int i = 0; i < outputs.size(); i++) {
            TransactionOutput output = outputs.get(i);
            if (request.bondUtxo().getAddress().equals(output.getAddress())
                    && output.getInlineDatum() != null
                    && output.getInlineDatum().serializeToHex()
                    .equalsIgnoreCase(request.bondUtxo().getInlineDatum())
                    && quantityOf(output, bondNft).equals(BigInteger.ONE)) {
                matches.add(i);
            }
        }
        if (matches.size() != 1) {
            throw new IllegalStateException("bond echo matches " + matches.size() + " outputs, expected one");
        }
        return matches.getFirst().longValue();
    }

    /**
     * The position of the lender's converted output <em>within the asset-manager-credential-filtered
     * output list</em> — the index {@code assetOutputIndexes} carries. Matched by its
     * {@code converted_to_liquidity} datum so a reorder relative to the borrower compensation output is
     * caught rather than assumed.
     */
    private long locateLenderConvertedOutput(Transaction probe, Request request, Numbers numbers) {
        String convertedDatumHex = lenderConvertedDatum(request).serializeToHex();
        List<TransactionOutput> filtered = assetManagerOutputs(probe);
        List<Integer> matches = new ArrayList<>();
        for (int i = 0; i < filtered.size(); i++) {
            TransactionOutput output = filtered.get(i);
            if (output.getInlineDatum() != null
                    && output.getInlineDatum().serializeToHex().equalsIgnoreCase(convertedDatumHex)) {
                matches.add(i);
            }
        }
        if (matches.size() != 1) {
            throw new IllegalStateException(
                    "the lender converted output matches " + matches.size() + " asset-manager outputs, expected one");
        }
        return matches.getFirst().longValue();
    }

    /** The outputs at the asset-manager spend credential, in body order — the {@code get_outputs_to_smart_credential} list. */
    private List<TransactionOutput> assetManagerOutputs(Transaction tx) {
        String assetManagerSpend = registry.getAssetManagerSpendScriptHash();
        List<TransactionOutput> filtered = new ArrayList<>();
        for (TransactionOutput output : tx.getBody().getOutputs()) {
            if (assetManagerSpend.equals(paymentCredentialOf(output.getAddress()))) {
                filtered.add(output);
            }
        }
        return filtered;
    }

    // ---- structural re-derivation from the finished body ------------------------------------------

    private void assertStructure(Transaction transaction, Request request, Numbers numbers,
                                 long lenderBondOutputIndex, long assetOutputIndex) {
        // The bond echo is where the claim redeemer says, and it is byte-identical to the input.
        structural(lenderBondOutputIndex == locateBondOutput(transaction, request),
                "lenderBondOutputIndex " + lenderBondOutputIndex + " no longer points at the bond echo");
        TransactionOutput echo = transaction.getBody().getOutputs().get((int) lenderBondOutputIndex);
        structural(request.bondUtxo().getInlineDatum().equalsIgnoreCase(echo.getInlineDatum().serializeToHex()),
                "the bond echo datum is not byte-identical to the bond input datum");

        // The asset-manager filtered list is [borrower compensation, lender converted], and
        // assetOutputIndexes points at the lender one.
        List<TransactionOutput> assetOutputs = assetManagerOutputs(transaction);
        structural(assetOutputs.size() == 2, "expected exactly two asset-manager outputs, got " + assetOutputs.size());
        structural(assetOutputIndex == locateLenderConvertedOutput(transaction, request, numbers),
                "assetOutputIndex " + assetOutputIndex + " no longer points at the lender converted output");
        structural(assetOutputIndex != 0,
                "the borrower compensation output must occupy filtered slot 0 (the bare loan index)");

        // The lender's paid-in-advance output is ada-only (dosProtection flatten == 1) and covers the amount.
        TransactionOutput lenderOutput = assetOutputs.get((int) assetOutputIndex);
        structural(flattenedCount(lenderOutput) == 1, "the lender converted output must be ada-only (flatten == 1)");
        structural(lenderOutput.getValue().getCoin()
                        .compareTo(numbers.convertedLoanCollateralToPrincipalAmount()) >= 0,
                "the lender converted output holds less ada than convertedLoanCollateralToPrincipalAmount");

        // The borrower compensation output carries at least the equity in collateral tokens, flatten == 2.
        TransactionOutput borrowerOutput = assetOutputs.get(0);
        AssetType collateral = request.loan().datum().collateral().assetType();
        structural(quantityOf(borrowerOutput, collateral).compareTo(numbers.equity()) >= 0,
                "the borrower compensation output holds less collateral than the equity");
        structural(flattenedCount(borrowerOutput) == 2,
                "the borrower compensation output must be a token plus its min-ada rider (flatten == 2)");

        // The loan NFT is burned exactly once.
        structural(mintedQuantity(transaction, new AssetType(registry.getLoanPolicyId(), request.loan().loanId()))
                        .equals(BigInteger.ONE.negate()),
                "the loan NFT must be burned exactly once");
    }

    // ---- primitives -------------------------------------------------------------------------------

    private static ConstrPlutusData constr(int alternative, PlutusData... fields) {
        return ConstrPlutusData.builder().alternative(alternative).data(ListPlutusData.of(fields)).build();
    }

    private static PlutusData list(PlutusData... items) {
        return ListPlutusData.of(items);
    }

    private static TransactionInput inputOf(Utxo utxo) {
        return new TransactionInput(utxo.getTxHash(), utxo.getOutputIndex());
    }

    private static String paymentCredentialOf(String address) {
        return new com.bloxbean.cardano.client.address.Address(address)
                .getPaymentCredentialHash().map(HexUtil::encodeHexString).orElse("");
    }

    private static BigInteger quantityOf(TransactionOutput output, AssetType asset) {
        return output.getValue().getMultiAssets().stream()
                .filter(multiAsset -> multiAsset.getPolicyId().equalsIgnoreCase(asset.policyId()))
                .flatMap(multiAsset -> multiAsset.getAssets().stream())
                .filter(a -> HexUtil.encodeHexString(a.getNameAsBytes()).equalsIgnoreCase(asset.assetName()))
                .map(Asset::getValue)
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    private static BigInteger mintedQuantity(Transaction tx, AssetType asset) {
        return tx.getBody().getMint().stream()
                .filter(multiAsset -> multiAsset.getPolicyId().equalsIgnoreCase(asset.policyId()))
                .flatMap(multiAsset -> multiAsset.getAssets().stream())
                .filter(a -> HexUtil.encodeHexString(a.getNameAsBytes()).equalsIgnoreCase(asset.assetName()))
                .map(Asset::getValue)
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    private static int flattenedCount(TransactionOutput output) {
        int tokens = output.getValue().getMultiAssets().stream()
                .mapToInt(multiAsset -> multiAsset.getAssets().size())
                .sum();
        return tokens + (output.getValue().getCoin().signum() > 0 ? 1 : 0);
    }

    private static void structural(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("STRUCTURAL_ASSERTION_FAILED: " + message);
        }
    }
}
