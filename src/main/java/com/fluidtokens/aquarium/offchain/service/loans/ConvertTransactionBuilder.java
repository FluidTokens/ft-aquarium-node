package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.function.TxBuilder;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.ClaimData;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Builds a <b>LiquidateAndConvert</b>: liquidates a loan and, in the same transaction, creates the
 * Minswap V2 swap order that turns its collateral into the lender's principal.
 *
 * <h2>What this builder is and is not</h2>
 * It <b>assembles</b>; it does not decide. Everything the validator dictates was computed by
 * {@link ConvertOrderPlan} against the live pool datum, and everything about profitability by
 * {@link ConvertEconomics}. What is left here is the part that can only be checked against a node:
 * inputs, outputs, indexes, fees and ex-units.
 *
 * <h2>⛔ A REAL EVALUATOR, FROM THIS CLASS'S FIRST COMMIT</h2>
 * There is no constructor without a {@link TransactionEvaluator}. The sibling builders each grew one
 * as a convenience and it is how the 2026-08-21 incident shipped placeholder ex-units — 10000 mem /
 * 1000 steps against a real 352,041,926 — which pass the mempool and fail in <b>phase 2</b>, forfeiting
 * collateral. That risk is not abstract here: <b>the operator's entire exposure on this path is "a
 * transaction fee per execution", and that sentence is true only while the budgets are real.</b>
 * Placeholders move the exposure to the collateral, which is the one way the case for running this
 * bot at a stated loss stops holding.
 *
 * <h2>⚠ Two absolute output indexes, and a dummy output that moves them</h2>
 * The convert redeemer names {@code minswapOrderSuccessInlineDatumOutputIndexes} and its refund twin
 * as indexes into {@code self.outputs} — <b>absolute</b>. This transaction carries withdrawals, and
 * CCL prepends a dummy output at the change address for every withdrawing transaction (trap 1), then
 * appends change after our outputs. So the layout is <b>observed, never predicted</b>: the body is
 * assembled once with placeholder indexes purely to read the finished layout, the carriers are located
 * <b>by their datum bytes</b> rather than by arithmetic, and the whole thing is assembled again with
 * the observed values. {@link #assertStructure} then re-derives them from the finished body.
 *
 * <h2>⚠ Trap 17 is unavoidable on this path</h2>
 * The bot's income is the liquidation fee <b>in the collateral token</b>, so every convert produces a
 * change output carrying native assets. A subsequent build that spends it must cope with a
 * multi-asset wallet UTxO; that is a property of the path, not a defect to design away.
 */
@Slf4j
public class ConvertTransactionBuilder {

    /** {@code general_spend} ignores its redeemer; authorisation is the withdrawal beside it. */
    static final PlutusData GENERAL_SPEND_REDEEMER = CompoundTransactionBuilder.GENERAL_SPEND_REDEEMER;

    public enum Refusal {
        /** {@code loans.minswap.*} is unset or belongs to another network — see {@code LoansConfigVerifier}. */
        CONVERT_ACTION_NOT_DERIVED,
        /**
         * ⚠ {@code repaymentReceipts} is true on this loan. {@code equity_sent_to_borrower} then
         * demands a repayment-receipt NFT in the equity output — {@code quantity_of(value,
         * repaymentPolicyId, hash_output_ref(loanRef)) == 1} — which this builder does not mint.
         * A named refusal beats a silent wrong build.
         */
        REPAYMENT_RECEIPTS_NOT_MODELLED,
        /**
         * The equity output is absent, short, or carries something the validator's DoS guard forbids.
         */
        EQUITY_OUTPUT_MISMATCH,
        /** The lender bond's datum did not survive a decode→re-encode, so it cannot be echoed. */
        BOND_DATUM_NOT_BYTE_IDENTICAL,
        /** The built order output is not what the plan said it must be. */
        ORDER_OUTPUT_MISMATCH,
        /** A carrier output's inline datum does not hash to the value the order datum embeds. */
        CARRIER_DATUM_MISMATCH,
        /** The bot's collateral-token fee is absent from the built body. */
        FEE_NOT_PAID_TO_BOT,
        /** An index the redeemer claims does not point at what it claims, on the finished body. */
        INDEX_MISMATCH,
        /**
         * ⛔ No usable oracle for the collateral leg. A convert exchanges two distinct assets, so at
         * least one leg is a token and {@code loan_claim_action} requires an oracle withdrawal for it.
         * Building without one produces a transaction that assembles, serialises and passes every
         * structural check here — and fails on chain.
         */
        COLLATERAL_ORACLE_MISSING
    }

    public static final class RefusedException extends RuntimeException {
        private final transient Refusal reason;

        RefusedException(Refusal reason, String detail) {
            super(reason + ": " + detail);
            this.reason = reason;
        }

        public Refusal reason() {
            return reason;
        }
    }

    private static RefusedException refuse(Refusal reason, String detail) {
        return new RefusedException(reason, detail);
    }

    /**
     * @param poolRefUtxo the Minswap pool UTxO, located BY ITS NFT at scan time — never a pinned
     *                    coordinate, because a pool is respent on every swap
     * @param collateralOracle the feed for the COLLATERAL leg. ⛔ Never {@code null} on this path:
     *                    a convert exchanges two distinct assets, so at least one leg is a token and
     *                    {@code retrieve_oracle_data} demands an oracle withdrawal for it. The
     *                    principal leg needs none when it is ada, which the ada short-circuit handles
     * @param orderAddress {@code Address(Script(minswapOrderSpendScriptHash), lenderStakeCredential)}
     *                     — the validator checks this exactly, including the LENDER's stake part
     * @param claim       the loan-claim redeemer's per-input data; its {@code lenderBondOutputIndex}
     *                    is filled in by the second pass, not by the caller
     * @param repaymentReceipts the loan datum's own flag. True demands a receipt NFT in the equity
     *                    output which this builder does not mint, so it is a named refusal
     */
    public record Request(Utxo loanUtxo,
                          Utxo bondUtxo,
                          Utxo poolRefUtxo,
                          com.fluidtokens.aquarium.offchain.model.loans.OracleEntry collateralOracle,
                          Utxo configUtxo,
                          Utxo lmConfigUtxo,
                          Utxo walletUtxo,
                          Map<String, TransactionInput> referenceScripts,
                          ConvertOrderPlan plan,
                          ClaimData claim,
                          AssetType collateral,
                          AssetType lenderBondAsset,
                          boolean repaymentReceipts,
                          String orderAddress,
                          String changeAddress,
                          long validFromSlot,
                          long validToSlot) {

        /**
         * ⛔ <b>A SLOT THIS LARGE IS A MILLISECOND TIMESTAMP, and javac cannot tell you so.</b>
         *
         * <p>Until 2026-09-05 {@code ConvertLiquidationRouter} passed its {@code validFromMillis} /
         * {@code validToMillis} straight into these two components. Both sides are {@code long} and
         * both are positional, so the call compiled in silence and the ledger rejected every convert
         * with {@code PastHorizon … (ELit (SlotNo 1788596164000))} — about 12,776× past the tip.
         * <b>A convert had therefore never built, on any network, under any configuration.</b>
         *
         * <p>⚠ The guard is here rather than in the router because <b>this is the boundary the
         * confusion crosses</b>. A rule stated where the mistake is made fires for every future
         * caller; one stated in the caller that got it wrong protects only that caller.
         *
         * <p>The threshold separates the two families for centuries: a Cardano slot advances one per
         * second, so mainnet is ~1.4e8 today and reaches ~2.4e9 in the year 2100, while POSIX
         * milliseconds have exceeded 1.7e12 since 2023. <b>Anything above 1e11 is a timestamp.</b>
         */
        private static final long MILLISECONDS_NOT_SLOTS = 100_000_000_000L;

        public Request {
            if (validFromSlot > MILLISECONDS_NOT_SLOTS || validToSlot > MILLISECONDS_NOT_SLOTS) {
                throw new IllegalArgumentException(("validFromSlot=%d validToSlot=%d — a slot cannot "
                        + "be this large; these are MILLISECONDS. Convert them at the boundary with "
                        + "converters.time().toSlot(utc(millis)), exactly as every sibling router "
                        + "does. Handed to the ledger unconverted this is a PastHorizon rejection "
                        + "and the transaction never builds.")
                        .formatted(validFromSlot, validToSlot));
            }
        }
    }

    private final LoansContractRegistry registry;
    private final Network network;
    private final UtxoSupplier utxoSupplier;
    private final ProtocolParamsSupplier protocolParamsSupplier;
    private final BackendService backendService;
    private final TransactionEvaluator scriptCostEvaluator;

    /** Offline: a rig supplies the scripts and evaluates for itself. */
    public ConvertTransactionBuilder(LoansContractRegistry registry, Network network,
                                     UtxoSupplier utxoSupplier,
                                     ProtocolParamsSupplier protocolParamsSupplier,
                                     TransactionEvaluator scriptCostEvaluator) {
        this(registry, network, utxoSupplier, protocolParamsSupplier, null, scriptCostEvaluator);
    }

    /** Production: one backend wires the utxo supplier, params and script supplier. */
    public ConvertTransactionBuilder(LoansContractRegistry registry, Network network,
                                     BackendService backendService,
                                     UtxoSupplier utxoSupplier,
                                     ProtocolParamsSupplier protocolParamsSupplier,
                                     TransactionEvaluator scriptCostEvaluator) {
        this(registry, network, utxoSupplier, protocolParamsSupplier, backendService, scriptCostEvaluator);
    }

    private ConvertTransactionBuilder(LoansContractRegistry registry, Network network,
                                      UtxoSupplier utxoSupplier,
                                      ProtocolParamsSupplier protocolParamsSupplier,
                                      BackendService backendService,
                                      TransactionEvaluator scriptCostEvaluator) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.network = Objects.requireNonNull(network, "network");
        this.utxoSupplier = Objects.requireNonNull(utxoSupplier, "utxoSupplier");
        this.protocolParamsSupplier =
                Objects.requireNonNull(protocolParamsSupplier, "protocolParamsSupplier");
        this.backendService = backendService;
        // ⛔ NOT optional, and deliberately not defaulted. See the class javadoc.
        this.scriptCostEvaluator = Objects.requireNonNull(scriptCostEvaluator,
                "a convert builder without a TransactionEvaluator would ship placeholder ex-units and "
                        + "fail in phase 2 — CCL trap 8. There is no constructor without one.");
    }

    // ---- the one entry point --------------------------------------------------------------------

    public Transaction build(Request request) {
        if (registry.getLmLiquidateAndConvertActionScriptHash() == null) {
            throw refuse(Refusal.CONVERT_ACTION_NOT_DERIVED,
                    "loans.minswap.* is not configured, so the convert action cannot be withdrawn through");
        }
        // ⛔ THE LIMB THIS BUILDER SHIPPED WITHOUT. Checked first because its absence is invisible
        // downstream: every structural assertion in this class inspects what we EMIT, and none can
        // see a withdrawal that was never added.
        if (request.collateralOracle() == null || !request.collateralOracle().usableForLiquidation()) {
            throw refuse(Refusal.COLLATERAL_ORACLE_MISSING,
                    "the collateral leg has no usable oracle entry; loan_claim_action would refuse "
                            + "this transaction on chain and nothing here would have noticed");
        }
        if (request.repaymentReceipts()) {
            throw refuse(Refusal.REPAYMENT_RECEIPTS_NOT_MODELLED,
                    "this loan has repaymentReceipts = true, so equity_sent_to_borrower demands a "
                            + "receipt NFT in the equity output that this builder does not mint");
        }

        List<TransactionInput> refInputs = referenceInputs(request);
        long configRefIndex = indexOf(refInputs, inputOf(request.configUtxo()), "main config");
        // ⛔ The LM config, not the main one: lender_manager.withdraw resolves the ACTION script from
        // the LM config datum it is handed (§22.3's fourth wrinkle, found by dry-eval not by reading).
        long lmConfigRefIndex = indexOf(refInputs, inputOf(request.lmConfigUtxo()), "lm config");
        // The pool's index is into the FILTERED list of pool reference inputs, of which there is one.
        long poolRefIndex = 0L;
        long collateralOracleRefIndex = indexOf(refInputs,
                request.collateralOracle().referenceInput(), "collateral oracle");
        // ⚠ The principal leg is ada here: retrieve_oracle_data short-circuits on an empty policy id
        // and never reads this input — but the index must still be IN RANGE. Index 0 of the
        // canonically sorted reference inputs always is, which is what the sibling builders pass too.
        long principalOracleRefIndex = 0L;

        // ⛔ PASS 1. The two carrier indexes and lenderBondOutputIndex are ABSOLUTE positions in
        // self.outputs, and a withdrawing transaction gets a dummy output prepended plus change
        // appended (trap 1). Build once with placeholders purely to read the finished layout.
        Transaction probe = assembleAndComplete(request, refInputs, configRefIndex, lmConfigRefIndex,
                poolRefIndex, collateralOracleRefIndex, principalOracleRefIndex, 0L, 0L, 0L, null);

        long successIndex = outputIndexWithDatum(probe, request.plan().successDatum(), "success carrier");
        long refundIndex = outputIndexWithDatum(probe, request.plan().refundDatum(), "refund carrier");
        long bondOutputIndex = outputIndexWithDatumHex(probe, request.bondUtxo().getInlineDatum(),
                "lender bond echo");

        // PASS 2, with the observed layout, and a post-assert that re-derives it from the body.
        return assembleAndComplete(request, refInputs, configRefIndex, lmConfigRefIndex, poolRefIndex,
                collateralOracleRefIndex, principalOracleRefIndex, successIndex, refundIndex, bondOutputIndex,
                (ctx, txn) -> assertStructure(txn, request, successIndex, refundIndex, bondOutputIndex));
    }

    // ---- assembly -------------------------------------------------------------------------------

    private Transaction assembleAndComplete(Request request, List<TransactionInput> refInputs,
                                            long configRefIndex, long lmConfigRefIndex, long poolRefIndex,
                                            long collateralOracleRefIndex, long principalOracleRefIndex,
                                            long successIndex, long refundIndex, long bondOutputIndex,
                                            TxBuilder verify) {
        ConvertOrderPlan plan = request.plan();
        ScriptTx tx = new ScriptTx();

        tx.collectFrom(request.loanUtxo(), GENERAL_SPEND_REDEEMER);
        tx.collectFrom(request.bondUtxo(), GENERAL_SPEND_REDEEMER);
        tx.collectFrom(request.walletUtxo());

        // 1. The Minswap order. Its value is the validator's, not a min-ada estimate: an ADA
        //    collateral's order holds exactly the swappable amount, a token collateral's holds the
        //    tokens plus exactly 2_800_000 lovelace.
        tx.payToContract(request.orderAddress(), orderValue(request), plan.orderDatum());

        // 2. The lender bond, restored byte-for-byte. equals_data compares the WHOLE output, so the
        //    value goes back exactly as it came in and the datum is the input's own bytes.
        tx.payToContract(request.bondUtxo().getAddress(), List.copyOf(request.bondUtxo().getAmount()),
                echoedBondDatum(request));

        // ⛔ 3. THE BORROWER'S EQUITY, when there is any. loan_claim_action's equity_sent_to_borrower
        //    dictates every field of this output, and two of them are easy to get backwards:
        //    the owner is the BORROWER's bond (not the lender's), and the asset is the COLLATERAL
        //    (because this loan's equityInPrincipalCurrency is false — the convert action requires it).
        //
        //    ⚠ AND ITS VALUE MUST BE EXACT. The validator's DoS guard is
        //    `length(flatten(value)) == 2 + receiptAssetCount`, so with receipts off this output may
        //    hold ada and the equity token and NOTHING ELSE. One stray asset — a change adjustment,
        //    a merged output — fails it on chain (CCL trap 17 territory, which this path already
        //    fights because the bot's fee is a native token).
        BigInteger equity = request.claim().equity();
        if (equity.signum() > 0) {
            tx.payToContract(assetManagerAddress(),
                    List.of(Amount.lovelace(EQUITY_OUTPUT_LOVELACE),
                            Amount.asset(request.collateral().toUnit(), equity)),
                    LiquidationTxEncoder.assetManagerDatumWithToken(
                            new com.fluidtokens.aquarium.offchain.model.loans.AssetManagerDatumWithToken(
                                    request.loanUtxo().getTxHash(),
                                    request.loanUtxo().getOutputIndex(),
                                    LiquidationTxEncoder.PARTIAL_LIQUIDATION_ACTION_HEX,
                                    borrowerBondAsset(request))));
        }

        // 4+5. The two datum carriers. Their ADDRESSES ARE UNCONSTRAINED by the validator, so they are
        //      paid to the bot — which is what makes their min-ada working capital rather than cost,
        //      and is an invariant ConvertEconomics rests on.
        tx.payToContract(request.changeAddress(), List.of(Amount.lovelace(BigInteger.ZERO)),
                plan.successDatum());
        tx.payToContract(request.changeAddress(), List.of(Amount.lovelace(BigInteger.ZERO)),
                plan.refundDatum());

        // ⛔ THE LOAN NFT MUST BE BURNED, and until 2026-09-05 this builder minted nothing at all.
        //
        // loan_claim_action requires `quantity_of(self.mint, loanPolicyId, inputAction.loanId) == -1`
        // — the claim is what RETIRES the loan. With no mint field the NFT simply fell into the bot's
        // change output, which is how it was first noticed (findings §57.3, recorded as an open lead
        // before it could be confirmed). Traced evaluation named the conjunct outright:
        //   Log("quantity_of(self.mint, loanPolicyId, inputAction.loanId) == -1 ? False")
        //
        // Both liquidation siblings already do this identically; convert was the one that did not.
        // isPoolOrigin=false / originWithdrawRedeemerIndex=0 are inert for a burn: loan.ak's
        // check_mint reads them only when something is MINTED (quantity > 0).
        tx.mintAsset(registry.getLoanScript(),
                List.of(new com.bloxbean.cardano.client.transaction.spec.Asset("0x" + request.claim().loanId(), BigInteger.ONE.negate())),
                LiquidationTxEncoder.loanMintRedeemer(configRefIndex, false, 0));

        // The four withdraw-0 invocations. ⚠ The asset-manager withdraw script must be ABSENT: the
        // validator explicitly refuses a convert that also spends an asset-manager input.
        tx.withdraw(rewardAddress(registry.getLoanPolicyId()), BigInteger.ZERO,
                LiquidationTxEncoder.loanWithdrawRedeemer(configRefIndex));
        tx.withdraw(rewardAddress(registry.getLoanClaimActionScriptHash()), BigInteger.ZERO,
                LiquidationTxEncoder.loanClaimActionWithdrawRedeemer(configRefIndex,
                        List.of(withIndexes(request.claim(), bondOutputIndex,
                                collateralOracleRefIndex, principalOracleRefIndex))));
        // ⛔ THE ACTION MATTERS. lender_manager.ak reads it, resolves the matching action-script hash
        // from the LM config datum, and requires THAT script's withdrawal to be present. The default
        // one-argument overload says `Liquidate`, so the validator hunts for the PLAIN action's script
        // — which this transaction does not withdraw through — and refuses, with nothing in the
        // failure naming the cause. Measured: EvaluationFailure at this withdrawal, first run.
        tx.withdraw(rewardAddress(registry.getLenderManagerWithdrawScriptHash()), BigInteger.ZERO,
                LiquidationTxEncoder.lenderManagerWithdrawRedeemer(lmConfigRefIndex,
                        LiquidationTxEncoder.LenderManagerAction.LIQUIDATE_AND_CONVERT));
        // ⛔ THE ORACLE WITHDRAWAL. loan_claim_action calls retrieve_oracle_data with the collateral's
        // own policy id; a non-empty one means the FluidTokens oracle validator MUST execute.
        //
        // ⚑ AND NOT THE SIBLING'S CALL. LiquidatePayInAdvanceTransactionBuilder uses
        // oracleRedeemer(feed, providerRefInputIndex, List.of()) — an EMPTY signature list, which is
        // the Charli3/Orcfax shape, where the price is proven by a provider reference input instead.
        // Mainnet FLDT is served MULTISIG (findings §40), so its branch runs verify_ed25519_signature
        // against the feed's published signatures and an empty list fails the threshold. Copying the
        // sibling verbatim is the hurried fix, and it would emit a transaction that assembles cleanly
        // and dies in phase 2.
        tx.withdraw(request.collateralOracle().rewardAddress(), BigInteger.ZERO,
                LiquidationTxEncoder.oracleRedeemer(request.collateralOracle().feed(),
                        request.collateralOracle().signatures()));

        tx.withdraw(rewardAddress(registry.getLmLiquidateAndConvertActionScriptHash()), BigInteger.ZERO,
                ConvertTxEncoder.convertRedeemer(Math.toIntExact(configRefIndex),
                        List.of(0),
                        List.of(request.lenderBondAsset().assetName()),
                        List.of(Math.toIntExact(poolRefIndex)),
                        List.of(ConvertTxEncoder.POOL_NFT_ASSET_NAME),
                        List.of(Math.toIntExact(successIndex)),
                        List.of(Math.toIntExact(refundIndex))));

        // ⛔ EXACTLY ONE ROUTE PER VALIDATOR (CCL traps 9 and 13): a script both witnessed and
        // referenced is ExtraneousScriptWitnessesUTXOW; a redeemer with no reachable script is
        // RequiredRedeemersMismatch. Both fail loudly; neither fails quietly.
        for (PlutusScript spend : List.of(registry.getLoanSpendScript(),
                registry.getLenderManagerSpendScript())) {
            if (!isReferenced(request, spend)) {
                tx.attachSpendingValidator(spend);
            }
        }
        for (PlutusScript reward : List.of(registry.getLoanScript(),
                registry.getLoanClaimActionScript(), registry.getLenderManagerScript(),
                registry.getLmLiquidateAndConvertActionScript())) {
            if (!isReferenced(request, reward)) {
                tx.attachRewardValidator(reward);
            }
        }

        return complete(request, refInputs, tx, verify);
    }

    private Transaction complete(Request request, List<TransactionInput> refInputs, ScriptTx tx,
                                 TxBuilder verify) {
        tx.readFrom(refInputs.toArray(TransactionInput[]::new));
        QuickTxBuilder quickTxBuilder = backendService != null
                ? new QuickTxBuilder(backendService)
                : new QuickTxBuilder(utxoSupplier, protocolParamsSupplier, null);

        var context = quickTxBuilder.compose(tx)
                .feePayer(request.changeAddress())
                .collateralPayer(request.changeAddress())
                .validFrom(request.validFromSlot())
                .validTo(request.validToSlot())
                // The four outputs are distinct by construction; merging them would destroy the
                // absolute indexes the redeemer names.
                .mergeOutputs(false)
                // ⛔ PASS 2 ABORTS on a failed evaluation rather than handing back placeholder
                // ex-units with a log.warn (CCL trap 8). PASS 1 MUST NOT, and that was the bug.
                //
                // Pass 1 exists only to read the finished output layout, so it is assembled with
                // PLACEHOLDER indexes 0/0/0 (trap 1). Index 0 is the dummy output a withdrawing
                // transaction gets prepended — it carries no datum, so the validator's
                // `expect InlineDatum(..) = output.datum` ABORTS on it. With the strict setting this
                // threw inside pass 1 and the real transaction was never assembled at all: every
                // convert died on its own scaffolding, and the evaluator's ex-unit budget described
                // a body nobody would ever have submitted.
                //
                // ⚠ Tolerating the error here is safe for exactly one reason — pass 1's transaction
                // is DISCARDED. Only its output ordering is read. Pass 2 re-evaluates strictly with
                // the observed indexes, and `assertStructure` re-derives that layout from the
                // finished body, so nothing built on placeholder ex-units can escape this method.
                .ignoreScriptCostEvaluationError(verify == null)
                .withTxEvaluator(scriptCostEvaluator)
                // CCL trap 9b, BOTH seams: the default selector will spend a UTxO carrying a
                // reference script, and ChangeOutputAdjustments falls through to its own selector.
                .withUtxoSelectionStrategy(ReferenceScriptSafeUtxoSelection.strategy(utxoSupplier))
                .preBalanceTx((ctx, txn) ->
                        ctx.setUtxoSelector(ReferenceScriptSafeUtxoSelection.selector(utxoSupplier)));

        if (verify != null) {
            context = context.postBalanceTx(verify);
        }

        // CCL trap 9: the reference-script fee is charged only for bytes the library can OBTAIN.
        List<PlutusScript> referenced = referencedScripts(request);
        if (!referenced.isEmpty()) {
            context = context.withReferenceScripts(referenced.toArray(PlutusScript[]::new))
                    .removeDuplicateScriptWitnesses(true);
        }
        if (backendService == null) {
            // CCL trap 2: offline, ReferenceScriptResolver NPEs on a missing supplier.
            context = context.withScriptSupplier(scriptHash -> java.util.Optional.empty());
        }
        return context.build();
    }

    // ---- the post-assert, which is the real guard -------------------------------------------------

    /**
     * Re-derives from the FINISHED body everything the redeemer claims. §22.3's one failure mode is an
     * index that is wrong and still evaluates green on a fixture, so the check is structural and runs
     * inside the pipeline where no future caller can forget it.
     */
    void assertStructure(Transaction txn, Request request, long successIndex, long refundIndex,
                         long bondOutputIndex) {
        List<TransactionOutput> outs = txn.getBody().getOutputs();

        assertCarrier(outs, successIndex, request.plan().successDatum(),
                request.plan().successDatumHash(), "success");
        assertCarrier(outs, refundIndex, request.plan().refundDatum(),
                request.plan().refundDatumHash(), "refund");

        if (bondOutputIndex < 0 || bondOutputIndex >= outs.size()) {
            throw refuse(Refusal.INDEX_MISMATCH, "lenderBondOutputIndex " + bondOutputIndex
                    + " is outside the " + outs.size() + " outputs the body has");
        }
        TransactionOutput bondOut = outs.get((int) bondOutputIndex);
        if (!request.bondUtxo().getAddress().equals(bondOut.getAddress())) {
            throw refuse(Refusal.INDEX_MISMATCH,
                    "lenderBondOutputIndex points at " + bondOut.getAddress() + ", not the bond address");
        }
        if (bondOut.getInlineDatum() == null
                || !request.bondUtxo().getInlineDatum()
                        .equalsIgnoreCase(bondOut.getInlineDatum().serializeToHex())) {
            throw refuse(Refusal.BOND_DATUM_NOT_BYTE_IDENTICAL,
                    "the bond output's datum is not byte-identical to its input's, so equals_data fails");
        }

        // ⛔ The equity output, when one was owed. Checked on the FINISHED body, because the exact-value
        // guard is about what survives balancing rather than what we intended to emit.
        BigInteger equity = request.claim().equity();
        if (equity.signum() > 0) {
            String amAddress = assetManagerAddress();
            TransactionOutput equityOut = outs.stream()
                    .filter(o -> amAddress.equals(o.getAddress()))
                    .findFirst()
                    .orElseThrow(() -> refuse(Refusal.EQUITY_OUTPUT_MISMATCH,
                            "the borrower is owed " + equity + " of equity and no output reaches the "
                                    + "asset-manager credential"));
            if (quantityOf(equityOut, request.collateral()).compareTo(equity) < 0) {
                throw refuse(Refusal.EQUITY_OUTPUT_MISMATCH,
                        "the equity output holds %s of the collateral, the borrower is owed %s"
                                .formatted(quantityOf(equityOut, request.collateral()), equity));
            }
            // ⛔ THE OWNER. equity_sent_to_borrower compares the whole datum with equals_data, and the
            // ownerAsset is the BORROWER's bond. Substituting the lender's — the bond this builder
            // already holds in hand, one field away — produces a perfectly well-formed output the
            // borrower can never claim. Found by a mutant: swapping them killed no test until this
            // check existed, because everything else about the output was still right.
            PlutusData expectedDatum = LiquidationTxEncoder.assetManagerDatumWithToken(
                    new com.fluidtokens.aquarium.offchain.model.loans.AssetManagerDatumWithToken(
                            request.loanUtxo().getTxHash(), request.loanUtxo().getOutputIndex(),
                            LiquidationTxEncoder.PARTIAL_LIQUIDATION_ACTION_HEX,
                            borrowerBondAsset(request)));
            if (equityOut.getInlineDatum() == null
                    || !expectedDatum.serializeToHex()
                            .equalsIgnoreCase(equityOut.getInlineDatum().serializeToHex())) {
                throw refuse(Refusal.EQUITY_OUTPUT_MISMATCH,
                        "the equity output's datum is not the partial-liquidation compensation datum "
                                + "owned by the BORROWER's bond, so equity_sent_to_borrower's "
                                + "equals_data fails");
            }

            // ⚠ THE DoS GUARD, and the reason it is asserted rather than assumed: ada plus the equity
            // token and NOTHING else. A change adjustment or a merged output adds an entry here and
            // the validator refuses — on chain, after the fee is spent.
            int entries = flattenedLength(equityOut);
            if (entries != 2) {
                throw refuse(Refusal.EQUITY_OUTPUT_MISMATCH,
                        ("the equity output carries %d distinct assets; equity_sent_to_borrower's DoS "
                                + "guard demands exactly 2 (ada and the equity token) when "
                                + "repaymentReceipts is false").formatted(entries));
            }
        }

        // The order output: located by its datum, then checked for the value the plan dictates.
        TransactionOutput order = outs.stream()
                .filter(o -> request.orderAddress().equals(o.getAddress()))
                .findFirst()
                .orElseThrow(() -> refuse(Refusal.ORDER_OUTPUT_MISMATCH,
                        "no output at the Minswap order address " + request.orderAddress()));
        BigInteger expectedLovelace = request.plan().orderLovelace();
        if (order.getValue().getCoin().compareTo(expectedLovelace) != 0) {
            throw refuse(Refusal.ORDER_OUTPUT_MISMATCH,
                    "the order holds %s lovelace, the validator demands %s"
                            .formatted(order.getValue().getCoin(), expectedLovelace));
        }
        if (!request.collateral().isAda()) {
            BigInteger collateralInOrder = quantityOf(order, request.collateral());
            if (collateralInOrder.compareTo(request.plan().swappableCollateralAmount()) != 0) {
                throw refuse(Refusal.ORDER_OUTPUT_MISMATCH,
                        "the order holds %s of the collateral, the validator demands %s"
                                .formatted(collateralInOrder, request.plan().swappableCollateralAmount()));
            }

            // ⛔ The fee is the bot's INCOME and no validator asks for it — so nothing but this would
            // notice a builder that left it in the order or gave it to the lender (§25.3).
            BigInteger toBot = outs.stream()
                    .filter(o -> request.changeAddress().equals(o.getAddress()))
                    .map(o -> quantityOf(o, request.collateral()))
                    .reduce(BigInteger.ZERO, BigInteger::add);
            if (toBot.compareTo(request.plan().liquidationFee()) < 0) {
                throw refuse(Refusal.FEE_NOT_PAID_TO_BOT,
                        "the bot's outputs carry %s of the collateral but the fee it earned is %s"
                                .formatted(toBot, request.plan().liquidationFee()));
            }
        }
    }

    private void assertCarrier(List<TransactionOutput> outs, long index, PlutusData datum,
                               String expectedHash, String which) {
        if (index < 0 || index >= outs.size()) {
            throw refuse(Refusal.INDEX_MISMATCH, which + " carrier index " + index
                    + " is outside the " + outs.size() + " outputs the body has");
        }
        TransactionOutput out = outs.get((int) index);
        if (out.getInlineDatum() == null
                || !datum.serializeToHex().equalsIgnoreCase(out.getInlineDatum().serializeToHex())) {
            throw refuse(Refusal.CARRIER_DATUM_MISMATCH,
                    which + " carrier at index " + index + " does not hold the datum the order embeds");
        }
        if (!expectedHash.equalsIgnoreCase(out.getInlineDatum().getDatumHash())) {
            throw refuse(Refusal.CARRIER_DATUM_MISMATCH,
                    which + " carrier hashes to " + out.getInlineDatum().getDatumHash()
                            + " but the order datum embeds " + expectedHash);
        }
    }

    // ---- plumbing ---------------------------------------------------------------------------------

    /**
     * The ada this builder puts in the equity output. Comfortably over min-ada for an
     * ada-plus-one-token output, and fixed rather than computed so the DoS guard's exact-value
     * requirement is not at the mercy of a min-ada calculation that could round differently.
     *
     * <p>⚠ Counted as the bot's outlay by the same conservative rule §30 applies to the order's
     * 2.8 ada: the loan input carries ada of its own and it is not yet measured how much of it
     * {@code loan_claim_action} lets flow here. Wrong in the safe direction.
     */
    static final BigInteger EQUITY_OUTPUT_LOVELACE = BigInteger.valueOf(2_000_000L);

    /** {@code Address(Script(assetManagerSpendScriptHash), None)} — the non-CIP-113 smart credential. */
    private String assetManagerAddress() {
        return AddressProvider.getEntAddress(
                Credential.fromScript(HexUtil.decodeHexString(
                        registry.getAssetManagerSpendScriptHash())), network).getAddress();
    }

    /**
     * {@code Asset { borrowerBondPolicyId, loanId }} — the BORROWER's bond, which is what owns the
     * equity escrow. Using the lender's would produce a well-formed output the borrower can never
     * claim, and no validator here would object to the substitution.
     */
    private AssetType borrowerBondAsset(Request request) {
        return new AssetType(registry.getBorrowerBondPolicyId(), request.claim().loanId());
    }

    private List<Amount> orderValue(Request request) {
        List<Amount> amounts = new ArrayList<>();
        amounts.add(Amount.lovelace(request.plan().orderLovelace()));
        if (!request.collateral().isAda()) {
            // ⚠ CCL trap 3: the 3-arg Amount.asset(policy, name, qty) HEX-ENCODES the name, which
            // would double-encode an already-hex asset name. The unit form takes the bytes as given.
            amounts.add(Amount.asset(request.collateral().toUnit(),
                    request.plan().swappableCollateralAmount()));
        }
        return amounts;
    }

    /**
     * The bond's own datum bytes, refused rather than "fixed" if they do not survive a round trip —
     * CCL trap 4: a decode→re-encode is not byte-stable, and {@code equals_data} compares bytes.
     */
    private PlutusData echoedBondDatum(Request request) {
        String hex = request.bondUtxo().getInlineDatum();
        try {
            PlutusData decoded = PlutusData.deserialize(HexUtil.decodeHexString(hex));
            if (!hex.equalsIgnoreCase(decoded.serializeToHex())) {
                throw refuse(Refusal.BOND_DATUM_NOT_BYTE_IDENTICAL,
                        "the bond's datum does not survive a decode/re-encode round trip");
            }
            return decoded;
        } catch (RefusedException e) {
            throw e;
        } catch (Exception e) {
            throw refuse(Refusal.BOND_DATUM_NOT_BYTE_IDENTICAL, "the bond's datum is undecodable: " + e);
        }
    }

    /**
     * The claim data with the three indexes only the assembled body can know: the bond echo's absolute
     * output position, and the two oracle reference-input positions. The caller supplies the economics;
     * the builder supplies the geometry.
     */
    private static ClaimData withIndexes(ClaimData claim, long bondOutputIndex,
                                         long collateralOracleRefIndex, long principalOracleRefIndex) {
        return new ClaimData(claim.liquidationMode(), BigInteger.valueOf(bondOutputIndex),
                BigInteger.valueOf(collateralOracleRefIndex),
                BigInteger.valueOf(principalOracleRefIndex),
                claim.lenderAuth(), claim.equity(), claim.loanId(), claim.remainingDebt());
    }

    private List<TransactionInput> referenceInputs(Request request) {
        var all = new java.util.LinkedHashSet<TransactionInput>(List.of(
                inputOf(request.configUtxo()), inputOf(request.lmConfigUtxo()),
                inputOf(request.poolRefUtxo())));
        // The oracle's own three: the NFT-bearing input the validator reads the credential and value
        // from, its published script, and — for a provider-backed feed — the provider UTxO. Any of
        // them may be null (a multisig feed has no provider), and a null is skipped rather than
        // encoded as a coordinate that resolves to nothing.
        Stream.of(request.collateralOracle().referenceInput(),
                        request.collateralOracle().referenceScript(),
                        request.collateralOracle().charlieProviderReferenceInput())
                .filter(Objects::nonNull)
                .forEach(all::add);
        all.addAll(request.referenceScripts().values());
        return canonical(new ArrayList<>(all));
    }

    private static long outputIndexWithDatum(Transaction txn, PlutusData datum, String what) {
        String hex = datum.serializeToHex();
        List<TransactionOutput> outs = txn.getBody().getOutputs();
        for (int i = 0; i < outs.size(); i++) {
            var inline = outs.get(i).getInlineDatum();
            if (inline != null && hex.equalsIgnoreCase(inline.serializeToHex())) {
                return i;
            }
        }
        throw refuse(Refusal.CARRIER_DATUM_MISMATCH,
                "the first pass produced no output carrying the " + what + "'s datum");
    }

    private static long outputIndexWithDatumHex(Transaction txn, String datumHex, String what) {
        List<TransactionOutput> outs = txn.getBody().getOutputs();
        for (int i = 0; i < outs.size(); i++) {
            var inline = outs.get(i).getInlineDatum();
            if (inline != null && datumHex.equalsIgnoreCase(inline.serializeToHex())) {
                return i;
            }
        }
        throw refuse(Refusal.INDEX_MISMATCH,
                "the first pass produced no output carrying the " + what + "'s datum");
    }

    /** {@code length(flatten(value))} — ada counts as one entry, each distinct token as one more. */
    private static int flattenedLength(TransactionOutput out) {
        int assets = out.getValue() == null || out.getValue().getMultiAssets() == null
                ? 0
                : out.getValue().getMultiAssets().stream().mapToInt(ma -> ma.getAssets().size()).sum();
        boolean hasAda = out.getValue() != null && out.getValue().getCoin() != null
                && out.getValue().getCoin().signum() > 0;
        return assets + (hasAda ? 1 : 0);
    }

    private static BigInteger quantityOf(TransactionOutput out, AssetType asset) {
        if (out.getValue() == null || out.getValue().getMultiAssets() == null) {
            return BigInteger.ZERO;
        }
        return out.getValue().getMultiAssets().stream()
                .filter(ma -> ma.getPolicyId().equalsIgnoreCase(asset.policyId()))
                .flatMap(ma -> ma.getAssets().stream())
                .filter(a -> HexUtil.encodeHexString(HexUtil.decodeHexString(
                        a.getName().startsWith("0x") ? a.getName().substring(2) : a.getName()))
                        .equalsIgnoreCase(asset.assetName()))
                .map(a -> a.getValue())
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    private boolean isReferenced(Request request, PlutusScript script) {
        try {
            return request.referenceScripts().containsKey(
                    HexUtil.encodeHexString(script.getScriptHash()));
        } catch (Exception e) {
            throw new IllegalStateException("cannot hash a registry script", e);
        }
    }

    private List<PlutusScript> referencedScripts(Request request) {
        List<PlutusScript> out = new ArrayList<>();
        for (PlutusScript s : List.of(registry.getLoanSpendScript(),
                registry.getLenderManagerSpendScript(), registry.getLoanScript(),
                registry.getLoanClaimActionScript(), registry.getLenderManagerScript(),
                registry.getLmLiquidateAndConvertActionScript())) {
            if (isReferenced(request, s)) {
                out.add(s);
            }
        }
        return out;
    }

    private String rewardAddress(String scriptHash) {
        return AddressProvider.getRewardAddress(
                Credential.fromScript(HexUtil.decodeHexString(scriptHash)), network).getAddress();
    }

    private static TransactionInput inputOf(Utxo utxo) {
        return new TransactionInput(utxo.getTxHash(), utxo.getOutputIndex());
    }

    /** The ledger sorts reference inputs before a validator sees them; so must any index we compute. */
    private static List<TransactionInput> canonical(List<TransactionInput> inputs) {
        List<TransactionInput> sorted = new ArrayList<>(inputs);
        sorted.sort(Comparator.comparing(TransactionInput::getTransactionId)
                .thenComparingInt(TransactionInput::getIndex));
        return sorted;
    }

    private static long indexOf(List<TransactionInput> inputs, TransactionInput target, String what) {
        for (int i = 0; i < inputs.size(); i++) {
            if (inputs.get(i).equals(target)) {
                return i;
            }
        }
        throw refuse(Refusal.INDEX_MISMATCH, "the " + what + " reference input is not in the list");
    }
}
