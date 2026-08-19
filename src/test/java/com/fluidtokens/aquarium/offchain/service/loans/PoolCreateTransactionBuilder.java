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
import java.util.OptionalInt;

/**
 * Builds the <b>pool-creation</b> transaction of a Lending v4 pool: mint one pool NFT under the
 * deployed {@code pool.pool} policy, lock the pool liquidity plus that NFT at the pool spend address
 * under a hand-encoded {@code PoolDatum}, and mint the matching <b>PoolManager NFT</b> under the
 * deployed {@code pool_manager.poolManager} policy into the PoolManager spend address under a
 * {@code PoolManagerDatum}.
 *
 * <h2>The PoolManager is not optional here, whatever the README says (T-024)</h2>
 * {@code pool.ak} never mentions the PoolManager, and FluidTokens' README calls it mandatory only
 * <em>"for automatic compounding"</em>. Both are true and neither makes it skippable in practice: a
 * pool minted without one <b>can never be compounded by anyone</b>, which is why FluidTokens flagged
 * the pools this factory produced. Once the PoolManager <em>is</em> minted, {@code pool_manager.ak}'s
 * own {@code check_mint} is strict about it:
 * <ul>
 *   <li>{@code poolManagerMintedNFTs == poolMintedNFTs} (line 173) — a {@code Pairs} equality over the
 *       two sorted token dicts, so the PoolManager NFT must carry the <b>same asset name</b> at the
 *       <b>same quantity</b>, and a PoolManager mint without a same-transaction pool mint is refused;</li>
 *   <li>the output must sit at {@code Script(poolManagerSpendScriptHash)}, hold exactly one token of
 *       the policy, and carry an inline datum that type-checks as {@code PoolManagerDatum} (lines
 *       121-127). The stale comment at line 166 — <em>"we don't check the destination of the minted
 *       NFTs"</em> — describes code that is no longer there; the code is stronger than its comment;</li>
 *   <li>{@code length(poolInputs) == length(poolManagerBurntNFTs)} — both zero on a creation.</li>
 * </ul>
 *
 * <h2>The PoolManager policy travels in the witness set, not by reference input</h2>
 * Unlike {@code pool.pool}, which has a published preview reference script this builder declares to
 * {@code withReferenceScripts} (see below), the pool-manager family has none. A minting policy may
 * legitimately travel either way, so this transaction simply keeps the witness copy {@code mintAsset}
 * leaves behind — nothing is stripped for it, nothing is read for it, and no reference input is
 * invented for a dependency the mint does not have. It costs ~2.2 KB of transaction size and no
 * reference-script fee. The <em>cancel</em> is where the missing publications bite; see
 * {@link PoolFixtures#poolManagerCancelScriptHashes()}.
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
     * @param poolAssetNameHex       the 29-byte pool NFT name (index prefix + hashed output ref). The
     *                               PoolManager NFT is minted under the <em>same</em> name; the
     *                               validator requires it, so there is no second name to pass
     * @param poolDatum              the {@code PoolDatum} the pool output carries inline
     * @param poolLovelace           the pool liquidity leg of the pool output's value
     * @param poolManagerAddress     the enterprise address of {@code poolManagerSpendScriptHash}
     * @param poolManagerDatum       the {@code PoolManagerDatum} the PoolManager output carries inline
     * @param poolManagerLovelace    the PoolManager output's lovelace leg
     */
    public record Request(Utxo seedUtxo,
                          Utxo configUtxo,
                          Utxo poolPolicyRefScriptUtxo,
                          String funderAddress,
                          String poolAddress,
                          String poolAssetNameHex,
                          PlutusData poolDatum,
                          long poolLovelace,
                          String poolManagerAddress,
                          PlutusData poolManagerDatum,
                          long poolManagerLovelace) {
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
        return buildWith(request, Overrides.none(), mutation);
    }

    /**
     * The adversarial seam: every field {@link PoolCreateDryEvalTest} needs to corrupt in order to hand
     * the machine a transaction this builder would never emit on its own. A null component means "use
     * the {@link Request}'s honest value", so {@link Overrides#none()} builds exactly what
     * {@link #build} builds.
     *
     * <h2>Why the PoolManager legs need their own overrides</h2>
     * Three of {@code pool_manager.ak}'s {@code check_mint} conjuncts can only be broken at assembly
     * time, not by byte-surgery on a finished body: minting the PoolManager NFT under a name that is not
     * the pool NFT's ({@code poolManagerMintedNFTs == poolMintedNFTs}), paying it somewhere other than
     * {@code Script(poolManagerSpendScriptHash)} ({@code correctAddress}), and giving it a datum that is
     * not a {@code PoolManagerDatum} ({@code expect PoolManagerDatum { .. } = outputDatum}). Surgery
     * would work for the first two but would leave the value unbalanced; rebuilding keeps every other
     * property of the transaction intact so the named conjunct is the only thing wrong.
     *
     * @param inputRef                 the mint redeemer's {@code inputRef}
     * @param poolAssetNameHex         the pool NFT's asset name
     * @param poolAddress              where the pool output is paid
     * @param poolManagerAssetNameHex  the PoolManager NFT's asset name (honestly: the pool NFT's)
     * @param poolManagerAddress       where the PoolManager output is paid
     * @param poolManagerDatum         the PoolManager output's inline datum
     */
    record Overrides(TransactionInput inputRef,
                     String poolAssetNameHex,
                     String poolAddress,
                     String poolManagerAssetNameHex,
                     String poolManagerAddress,
                     PlutusData poolManagerDatum) {

        static Overrides none() {
            return new Overrides(null, null, null, null, null, null);
        }

        static Overrides pool(TransactionInput inputRef, String assetNameHex, String poolAddress) {
            return new Overrides(inputRef, assetNameHex, poolAddress, null, null, null);
        }

        static Overrides poolManagerAssetName(String assetNameHex) {
            return new Overrides(null, null, null, assetNameHex, null, null);
        }

        static Overrides poolManagerAddress(String address) {
            return new Overrides(null, null, null, null, address, null);
        }

        static Overrides poolManagerDatum(PlutusData datum) {
            return new Overrides(null, null, null, null, null, datum);
        }
    }

    /**
     * As {@link #build}, but with the {@link Overrides} applied — see that record for what each one
     * breaks and why it cannot be done by byte-surgery instead.
     */
    Transaction buildWith(Request request, Overrides overrides) {
        return buildWith(request, overrides, Mutation.none());
    }

    private Transaction buildWith(Request request, Overrides overrides, Mutation mutation) {
        long configRefInputIndex = configRefIndexAmong(request);
        ScriptTx tx = assemble(request, configRefInputIndex, overrides);
        Transaction built = complete(request, tx, mutation);

        int actual = configRefIndexOf(built, request.configUtxo());
        if (actual != configRefInputIndex) {
            throw new IllegalStateException(
                    "the mint redeemer claims configRefInputIndex " + configRefInputIndex
                            + " but the config reference input landed at " + actual
                            + " in the finished body");
        }
        return assertPoolWithdrawRedeemerIsUnread(built);
    }

    /**
     * The evidence behind {@link #UNREAD_POOL_WITHDRAW_INDEX}, read off the FINISHED body: a pool
     * creation carries no {@code pool.pool} withdraw redeemer at all, which is why the PoolManager mint
     * redeemer's {@code poolWithdrawRedeemerIndex} has nothing to point at and — per
     * {@code pool_manager.ak}'s {@code if length(poolManagerBurntNFTs) > 0} guard — is never evaluated.
     * <p>
     * <b>Return-consuming on purpose.</b> It hands back the transaction it checked, so the only way to
     * drop this call is to stop returning a transaction, which does not compile. That is the same reason
     * the value it defends is proven <em>green</em> rather than red by
     * {@link PoolCreateDryEvalTest}: an index the validator never reads cannot be shown load-bearing by
     * a refusal, only inert by an acceptance.
     * <p>
     * <b>Package-private so its refusing branch can be pinned.</b> On every transaction this builder
     * emits the branch is unreachable — that is the whole claim — so neutering the body would change
     * nothing observable and the guard would be unproven. {@link PoolCreateDryEvalTest} therefore hands
     * it, directly, a body that <em>does</em> carry a {@code pool.pool} withdraw redeemer and requires
     * the refusal.
     */
    Transaction assertPoolWithdrawRedeemerIsUnread(Transaction built) {
        OptionalInt found = PoolCancelTransactionBuilder
                .poolWithdrawRedeemerIndexIn(built, registry.getPoolPolicyId());
        if (found.isPresent()) {
            throw new IllegalStateException(
                    "the pool-creation transaction carries a pool.pool withdraw redeemer at "
                            + "self.redeemers[" + found.getAsInt() + "], so the PoolManager mint "
                            + "redeemer's poolWithdrawRedeemerIndex is no longer unread and "
                            + UNREAD_POOL_WITHDRAW_INDEX + " can no longer be assumed safe");
        }
        return built;
    }

    /**
     * The value the PoolManager mint redeemer's {@code poolWithdrawRedeemerIndex} carries on the create
     * path. It is <b>never read</b> there — {@code check_mint} consults it only inside
     * {@code if length(poolManagerBurntNFTs) > 0}, and a creation burns nothing, so neither the
     * {@code safe_list_at} nor its {@code expect index >= 0} is evaluated. Zero rather than a sentinel
     * such as {@code -1} because an out-of-range index would abort the moment the guard ever did open,
     * and because {@link #assertPoolWithdrawRedeemerIsUnread} — not this constant — is what keeps the
     * claim true.
     */
    private static final long UNREAD_POOL_WITHDRAW_INDEX = 0L;

    // ---- assembly ---------------------------------------------------------------------------------

    private ScriptTx assemble(Request request, long configRefInputIndex, Overrides overrides) {
        TransactionInput inputRef = overrides.inputRef() != null ? overrides.inputRef()
                : new TransactionInput(request.seedUtxo().getTxHash(), request.seedUtxo().getOutputIndex());
        String assetNameHex = orElse(overrides.poolAssetNameHex(), request.poolAssetNameHex());
        String poolAddress = orElse(overrides.poolAddress(), request.poolAddress());
        // Honestly the pool NFT's own name: pool_manager.ak makes them equal, so the default is the
        // pool's name and not a second field of the request.
        String poolManagerAssetNameHex = orElse(overrides.poolManagerAssetNameHex(), assetNameHex);
        String poolManagerAddress = orElse(overrides.poolManagerAddress(), request.poolManagerAddress());
        PlutusData poolManagerDatum = overrides.poolManagerDatum() != null
                ? overrides.poolManagerDatum() : request.poolManagerDatum();
        return assemble(request, configRefInputIndex, inputRef, assetNameHex, poolAddress,
                poolManagerAssetNameHex, poolManagerAddress, poolManagerDatum);
    }

    private static String orElse(String override, String honest) {
        return override != null ? override : honest;
    }

    private ScriptTx assemble(Request request, long configRefInputIndex, TransactionInput inputRef,
                              String assetNameHex, String poolAddress, String poolManagerAssetNameHex,
                              String poolManagerAddress, PlutusData poolManagerDatum) {
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

        // The PoolManager NFT: same asset name, same quantity, minted in the same transaction — all
        // three forced by pool_manager.ak:173's `poolManagerMintedNFTs == poolMintedNFTs`, a Pairs
        // equality over the two sorted token dicts. poolWithdrawRedeemerIndex is encoded 0 and is
        // UNREAD here: check_mint only consults it inside `if length(poolManagerBurntNFTs) > 0`, and a
        // creation burns nothing. assertPoolWithdrawRedeemerIsUnread re-checks that from the finished
        // body.
        tx.mintAsset(registry.getPoolManagerScript(),
                List.of(new Asset("0x" + poolManagerAssetNameHex, BigInteger.ONE)),
                PoolTxEncoder.poolManagerMintRedeemer(configRefInputIndex, UNREAD_POOL_WITHDRAW_INDEX));

        List<Amount> poolManagerValue = List.of(
                Amount.lovelace(BigInteger.valueOf(request.poolManagerLovelace())),
                Amount.asset(registry.getPoolManagerPolicyId() + poolManagerAssetNameHex, BigInteger.ONE));
        tx.payToContract(poolManagerAddress, poolManagerValue, poolManagerDatum);

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
