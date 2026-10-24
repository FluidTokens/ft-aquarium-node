package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.MinAdaCalculator;
import com.bloxbean.cardano.client.api.ScriptSupplier;
import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.api.util.ValueUtil;
import com.bloxbean.cardano.client.coinselection.impl.ExcludeUtxoSelector;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.function.TxBuilder;
import com.bloxbean.cardano.client.function.TxBuilderContext;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.transaction.spec.VkeyWitness;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.model.loans.CompoundCandidate;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builds the unsigned repayment-escrow <b>compound</b> transaction: collect a repaid loan's principal
 * from the asset manager and deliver it into the lender's pool, keeping the pool owner's compounding
 * fee (findings §20, §22).
 *
 * <h2>Shape</h2>
 * Four script spends, each authorised by a withdrawal because {@code general_spend} only checks that
 * one exists for its credential, plus seven withdraw-0 invocations — <b>eleven redeemers</b>
 * (§22.1–22.2). One candidate per transaction: the list-shaped redeemers carry exactly one element.
 * Batching several escrows is legal on chain and deliberately not attempted here — every index in
 * §22.3 becomes a genuine list the moment it is, and the failure mode of the worst of them is silent.
 *
 * <h2>⛔ The three index spaces</h2>
 * "Index" means three different things in this transaction (§22.3), and only one of the three can be
 * wrong <em>and still evaluate green</em>: the {@code self.redeemers} positions inside
 * {@code CompoundLiquidityActionWithdrawRedeemer}, because {@code pm_compound_liquidity} gates purely
 * on the action tags it finds there. An offline dry-eval cannot be relied on to catch it — the wrong
 * sibling's tag still type-checks. <b>So the guard is here, in the builder, as a hard refusal derived
 * from the FINISHED body</b>, matching each cited position by its script purpose and never by the
 * redeemer's data shape (CCL trap 14: two of these redeemers are byte-indistinguishable).
 *
 * <h2>⛔ Three byte-identical echoes</h2>
 * {@code lm_compound_action} requires {@code equals_data} on the lender bond and the pool manager,
 * and {@code pool_compound_action} requires it on the pool's datum. CCL trap 4: a decode→re-encode
 * round trip is not byte-stable, so each datum is round-tripped and the build is <b>refused</b> if the
 * bytes move, rather than shipping a transaction that fails on chain after the fee is spent.
 *
 * <h2>Where the fee goes</h2>
 * No validator pays the bot. The pool is required to receive {@code addedLiquidity − compoundingFee};
 * the remainder simply is not constrained, so it flows to the change address — which must be the
 * bot's. §20.1 warns that a builder balancing the whole escrow into the pool donates its own fee, so
 * the pool output is computed as {@code poolInput + (addedLiquidity − fee)} and the bot's net position
 * is re-derived from the finished body and asserted. <b>An explicit fee output is deliberately not
 * emitted</b>: the fee is frequently zero (the only live preview pool publishes
 * {@code compoudingFeePerMille = 0}) and often below min-ada, and an output that cannot hold it would
 * turn an economic question into a build failure. The assertion, not the output shape, is what
 * protects the value.
 *
 * <h2>Ex-units</h2>
 * Measured, never guessed — CCL trap 8. An evaluator is wired through {@code withTxEvaluator} and
 * {@code ignoreScriptCostEvaluationError(evaluator == null)}, and the operator's whole risk case
 * ("exposure is the transaction fee per execution") is true only while that holds: placeholder
 * ex-units move the exposure to the collateral.
 *
 * <h2>The bot ends with ONE output (FAB-152)</h2>
 * The seven withdrawals make cardano-client-lib add a 1 ADA "dummy" output at the change address to
 * trigger input selection (CCL trap 1), and {@code mergeOutputs(false)} — needed so the pool, bond and
 * pool-manager echoes are never coalesced — puts the change in a SECOND output beside it. A
 * target-shape wallet then read {@code ADA_FRAGMENTED} after every compound and the rebalancer paid a
 * fee on the next block to merge the two (mainnet {@code 4e40b3fa…}: {@code #0} 1 ADA, {@code #4} the
 * change). So the {@code preBalanceTx} hook folds every datum-free, script-free output at the change
 * address into the last of them, matched by address and content and never by index. It runs before
 * collateral, script-cost evaluation and balancing, so ex-units and the fee are computed on the
 * folded body; {@link #assertStructure} refuses a finished body that still has two.
 */
@Slf4j
public class CompoundTransactionBuilder {

    /** The unit redeemer {@code Constr 0 []} every {@code general_spend} handler takes. */
    static final PlutusData GENERAL_SPEND_REDEEMER =
            ConstrPlutusData.builder().alternative(0).data(ListPlutusData.of()).build();

    /** Four script inputs, therefore four spend redeemers before the first reward redeemer. */
    static final int SCRIPT_SPEND_COUNT = 4;

    /**
     * The provisional reservation target used by CCL when the wallet has not yet reached the
     * protocol-ceiling target shape. It is only a selection preference: the finished body's actual
     * fee remains the authority in {@link #validateCollateral}.
     */
    private static final BigInteger PROVISIONAL_COLLATERAL = BigInteger.valueOf(5_000_000L);

    public enum Refusal {
        CANDIDATE_NOT_READY,
        POOL_DATUM_NOT_BYTE_IDENTICAL,
        BOND_DATUM_NOT_BYTE_IDENTICAL,
        POOL_MANAGER_DATUM_NOT_BYTE_IDENTICAL,
        REDEEMER_INDEX_MISMATCH,
        POOL_OUTPUT_MISMATCH,
        ECHO_NOT_BYTE_IDENTICAL,
        BOT_NET_MISMATCH,
        COLLATERAL_RETURN_NEGATIVE,
        BOT_OUTPUTS_NOT_FOLDED,
        PRINCIPAL_MISMATCH,
        POOL_MIN_ADA_UNSTABLE,
        INPUT_NOT_UNIQUE,
        INPUT_UNRESOLVED,
        UNEXPECTED_SPENDING_INPUT,
        UNEXPECTED_OUTPUT,
        VALUE_NOT_CONSERVED,
        MINT_OR_WITHDRAWAL_NOT_ZERO,
        OUTPUT_BELOW_MIN_ADA,
        COLLATERAL_NOT_DISTINCT_PURE_ADA,
        FEE_BELOW_LEDGER_MINIMUM,
        TRANSACTION_TOO_LARGE,
        FINAL_EVALUATION_MISMATCH
    }

    @Getter
    public static final class RefusedException extends RuntimeException {
        private final Refusal reason;

        RefusedException(Refusal reason, String detail) {
            super(reason + ": " + detail);
            this.reason = reason;
        }
    }

    static RefusedException refuse(Refusal reason, String detail) {
        return new RefusedException(reason, detail);
    }

    /**
     * @param candidate      a {@link CompoundCandidate#structurallyReady()} candidate
     * @param referenceScripts script hash → the UTxO publishing it. A validator listed here travels
     *                       as a REFERENCE INPUT; one absent travels inline in the witness set.
     *                       ⛔ Exactly one route per validator: a script both witnessed and
     *                       referenced is {@code ExtraneousScriptWitnessesUTXOW} (CCL trap 9)
     * @param bondUtxo       the lender bond's UTxO. Passed in rather than taken from the candidate:
     *                       {@link com.fluidtokens.aquarium.offchain.model.loans.LenderBond} carries
     *                       the decoded datum and the coordinates, not the value, and
     *                       {@code equals_data} compares the whole output — so the builder needs the
     *                       real amounts, from the same index read that produced the candidate
     * @param configUtxo     the main config UTxO, a reference input
     * @param lmConfigUtxo   the lender-manager config UTxO, a reference input
     * @param walletUtxo     the bot's nominated ordinary input, paying the fee; collateral is a
     *                       separate pure-ADA UTxO selected from the same credential
     * @param changeAddress  the bot's address — <b>where the compounding fee lands</b>
     * @param compoundingFee {@code addedLiquidity * feePerMille / 1000}, computed by
     *                       {@link CompoundEconomics} with the validator's truncation
     * @param validFromSlot  ⚠ anchor this to the chain tip, not a wall clock (CCL trap 20). The
     *                       builder takes slots because only the caller knows the node's position.
     */
    public record Request(CompoundCandidate candidate,
                          Map<String, TransactionInput> referenceScripts,
                          Utxo bondUtxo,
                          Utxo configUtxo,
                          Utxo lmConfigUtxo,
                          Utxo walletUtxo,
                          String changeAddress,
                          BigInteger compoundingFee,
                          long validFromSlot,
                          long validToSlot) {
    }

    private final LoansContractRegistry registry;
    private final Network network;
    private final UtxoSupplier utxoSupplier;
    private final ProtocolParamsSupplier protocolParamsSupplier;
    /**
     * Where cardano-client-lib fetches the BYTES of a script that travels as a reference script, or
     * {@code null} for the offline rigs. Production builds with
     * {@code new QuickTxBuilder(utxoSupplier, protocolParamsSupplier, scriptSupplier, null)}:
     * submit-incapable, because the processor slot is null and the evaluator arrives through
     * {@code withTxEvaluator} (CCL trap 8) — and priced, because production declares no reference-script
     * list and this supplier serves the bytes of EVERY referenced script, including a configured
     * coordinate that publishes a script no registry validator names (CCL trap 9). Mirrors the field of
     * the same name in its three siblings (FAB-134 B3b).
     */
    private final ScriptSupplier scriptSupplier;
    private final TransactionEvaluator scriptCostEvaluator;

    /**
     * The script credentials a compound transaction withdraws through, in the order {@code build} adds
     * them. Each needs a REGISTERED reward account or the transaction fails at submit
     * ({@code ConwayWithdrawalsMissingAccounts}); {@code MainnetReferenceScriptsTest} checks that against
     * this list, and {@code CompoundDryEvalTest} pins it to the built transaction's withdrawals.
     */
    public static List<String> withdrawCredentials(LoansContractRegistry registry) {
        return List.of(registry.getAssetManagerWithdrawScriptHash(), registry.getLenderManagerWithdrawScriptHash(),
                registry.getLmCompoundActionScriptHash(), registry.getPoolPolicyId(),
                registry.getPoolCompoundActionScriptHash(), registry.getPoolManagerPolicyId(),
                registry.getPmCompoundLiquidityScriptHash());
    }

    /** Offline: rigs supply every script and evaluate for themselves. */
    public CompoundTransactionBuilder(LoansContractRegistry registry, Network network,
                                      UtxoSupplier utxoSupplier,
                                      ProtocolParamsSupplier protocolParamsSupplier,
                                      TransactionEvaluator scriptCostEvaluator) {
        this(registry, network, (ScriptSupplier) null, utxoSupplier, protocolParamsSupplier,
                scriptCostEvaluator);
    }

    /**
     * The production constructor (FAB-134 B3b-4). Every collaborator is injected and none may be null —
     * <b>the evaluator included</b>. {@code YaciConfig} hands over the index-first {@link UtxoSupplier},
     * the per-epoch {@link ProtocolParamsSupplier}, a {@link ScriptSupplier} that serves verified script
     * bytes, and Blockfrost's {@code /utils/txs/evaluate} narrowed to a {@link TransactionEvaluator}.
     * Nothing here is a {@code BackendService}; see the {@link #scriptSupplier} field.
     */
    public CompoundTransactionBuilder(LoansContractRegistry registry, Network network,
                                      UtxoSupplier utxoSupplier,
                                      ProtocolParamsSupplier protocolParamsSupplier,
                                      ScriptSupplier scriptSupplier,
                                      TransactionEvaluator scriptCostEvaluator) {
        this(registry, network, Objects.requireNonNull(scriptSupplier, "scriptSupplier"), utxoSupplier,
                protocolParamsSupplier, Objects.requireNonNull(scriptCostEvaluator,
                        "a production compound builder without a TransactionEvaluator would ship placeholder "
                                + "ex-units and fail in phase 2, forfeiting collateral — CCL trap 8"));
    }

    /** Both public constructors land here; {@code scriptSupplier} is null only on the offline path. */
    private CompoundTransactionBuilder(LoansContractRegistry registry, Network network,
                                       ScriptSupplier scriptSupplier,
                                       UtxoSupplier utxoSupplier,
                                       ProtocolParamsSupplier protocolParamsSupplier,
                                       TransactionEvaluator scriptCostEvaluator) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.network = Objects.requireNonNull(network, "network");
        this.utxoSupplier = Objects.requireNonNull(utxoSupplier, "utxoSupplier");
        this.protocolParamsSupplier = Objects.requireNonNull(protocolParamsSupplier, "protocolParamsSupplier");
        this.scriptSupplier = scriptSupplier;
        this.scriptCostEvaluator = scriptCostEvaluator;
    }

    // ---- the one entry point ----------------------------------------------------------------

    public Transaction build(Request request) {
        CompoundCandidate c = request.candidate();
        if (!c.structurallyReady()) {
            throw refuse(Refusal.CANDIDATE_NOT_READY,
                    "candidate " + c.loanId() + " is excluded as " + c.exclusion() + ": " + c.detail());
        }

        // Reference inputs, canonically ordered before any index is read off them: the ledger sorts
        // them by (txHash, outputIndex) before a validator ever sees the list.
        List<TransactionInput> all = new ArrayList<>(List.of(
                inputOf(request.configUtxo()), inputOf(request.lmConfigUtxo())));
        all.addAll(request.referenceScripts().values());
        List<TransactionInput> refInputs = canonical(all);
        long configRefIndex = indexOf(refInputs, inputOf(request.configUtxo()), "main config");
        // ⛔ A FOURTH WRINKLE ON §22.3: `configRefInputIndex` does not name the same config on every
        // validator. lender_manager.withdraw resolves the LM CONFIG NFT from the index it is handed
        // (it reads lmConfig fields 1..7 to pick the action script), while every other redeemer here
        // resolves the MAIN config. Same field name, different reference input — and handing it the
        // main config's index fails as a plain EvaluationFailure with nothing naming the cause.
        // Found by the dry-eval, not by reading: withdraw index 4.
        long lmConfigRefIndex = indexOf(refInputs, inputOf(request.lmConfigUtxo()), "lm config");

        // ⛔ self.redeemers positions. Computed, then re-derived from the finished body and compared
        // (assertStructure) — §22.3's one failure mode that can be wrong and still evaluate green.
        List<String> withdrawalOrder = withdrawalOrder();
        long poolWithdrawRedeemerIndex = rewardRedeemerIndex(withdrawalOrder, registry.getPoolPolicyId());
        long lmWithdrawRedeemerIndex =
                rewardRedeemerIndex(withdrawalOrder, registry.getLenderManagerWithdrawScriptHash());

        ScriptTx tx = assemble(request, configRefIndex, lmConfigRefIndex,
                poolWithdrawRedeemerIndex, lmWithdrawRedeemerIndex);

        return complete(request, refInputs, tx,
                (ctx, txn) -> assertStructure(txn, request, withdrawalOrder,
                        poolWithdrawRedeemerIndex, lmWithdrawRedeemerIndex));
    }

    // ---- assembly ---------------------------------------------------------------------------

    private ScriptTx assemble(Request request, long configRefIndex, long lmConfigRefIndex,
                              long poolWithdrawRedeemerIndex, long lmWithdrawRedeemerIndex) {
        CompoundCandidate c = request.candidate();
        ScriptTx tx = new ScriptTx();

        // Four script spends. general_spend ignores the redeemer; the authorisation is the presence
        // of a withdrawal at its withdrawScriptHash, added below.
        tx.collectFrom(c.escrow(), GENERAL_SPEND_REDEEMER);
        tx.collectFrom(request.bondUtxo(), GENERAL_SPEND_REDEEMER);
        tx.collectFrom(c.pool(), GENERAL_SPEND_REDEEMER);
        tx.collectFrom(c.poolManager(), GENERAL_SPEND_REDEEMER);
        tx.collectFrom(request.walletUtxo());

        // The pool gains Q-F in the principal's own raw unit. ADA keeps its established combined
        // lovelace semantics. For a native principal the pool's complete value is preserved, only the
        // principal quantity changes, and coin rises solely by the current-protocol min-ADA top-up T.
        PlutusData poolDatum = echo(c.pool().getInlineDatum(),
                Refusal.POOL_DATUM_NOT_BYTE_IDENTICAL, "pool");
        tx.payToContract(c.pool().getAddress(), poolOutputAmounts(request, poolDatum), poolDatum);

        // Two byte-identical restorations: equals_data compares the WHOLE output, so value and datum
        // both go back exactly as they came in.
        tx.payToContract(request.bondUtxo().getAddress(), List.copyOf(request.bondUtxo().getAmount()),
                echo(c.bond().inlineDatum(), Refusal.BOND_DATUM_NOT_BYTE_IDENTICAL, "lender bond"));
        tx.payToContract(c.poolManager().getAddress(), List.copyOf(c.poolManager().getAmount()),
                echo(c.poolManager().getInlineDatum(), Refusal.POOL_MANAGER_DATUM_NOT_BYTE_IDENTICAL,
                        "pool manager"));

        // The seven withdrawals (§22.2) -- exactly withdrawCredentials(registry), which the compound
        // registration check reads. Order of addition is irrelevant — the ledger sorts them.
        tx.withdraw(rewardAddress(registry.getAssetManagerWithdrawScriptHash()), BigInteger.ZERO,
                CompoundTxEncoder.assetManagerWithdraw(configRefIndex));
        // ⛔ THE LM CONFIG, not the main one — see build().
        tx.withdraw(rewardAddress(registry.getLenderManagerWithdrawScriptHash()), BigInteger.ZERO,
                CompoundTxEncoder.lenderManagerWithdraw(lmConfigRefIndex));
        tx.withdraw(rewardAddress(registry.getLmCompoundActionScriptHash()), BigInteger.ZERO,
                CompoundTxEncoder.lmCompound(configRefIndex, List.of(c.poolId()),
                        List.of(0L), List.of(0L)));
        tx.withdraw(rewardAddress(registry.getPoolPolicyId()), BigInteger.ZERO,
                CompoundTxEncoder.poolWithdraw(configRefIndex));
        tx.withdraw(rewardAddress(registry.getPoolCompoundActionScriptHash()), BigInteger.ZERO,
                CompoundTxEncoder.poolCompoundAction(configRefIndex, List.of(c.poolId())));
        tx.withdraw(rewardAddress(registry.getPoolManagerPolicyId()), BigInteger.ZERO,
                CompoundTxEncoder.poolManagerWithdraw(configRefIndex));
        tx.withdraw(rewardAddress(registry.getPmCompoundLiquidityScriptHash()), BigInteger.ZERO,
                CompoundTxEncoder.compoundLiquidity(poolWithdrawRedeemerIndex, lmWithdrawRedeemerIndex));


        // ⛔ EXACTLY ONE ROUTE PER VALIDATOR. A script carried in the witness set AND named by a
        // reference input is rejected as ExtraneousScriptWitnessesUTXOW (CCL trap 9), so a validator
        // with a published coordinate is attached NOT AT ALL — only read from. One without a
        // coordinate travels inline, because a redeemer with no reachable script is
        // RequiredRedeemersMismatch (trap 13). Both halves fail loudly; neither fails quietly.
        for (var spend : List.of(registry.getAssetManagerSpendScript(),
                registry.getLenderManagerSpendScript(), registry.getPoolSpendScript(),
                registry.getPoolManagerSpendScript())) {
            if (!isReferenced(request, spend)) {
                tx.attachSpendingValidator(spend);
            }
        }
        for (var reward : List.of(registry.getAssetManagerScript(), registry.getLenderManagerScript(),
                registry.getLmCompoundActionScript(), registry.getPoolScript(),
                registry.getPoolCompoundActionScript(), registry.getPoolManagerScript(),
                registry.getPmCompoundLiquidityScript())) {
            if (!isReferenced(request, reward)) {
                tx.attachRewardValidator(reward);
            }
        }

        return tx;
    }

    private Transaction complete(Request request, List<TransactionInput> refInputs,
                                 ScriptTx tx, TxBuilder verify) {
        tx.readFrom(refInputs.toArray(TransactionInput[]::new));
        TransactionInput[] collateralInputs = collateralInputsFor(request);
        Set<TransactionInput> reservedCollateral = Set.of(collateralInputs);
        // Production: the injected utxo supplier (the local index first), the per-epoch protocol params and
        // a SCRIPT SUPPLIER serving verified script bytes. The processor slot is null and stays null: the
        // evaluator arrives through withTxEvaluator below (CCL trap 8), so this builder can price a
        // transaction and has nothing that could submit one. Offline: the three-argument form, because
        // the rigs hand every script in explicitly.
        QuickTxBuilder quickTxBuilder = scriptSupplier != null
                ? new QuickTxBuilder(utxoSupplier, protocolParamsSupplier, scriptSupplier, null)
                : new QuickTxBuilder(utxoSupplier, protocolParamsSupplier, null);

        var context = quickTxBuilder.compose(tx)
                .feePayer(request.changeAddress())
                .collateralPayer(request.changeAddress())
                .validFrom(request.validFromSlot())
                .validTo(request.validToSlot())
                .mergeOutputs(false)
                // CCL trap 8/13: with an evaluator present a failed evaluation must ABORT the build.
                // The default (true) turns it into a log.warn and hands back placeholder ex-units,
                // which is a phase-2 failure waiting to be submitted.
                .ignoreScriptCostEvaluationError(scriptCostEvaluator == null)
                // CCL trap 9b, BOTH seams: the default selector will happily spend a UTxO carrying a
                // reference script, and ChangeOutputAdjustments falls through to its own selector.
                .withUtxoSelectionStrategy(ReferenceScriptSafeUtxoSelection.strategy(utxoSupplier))
                // ⛔ ONE LAMBDA: preBalanceTx is a SETTER (QuickTxBuilder:263 assigns), so a second call
                // would silently discard the first. Both jobs live here.
                .preBalanceTx((ctx, txn) -> {
                    // QuickTxBuilder initially wraps the selector to exclude explicit collateral,
                    // but replacing it here would discard that wrapper. Reapply it every time this
                    // setter is used so neither the explicit selection nor a balancing shortfall can
                    // consume the reserved pure-ADA collateral.
                    ctx.setUtxoSelector(new ExcludeUtxoSelector(
                            ReferenceScriptSafeUtxoSelection.selector(utxoSupplier),
                            reservedCollateral));
                    // FAB-152: after the selector, fold the bot's outputs into one. This runs after
                    // input selection and change, and BEFORE collateral, script-cost evaluation and
                    // balancing — so the ex-units and the fee are computed on the folded body.
                    foldBotOutputs(txn, request.changeAddress());
                })
                // ⛔ CCL trap 9b, the THIRD seam: with no collateral inputs named, QuickTxBuilder's
                // buildCollateralOutput picks collateral with its own unguarded
                // DefaultUtxoSelectionStrategyImpl (a hardcoded 5 ADA target) — blind to both guards
                // above. Measured (FAB-134 B3b-4): a 3.0-4.5 ADA wallet beside published reference
                // scripts pledged one of them as COLLATERAL, which a phase-2 failure consumes. So the
                // collateral is chosen here from reference-script-free wallet UTxOs. The protocol
                // ceiling is a preferred target; the exact final fee decides sufficiency below.
                .withCollateralInputs(collateralInputs)
                .postBalanceTx(verify.andThen((ctx, txn) -> validateFinalTransaction(ctx, txn, request)));

        // CCL trap 9: the reference-script fee is only charged for bytes the library can OBTAIN.
        // Without this it charges ZERO and the transaction is short by 15 lovelace per byte —
        // FeeTooSmallUTxO at phase 1. removeDuplicateScriptWitnesses strips any copy a mint or
        // attach left behind, which is the other half of the same rejection.
        //
        // ⛔ But a DECLARED list wins outright: when withReferenceScripts is non-empty, cardano-client-lib
        // 0.7.2 (FeeCalculators:125-145) prices exactly the declared scripts and never asks the supplier.
        // referencedScripts() can only name REGISTRY validators, while CompoundExecutor references any
        // configured coordinate that publishes a script — a superseded coordinate left in an operator's
        // override after a redeploy is one. Declared, that script was charged zero (FAB-134 B3b-4,
        // measured: 32,716 lovelace under the ledger floor, FeeTooSmallUTxO).
        //
        // ⇒ Production declares NOTHING and the byte-serving supplier prices every referenced script, as
        // the three siblings do. Offline there is no supplier, so the declaration stays.
        // removeDuplicateScriptWitnesses stays on both paths.
        List<PlutusScript> referenced = referencedScripts(request);
        if (!referenced.isEmpty()) {
            if (scriptSupplier == null) {
                context = context.withReferenceScripts(referenced.toArray(PlutusScript[]::new));
            }
            context = context.removeDuplicateScriptWitnesses(true);
        }

        if (scriptSupplier == null) {
            // CCL trap 2: offline, ReferenceScriptResolver walks every reference input looking for a
            // script to fetch and NPEs on the missing supplier. The rigs hand scripts in explicitly.
            context = context.withScriptSupplier(scriptHash -> java.util.Optional.empty());
        }
        if (scriptCostEvaluator != null) {
            context = context.withTxEvaluator(scriptCostEvaluator);
        }
        return context.build();
    }

    // ---- asset-aware construction and finished-body ledger checks ---------------------------

    private List<Amount> poolOutputAmounts(Request request, PlutusData datum) {
        return ValueUtil.toAmountList(poolOutputValue(request, datum));
    }

    private Value poolOutputValue(Request request, PlutusData datum) {
        CompoundCandidate candidate = request.candidate();
        BigInteger delta = compoundDelta(request);
        Value value = valueOf(candidate.pool().getAmount());
        if (candidate.principalIsAda()) {
            return value.addCoin(delta);
        }

        String unit = principalUnit(request);
        value = value.add(assetValue(unit, delta));
        BigInteger originalCoin = lovelaceOf(candidate.pool().getAmount());
        BigInteger coin = originalCoin;
        ProtocolParams params = protocolParams();
        MinAdaCalculator calculator = new MinAdaCalculator(params);
        for (int attempt = 0; attempt < 16; attempt++) {
            value.setCoin(coin);
            TransactionOutput output = new TransactionOutput(candidate.pool().getAddress(), value);
            output.setInlineDatum(datum);
            BigInteger required = calculator.calculateMinAda(output);
            BigInteger next = originalCoin.add(required.subtract(originalCoin).max(BigInteger.ZERO));
            if (next.equals(coin)) {
                return value;
            }
            coin = next;
        }
        throw refuse(Refusal.POOL_MIN_ADA_UNSTABLE,
                "pool coin/min-ADA did not stabilise after 16 serialisation-aware iterations");
    }

    private BigInteger compoundDelta(Request request) {
        BigInteger quantity = request.candidate().addedLiquidity();
        BigInteger fee = request.compoundingFee();
        if (quantity == null || fee == null || quantity.signum() <= 0 || fee.signum() < 0
                || fee.compareTo(quantity) >= 0) {
            throw refuse(Refusal.PRINCIPAL_MISMATCH,
                    "principal quantity and fee must satisfy Q > F >= 0, got Q=" + quantity + ", F=" + fee);
        }
        String unit = principalUnit(request);
        BigInteger actual = unitsOf(request.candidate().escrow().getAmount())
                .getOrDefault(unit, BigInteger.ZERO);
        if (!actual.equals(quantity)) {
            throw refuse(Refusal.PRINCIPAL_MISMATCH,
                    "escrow carries " + actual + " " + unit + ", candidate says Q=" + quantity);
        }
        Set<String> allowed = request.candidate().principalIsAda()
                ? Set.of("lovelace") : Set.of("lovelace", unit);
        Set<String> carried = new HashSet<>(unitsOf(request.candidate().escrow().getAmount()).keySet());
        carried.removeIf(k -> unitsOf(request.candidate().escrow().getAmount()).get(k).signum() == 0);
        if (!allowed.containsAll(carried)) {
            throw refuse(Refusal.PRINCIPAL_MISMATCH,
                    "escrow carries unsupported units " + carried + ", allowed " + allowed);
        }
        return quantity.subtract(fee);
    }

    private String principalUnit(Request request) {
        CompoundCandidate candidate = request.candidate();
        if (candidate.principalIsAda()) {
            return "lovelace";
        }
        if (candidate.bond() == null || candidate.bond().datum() == null
                || candidate.bond().datum().principalAsset() == null
                || candidate.bond().datum().principalAsset().isAda()) {
            throw refuse(Refusal.PRINCIPAL_MISMATCH,
                    "token-principal candidate has no non-ADA principal unit in its lender bond");
        }
        return candidate.bond().datum().principalAsset().toUnit();
    }

    /** Reserve distinct pure-ADA collateral; exact sufficiency is proven against the final fee. */
    private TransactionInput[] collateralInputsFor(Request request) {
        ProtocolParams params = protocolParams();
        BigInteger required = LiquidateTransactionBuilder.maxPossibleCollateral(params);
        Set<TransactionInput> ordinary = Set.of(inputOf(request.candidate().escrow()),
                inputOf(request.bondUtxo()), inputOf(request.candidate().pool()),
                inputOf(request.candidate().poolManager()), inputOf(request.walletUtxo()));
        List<Utxo> wallet;
        try {
            wallet = utxoSupplier.getAll(request.changeAddress());
        } catch (Exception e) {
            throw refuse(Refusal.COLLATERAL_NOT_DISTINCT_PURE_ADA,
                    "could not read wallet UTxOs while reserving collateral: " + e);
        }
        List<Utxo> candidates = wallet.stream()
                .filter(ReferenceScriptSafeUtxoSelection::spendable)
                .filter(u -> u.getInlineDatum() == null && u.getDataHash() == null)
                .filter(CompoundTransactionBuilder::pureAda)
                .filter(u -> !ordinary.contains(inputOf(u)))
                .sorted(Comparator.comparing((Utxo u) -> lovelaceOf(u.getAmount())).reversed())
                .toList();
        int limit = Math.max(1, params.getMaxCollateralInputs());
        Utxo preferred = candidates.stream()
                .filter(u -> lovelaceOf(u.getAmount()).equals(required))
                .findFirst().orElse(null);
        if (preferred == null) {
            // Do not reserve every pure-ADA UTxO merely to chase the protocol-wide fee ceiling. A
            // temporarily unshaped wallet commonly has one 5 ADA collateral candidate and one larger
            // balancing input; taking both makes an otherwise valid transaction look unfunded. Prefer
            // the smallest candidate at least as large as CCL's provisional target, or the largest
            // available candidate when none reaches it. The exact final fee decides sufficiency below.
            preferred = candidates.stream()
                    .filter(u -> lovelaceOf(u.getAmount()).compareTo(PROVISIONAL_COLLATERAL) >= 0)
                    .min(Comparator.comparing(u -> lovelaceOf(u.getAmount())))
                    .orElseGet(() -> candidates.stream()
                            .max(Comparator.comparing(u -> lovelaceOf(u.getAmount())))
                            .orElse(null));
        }
        if (preferred == null) {
            throw refuse(Refusal.COLLATERAL_NOT_DISTINCT_PURE_ADA,
                    "wallet has no distinct pure-ADA collateral candidate");
        }
        List<TransactionInput> chosen = List.of(inputOf(preferred));
        if (chosen.size() > limit) {
            throw refuse(Refusal.COLLATERAL_NOT_DISTINCT_PURE_ADA,
                    "collateral selection exceeds maxCollateralInputs=" + limit);
        }
        // The ceiling is a preferred reservation target, not a minimum. The body does not yet have
        // its evaluator costs or final fee here; validateCollateral later proves this chosen capacity
        // against ceil(actual final fee * collateralPercent / 100) and refuses any shortfall.
        return chosen.toArray(TransactionInput[]::new);
    }

    private ProtocolParams protocolParams() {
        try {
            ProtocolParams params = protocolParamsSupplier.getProtocolParams();
            if (params == null || params.getCoinsPerUtxoSize() == null
                    || params.getCoinsPerUtxoSize().isBlank()) {
                throw new IllegalStateException("missing coinsPerUtxoSize");
            }
            return params;
        } catch (Exception e) {
            throw refuse(Refusal.POOL_MIN_ADA_UNSTABLE,
                    "current protocol parameters are unavailable: " + e);
        }
    }

    private void validateFinalTransaction(TxBuilderContext context, Transaction built, Request request) {
        final Transaction tx;
        try {
            tx = Transaction.deserialize(built.serialize());
        } catch (Exception e) {
            throw refuse(Refusal.VALUE_NOT_CONSERVED,
                    "the final unsigned transaction does not round-trip: " + e);
        }
        List<String> order = withdrawalOrder();
        assertStructure(tx, request, order,
                rewardRedeemerIndex(order, registry.getPoolPolicyId()),
                rewardRedeemerIndex(order, registry.getLenderManagerWithdrawScriptHash()));
        validateInputsOutputsAndValue(tx, request);
        validateCollateral(tx, request);
        validateMinAdaAndSize(tx, context.getProtocolParams());
        validateMinimumFee(tx, context);
        validateFinalEvaluation(tx, context.getAllUtxos());
    }

    /**
     * Package-visible mutation seam for the ledger-shaped tests. It deliberately serialises and
     * deserialises first, just like the production post-balance gate, so a test cannot prove a
     * property of an in-memory shape that is not the body which would leave this builder.
     */
    void validateLedgerShape(Transaction built, Request request) {
        final Transaction tx;
        try {
            tx = Transaction.deserialize(built.serialize());
        } catch (Exception e) {
            throw refuse(Refusal.VALUE_NOT_CONSERVED,
                    "the unsigned transaction does not round-trip: " + e);
        }
        validateInputsOutputsAndValue(tx, request);
        validateCollateral(tx, request);
        validateMinAdaAndSize(tx, protocolParams());
    }

    /**
     * Package-visible mutation seam for the final ledger fee comparison. The test supplies the
     * production-derived builder context and this method invokes the same guard as
     * {@link #validateFinalTransaction}; it does not duplicate the fee formula.
     */
    void validateLedgerFee(Transaction built, TxBuilderContext context) {
        final Transaction tx;
        try {
            tx = Transaction.deserialize(built.serialize());
        } catch (Exception e) {
            throw refuse(Refusal.FEE_BELOW_LEDGER_MINIMUM,
                    "the unsigned transaction does not round-trip: " + e);
        }
        validateMinimumFee(tx, context);
    }

    private void validateInputsOutputsAndValue(Transaction tx, Request request) {
        List<TransactionInput> inputs = tx.getBody().getInputs();
        if (inputs.size() != new HashSet<>(inputs).size()) {
            throw refuse(Refusal.INPUT_NOT_UNIQUE, "the final body repeats a spending input");
        }
        Set<TransactionInput> expectedScripts = Set.of(inputOf(request.candidate().escrow()),
                inputOf(request.bondUtxo()), inputOf(request.candidate().pool()),
                inputOf(request.candidate().poolManager()));
        Set<TransactionInput> foundScripts = new HashSet<>();
        Map<String, BigInteger> allInputs = new TreeMap<>();
        Map<String, BigInteger> walletInputs = new TreeMap<>();
        for (TransactionInput input : inputs) {
            Utxo spent = resolve(input, Refusal.INPUT_UNRESOLVED, "spending input");
            addUnits(allInputs, unitsOf(spent.getAmount()));
            if (expectedScripts.contains(input)) {
                foundScripts.add(input);
            } else if (samePaymentCredential(spent.getAddress(), request.changeAddress())) {
                addUnits(walletInputs, unitsOf(spent.getAmount()));
            } else {
                throw refuse(Refusal.UNEXPECTED_SPENDING_INPUT,
                        "unclassified spending input " + ref(input) + " at " + spent.getAddress()
                                + "; ordinary compound permits only escrow/bond/pool/manager and wallet inputs");
            }
        }
        if (!foundScripts.equals(expectedScripts)) {
            throw refuse(Refusal.UNEXPECTED_SPENDING_INPUT,
                    "expected script inputs " + expectedScripts + ", found " + foundScripts);
        }

        if (tx.getBody().getMint() != null && tx.getBody().getMint().stream()
                .flatMap(ma -> ma.getAssets().stream()).anyMatch(a -> a.getValue().signum() != 0)) {
            throw refuse(Refusal.MINT_OR_WITHDRAWAL_NOT_ZERO, "ordinary compound must mint/burn zero");
        }
        if (tx.getBody().getWithdrawals() != null && tx.getBody().getWithdrawals().stream()
                .anyMatch(w -> w.getCoin().signum() != 0)) {
            throw refuse(Refusal.MINT_OR_WITHDRAWAL_NOT_ZERO,
                    "ordinary compound withdrawals must all be zero");
        }
        if (tx.getBody().getCerts() != null && !tx.getBody().getCerts().isEmpty()) {
            throw refuse(Refusal.MINT_OR_WITHDRAWAL_NOT_ZERO, "ordinary compound carries certificates");
        }

        TransactionOutput pool = onlyOutputWithDatum(tx, request.candidate().pool().getAddress(),
                request.candidate().pool().getInlineDatum(), "pool");
        TransactionOutput bond = onlyOutputWithDatum(tx, request.bondUtxo().getAddress(),
                request.candidate().bond().inlineDatum(), "lender bond");
        TransactionOutput manager = onlyOutputWithDatum(tx, request.candidate().poolManager().getAddress(),
                request.candidate().poolManager().getInlineDatum(), "pool manager");
        Map<String, BigInteger> allOutputs = new TreeMap<>();
        Map<String, BigInteger> walletOutputs = new TreeMap<>();
        for (TransactionOutput output : tx.getBody().getOutputs()) {
            addUnits(allOutputs, unitsOf(output.getValue()));
            if (output == pool || output == bond || output == manager) {
                continue;
            }
            if (samePaymentCredential(output.getAddress(), request.changeAddress())) {
                addUnits(walletOutputs, unitsOf(output.getValue()));
            } else {
                throw refuse(Refusal.UNEXPECTED_OUTPUT,
                        "unclassified output at " + output.getAddress() + " with value "
                                + unitsOf(output.getValue()));
            }
        }

        Map<String, BigInteger> lhs = new TreeMap<>(allInputs);
        if (tx.getBody().getMint() != null) {
            tx.getBody().getMint().forEach(ma -> ma.getAssets().forEach(a ->
                    lhs.merge(ma.getPolicyId() + a.getNameAsHex().replaceFirst("^0x", ""),
                            a.getValue(), BigInteger::add)));
        }
        Map<String, BigInteger> rhs = new TreeMap<>(allOutputs);
        rhs.merge("lovelace", tx.getBody().getFee(), BigInteger::add);
        removeZeros(lhs);
        removeZeros(rhs);
        if (!lhs.equals(rhs)) {
            throw refuse(Refusal.VALUE_NOT_CONSERVED,
                    "inputs+mint " + lhs + " != outputs+fee " + rhs);
        }

        Map<String, BigInteger> walletDelta = new TreeMap<>(walletOutputs);
        subtractUnits(walletDelta, walletInputs);
        Map<String, BigInteger> expectedDelta = new TreeMap<>();
        PlutusData expectedPoolDatum = echo(request.candidate().pool().getInlineDatum(),
                Refusal.POOL_DATUM_NOT_BYTE_IDENTICAL, "pool");
        BigInteger poolTopUp = poolOutputValue(request, expectedPoolDatum).getCoin()
                .subtract(lovelaceOf(request.candidate().pool().getAmount()));
        if (request.candidate().principalIsAda()) {
            expectedDelta.put("lovelace", request.compoundingFee().subtract(tx.getBody().getFee()));
        } else {
            String principal = principalUnit(request);
            BigInteger principalPaidToCredential = tx.getBody().getOutputs().stream()
                    .filter(o -> samePaymentCredential(o.getAddress(), request.changeAddress()))
                    .map(o -> unitsOf(o.getValue()).getOrDefault(principal, BigInteger.ZERO))
                    .reduce(BigInteger.ZERO, BigInteger::add);
            BigInteger principalSpentByCredential = inputs.stream()
                    .map(i -> resolve(i, Refusal.INPUT_UNRESOLVED, "principal-accounting input"))
                    .filter(u -> samePaymentCredential(u.getAddress(), request.changeAddress()))
                    .map(u -> unitsOf(u.getAmount()).getOrDefault(principal, BigInteger.ZERO))
                    .reduce(BigInteger.ZERO, BigInteger::add);
            BigInteger actualPrincipalDelta = principalPaidToCredential.subtract(principalSpentByCredential);
            if (!actualPrincipalDelta.equals(request.compoundingFee())) {
                throw refuse(Refusal.BOT_NET_MISMATCH,
                        "credential-owned wallet principal delta is " + actualPrincipalDelta
                                + " " + principal + ", expected fee " + request.compoundingFee());
            }
            expectedDelta.put(principal, request.compoundingFee());
            expectedDelta.put("lovelace", lovelaceOf(request.candidate().escrow().getAmount())
                    .subtract(tx.getBody().getFee()).subtract(poolTopUp));
        }
        removeZeros(walletDelta);
        removeZeros(expectedDelta);
        if (!walletDelta.equals(expectedDelta)) {
            throw refuse(Refusal.BOT_NET_MISMATCH,
                    "credential-owned wallet delta is " + walletDelta + ", expected " + expectedDelta);
        }
    }

    private void validateCollateral(Transaction tx, Request request) {
        List<TransactionInput> collateral = tx.getBody().getCollateral() == null
                ? List.of() : tx.getBody().getCollateral();
        if (collateral.isEmpty() || collateral.size() != new HashSet<>(collateral).size()) {
            throw refuse(Refusal.COLLATERAL_NOT_DISTINCT_PURE_ADA,
                    "collateral must be a non-empty unique set");
        }
        Set<TransactionInput> spending = new HashSet<>(tx.getBody().getInputs());
        BigInteger capacity = BigInteger.ZERO;
        for (TransactionInput input : collateral) {
            if (spending.contains(input)) {
                throw refuse(Refusal.COLLATERAL_NOT_DISTINCT_PURE_ADA,
                        "collateral " + ref(input) + " is also an ordinary spending input");
            }
            Utxo utxo = resolve(input, Refusal.COLLATERAL_NOT_DISTINCT_PURE_ADA, "collateral input");
            if (!pureAda(utxo) || utxo.getInlineDatum() != null || utxo.getDataHash() != null
                    || utxo.getReferenceScriptHash() != null
                    || !samePaymentCredential(utxo.getAddress(), request.changeAddress())) {
                throw refuse(Refusal.COLLATERAL_NOT_DISTINCT_PURE_ADA,
                        "collateral " + ref(input)
                                + " is not datum-free, reference-script-free credential-owned pure ADA");
            }
            capacity = capacity.add(lovelaceOf(utxo.getAmount()));
        }
        ProtocolParams params = protocolParams();
        BigInteger required = ceilPercent(tx.getBody().getFee(), params.getCollateralPercent());
        BigInteger declared = tx.getBody().getTotalCollateral();
        if (declared == null || declared.compareTo(required) < 0 || capacity.compareTo(declared) < 0) {
            throw refuse(Refusal.COLLATERAL_NOT_DISTINCT_PURE_ADA,
                    "capacity=" + capacity + ", declared=" + declared + ", required=" + required);
        }
        BigInteger returned = capacity.subtract(declared);
        TransactionOutput collateralReturn = tx.getBody().getCollateralReturn();
        if (returned.signum() == 0) {
            if (collateralReturn != null && !unitsOf(collateralReturn.getValue()).isEmpty()) {
                throw refuse(Refusal.COLLATERAL_NOT_DISTINCT_PURE_ADA,
                        "zero collateral return expected, body carries " + unitsOf(collateralReturn.getValue()));
            }
        } else if (collateralReturn == null
                || !samePaymentCredential(collateralReturn.getAddress(), request.changeAddress())
                || !unitsOf(collateralReturn.getValue()).equals(Map.of("lovelace", returned))) {
            throw refuse(Refusal.COLLATERAL_NOT_DISTINCT_PURE_ADA,
                    "collateral return does not equal capacity-totalCollateral: " + returned);
        }
    }

    private void validateMinAdaAndSize(Transaction tx, ProtocolParams params) {
        MinAdaCalculator calculator = new MinAdaCalculator(params);
        List<TransactionOutput> outputs = new ArrayList<>(tx.getBody().getOutputs());
        if (tx.getBody().getCollateralReturn() != null) {
            outputs.add(tx.getBody().getCollateralReturn());
        }
        for (int i = 0; i < outputs.size(); i++) {
            TransactionOutput output = outputs.get(i);
            if (unitsOf(output.getValue()).values().stream().anyMatch(q -> q.signum() < 0)) {
                throw refuse(Refusal.VALUE_NOT_CONSERVED, "output " + i + " carries a negative quantity");
            }
            BigInteger minimum = calculator.calculateMinAda(output);
            if (output.getValue().getCoin().compareTo(minimum) < 0) {
                throw refuse(Refusal.OUTPUT_BELOW_MIN_ADA,
                        "output " + i + " has " + output.getValue().getCoin() + ", minimum " + minimum);
            }
        }
        try {
            Transaction witnessed = withDummyWitness(tx);
            int size = witnessed.serialize().length;
            if (params.getMaxTxSize() == null || size > params.getMaxTxSize()) {
                throw refuse(Refusal.TRANSACTION_TOO_LARGE,
                        "one-witness transaction is " + size + " bytes, max " + params.getMaxTxSize());
            }
        } catch (RefusedException e) {
            throw e;
        } catch (Exception e) {
            throw refuse(Refusal.TRANSACTION_TOO_LARGE, "could not measure final transaction: " + e);
        }
    }

    private void validateMinimumFee(Transaction tx, TxBuilderContext context) {
        try {
            ProtocolParams params = context.getProtocolParams();
            Transaction witnessed = withDummyWitness(tx);
            BigInteger base = BigInteger.valueOf(params.getMinFeeA())
                    .multiply(BigInteger.valueOf(witnessed.serialize().length))
                    .add(BigInteger.valueOf(params.getMinFeeB()));
            BigDecimal script = BigDecimal.ZERO;
            for (Redeemer redeemer : tx.getWitnessSet().getRedeemers()) {
                script = script.add(params.getPriceMem().multiply(new BigDecimal(redeemer.getExUnits().getMem())))
                        .add(params.getPriceStep().multiply(new BigDecimal(redeemer.getExUnits().getSteps())));
            }
            BigInteger scriptFee = script.setScale(0, RoundingMode.CEILING).toBigIntegerExact();
            long referenceBytes = referenceScriptBytes(tx, context);
            BigInteger referenceFee = tierReferenceFee(referenceBytes,
                    params.getMinFeeRefScriptCostPerByte());
            BigInteger minimum = base.add(scriptFee).add(referenceFee);
            if (tx.getBody().getFee().compareTo(minimum) < 0) {
                throw refuse(Refusal.FEE_BELOW_LEDGER_MINIMUM,
                        "fee " + tx.getBody().getFee() + " is below " + minimum
                                + " (base " + base + ", scripts " + scriptFee
                                + ", reference scripts " + referenceFee + ")");
            }
        } catch (RefusedException e) {
            throw e;
        } catch (Exception e) {
            throw refuse(Refusal.FEE_BELOW_LEDGER_MINIMUM,
                    "could not calculate the final ledger fee floor: " + e);
        }
    }

    private long referenceScriptBytes(Transaction tx, TxBuilderContext context) {
        Map<String, PlutusScript> declared = new HashMap<>();
        for (PlutusScript script : context.getRefPlutusScripts()) {
            try {
                declared.put(HexUtil.encodeHexString(script.getScriptHash()), script);
            } catch (Exception e) {
                throw refuse(Refusal.FEE_BELOW_LEDGER_MINIMUM, "could not hash declared reference script: " + e);
            }
        }
        long bytes = 0;
        for (TransactionInput input : tx.getBody().getReferenceInputs()) {
            Utxo utxo = resolve(input, Refusal.FEE_BELOW_LEDGER_MINIMUM, "reference input");
            if (utxo.getReferenceScriptHash() == null) {
                continue;
            }
            PlutusScript script = declared.get(utxo.getReferenceScriptHash());
            if (script == null && scriptSupplier != null) {
                try {
                    script = scriptSupplier.getScript(utxo.getReferenceScriptHash()).orElse(null);
                } catch (Exception e) {
                    throw refuse(Refusal.FEE_BELOW_LEDGER_MINIMUM,
                            "could not load reference script " + utxo.getReferenceScriptHash() + ": " + e);
                }
            }
            if (script == null) {
                throw refuse(Refusal.FEE_BELOW_LEDGER_MINIMUM,
                        "no bytes for referenced script " + utxo.getReferenceScriptHash());
            }
            try {
                bytes += script.serializeScriptBody().length;
            } catch (Exception e) {
                throw refuse(Refusal.FEE_BELOW_LEDGER_MINIMUM,
                        "could not serialise referenced script " + utxo.getReferenceScriptHash() + ": " + e);
            }
        }
        return bytes;
    }

    private void validateFinalEvaluation(Transaction tx, Set<Utxo> resolved) {
        if (scriptCostEvaluator == null) {
            return; // Legacy offline sizing rigs cannot submit; production constructor forbids null.
        }
        final Result<List<EvaluationResult>> result;
        try {
            result = scriptCostEvaluator.evaluateTx(tx.serialize(), resolved);
        } catch (Exception e) {
            throw refuse(Refusal.FINAL_EVALUATION_MISMATCH, "final-body evaluation failed: " + e);
        }
        if (result == null || !result.isSuccessful() || result.getValue() == null) {
            throw refuse(Refusal.FINAL_EVALUATION_MISMATCH,
                    "final-body evaluator returned " + (result == null ? "null" : result.getResponse()));
        }
        Map<String, EvaluationResult> fresh = new HashMap<>();
        for (EvaluationResult costing : result.getValue()) {
            String key = redeemerKey(costing.getRedeemerTag().name(), costing.getIndex());
            if (fresh.put(key, costing) != null) {
                throw refuse(Refusal.FINAL_EVALUATION_MISMATCH,
                        "evaluator returned duplicate costing for " + key);
            }
        }
        for (Redeemer declared : tx.getWitnessSet().getRedeemers()) {
            String key = redeemerKey(declared.getTag().name(), declared.getIndex().intValue());
            EvaluationResult costing = fresh.remove(key);
            if (costing == null || costing.getExUnits() == null
                    || costing.getExUnits().getMem().signum() <= 0
                    || costing.getExUnits().getSteps().signum() <= 0
                    || !costing.getExUnits().getMem().equals(declared.getExUnits().getMem())
                    || !costing.getExUnits().getSteps().equals(declared.getExUnits().getSteps())) {
                throw refuse(Refusal.FINAL_EVALUATION_MISMATCH,
                        "declared ex-units do not equal fresh final-body evaluation for " + key);
            }
        }
        if (!fresh.isEmpty()) {
            throw refuse(Refusal.FINAL_EVALUATION_MISMATCH,
                    "evaluator returned costings for absent redeemers " + fresh.keySet());
        }
    }

    private Utxo resolve(TransactionInput input, Refusal reason, String what) {
        try {
            return utxoSupplier.getTxOutput(input.getTransactionId(), input.getIndex())
                    .orElseThrow(() -> refuse(reason, what + " " + ref(input) + " is unresolved"));
        } catch (RefusedException e) {
            throw e;
        } catch (Exception e) {
            throw refuse(reason, what + " " + ref(input) + " could not be resolved: " + e);
        }
    }

    private static Transaction withDummyWitness(Transaction tx) {
        Transaction copy = TransactionUtil.createCopy(tx);
        if (copy.getWitnessSet() == null) {
            copy.setWitnessSet(new TransactionWitnessSet());
        }
        if (copy.getWitnessSet().getVkeyWitnesses() == null) {
            copy.getWitnessSet().setVkeyWitnesses(new ArrayList<>());
        }
        VkeyWitness witness = new VkeyWitness();
        witness.setVkey(new byte[32]);
        witness.setSignature(new byte[64]);
        copy.getWitnessSet().getVkeyWitnesses().add(witness);
        return copy;
    }

    private static BigInteger ceilPercent(BigInteger amount, BigDecimal percent) {
        return new BigDecimal(amount).multiply(percent).divide(BigDecimal.valueOf(100), 0,
                RoundingMode.CEILING).toBigIntegerExact();
    }

    private static BigInteger tierReferenceFee(long bytes, BigDecimal initialPrice) {
        if (bytes == 0 || initialPrice == null) {
            return BigInteger.ZERO;
        }
        long remaining = bytes;
        BigDecimal price = initialPrice;
        BigDecimal total = BigDecimal.ZERO;
        while (remaining > 0) {
            long inTier = Math.min(25_600L, remaining);
            total = total.add(price.multiply(BigDecimal.valueOf(inTier)));
            remaining -= inTier;
            price = price.multiply(new BigDecimal("1.2"));
        }
        return total.setScale(0, RoundingMode.FLOOR).toBigIntegerExact();
    }

    private static String redeemerKey(String tag, int index) {
        return tag.toLowerCase() + "#" + index;
    }

    private static String ref(TransactionInput input) {
        return input.getTransactionId() + "#" + input.getIndex();
    }

    private static boolean pureAda(Utxo utxo) {
        Map<String, BigInteger> units = unitsOf(utxo.getAmount());
        units.entrySet().removeIf(e -> e.getValue().signum() == 0);
        return units.keySet().equals(Set.of("lovelace"))
                && units.get("lovelace").signum() > 0;
    }

    private static boolean samePaymentCredential(String left, String right) {
        try {
            var a = new Address(left).getPaymentCredential().orElse(null);
            var b = new Address(right).getPaymentCredential().orElse(null);
            return a != null && b != null && a.getType() == b.getType()
                    && Arrays.equals(a.getBytes(), b.getBytes());
        } catch (Exception e) {
            return false;
        }
    }

    private static Value valueOf(List<Amount> amounts) {
        Value value = Value.builder().coin(BigInteger.ZERO).multiAssets(new ArrayList<>()).build();
        for (Amount amount : amounts) {
            if ("lovelace".equals(amount.getUnit())) {
                value = value.addCoin(amount.getQuantity());
            } else {
                value = value.add(assetValue(amount.getUnit(), amount.getQuantity()));
            }
        }
        return value;
    }

    private static Value assetValue(String unit, BigInteger quantity) {
        return Value.from(unit.substring(0, 56), "0x" + unit.substring(56), quantity);
    }

    private static Map<String, BigInteger> unitsOf(List<Amount> amounts) {
        Map<String, BigInteger> units = new TreeMap<>();
        for (Amount amount : amounts) {
            units.merge(amount.getUnit(), amount.getQuantity(), BigInteger::add);
        }
        removeZeros(units);
        return units;
    }

    private static Map<String, BigInteger> unitsOf(Value value) {
        return unitsOf(ValueUtil.toAmountList(value));
    }

    private static void addUnits(Map<String, BigInteger> target, Map<String, BigInteger> add) {
        add.forEach((unit, quantity) -> target.merge(unit, quantity, BigInteger::add));
    }

    private static void subtractUnits(Map<String, BigInteger> target, Map<String, BigInteger> subtract) {
        subtract.forEach((unit, quantity) -> target.merge(unit, quantity.negate(), BigInteger::add));
    }

    private static void removeZeros(Map<String, BigInteger> units) {
        units.entrySet().removeIf(e -> e.getValue() == null || e.getValue().signum() == 0);
    }

    private static TransactionOutput onlyOutputWithDatum(Transaction tx, String address,
                                                         String datumHex, String what) {
        List<TransactionOutput> matches = tx.getBody().getOutputs().stream()
                .filter(o -> address.equals(o.getAddress()))
                .filter(o -> o.getInlineDatum() != null && datumHexOf(o).equalsIgnoreCase(datumHex))
                .toList();
        if (matches.size() != 1) {
            throw refuse(Refusal.UNEXPECTED_OUTPUT,
                    what + " has " + matches.size() + " outputs carrying its address+datum, expected one");
        }
        return matches.getFirst();
    }

    // ---- the post-assert, which is the real guard --------------------------------------------

    /**
     * Re-derives from the FINISHED body everything the redeemers claim, and refuses on any mismatch.
     * Runs inside the build pipeline so there is no separate call for a future path to forget.
     */
    void assertStructure(Transaction txn, Request request, List<String> withdrawalOrder,
                         long poolWithdrawRedeemerIndex, long lmWithdrawRedeemerIndex) {
        CompoundCandidate c = request.candidate();

        // ⛔ 1. The redeemer positions (g) cites, matched BY SCRIPT PURPOSE. Trap 14: PoolWithdraw
        // and PoolManagerWithdraw are byte-indistinguishable, so a shape test here would be unsafe.
        assertRewardRedeemerAt(txn, poolWithdrawRedeemerIndex, registry.getPoolPolicyId(),
                "poolWithdrawRedeemerIndex");
        assertRewardRedeemerAt(txn, lmWithdrawRedeemerIndex,
                registry.getLenderManagerWithdrawScriptHash(), "lenderManagerWithdrawRedeemerIndex");

        // 2. The pool output is the complete input Value plus Q-F in the principal unit and, for a
        // token principal, only the stable current-protocol min-ADA top-up T.
        TransactionOutput poolOut = outputWithDatum(txn, c.pool().getAddress(), c.pool().getInlineDatum());
        if (poolOut == null) {
            throw refuse(Refusal.POOL_OUTPUT_MISMATCH,
                    "no output at the pool address carries the pool's original datum bytes");
        }
        PlutusData poolDatum = echo(c.pool().getInlineDatum(),
                Refusal.POOL_DATUM_NOT_BYTE_IDENTICAL, "pool");
        Map<String, BigInteger> expectedPool = unitsOf(poolOutputValue(request, poolDatum));
        Map<String, BigInteger> actualPool = unitsOf(poolOut.getValue());
        if (!actualPool.equals(expectedPool)) {
            throw refuse(Refusal.POOL_OUTPUT_MISMATCH,
                    "pool output is %s, expected %s (input plus Q-F in %s and only required top-up)"
                            .formatted(actualPool, expectedPool, principalUnit(request)));
        }

        // 3. The two equals_data echoes are byte-identical, checked on the built body rather than on
        //    what we intended to emit.
        assertEcho(txn, request.bondUtxo().getAddress(), c.bond().inlineDatum(),
                request.bondUtxo().getAmount(), "lender bond");
        assertEcho(txn, c.poolManager().getAddress(), c.poolManager().getInlineDatum(),
                c.poolManager().getAmount(), "pool manager");

        // 4. Full per-unit wallet accounting is performed by validateFinalTransaction on the
        // deserialised finished body, including every balancer-added input and credential-owned
        // output. Keeping it there makes the exact returned bytes, not this mutable object, the gate.

        // 5. CCL trap 16: a negative collateral return is not a phase-1 rejection, it is an
        //    unparseable transaction — refused before any validation runs, and hard to diagnose.
        var collateralReturn = txn.getBody().getCollateralReturn();
        if (collateralReturn != null && collateralReturn.getValue().getCoin().signum() < 0) {
            throw refuse(Refusal.COLLATERAL_RETURN_NEGATIVE,
                    "collateral return is " + collateralReturn.getValue().getCoin()
                            + "; the pure-ada collateral capacity cannot cover the required amount");
        }

        // 6. FAB-152: the bot gets ONE datum-free output back. Two means the fold did not run, and the
        //    wallet would come out ADA_FRAGMENTED and pay a rebalance — refused loudly instead.
        long datumFreeAtChange = txn.getBody().getOutputs().stream()
                .filter(o -> request.changeAddress().equals(o.getAddress()))
                .filter(o -> o.getInlineDatum() == null && o.getDatumHash() == null)
                .count();
        if (datumFreeAtChange > 1) {
            throw refuse(Refusal.BOT_OUTPUTS_NOT_FOLDED,
                    datumFreeAtChange + " datum-free outputs at the change address, expected at most one");
        }
    }

    /**
     * FAB-152: folds every output at {@code changeAddress} carrying no datum (inline or hash) and no
     * script reference into the LAST such output, removing the others. Selected by address and
     * content, never by index — the withdrawal dummy's position is cardano-client-lib's (CCL trap 1).
     * One such output or none: nothing to do.
     */
    static void foldBotOutputs(Transaction txn, String changeAddress) {
        List<TransactionOutput> outputs = txn.getBody().getOutputs();
        List<TransactionOutput> bot = outputs.stream()
                .filter(o -> changeAddress.equals(o.getAddress()))
                .filter(o -> o.getInlineDatum() == null && o.getDatumHash() == null && o.getScriptRef() == null)
                .toList();
        if (bot.size() < 2) {
            return;
        }
        TransactionOutput keep = bot.get(bot.size() - 1);
        Value sum = Value.builder().coin(BigInteger.ZERO).multiAssets(new ArrayList<>()).build();
        for (TransactionOutput o : bot) {
            sum = sum.add(o.getValue());
        }
        keep.setValue(sum);
        // By identity: two outputs of equal value are still two outputs.
        outputs.removeIf(o -> o != keep && bot.stream().anyMatch(b -> b == o));
    }

    private void assertRewardRedeemerAt(Transaction txn, long position, String scriptHash, String field) {
        List<String> rewards = rewardScriptHashesInBodyOrder(txn);
        long rewardOffset = position - SCRIPT_SPEND_COUNT;
        if (rewardOffset < 0 || rewardOffset >= rewards.size()) {
            throw refuse(Refusal.REDEEMER_INDEX_MISMATCH,
                    "%s = %d is outside the body's redeemer list (%d spends + %d rewards)"
                            .formatted(field, position, SCRIPT_SPEND_COUNT, rewards.size()));
        }
        String actual = rewards.get((int) rewardOffset);
        if (!scriptHash.equalsIgnoreCase(actual)) {
            throw refuse(Refusal.REDEEMER_INDEX_MISMATCH,
                    "%s = %d points at the withdrawal for %s, not %s".formatted(field, position, actual, scriptHash));
        }
    }

    /** Withdrawal script hashes in the body's own canonical order — the order the ledger indexes. */
    static List<String> rewardScriptHashesInBodyOrder(Transaction txn) {
        var withdrawals = txn.getBody().getWithdrawals();
        if (withdrawals == null) {
            return List.of();
        }
        return withdrawals.stream()
                .sorted(Comparator.comparing(w -> HexUtil.encodeHexString(
                        new Address(w.getRewardAddress()).getBytes())))
                .map(w -> HexUtil.encodeHexString(
                        new Address(w.getRewardAddress()).getDelegationCredentialHash().orElse(new byte[0])))
                .toList();
    }

    private void assertEcho(Transaction txn, String address, String datumHex,
                            List<Amount> amounts, String what) {
        TransactionOutput out = outputWithDatum(txn, address, datumHex);
        if (out == null) {
            throw refuse(Refusal.ECHO_NOT_BYTE_IDENTICAL,
                    what + " echo is absent, or its datum bytes moved");
        }
        Map<String, BigInteger> actual = unitsOf(out.getValue());
        Map<String, BigInteger> expected = unitsOf(amounts);
        if (!actual.equals(expected)) {
            throw refuse(Refusal.ECHO_NOT_BYTE_IDENTICAL,
                    "%s echo is %s, input was %s — equals_data compares the complete Value"
                            .formatted(what, actual, expected));
        }
    }

    // ---- primitives -------------------------------------------------------------------------

    /**
     * Decode and re-encode, refusing unless the bytes are identical. CCL trap 4: cardano-client-lib
     * re-serialises whatever {@code PlutusData} it is handed, and a datum posted with a different
     * (equally legal) bytestring chunking comes back different — which {@code equals_data} rejects
     * on chain after the fee is spent. Caught here for free instead.
     */
    static PlutusData echo(String originalHex, Refusal reason, String what) {
        if (originalHex == null) {
            throw refuse(reason, what + " has no inline datum to echo");
        }
        try {
            PlutusData decoded = ConstrPlutusData.deserialize(
                    CborSerializationUtil.deserialize(HexUtil.decodeHexString(originalHex)));
            if (!originalHex.equalsIgnoreCase(decoded.serializeToHex())) {
                throw refuse(reason, what + " datum re-encodes to different bytes");
            }
            return decoded;
        } catch (RefusedException e) {
            throw e;
        } catch (Exception e) {
            throw refuse(reason, what + " datum could not be decoded: " + e);
        }
    }

    /** The canonical withdrawal order: reward addresses sorted by their raw bytes. */
    List<String> withdrawalOrder() {
        List<String> hashes = new ArrayList<>(List.of(
                registry.getAssetManagerWithdrawScriptHash(),
                registry.getLenderManagerWithdrawScriptHash(),
                registry.getLmCompoundActionScriptHash(),
                registry.getPoolPolicyId(),
                registry.getPoolCompoundActionScriptHash(),
                registry.getPoolManagerPolicyId(),
                registry.getPmCompoundLiquidityScriptHash()));
        hashes.sort(Comparator.comparing(h -> HexUtil.encodeHexString(
                new Address(rewardAddress(h)).getBytes())));
        return List.copyOf(hashes);
    }

    /**
     * A reward redeemer's position in {@code self.redeemers}: every spend redeemer sorts before every
     * reward redeemer, and rewards are ordered by their withdrawal's position.
     */
    static long rewardRedeemerIndex(List<String> withdrawalOrder, String scriptHash) {
        int at = withdrawalOrder.indexOf(scriptHash);
        if (at < 0) {
            throw refuse(Refusal.REDEEMER_INDEX_MISMATCH,
                    scriptHash + " is not among the transaction's withdrawals");
        }
        return SCRIPT_SPEND_COUNT + at;
    }

    /** Whether this validator has a published coordinate, so it must NOT also be attached. */
    private static boolean isReferenced(Request request, PlutusScript script) {
        try {
            return request.referenceScripts().containsKey(HexUtil.encodeHexString(script.getScriptHash()));
        } catch (Exception e) {
            throw refuse(Refusal.REDEEMER_INDEX_MISMATCH, "could not hash a validator: " + e);
        }
    }

    /** The scripts travelling by reference, whose bytes CCL needs in order to price them. */
    private List<PlutusScript> referencedScripts(Request request) {
        List<PlutusScript> out = new ArrayList<>();
        for (PlutusScript script : List.of(registry.getAssetManagerSpendScript(),
                registry.getLenderManagerSpendScript(), registry.getPoolSpendScript(),
                registry.getPoolManagerSpendScript(), registry.getAssetManagerScript(),
                registry.getLenderManagerScript(), registry.getLmCompoundActionScript(),
                registry.getPoolScript(), registry.getPoolCompoundActionScript(),
                registry.getPoolManagerScript(), registry.getPmCompoundLiquidityScript())) {
            if (isReferenced(request, script)) {
                out.add(script);
            }
        }
        return out;
    }

    private String rewardAddress(String scriptHash) {
        return AddressProvider.getRewardAddress(Credential.fromScript(scriptHash), network).getAddress();
    }

    private static TransactionOutput outputWithDatum(Transaction txn, String address, String datumHex) {
        return txn.getBody().getOutputs().stream()
                .filter(o -> address.equals(o.getAddress()))
                .filter(o -> o.getInlineDatum() != null && datumHexOf(o).equalsIgnoreCase(datumHex))
                .findFirst()
                .orElse(null);
    }

    private static String datumHexOf(TransactionOutput out) {
        try {
            return out.getInlineDatum().serializeToHex();
        } catch (Exception e) {
            return "";
        }
    }

    private static List<Amount> plusLovelace(List<Amount> amounts, BigInteger delta) {
        List<Amount> out = new ArrayList<>();
        boolean seen = false;
        for (Amount a : amounts) {
            if ("lovelace".equals(a.getUnit())) {
                out.add(Amount.lovelace(a.getQuantity().add(delta)));
                seen = true;
            } else {
                out.add(a);
            }
        }
        if (!seen) {
            out.add(Amount.lovelace(delta));
        }
        return out;
    }

    private static BigInteger lovelaceOf(List<Amount> amounts) {
        return amounts.stream()
                .filter(a -> "lovelace".equals(a.getUnit()))
                .map(Amount::getQuantity)
                .findFirst()
                .orElse(BigInteger.ZERO);
    }

    private static TransactionInput inputOf(Utxo utxo) {
        return new TransactionInput(utxo.getTxHash(), utxo.getOutputIndex());
    }

    private static List<TransactionInput> canonical(List<TransactionInput> inputs) {
        return inputs.stream()
                .sorted(Comparator.comparing(TransactionInput::getTransactionId)
                        .thenComparingInt(TransactionInput::getIndex))
                .toList();
    }

    private static long indexOf(List<TransactionInput> inputs, TransactionInput target, String what) {
        int at = inputs.indexOf(target);
        if (at < 0) {
            throw refuse(Refusal.REDEEMER_INDEX_MISMATCH, what + " is not among the reference inputs");
        }
        return at;
    }
}
