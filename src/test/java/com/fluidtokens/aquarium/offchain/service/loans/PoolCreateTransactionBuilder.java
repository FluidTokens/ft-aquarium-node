package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import com.fluidtokens.aquarium.offchain.service.TransactionInputComparator;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Builds the <b>pool-creation</b> transaction of a Lending v4 pool: mint one pool NFT under the
 * deployed {@code pool.pool} policy, and lock the pool liquidity plus that NFT at the pool spend
 * address under a hand-encoded {@code PoolDatum}.
 *
 * <h2>Structurally incapable of submitting</h2>
 * The {@link QuickTxBuilder} below is constructed with a <b>null</b> {@code TransactionProcessor} —
 * the only thing in cardano-client-lib that can put a transaction on a network — exactly as
 * {@link RequestMintTransactionBuilder} is. A builder that never has a processor cannot submit,
 * whatever a caller asks of it.
 *
 * <h2>Ex-units: optional, and null means exactly what it always meant</h2>
 * The {@code TransactionEvaluator} is a <b>constructor option</b>. Left null — the three-argument
 * constructor, which every offline test uses — nothing is wired, cardano-client-lib's
 * {@code ScriptCostEvaluators.evaluateScriptCost()} throws "Transaction evaluator is not set", the
 * throw is swallowed ({@code ignoreScriptCostEvaluationError} defaults to true) and the redeemer keeps
 * {@code ScriptTx}'s placeholder {@code mem 10000 / steps 10000} with the understated fee that follows
 * from it: fine for an offline rig, not fine for submission. Handed a real evaluator, the redeemer
 * carries the budget that evaluator measured, and {@code ignoreScriptCostEvaluationError(false)} is set
 * so an evaluation failure surfaces as a {@code TxBuildException} instead of being swallowed into
 * placeholders again — the exact silence that let understated budgets hide.
 *
 * <h2>Consequence B — the datum is not validated at mint</h2>
 * {@code pool.pool}'s {@code mint} handler ({@code validators/pool.ak:65-124}, deployed pin
 * {@code bbe9c1a}) checks only that the config NFT is present on the reference input at
 * {@code configRefInputIndex}, that {@code redeemer.inputRef} is spent, and that each minted pool
 * token is named {@code index ‖ blake2b_224(serialise_data(inputRef))} and sits alone in the
 * correspondingly indexed output at the pool spend credential. It never reads the {@code PoolDatum}.
 *
 * <h2>Consequence C — no validity interval</h2>
 * {@code check_mint} reads no validity range, no signature and no withdrawal, so this transaction is
 * built with none.
 *
 * <h2>Two reference inputs, so the config index is re-derived</h2>
 * Unlike the request mint's single reference input, this transaction reads two — the config and the
 * published pool-policy reference script — so {@code configRefInputIndex} is <b>not</b> a hardcoded
 * zero. It is computed from the canonically sorted reference-input set the finished body carries and
 * asserted, in the spirit of {@link RequestMintTransactionBuilder}.
 *
 * <h2>The reference-script wiring, and the two ledger rules it satisfies</h2>
 * This builder reads the published {@code pool.pool} reference script <em>and</em> mints under the same
 * policy, and {@code mintAsset} leaves a witness copy of that policy behind. Conway refuses a script
 * that travels both ways, and it charges for the reference script's bytes. Both are handled by the same
 * pair of calls {@link PoolBorrowTransactionBuilder} and {@link PoolCancelTransactionBuilder} already
 * make — {@code withReferenceScripts(pool.pool)} and {@code removeDuplicateScriptWitnesses(true)}:
 * <ul>
 *   <li>the witness copy is stripped after balancing, so the finished witness set carries no Plutus
 *       script and the ledger's {@code ExtraneousScriptWitnessesUTXOW} rule is satisfied;</li>
 *   <li>declaring the script populates cardano-client-lib's {@code TxBuilderContext.refScripts}, which
 *       is the <b>only</b> thing that makes its {@code FeeCalculators} charge the Conway reference-script
 *       fee. Without it — an empty {@code refScripts} map beside a {@code ScriptSupplier} that resolves
 *       nothing — that helper computes a reference-script size of zero and charges zero, silently, and
 *       the ledger answers {@code FeeTooSmallUTxO}. The fee is still computed by cardano-client-lib;
 *       this builder does no fee arithmetic of its own.</li>
 * </ul>
 * Unlike the ex-units evaluator below, this wiring is <b>unconditional</b>: a transaction carrying its
 * own policy both ways is refused by the ledger whether or not an evaluator was wired, so there is no
 * path on which this builder may emit that shape. The only way back to it is the {@link Mutation} knob,
 * which exists so {@link LoanFactoryDryEvalTest} can prove the gate that refuses it.
 */
public final class PoolCreateTransactionBuilder {

    private final LoansContractRegistry registry;
    private final UtxoSupplier utxoSupplier;
    private final ProtocolParamsSupplier protocolParamsSupplier;

    /** Null for the offline rig; a real evaluator when the transaction has to be submittable. */
    private final TransactionEvaluator txEvaluator;

    /** No evaluator: placeholder ex-units, understated fee, byte-for-byte the offline shape. */
    public PoolCreateTransactionBuilder(LoansContractRegistry registry,
                                        UtxoSupplier utxoSupplier,
                                        ProtocolParamsSupplier protocolParamsSupplier) {
        this(registry, utxoSupplier, protocolParamsSupplier, null);
    }

    public PoolCreateTransactionBuilder(LoansContractRegistry registry,
                                        UtxoSupplier utxoSupplier,
                                        ProtocolParamsSupplier protocolParamsSupplier,
                                        TransactionEvaluator txEvaluator) {
        this.registry = registry;
        this.utxoSupplier = utxoSupplier;
        this.protocolParamsSupplier = protocolParamsSupplier;
        this.txEvaluator = txEvaluator;
    }

    /**
     * Everything the pool-creation transaction needs.
     *
     * @param seedUtxo               the UTxO the mint redeemer names as {@code inputRef}; also the
     *                               source of the pool liquidity and the fee
     * @param configUtxo             the config reference input, read at {@code configRefInputIndex}
     * @param poolPolicyRefScriptUtxo the published pool-policy reference-script UTxO (a second
     *                               reference input)
     * @param funderAddress          fee payer, collateral payer and change address
     * @param poolAddress            the enterprise address of {@code poolSpendScriptHash}
     * @param poolAssetNameHex       the 29-byte pool NFT name (index prefix + hashed output ref)
     * @param poolDatum              the {@code PoolDatum} the pool output carries inline
     * @param poolLovelace           the pool liquidity leg of the pool output's value
     */
    public record Request(Utxo seedUtxo,
                          Utxo configUtxo,
                          Utxo poolPolicyRefScriptUtxo,
                          String funderAddress,
                          String poolAddress,
                          String poolAssetNameHex,
                          PlutusData poolDatum,
                          long poolLovelace) {
    }

    /**
     * The one reference-script knob {@link LoanFactoryDryEvalTest} turns, to reproduce the exact shape
     * the preview ledger refused on 2026-08-18: the pool policy in the witness set <em>and</em> on a
     * reference input, with no reference-script fee paid for it. Omitting the wiring is what the builder
     * did before this defect was fixed, so a mutated build is that older builder, byte for byte. Every
     * field of the default instance is inert, so {@link #build} and a default-mutated build are the same
     * transaction.
     *
     * @param omitReferenceScriptWiring drop {@code withReferenceScripts} + {@code
     *                                  removeDuplicateScriptWitnesses} from the submittable path
     */
    record Mutation(boolean omitReferenceScriptWiring) {

        static Mutation none() {
            return new Mutation(false);
        }
    }

    /**
     * Assembles and completes the pool-creation transaction.
     * <p>
     * The pool output's value is exactly {@code {poolLovelace, 1 pool NFT}}. The mint redeemer's
     * {@code configRefInputIndex} is re-derived from the finished body's canonically sorted reference
     * inputs and asserted against the value the redeemer actually claimed.
     */
    public Transaction build(Request request) {
        return build(request, Mutation.none());
    }

    /**
     * As {@link #build}, but with the reference-script knob turned — see {@link Mutation}. The
     * post-build config-index assertion still runs: the mutation changes what travels where, not any
     * index.
     */
    Transaction build(Request request, Mutation mutation) {
        return buildWith(request,
                new TransactionInput(request.seedUtxo().getTxHash(), request.seedUtxo().getOutputIndex()),
                request.poolAssetNameHex(),
                request.poolAddress(),
                mutation);
    }

    /**
     * As {@link #build}, but with the mint redeemer's {@code inputRef}, the NFT's asset name and the
     * pool output's address overridden — the seam the adversarial mutations of
     * {@link PoolCreateDryEvalTest} use to hand the machine a transaction this builder would never
     * emit on its own.
     */
    Transaction buildWith(Request request, TransactionInput inputRefOverride,
                          String assetNameOverride, String poolAddressOverride) {
        return buildWith(request, inputRefOverride, assetNameOverride, poolAddressOverride,
                Mutation.none());
    }

    private Transaction buildWith(Request request, TransactionInput inputRefOverride,
                                  String assetNameOverride, String poolAddressOverride,
                                  Mutation mutation) {
        long configRefInputIndex = configRefIndexAmong(request);
        ScriptTx tx = assemble(request, configRefInputIndex, inputRefOverride, assetNameOverride,
                poolAddressOverride);
        Transaction built = complete(request, tx, mutation);

        int actual = configRefIndexOf(built, request.configUtxo());
        if (actual != configRefInputIndex) {
            throw new IllegalStateException(
                    "the mint redeemer claims configRefInputIndex " + configRefInputIndex
                            + " but the config reference input landed at " + actual
                            + " in the finished body");
        }
        return built;
    }

    // ---- assembly ---------------------------------------------------------------------------------

    private ScriptTx assemble(Request request, long configRefInputIndex, TransactionInput inputRef,
                              String assetNameHex, String poolAddress) {
        ScriptTx tx = new ScriptTx();

        // The seed, by name: coin selection must not be free to leave it out.
        tx.collectFrom(request.seedUtxo());

        // Minted without a receiver argument, for the same reason RequestMintTransactionBuilder does:
        // the mintAsset(.., receiver, datum) overload re-derives the amount through
        // Amount.asset(policyId, asset.getName(), value), and this repo has been bitten by that name
        // round-trip (see LoanFixtures.unit()). The output is paid explicitly below instead.
        tx.mintAsset(registry.getPoolScript(),
                List.of(new Asset("0x" + assetNameHex, BigInteger.ONE)),
                PoolTxEncoder.poolMintRedeemer(configRefInputIndex, inputRef));

        List<Amount> poolValue = List.of(
                Amount.lovelace(BigInteger.valueOf(request.poolLovelace())),
                Amount.asset(registry.getPoolPolicyId() + assetNameHex, BigInteger.ONE));
        tx.payToContract(poolAddress, poolValue, request.poolDatum());

        tx.readFrom(new TransactionInput(request.configUtxo().getTxHash(),
                request.configUtxo().getOutputIndex()));
        tx.readFrom(new TransactionInput(request.poolPolicyRefScriptUtxo().getTxHash(),
                request.poolPolicyRefScriptUtxo().getOutputIndex()));

        return tx.withChangeAddress(request.funderAddress());
    }

    private Transaction complete(Request request, ScriptTx tx, Mutation mutation) {
        try {
            // The third argument is the TransactionProcessor and it stays null; see the class javadoc.
            QuickTxBuilder.TxContext context = new QuickTxBuilder(utxoSupplier, protocolParamsSupplier, null)
                    .compose(tx)
                    .feePayer(request.funderAddress())
                    .collateralPayer(request.funderAddress())
                    // A script supplier that resolves nothing: there is no remote source for anything
                    // here. On the submittable path the pool policy reaches the context through
                    // withReferenceScripts instead — see withReferenceScriptWiring.
                    .withScriptSupplier(scriptHash -> Optional.empty())
                    .mergeOutputs(false);
            // No validFrom / validTo — consequence C.
            return withExUnitsEvaluation(withReferenceScriptWiring(context, mutation)).build();
        } catch (Exception e) {
            throw new IllegalStateException("cannot build the pool-creation transaction", e);
        }
    }

    /**
     * Wires the evaluator when there is one, and leaves the context untouched when there is not — so the
     * no-evaluator build is the same call sequence it has always been. {@code
     * ignoreScriptCostEvaluationError(false)} is set with the evaluator and only with it: a build that
     * asked for real budgets must fail loudly rather than fall back to placeholders.
     */
    private QuickTxBuilder.TxContext withExUnitsEvaluation(QuickTxBuilder.TxContext context) {
        if (txEvaluator == null) {
            return context;
        }
        return context.withTxEvaluator(txEvaluator).ignoreScriptCostEvaluationError(false);
    }

    /**
     * Declares the {@code pool.pool} reference script and asks for the duplicate witness copy to be
     * stripped — the proven idiom of {@link PoolBorrowTransactionBuilder} and
     * {@link PoolCancelTransactionBuilder}, and the whole of the ledger fix (see the class javadoc).
     * cardano-client-lib does the rest: it charges the Conway reference-script fee off the bytes
     * declared here, and drops the witness copy after balancing.
     * <p>
     * <b>Unconditional</b>, unlike the ex-units evaluator above. The two were briefly gated together, so
     * that the no-evaluator transaction stayed byte-identical to its pin — which meant the pin
     * immortalised a transaction the ledger refuses. A builder does not get to emit a known-broken shape
     * because a test pinned it; the pin was re-taken instead ({@link LoanFactoryDryEvalTest}). The
     * {@link Mutation} knob is the only way back to the pre-fix shape, and it exists purely to prove the
     * gate that refuses it.
     */
    private QuickTxBuilder.TxContext withReferenceScriptWiring(QuickTxBuilder.TxContext context,
                                                               Mutation mutation) {
        if (mutation.omitReferenceScriptWiring()) {
            return context;
        }
        return context.withReferenceScripts(registry.getPoolScript())
                .removeDuplicateScriptWitnesses(true);
    }

    /** Where the config would land in the canonically sorted set of the two reference inputs. */
    private static long configRefIndexAmong(Request request) {
        List<TransactionInput> refInputs = new ArrayList<>(List.of(
                new TransactionInput(request.configUtxo().getTxHash(), request.configUtxo().getOutputIndex()),
                new TransactionInput(request.poolPolicyRefScriptUtxo().getTxHash(),
                        request.poolPolicyRefScriptUtxo().getOutputIndex())));
        refInputs.sort(new TransactionInputComparator());
        return refInputs.indexOf(
                new TransactionInput(request.configUtxo().getTxHash(), request.configUtxo().getOutputIndex()));
    }

    /** Where the config landed in the canonically sorted reference-input set of the finished body. */
    private static int configRefIndexOf(Transaction tx, Utxo configUtxo) {
        List<TransactionInput> refInputs = new ArrayList<>(tx.getBody().getReferenceInputs());
        refInputs.sort(new TransactionInputComparator());
        int index = refInputs.indexOf(
                new TransactionInput(configUtxo.getTxHash(), configUtxo.getOutputIndex()));
        if (index < 0) {
            throw new IllegalStateException("the config reference input is missing from the body");
        }
        return index;
    }
}
