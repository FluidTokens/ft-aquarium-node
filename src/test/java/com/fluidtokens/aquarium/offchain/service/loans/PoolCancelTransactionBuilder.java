package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.model.Utxo;
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
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import com.fluidtokens.aquarium.offchain.service.TransactionInputComparator;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Builds the <b>pool-cancel</b> transaction of a Lending v4 pool: spend the pool UTxO, burn the pool
 * NFT, and recover the pool's unlent lovelace to the lender — in the layout the deployed
 * {@code pool_cancel_action}, {@code pool.pool} (both its {@code withdraw} and its {@code mint}
 * handlers) and {@code general_spend} validators accept under the real PlutusV3 machine. For
 * <b>one</b> pool input only; multi-pool batching is out of scope.
 *
 * <h2>Why this exists before any pool is funded</h2>
 * {@code pool_cancel_action} is the only way to recover the unlent ada from a pool this factory
 * creates. <b>Never lock value you have no code to unlock:</b> building and dry-evaluating the escape
 * hatch first ({@link PoolCancelDryEvalTest}) makes pool origination reversible before it is real.
 *
 * <h2>Eight script purposes, six scripts (from source at {@code ff005fb}) — T-024</h2>
 * {@code pool.pool} has no {@code spend} handler ({@code validators/pool.ak}); the pool UTxO sits at a
 * {@code general_spend} address whose spend defers to a withdrawal of {@code pool.pool} itself, whose
 * {@code Cancel} action in turn requires a withdrawal of {@code pool_cancel_action} — the validator
 * that does the real work. The PoolManager repeats that structure one level over. So the transaction
 * carries:
 * <ol>
 *   <li><b>Mint</b> (burn, −1) under the {@code pool.pool} policy — {@code check_mint}
 *       ({@code validators/pool.ak}) filters {@code quantity > 0}, so a pure burn leaves
 *       {@code mintedTokens} empty and it reduces to {@code isInputRefSpent}: the mint redeemer's
 *       {@code inputRef} must be a spent input. This builder names the pool UTxO's own reference,
 *       spent by construction;</li>
 *   <li><b>Mint</b> (burn, −1) under the {@code pool_manager.poolManager} policy, same asset name.
 *       Its {@code check_mint} is <em>not</em> permissive about burns the way {@code pool.pool} is:
 *       {@code poolManagerBurntNFTs == poolBurntNFTs} (name and quantity),
 *       {@code length(poolInputs) == length(poolManagerBurntNFTs)}, and the pool's own withdraw
 *       redeemer — found at {@code redeemer.poolWithdrawRedeemerIndex} — must be a {@code Cancel};</li>
 *   <li><b>Spend</b> — {@code general_spend} over the pool UTxO. Its redeemer is ignored
 *       ({@code _redeemer: Data}); with the pool's inline datum present its only check is that some
 *       withdrawal sits at {@code Script(pool.pool hash)} ({@code general_spend.ak} else-branch);</li>
 *   <li><b>Spend</b> — {@code general_spend} over the PoolManager UTxO, deferring the same way to a
 *       withdrawal at {@code Script(pool_manager.poolManager hash)};</li>
 *   <li><b>Withdraw</b> (reward, amount 0) at {@code pool.pool} — {@code PoolWithdrawRedeemer{
 *       configRefInputIndex, Cancel}}. Its {@code Cancel} branch requires a withdrawal at
 *       {@code poolCancelActionScriptHash} (config index 22);</li>
 *   <li><b>Withdraw</b> (reward, amount 0) at {@code pool_cancel_action} —
 *       {@code PoolCancelActionWithdrawRedeemer{configRefInputIndex, [CancelData{poolId}]}};</li>
 *   <li><b>Withdraw</b> (reward, amount 0) at {@code pool_manager.poolManager} —
 *       {@code PoolManagerWithdrawRedeemer{configRefInputIndex, CancelPoolManager}}, routing to
 *       {@code pm_cancel_pool_manager};</li>
 *   <li><b>Withdraw</b> (reward, amount 0) at {@code pool_manager/pm_cancel_pool_manager} —
 *       {@code CancelPoolManagerActionWithdrawRedeemer{configRefInputIndex, poolWithdrawRedeemerIndex,
 *       [poolManagerNFTAssetName]}}.</li>
 * </ol>
 *
 * <h2>Why the PoolManager legs are mandatory, not an addition</h2>
 * Two independent reasons, either of which alone would force them:
 * <ul>
 *   <li><b>The orphan lock.</b> {@code pool_cancel_action} constrains no mint but its own, so a pool
 *       <em>can</em> be cancelled without burning its PoolManager — and {@code pm_cancel_pool_manager}
 *       then requires a live pool holding the matching NFT, which no longer exists. The PoolManager
 *       becomes permanently unspendable and its ~2 ADA is stranded. Burning it in the same transaction
 *       is the only way out;</li>
 *   <li><b>{@code lenderAuth} now delegates.</b> The pool datum's {@code lenderAuth} is
 *       {@code CardanoWithdrawScript(poolManagerPolicyId)} (FluidTokens' convention, README:60), so
 *       {@code pool_cancel_action}'s authorisation conjunct is
 *       {@code pairs.has_key(withdrawals, Script(poolManagerPolicyId))}. Leave the PoolManager legs out
 *       and the <em>pool</em> cancel is refused outright — proven by
 *       {@link PoolCancelDryEvalTest}, and the reason the orphan cannot be created through this builder
 *       at all.</li>
 * </ul>
 *
 * <h2>The pool-manager reference scripts are NOT published on preview</h2>
 * Three of the six validators — the PoolManager {@code general_spend}, {@code pool_manager.poolManager}
 * and {@code pm_cancel_pool_manager} — have no published preview reference-script UTxO. The offline rig
 * supplies synthesised coordinates ({@link PoolFixtures#SYNTHETIC_POOL_MANAGER_REFERENCE_SCRIPTS}),
 * which is enough to prove the <b>shape</b> against the real validators and proves nothing about
 * <b>submittability</b>. {@code LoanFactory} refuses to create a PoolManager-bearing pool until they are
 * published, for the orphan-lock reason above.
 *
 * <h2>{@code pool_cancel_action} constrains NO output — the CCL absolute-output-0 seam does not apply</h2>
 * The withdraw handler's whole body is {@code utils.indexed_all(poolInputs, fn(index, input) ...)}
 * whose predicate is a three-conjunct {@code and}: the pool NFT named by the {@code CancelData}
 * (a) is held on the input ({@code quantity_of(input.output.value, poolPolicyId, poolId) == 1}),
 * (b) is burnt ({@code quantity_of(self.mint, poolPolicyId, poolId) == -1}), and
 * (c) the lender authorised the action ({@code authorize_action(create_auth(datum.lenderAuth, ..))}).
 * <b>Not one conjunct reads an output</b> — not by address, not by datum, not by value or position —
 * exactly as the design recorded and {@code request.ak}'s {@code check_cancel} does. Nor do
 * {@code pool.pool}'s two handlers or {@code general_spend} read any output here. So no validator reads
 * an absolute output position, and the 1-ada withdrawal dummy cardano-client-lib prepends is harmless:
 * this builder installs <b>no</b> {@code preBalanceTx} seam, and {@link #assertStructure} makes no
 * output-position claim. The recovered pool lovelace flows to the change (lender) address, and — like
 * {@code check_cancel} — the validators would accept a Cancel that sends it anywhere; that the lender
 * gets it is a property of this builder's {@code withChangeAddress}, arbitrated by
 * {@link PoolCancelDryEvalTest}'s change-address assertion, not by any script.
 *
 * <h2>Two authorisations, at two different levels — this moved in T-024</h2>
 * It used to be one: {@link PoolFixtures#lenderAuth()} was {@code CardanoSignature(botKeyHash)}, so
 * {@code pool_cancel_action}'s authorisation conjunct reduced to
 * {@code list.has(extra_signatories, botKeyHash)} and a required signer was the whole story. Now:
 * <ul>
 *   <li>the <b>pool</b> is authorised by a <em>withdrawal</em> — {@code lenderAuth} is
 *       {@code CardanoWithdrawScript(poolManagerPolicyId)}, so {@code create_auth} yields
 *       {@code CardanoWithdrawScriptAuth} and {@code authorize_action} is
 *       {@code pairs.has_key(withdrawals, Script(poolManagerPolicyId))}
 *       ({@code lib/fluidtokens/authorizer.ak}), satisfied by leg 7 above;</li>
 *   <li>the <b>PoolManager</b> is authorised by the signer — {@code PoolManagerDatum.poolOwnerAuth} is
 *       {@code CardanoSignature(botKeyHash)}, read by {@code pm_cancel_pool_manager}, and
 *       {@code extra_signatories} is the body's {@code required_signers} field (key 14). So this builder
 *       still declares the lender key hash with {@code withRequiredSigners} — no key and no signature
 *       are involved — but it now satisfies a different validator than it used to.</li>
 * </ul>
 *
 * <h2>Every index is derived from the finished body</h2>
 * Two indexes appear in redeemers here. {@code configRefInputIndex} — shared by the four withdraws and
 * the two mints, all resolving the same reference-input list — is deterministic (the config's position
 * among the canonically sorted reference inputs), so no layout probe is needed, but
 * {@link #assertStructure} re-derives it from the FINISHED body and refuses on any mismatch.
 * {@code poolWithdrawRedeemerIndex} is <b>not</b> deterministic before the build: it indexes
 * {@code self.redeemers}, whose size depends on coin selection, so it is probed, rebuilt and asserted
 * (see {@link #build}). It appears <em>twice</em> — once in the PoolManager mint redeemer, once in the
 * {@code pm_cancel_pool_manager} withdraw redeemer — and the two are derived independently rather than
 * copied from one another, so a single mistake cannot present itself as two agreeing witnesses. The
 * {@code CancelData.poolId}, the {@code poolManagerNFTAssetNames} entry and the mint's {@code inputRef}
 * are not indexes; they are re-derived and re-checked too. No other index appears as a literal in any
 * redeemer (invariant 4).
 *
 * <h2>Scripts travel by reference input; the witness set stays empty</h2>
 * All six validators are declared to {@code withReferenceScripts} and their witness copies stripped by
 * {@code removeDuplicateScriptWitnesses}, the proven idiom of {@link PoolBorrowTransactionBuilder}. Three
 * of the coordinates are the published preview reference-script UTxOs; three are synthesised, per the
 * section above. {@link #assertStructure} refuses if the finished witness set holds any Plutus script.
 *
 * <h2>Structurally incapable of submitting</h2>
 * The {@link QuickTxBuilder} below is constructed with a <b>null</b> {@code TransactionProcessor} — the
 * only thing in cardano-client-lib that can put a transaction on a network — exactly as
 * {@link PoolBorrowTransactionBuilder} is. A builder that never has a processor cannot submit, whatever a
 * caller asks of it.
 *
 * <h2>Ex-units: optional, and null means exactly what it always meant</h2>
 * The {@code TransactionEvaluator} is a <b>constructor option</b>, as it is on
 * {@link PoolBorrowTransactionBuilder}. Left null — the four-argument constructor, which every offline
 * test uses — the redeemers keep {@code ScriptTx}'s placeholder {@code mem 10000 / steps 10000} and the
 * understated fee that follows; the real ex-units are then measured separately by
 * {@link PoolCancelDryEvalTest} through the UPLC machine. Handed a real evaluator, the redeemers carry
 * the budget that evaluator measured, and {@code ignoreScriptCostEvaluationError(false)} is set so an
 * evaluation failure surfaces instead of silently degrading to placeholders. Script-cost evaluation runs
 * <em>before</em> {@code removeDuplicateScriptWitnesses}, so the three validators are still in the
 * witness set when the evaluator resolves them.
 */
public final class PoolCancelTransactionBuilder {

    /** The unit redeemer {@code Constr 0 []} the {@code general_spend} handler ignores. */
    private static final PlutusData GENERAL_SPEND_REDEEMER =
            ConstrPlutusData.builder().alternative(0).data(ListPlutusData.of()).build();

    /**
     * How many {@code Cancel} actions this builder emits — one, because it cancels one pool. Two places
     * must agree on it: the redeemer's {@code actionsForEachInput} list, and the count of pool inputs
     * {@link #assertStructure} re-derives from the finished body ({@code pool_cancel_action} indexes
     * {@code actionsForEachInput} by position in that filtered input list, so a mismatch aborts).
     */
    private static final int CANCEL_ACTIONS = 1;

    /**
     * How many PoolManagers this builder cancels — one, in lockstep with {@link #CANCEL_ACTIONS}.
     * {@code pm_cancel_pool_manager} asserts all three counts equal: pool inputs, PoolManager inputs and
     * {@code poolManagerNFTAssetNames} entries.
     */
    private static final int POOL_MANAGER_CANCELS = 1;

    private final LoansContractRegistry registry;
    private final Network network;
    private final UtxoSupplier utxoSupplier;
    private final ProtocolParamsSupplier protocolParamsSupplier;

    /** Null for the offline rig; a real evaluator when the transaction has to be submittable. */
    private final TransactionEvaluator txEvaluator;

    /** No evaluator: placeholder ex-units, understated fee, byte-for-byte the offline shape. */
    public PoolCancelTransactionBuilder(LoansContractRegistry registry,
                                        Network network,
                                        UtxoSupplier utxoSupplier,
                                        ProtocolParamsSupplier protocolParamsSupplier) {
        this(registry, network, utxoSupplier, protocolParamsSupplier, null);
    }

    public PoolCancelTransactionBuilder(LoansContractRegistry registry,
                                        Network network,
                                        UtxoSupplier utxoSupplier,
                                        ProtocolParamsSupplier protocolParamsSupplier,
                                        TransactionEvaluator txEvaluator) {
        this.registry = registry;
        this.network = network;
        this.utxoSupplier = utxoSupplier;
        this.protocolParamsSupplier = protocolParamsSupplier;
        this.txEvaluator = txEvaluator;
    }

    /**
     * Everything the pool-cancel transaction needs, all of it the honest values — the adversarial shapes
     * {@link PoolCancelDryEvalTest} feeds the machine are produced by byte-surgery on the finished body
     * (and, for the two leg-omission cases, by {@link #buildWithMutation}), not by mis-configuring this
     * request.
     *
     * @param poolUtxo             the pool input, spent through {@code general_spend}; the pool NFT it
     *                             holds is the token this transaction burns
     * @param poolManagerUtxo      the PoolManager input, spent through its own {@code general_spend};
     *                             {@code pm_cancel_pool_manager} requires it <em>spent</em>, not merely
     *                             read, and requires it to hold the matching PoolManager NFT
     * @param funderUtxo           fees, collateral and the change source — and, by construction, the
     *                             lender who receives the recovered lovelace
     * @param configUtxo           the config reference input, read at {@code configRefInputIndex}
     * @param referenceScriptUtxos the six reference-script UTxOs: {@code general_spend} over the pool,
     *                             {@code pool.pool}, {@code pool_cancel_action}, {@code general_spend}
     *                             over the PoolManager, {@code pool_manager.poolManager},
     *                             {@code pm_cancel_pool_manager}. The last three are synthesised — see
     *                             the class javadoc
     * @param funderAddress        fee payer, collateral payer and change address
     * @param lenderPaymentKeyHash the lender's 28-byte payment key hash — declared a required signer so
     *                             {@code authorize_action}'s {@code list.has(extra_signatories, hash)}
     *                             finds it. Since T-024 that check lives in
     *                             {@code pm_cancel_pool_manager}, over
     *                             {@code PoolManagerDatum.poolOwnerAuth}, not in
     *                             {@code pool_cancel_action}
     * @param poolAssetNameHex     the 29-byte pool NFT asset name — the {@code CancelData.poolId}, the
     *                             PoolManager NFT's name, and the token both mint entries burn
     */
    public record Request(Utxo poolUtxo,
                          Utxo poolManagerUtxo,
                          Utxo funderUtxo,
                          Utxo configUtxo,
                          List<Utxo> referenceScriptUtxos,
                          String funderAddress,
                          byte[] lenderPaymentKeyHash,
                          String poolAssetNameHex) {

        /**
         * The PoolManager NFT's asset name. It is the pool NFT's, and not a field of its own:
         * {@code pool_manager.ak:173} forces the two equal at mint, so carrying a second field would
         * only create a way for them to disagree.
         */
        public String poolManagerAssetNameHex() {
            return poolAssetNameHex;
        }
    }

    /**
     * The leg-omission knobs {@link PoolCancelDryEvalTest} turns. Each removes a whole leg, which
     * byte-surgery on the honest body cannot do without re-indexing every remaining redeemer. Every field
     * of the default instance is inert, so {@link #build} and a default-mutated build are the same
     * transaction.
     *
     * @param omitPoolPolicyWithdrawal drop the {@code pool.pool(Cancel)} withdrawal leg, to reach
     *                                 {@code general_spend}'s withdrawal check. {@code pool.pool} is
     *                                 still needed by the burn, so no reference script dangles into a
     *                                 {@code RequiredRedeemersMismatch}, while {@code general_spend}
     *                                 finds no withdrawal at its {@code withdrawScriptHash} and refuses
     * @param omitPoolManagerLegs      drop the PoolManager input, its burn and both PoolManager
     *                                 withdrawals — <b>the pre-T-024 cancel, exactly</b>. This is the
     *                                 shape that would strand a PoolManager forever, and the evidence
     *                                 that it cannot be built through this path: with {@code lenderAuth}
     *                                 delegating to the PoolManager withdraw script,
     *                                 {@code pool_cancel_action} finds no such withdrawal and refuses the
     *                                 <em>pool</em> cancel outright
     * @param omitPoolManagerBurn      keep every PoolManager leg but drop the burn from the mint field,
     *                                 isolating {@code pm_cancel_pool_manager}'s "the pool manager NFT
     *                                 must be burnt" conjunct
     */
    record Mutation(boolean omitPoolPolicyWithdrawal,
                    boolean omitPoolManagerLegs,
                    boolean omitPoolManagerBurn) {

        static Mutation none() {
            return new Mutation(false, false, false);
        }
    }

    /**
     * Assembles and completes the pool-cancel transaction, then re-derives every index it claims from the
     * finished body (V5).
     *
     * <h2>The probe → rebuild → assert loop, for the two {@code poolWithdrawRedeemerIndex} copies</h2>
     * {@code configRefInputIndex} is deterministic before the build (it is a position among reference
     * inputs this builder chooses). {@code poolWithdrawRedeemerIndex} is not: it indexes
     * {@code self.redeemers}, whose length depends on how many inputs coin selection consumed, so it can
     * only be read off a <em>finished</em> body. So the transaction is built once with a probe value,
     * the real index is read off that body, and — if it differs — the transaction is rebuilt with it.
     * The rebuild can in principle move the index again (a different redeemer payload is a different
     * transaction size, hence possibly a different fee and a different coin selection), which is exactly
     * why {@link #assertStructure} re-derives it a final time from the body actually returned and
     * refuses on any mismatch, rather than trusting the loop to have converged.
     */
    public Transaction build(Request request) {
        long configRefIndex = configRefIndexAmong(request);
        Transaction probe = probeLayout(request, configRefIndex, Mutation.none());
        long poolWithdrawRedeemerIndex = requirePoolWithdrawRedeemerIndex(probe);

        Transaction built = complete(request,
                assemble(request, configRefIndex, poolWithdrawRedeemerIndex, Mutation.none()),
                Mutation.none());

        assertStructure(request, built, configRefIndex);
        return built;
    }

    /**
     * As {@link #build}, but with one of the leg-omission knobs turned — the seam
     * {@link PoolCancelDryEvalTest} uses to hand the machine a transaction this builder would never emit
     * on its own. The post-build structural assertions are <b>skipped</b>: their whole job is to refuse
     * the transactions the mutations exist to produce.
     * <p>
     * The probe → rebuild loop still runs where it can. With the {@code pool.pool} withdrawal omitted
     * there is no index to find, so the probe value stands — which is correct: that mutant is refused by
     * {@code general_spend} long before any redeemer index is read.
     */
    Transaction buildWithMutation(Request request, Mutation mutation) {
        long configRefIndex = configRefIndexAmong(request);
        Transaction probe = probeLayout(request, configRefIndex, mutation);
        OptionalInt found = poolWithdrawRedeemerIndexIn(probe, registry.getPoolPolicyId());
        long index = found.isPresent() ? found.getAsInt() : PROBE_POOL_WITHDRAW_INDEX;
        return complete(request, assemble(request, configRefIndex, index, mutation), mutation);
    }

    /**
     * The value the first, throwaway build carries. Any value works — it is overwritten by the rebuild
     * whenever the real index differs — but zero keeps the probe body a legal transaction rather than
     * one whose {@code expect index >= 0} would abort if it were ever evaluated.
     */
    private static final long PROBE_POOL_WITHDRAW_INDEX = 0L;

    /**
     * The throwaway build the real index is read off. It is completed <b>without the ex-units
     * evaluator</b>, even when this builder has one, and that is not an optimisation: the probe carries a
     * deliberately provisional {@code poolWithdrawRedeemerIndex}, so {@code pool_manager.ak}'s
     * {@code check_mint} would resolve it to the wrong redeemer and refuse — and a wired evaluator with
     * {@code ignoreScriptCostEvaluationError(false)} turns that refusal into a build failure. Measuring
     * the cost of a transaction that is wrong by construction is meaningless anyway; only the finished
     * body's budgets matter.
     * <p>
     * The probe and the final build differ in redeemer payload and therefore in size and fee, which could
     * in principle move coin selection and with it the index. {@link #assertStructure} re-derives the
     * index from the body actually returned and refuses on any mismatch, so that possibility is a loud
     * failure rather than a silent wrong index.
     */
    private Transaction probeLayout(Request request, long configRefIndex, Mutation mutation) {
        return completeWith(request,
                assemble(request, configRefIndex, PROBE_POOL_WITHDRAW_INDEX, mutation), mutation, null);
    }

    private long requirePoolWithdrawRedeemerIndex(Transaction probe) {
        return poolWithdrawRedeemerIndexIn(probe, registry.getPoolPolicyId())
                .orElseThrow(() -> new IllegalStateException(
                        "the probe body carries no pool.pool withdraw redeemer, so neither the "
                                + "PoolManager mint redeemer nor pm_cancel_pool_manager has anything to "
                                + "point at"));
    }

    // ---- assembly ---------------------------------------------------------------------------------

    private ScriptTx assemble(Request request, long configRefIndex, long poolWithdrawRedeemerIndex,
                              Mutation mutation) {
        ScriptTx tx = new ScriptTx();
        boolean withPoolManager = !mutation.omitPoolManagerLegs();

        // Inputs: the pool (through general_spend, unit redeemer — the real authorisation is the
        // pool.pool withdraw it defers to), the PoolManager (same shape, deferring to the
        // pool_manager.poolManager withdraw), and the funder (fees + collateral + change).
        tx.collectFrom(request.poolUtxo(), GENERAL_SPEND_REDEEMER);
        if (withPoolManager) {
            tx.collectFrom(request.poolManagerUtxo(), GENERAL_SPEND_REDEEMER);
        }
        tx.collectFrom(request.funderUtxo());

        // The burn: −1 of the pool NFT under the pool.pool policy. The mint redeemer names the pool
        // UTxO's own reference as inputRef — spent by construction — which check_mint's isInputRefSpent
        // looks for with find_input.
        TransactionInput poolRef = inputOf(request.poolUtxo());
        tx.mintAsset(registry.getPoolScript(),
                List.of(new Asset("0x" + request.poolAssetNameHex(), BigInteger.valueOf(-1))),
                PoolTxEncoder.poolMintRedeemer(configRefIndex, poolRef));

        // The second burn: −1 of the PoolManager NFT, same asset name. pool_manager.ak's check_mint
        // demands `poolManagerBurntNFTs == poolBurntNFTs` (name AND quantity) and, separately,
        // `length(poolInputs) == length(poolManagerBurntNFTs)` — so exactly one pool input, exactly one
        // PoolManager NFT burnt, under the pool NFT's name. Its poolWithdrawRedeemerIndex is live here
        // (unlike on the create path) and comes from the finished body.
        if (withPoolManager && !mutation.omitPoolManagerBurn()) {
            tx.mintAsset(registry.getPoolManagerScript(),
                    List.of(new Asset("0x" + request.poolManagerAssetNameHex(), BigInteger.valueOf(-1))),
                    PoolTxEncoder.poolManagerMintRedeemer(configRefIndex, poolWithdrawRedeemerIndex));
        }

        // The four withdraw-0 legs. pool.pool(Cancel) is what the pool's general_spend requires present;
        // pool_cancel_action carries the CancelData and does the pool's real work;
        // pool_manager.poolManager(CancelPoolManager) is what the PoolManager's general_spend requires,
        // and is ALSO what satisfies the pool datum's lenderAuth now that it delegates to that script;
        // pm_cancel_pool_manager does the PoolManager's real work.
        if (!mutation.omitPoolPolicyWithdrawal()) {
            tx.withdraw(rewardAddress(registry.getPoolPolicyId()), BigInteger.ZERO,
                    PoolTxEncoder.poolWithdrawRedeemer(configRefIndex, PoolTxEncoder.ACTION_CANCEL),
                    request.funderAddress());
        }
        List<String> poolIds = List.of(request.poolAssetNameHex());
        if (poolIds.size() != CANCEL_ACTIONS) {
            throw new IllegalStateException("actionsForEachInput must have " + CANCEL_ACTIONS
                    + " element(s), had " + poolIds.size());
        }
        tx.withdraw(rewardAddress(registry.getPoolCancelActionScriptHash()), BigInteger.ZERO,
                PoolTxEncoder.poolCancelActionWithdrawRedeemer(configRefIndex, poolIds),
                request.funderAddress());

        if (withPoolManager) {
            tx.withdraw(rewardAddress(registry.getPoolManagerPolicyId()), BigInteger.ZERO,
                    PoolTxEncoder.poolManagerWithdrawRedeemer(configRefIndex,
                            PoolTxEncoder.PM_ACTION_CANCEL_POOL_MANAGER),
                    request.funderAddress());

            List<String> poolManagerNames = List.of(request.poolManagerAssetNameHex());
            if (poolManagerNames.size() != POOL_MANAGER_CANCELS) {
                throw new IllegalStateException("poolManagerNFTAssetNames must have "
                        + POOL_MANAGER_CANCELS + " element(s), had " + poolManagerNames.size());
            }
            tx.withdraw(rewardAddress(registry.getPmCancelPoolManagerScriptHash()), BigInteger.ZERO,
                    PoolTxEncoder.cancelPoolManagerActionWithdrawRedeemer(
                            configRefIndex, poolWithdrawRedeemerIndex, poolManagerNames),
                    request.funderAddress());
        }

        // Reference inputs: config + the published (and, for the pool-manager family, synthesised)
        // reference scripts. When the PoolManager legs are omitted their reference scripts go with them:
        // a reference input publishing a script that no redeemer invokes is an
        // `extra` in the evaluator's RequiredRedeemersMismatch, which would mask the refusal that mutant
        // exists to show.
        tx.readFrom(inputOf(request.configUtxo()));
        for (Utxo refScript : request.referenceScriptUtxos()) {
            if (withPoolManager || !isPoolManagerScript(refScript.getReferenceScriptHash())) {
                tx.readFrom(inputOf(refScript));
            }
        }

        return tx.withChangeAddress(request.funderAddress());
    }

    /** Whether a reference-script hash belongs to the pool-manager family. */
    private boolean isPoolManagerScript(String referenceScriptHash) {
        return referenceScriptHash != null
                && (referenceScriptHash.equalsIgnoreCase(registry.getPoolManagerSpendScriptHash())
                || referenceScriptHash.equalsIgnoreCase(registry.getPoolManagerPolicyId())
                || referenceScriptHash.equalsIgnoreCase(registry.getPmCancelPoolManagerScriptHash()));
    }

    private Transaction complete(Request request, ScriptTx tx, Mutation mutation) {
        return completeWith(request, tx, mutation, txEvaluator);
    }

    private Transaction completeWith(Request request, ScriptTx tx, Mutation mutation,
                                     TransactionEvaluator evaluator) {
        try {
            // Third argument (TransactionProcessor) stays null; see the class javadoc.
            QuickTxBuilder.TxContext context = new QuickTxBuilder(utxoSupplier, protocolParamsSupplier, null)
                    .compose(tx)
                    .feePayer(request.funderAddress())
                    .collateralPayer(request.funderAddress())
                    // The lender's payment key hash into required_signers (key 14) — what
                    // authorize_action's CardanoSignatureAuth branch reads. No key, no signature.
                    .withRequiredSigners(request.lenderPaymentKeyHash())
                    .mergeOutputs(false)
                    // The three validators travel by reference input; declaring them here and stripping
                    // the witness copies keeps the witness set empty (the proven idiom). readFrom in
                    // assemble() puts their coordinates in the body.
                    .withReferenceScripts(referencedScripts(mutation))
                    .removeDuplicateScriptWitnesses(true)
                    // Offline: cardano-client-lib would otherwise walk every reference input for a script
                    // to fetch and NPE on the missing supplier.
                    .withScriptSupplier(scriptHash -> Optional.empty());
            // No validFrom / validTo — pool_cancel_action reads no validity range.
            return withExUnitsEvaluation(context, evaluator).build();
        } catch (Exception e) {
            throw new IllegalStateException("cannot build the pool-cancel transaction", e);
        }
    }

    /**
     * Wires the evaluator when there is one, and leaves the context untouched when there is not — so the
     * no-evaluator build is the same call sequence it has always been. {@code
     * ignoreScriptCostEvaluationError(false)} is set with the evaluator and only with it: a build that
     * asked for real budgets must fail loudly rather than fall back to placeholders.
     */
    private QuickTxBuilder.TxContext withExUnitsEvaluation(QuickTxBuilder.TxContext context,
                                                           TransactionEvaluator evaluator) {
        if (evaluator == null) {
            return context;
        }
        return context.withTxEvaluator(evaluator).ignoreScriptCostEvaluationError(false);
    }

    private PlutusScript[] referencedScripts(Mutation mutation) {
        if (mutation.omitPoolManagerLegs()) {
            return new PlutusScript[]{
                    registry.getPoolSpendScript(),
                    registry.getPoolScript(),
                    registry.getPoolCancelActionScript()};
        }
        return new PlutusScript[]{
                registry.getPoolSpendScript(),
                registry.getPoolScript(),
                registry.getPoolCancelActionScript(),
                registry.getPoolManagerSpendScript(),
                registry.getPoolManagerScript(),
                registry.getPmCancelPoolManagerScript()};
    }

    // ---- V5: re-derive every claim from the finished body -----------------------------------------

    /**
     * {@link #assertStructure} over a caller-supplied body, recomputing {@code configRefInputIndex} the
     * way {@link #build} does.
     *
     * <h2>Why this is package-private</h2>
     * Every branch of {@link #assertStructure} is unreachable through {@link #build}: the builder only
     * ever hands it a body it just assembled correctly, so neutering any single check would change
     * nothing observable and the check would sit in the tree unproven — a guard whose name is tested and
     * whose body is not. {@link PoolCancelDryEvalTest} therefore calls this directly with one input
     * doctored at a time.
     *
     * <h2>Which input has to be doctored</h2>
     * <b>It depends on what the claim reads, and getting this wrong leaves the guard undefended.</b> Most
     * claims read the finished <em>body</em> and are pinned by a doctored body. The two NFT-holding
     * claims read the <em>{@link Request}</em> instead, so no doctored body can ever reach them — they are
     * pinned by a doctored {@code Request} passed alongside the honest body.
     * <p>
     * Pinned today, each by a case of
     * {@code PoolCancelDryEvalTest#theStructuralChecksRefuseEachClaimTheyMake} that dies when the check's
     * body is neutered: the PoolManager burn, both copies of {@code poolWithdrawRedeemerIndex}, the
     * PoolManager input count (doctored bodies), and the two NFT-holding claims (doctored requests). The
     * remaining checks of {@link #assertStructure} are <b>not</b> pinned — they would survive neutering
     * with the suite green.
     */
    void assertStructureOf(Request request, Transaction transaction) {
        assertStructure(request, transaction, configRefIndexAmong(request));
    }

    private void assertStructure(Request request, Transaction transaction, long configRefIndex) {
        // configRefInputIndex points at the config among the canonically sorted reference inputs.
        List<TransactionInput> sortedRefInputs = transaction.getBody().getReferenceInputs().stream()
                .sorted(new TransactionInputComparator())
                .toList();
        structural(configRefIndex >= 0 && configRefIndex < sortedRefInputs.size()
                        && sortedRefInputs.get((int) configRefIndex).equals(inputOf(request.configUtxo())),
                "configRefInputIndex " + configRefIndex + " does not point at the config reference input");

        // The mint field burns exactly −1 of the pool NFT, and nothing else under the pool policy.
        BigInteger burnt = mintQuantity(transaction, registry.getPoolPolicyId(), request.poolAssetNameHex());
        structural(burnt.equals(BigInteger.valueOf(-1)),
                "the mint field must burn exactly one pool NFT (−1), burnt " + burnt);

        // …and −1 of the PoolManager NFT, under the SAME name. pool_manager.ak compares the two burn
        // lists with Pairs equality, so a different name or a different quantity is a refusal — and a
        // cancel that omitted this burn entirely would strand the PoolManager forever (the orphan lock:
        // pm_cancel_pool_manager then requires a live pool holding the matching NFT, and there is none).
        BigInteger poolManagerBurnt = mintQuantity(transaction, registry.getPoolManagerPolicyId(),
                request.poolManagerAssetNameHex());
        structural(poolManagerBurnt.equals(BigInteger.valueOf(-1)),
                "the mint field must burn exactly one PoolManager NFT (−1) under the pool NFT's own "
                        + "name, burnt " + poolManagerBurnt);

        // Exactly one input at the PoolManager spend credential: pm_cancel_pool_manager requires as many
        // PoolManager inputs as pool inputs, and indexes poolManagerNFTAssetNames by position in that
        // filtered list.
        long atPoolManagerCredential = transaction.getBody().getInputs().stream()
                .filter(input -> isAtCredential(input, registry.getPoolManagerSpendScriptHash()))
                .count();
        structural(atPoolManagerCredential == POOL_MANAGER_CANCELS,
                "poolManagerNFTAssetNames has " + POOL_MANAGER_CANCELS + " element(s) but the finished "
                        + "body has " + atPoolManagerCredential + " input(s) at the PoolManager spend "
                        + "credential");
        // Reads the REQUEST, not the body: the pin for this claim is a doctored Request (see
        // assertStructureOf), because no doctored body can reach it.
        structural(holdsNft(request.poolManagerUtxo(), registry.getPoolManagerPolicyId(),
                        request.poolManagerAssetNameHex()),
                "the PoolManager input does not hold exactly one " + registry.getPoolManagerPolicyId()
                        + request.poolManagerAssetNameHex());

        // Both copies of poolWithdrawRedeemerIndex. The mint redeemer and pm_cancel_pool_manager carry
        // the value independently on chain, so each CLAIM is compared separately against the body and
        // breaking either alone is caught. The two re-derivations below are NOT independent witnesses —
        // they are the same pure function over the same body and cannot disagree. The separation that
        // matters is between the two claimed values, not between the two derivations.
        int poolWithdrawRedeemerIndex = poolWithdrawRedeemerIndexIn(transaction, registry.getPoolPolicyId())
                .orElseThrow(() -> new IllegalStateException("POOL_CANCEL_STRUCTURE_ASSERTION_FAILED: "
                        + "the finished body carries no pool.pool withdraw redeemer"));
        long claimedByMint = poolManagerMintRedeemerPoolWithdrawIndex(transaction);
        structural(claimedByMint == poolWithdrawRedeemerIndex,
                "the PoolManager mint redeemer claims poolWithdrawRedeemerIndex " + claimedByMint
                        + " but the pool.pool withdraw redeemer sits at self.redeemers["
                        + poolWithdrawRedeemerIndex + "]");
        int rederived = poolWithdrawRedeemerIndexIn(transaction, registry.getPoolPolicyId()).orElseThrow();
        long claimedByCancelAction = cancelPoolManagerPoolWithdrawIndex(transaction);
        structural(claimedByCancelAction == rederived,
                "the pm_cancel_pool_manager redeemer claims poolWithdrawRedeemerIndex "
                        + claimedByCancelAction + " but the pool.pool withdraw redeemer sits at "
                        + "self.redeemers[" + rederived + "]");

        // The mint redeemer's inputRef is among the body's inputs — check_mint's isInputRefSpent.
        TransactionInput mintInputRef =
                mintRedeemerInputRef(transaction, registry.getPoolPolicyId());
        structural(transaction.getBody().getInputs().contains(mintInputRef),
                "the mint redeemer's inputRef " + mintInputRef + " is not among the body's inputs, so "
                        + "check_mint's isInputRefSpent would fail");

        // Exactly one input at the pool spend credential — pool_cancel_action indexes actionsForEachInput
        // by position in that filtered list, and safe_list_at aborts once it runs off the end of it.
        long atPoolCredential = transaction.getBody().getInputs().stream()
                .filter(this::isAtPoolSpendCredential)
                .count();
        structural(atPoolCredential == CANCEL_ACTIONS,
                "actionsForEachInput has " + CANCEL_ACTIONS + " element(s) but the finished body has "
                        + atPoolCredential + " input(s) at the pool spend credential");

        // The CancelData.poolId the withdraw redeemer carries is the pool NFT the pool input holds.
        String cancelPoolId = cancelActionPoolId(transaction);
        structural(cancelPoolId.equalsIgnoreCase(request.poolAssetNameHex()),
                "the CancelData.poolId " + cancelPoolId + " is not the pool NFT name "
                        + request.poolAssetNameHex());
        // Reads the REQUEST too — same pin, a doctored Request.
        structural(poolInputHoldsNft(request),
                "the pool input does not hold exactly one " + registry.getPoolPolicyId()
                        + request.poolAssetNameHex());

        // The lender's key hash is in required_signers — authorize_action's CardanoSignatureAuth branch.
        String lenderHash = HexUtil.encodeHexString(request.lenderPaymentKeyHash());
        boolean signerPresent = transaction.getBody().getRequiredSigners() != null
                && transaction.getBody().getRequiredSigners().stream()
                .map(HexUtil::encodeHexString)
                .anyMatch(lenderHash::equalsIgnoreCase);
        structural(signerPresent,
                "the lender key hash " + lenderHash + " is not among required_signers");

        // Nothing in the witness set: every script travels by reference input.
        structural(transaction.getWitnessSet().getPlutusV3Scripts() == null
                        || transaction.getWitnessSet().getPlutusV3Scripts().isEmpty(),
                "the witness set carries Plutus scripts; the three validators must travel by reference");
    }

    // ---- derivations ------------------------------------------------------------------------------

    /** Where the config would sort among the canonically ordered reference inputs. */
    private long configRefIndexAmong(Request request) {
        List<TransactionInput> refInputs = new ArrayList<>();
        refInputs.add(inputOf(request.configUtxo()));
        for (Utxo refScript : request.referenceScriptUtxos()) {
            refInputs.add(inputOf(refScript));
        }
        refInputs.sort(new TransactionInputComparator());
        return refInputs.indexOf(inputOf(request.configUtxo()));
    }

    /** The signed quantity of {@code policyId ‖ assetName} in the finished body's mint field. */
    private static BigInteger mintQuantity(Transaction tx, String policyId, String assetNameHex) {
        if (tx.getBody().getMint() == null) {
            return BigInteger.ZERO;
        }
        return tx.getBody().getMint().stream()
                .filter(ma -> ma.getPolicyId().equalsIgnoreCase(policyId))
                .flatMap(ma -> ma.getAssets().stream())
                .filter(a -> strip(a.getNameAsHex()).equalsIgnoreCase(assetNameHex))
                .map(Asset::getValue)
                .findFirst()
                .orElse(BigInteger.ZERO);
    }

    /**
     * The {@code inputRef} the finished transaction's mint redeemer carries, decoded out of the witness
     * set — a {@code PoolMintRedeemer{configRefInputIndex, inputRef}} (constructor 0, two fields) whose
     * {@code inputRef} is an {@code OutputReference} with a flat {@code ByteArray} transaction id and an
     * {@code Int} output index. Every shape assumption is checked, never cast blindly.
     */
    private static TransactionInput mintRedeemerInputRef(Transaction tx, String poolPolicyId) {
        // Selected by mint index, not by shape: since T-024 there are two Mint redeemers and
        // PoolMintRedeemer / PoolManagerMintRedeemer are both two-field constructor 0 records, so
        // "the only Mint redeemer" no longer identifies anything and a shape test would pick either.
        int poolMintIndex = mintPolicyIndexOf(tx, poolPolicyId);
        List<Redeemer> mintRedeemers = tx.getWitnessSet().getRedeemers().stream()
                .filter(r -> r.getTag() == RedeemerTag.Mint)
                .filter(r -> r.getIndex().intValueExact() == poolMintIndex)
                .toList();
        if (mintRedeemers.size() != 1) {
            throw new IllegalStateException("expected exactly one Mint redeemer at index " + poolMintIndex
                    + " for the pool policy, found " + mintRedeemers.size());
        }
        List<PlutusData> redeemerFields = fieldsOf(mintRedeemers.get(0).getData(), "the mint redeemer");
        if (redeemerFields.size() != 2) {
            throw new IllegalStateException("PoolMintRedeemer has 2 fields, had " + redeemerFields.size());
        }
        List<PlutusData> refFields = fieldsOf(redeemerFields.get(1), "the mint redeemer's inputRef");
        if (refFields.size() != 2) {
            throw new IllegalStateException("OutputReference has 2 fields, had " + refFields.size());
        }
        if (!(refFields.get(0) instanceof BytesPlutusData transactionId)) {
            throw new IllegalStateException("OutputReference.transaction_id must be a flat ByteArray, was "
                    + refFields.get(0).getClass().getSimpleName());
        }
        if (!(refFields.get(1) instanceof BigIntPlutusData outputIndex)) {
            throw new IllegalStateException("OutputReference.output_index must be an Int, was "
                    + refFields.get(1).getClass().getSimpleName());
        }
        return new TransactionInput(HexUtil.encodeHexString(transactionId.getValue()),
                outputIndex.getValue().intValueExact());
    }

    /**
     * The single {@code CancelData.poolId} the pool_cancel_action withdraw redeemer carries. The redeemer
     * is identified by content — a {@code PoolCancelActionWithdrawRedeemer} whose second field is a
     * <em>list</em> of {@code CancelData} (as opposed to {@code PoolWithdrawRedeemer}'s fieldless
     * {@code Action}) — so it survives any withdrawal ordering.
     */
    private static String cancelActionPoolId(Transaction tx) {
        List<Redeemer> matches = tx.getWitnessSet().getRedeemers().stream()
                .filter(r -> r.getTag() == RedeemerTag.Reward && isCancelActionRedeemer(r))
                .toList();
        if (matches.size() != 1) {
            throw new IllegalStateException("expected exactly one pool_cancel_action withdraw redeemer, found "
                    + matches.size());
        }
        List<PlutusData> fields = fieldsOf(matches.get(0).getData(), "the pool_cancel_action redeemer");
        ListPlutusData actions = (ListPlutusData) fields.get(1);
        if (actions.getPlutusDataList().size() != CANCEL_ACTIONS) {
            throw new IllegalStateException("actionsForEachInput carries "
                    + actions.getPlutusDataList().size() + " CancelData, expected " + CANCEL_ACTIONS);
        }
        List<PlutusData> cancelData = fieldsOf(actions.getPlutusDataList().get(0), "the CancelData");
        if (!(cancelData.get(0) instanceof BytesPlutusData poolId)) {
            throw new IllegalStateException("CancelData.poolId must be a ByteArray, was "
                    + cancelData.get(0).getClass().getSimpleName());
        }
        return HexUtil.encodeHexString(poolId.getValue());
    }

    /** True if {@code r} is a {@code PoolCancelActionWithdrawRedeemer}: two fields, the second a list. */
    static boolean isCancelActionRedeemer(Redeemer r) {
        return r.getData() instanceof ConstrPlutusData constr
                && constr.getData().getPlutusDataList().size() == 2
                && constr.getData().getPlutusDataList().get(1) instanceof ListPlutusData;
    }

    /**
     * Where the {@code Withdraw(Script(scriptHash))} redeemer sits in {@code self.redeemers} — the whole
     * {@code Pairs<ScriptPurpose, Redeemer>} list a Plutus V3 script sees, not the withdrawals list —
     * or empty when the transaction carries no withdrawal at that script.
     *
     * <h2>How the position is derived, and why it does not depend on a disputed ordering</h2>
     * {@code self.redeemers} is a map keyed by {@code ScriptPurpose}, so it is grouped: all mints, all
     * spends, all rewards, then the certificate/vote/proposal purposes. Within each group the ledger's
     * own redeemer {@code index} is already the canonical position (mints by policy id, spends by output
     * reference, rewards by reward account), which is precisely what cardano-client-lib writes into
     * {@link Redeemer#getIndex()}. So the answer is
     * {@code (#Mint + #Spend) + rewardIndexOfThisWithdrawal}.
     * <p>
     * The <em>relative</em> order of the mint and spend groups is the one thing this could get wrong —
     * Plutus's {@code ScriptPurpose} declares {@code Minting} before {@code Spending} while the ledger's
     * redeemer tag numbering puts spend first — and it <b>cannot matter here</b>: rewards sort after both
     * under either convention, and both groups are counted, so their internal order cancels out. That is
     * true of every transaction this builder emits (mints, spends and rewards, nothing else). It is also
     * not the last word: {@link PoolCancelDryEvalTest} hands the deployed validators an off-by-one copy
     * of this index and requires them to refuse it, so the number is arbitrated by the machine and not by
     * this comment.
     *
     * @param scriptHash the withdraw script's hash — for the pool family, {@code poolPolicyId}
     */
    static OptionalInt poolWithdrawRedeemerIndexIn(Transaction tx, String scriptHash) {
        List<Withdrawal> withdrawals = tx.getBody().getWithdrawals();
        if (withdrawals == null || tx.getWitnessSet() == null
                || tx.getWitnessSet().getRedeemers() == null) {
            return OptionalInt.empty();
        }
        int rewardIndex = -1;
        for (int i = 0; i < withdrawals.size(); i++) {
            if (rewardAddressHolds(withdrawals.get(i).getRewardAddress(), scriptHash)) {
                if (rewardIndex >= 0) {
                    throw new IllegalStateException("two withdrawals at the same script " + scriptHash);
                }
                rewardIndex = i;
            }
        }
        if (rewardIndex < 0) {
            return OptionalInt.empty();
        }
        int precedingGroups = (int) tx.getWitnessSet().getRedeemers().stream()
                .filter(r -> r.getTag() == RedeemerTag.Mint || r.getTag() == RedeemerTag.Spend)
                .count();
        return OptionalInt.of(precedingGroups + rewardIndex);
    }

    /**
     * Whether a bech32 reward address carries {@code scriptHash} as its credential. A reward address is
     * one header byte followed by the 28-byte credential, so the credential is the address bytes' tail —
     * read off the address rather than recomputed from a network parameter, which is what lets this stay
     * static and be shared with {@link PoolCreateTransactionBuilder}.
     */
    private static boolean rewardAddressHolds(String rewardAddressBech32, String scriptHash) {
        String bytes = HexUtil.encodeHexString(
                new com.bloxbean.cardano.client.address.Address(rewardAddressBech32).getBytes());
        return bytes.toLowerCase().endsWith(scriptHash.toLowerCase());
    }

    /**
     * The {@code poolWithdrawRedeemerIndex} the PoolManager <em>mint</em> redeemer carries — the second
     * field of the {@code PoolManagerMintRedeemer} on the Mint redeemer that is not the pool policy's.
     * The two mint redeemers are told apart by their <b>index</b> among the canonically sorted minted
     * policy ids, never by shape: {@code PoolMintRedeemer} and {@code PoolManagerMintRedeemer} are both
     * two-field constructor 0 records and a shape test would pick whichever came first.
     */
    private long poolManagerMintRedeemerPoolWithdrawIndex(Transaction tx) {
        int mintIndex = mintPolicyIndexOf(tx, registry.getPoolManagerPolicyId());
        Redeemer redeemer = tx.getWitnessSet().getRedeemers().stream()
                .filter(r -> r.getTag() == RedeemerTag.Mint && r.getIndex().intValueExact() == mintIndex)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "no Mint redeemer at index " + mintIndex + " for the PoolManager policy"));
        List<PlutusData> fields = fieldsOf(redeemer.getData(), "the PoolManager mint redeemer");
        if (fields.size() != 2) {
            throw new IllegalStateException("PoolManagerMintRedeemer has 2 fields, had " + fields.size());
        }
        if (!(fields.get(1) instanceof BigIntPlutusData index)) {
            throw new IllegalStateException("PoolManagerMintRedeemer.poolWithdrawRedeemerIndex must be an "
                    + "Int, was " + fields.get(1).getClass().getSimpleName());
        }
        return index.getValue().longValueExact();
    }

    /** Where {@code policyId} sits among the canonically sorted policy ids of the mint field. */
    private static int mintPolicyIndexOf(Transaction tx, String policyId) {
        if (tx.getBody().getMint() == null) {
            throw new IllegalStateException("the body has no mint field");
        }
        List<String> policies = tx.getBody().getMint().stream()
                .map(MultiAsset::getPolicyId)
                .map(String::toLowerCase)
                .sorted()
                .toList();
        int index = policies.indexOf(policyId.toLowerCase());
        if (index < 0) {
            throw new IllegalStateException("the mint field carries nothing under " + policyId);
        }
        return index;
    }

    /**
     * The {@code poolWithdrawRedeemerIndex} the {@code pm_cancel_pool_manager} withdraw redeemer carries
     * — the second field of the Reward redeemer sitting at the {@code pm_cancel_pool_manager} withdrawal.
     * <p>
     * Identified by <b>position</b>, like every other redeemer this builder reads. A three-field shape
     * test would work today ({@code CancelPoolManagerActionWithdrawRedeemer} is the sole three-field
     * redeemer here), but shape identification is exactly what this slice removed everywhere else, and a
     * derivation that stays correct only while no second three-field redeemer appears is the same latent
     * trap in a different place.
     */
    private long cancelPoolManagerPoolWithdrawIndex(Transaction tx) {
        Redeemer redeemer = rewardRedeemerAt(tx, registry.getPmCancelPoolManagerScriptHash());
        List<PlutusData> fields = fieldsOf(redeemer.getData(), "the pm_cancel_pool_manager redeemer");
        if (fields.size() != 3) {
            throw new IllegalStateException("CancelPoolManagerActionWithdrawRedeemer has 3 fields, had "
                    + fields.size());
        }
        if (!(fields.get(1) instanceof BigIntPlutusData index)) {
            throw new IllegalStateException("CancelPoolManagerActionWithdrawRedeemer."
                    + "poolWithdrawRedeemerIndex must be an Int, was "
                    + fields.get(1).getClass().getSimpleName());
        }
        return index.getValue().longValueExact();
    }

    /**
     * True if {@code r} is a {@code CancelPoolManagerActionWithdrawRedeemer}: three fields. A
     * <b>doctoring selector for {@link PoolCancelDryEvalTest}</b>, not a derivation path — everything
     * this builder reads goes through {@link #rewardRedeemerAt} instead, by position.
     */
    static boolean isCancelPoolManagerRedeemer(Redeemer r) {
        return r.getData() instanceof ConstrPlutusData constr
                && constr.getData().getPlutusDataList().size() == 3;
    }

    /**
     * The Reward redeemer sitting at the withdrawal of {@code scriptHash}, identified by <b>position</b>
     * rather than by shape. The withdrawal position is exact, because a reward address maps one-to-one
     * onto a script hash.
     *
     * <h2>What this is and is not</h2>
     * {@code PoolWithdrawRedeemer} and {@code PoolManagerWithdrawRedeemer} <em>are</em>
     * byte-indistinguishable — both are constructor 0 with an {@code Int} and a fieldless {@code Constr}
     * — which {@code PoolTxEncoderTest} pins. But this is <b>preventive hardening, not the repair of an
     * observed mis-identification</b>, and the record should not be read as one:
     * <ul>
     *   <li>the ambiguity was <b>not reachable before T-024</b> — the old cancel carried only one
     *       two-field Reward redeemer, {@code pool_cancel_action}'s second field being a
     *       {@code ListPlutusData};</li>
     *   <li>even now a {@code findFirst} shape test would return the <b>correct</b> redeemer, because
     *       {@code pool.pool} sorts before {@code pool_manager} and the withdrawal list is in canonical
     *       byte order.</li>
     * </ul>
     * So what T-024 introduced is a latent ambiguity masked by hash ordering — a mask that a future
     * redeployment's hashes could lift without warning. The rule stands regardless: identify a redeemer
     * by its purpose's position, never by its data's shape.
     *
     * <h2>Duplicates</h2>
     * Two withdrawals at one script is a malformed body, not a body to pick a winner from — the same
     * stance {@link #poolWithdrawRedeemerIndexIn} takes, and deliberately so: two functions deriving the
     * same position with opposite duplicate policies would be a trap.
     */
    static Redeemer rewardRedeemerAt(Transaction tx, String scriptHash) {
        List<Withdrawal> withdrawals = tx.getBody().getWithdrawals();
        int rewardIndex = -1;
        for (int i = 0; withdrawals != null && i < withdrawals.size(); i++) {
            if (rewardAddressHolds(withdrawals.get(i).getRewardAddress(), scriptHash)) {
                if (rewardIndex >= 0) {
                    throw new IllegalStateException("two withdrawals at the same script " + scriptHash);
                }
                rewardIndex = i;
            }
        }
        int wanted = rewardIndex;
        if (wanted < 0) {
            throw new IllegalStateException("no withdrawal at script " + scriptHash);
        }
        return tx.getWitnessSet().getRedeemers().stream()
                .filter(r -> r.getTag() == RedeemerTag.Reward && r.getIndex().intValueExact() == wanted)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "no Reward redeemer at index " + wanted + " for script " + scriptHash));
    }

    private boolean poolInputHoldsNft(Request request) {
        return holdsNft(request.poolUtxo(), registry.getPoolPolicyId(), request.poolAssetNameHex());
    }

    private static boolean holdsNft(Utxo utxo, String policyId, String assetNameHex) {
        return utxo.getAmount().stream()
                .anyMatch(a -> (policyId + assetNameHex).equalsIgnoreCase(a.getUnit())
                        && BigInteger.ONE.equals(a.getQuantity()));
    }

    /** Whether an input of the finished body sits at {@code poolSpendScriptHash}. */
    private boolean isAtPoolSpendCredential(TransactionInput input) {
        return isAtCredential(input, registry.getPoolSpendScriptHash());
    }

    /** Whether an input of the finished body sits at the given payment credential. */
    private boolean isAtCredential(TransactionInput input, String paymentCredentialHash) {
        Utxo resolved = utxoSupplier.getTxOutput(input.getTransactionId(), input.getIndex())
                .orElseThrow(() -> new IllegalStateException(
                        "cannot resolve input " + input + " against the supplied UTxO set"));
        return new com.bloxbean.cardano.client.address.Address(resolved.getAddress())
                .getPaymentCredentialHash()
                .map(HexUtil::encodeHexString)
                .filter(hash -> hash.equals(paymentCredentialHash))
                .isPresent();
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private String rewardAddress(String scriptHash) {
        return AddressProvider.getRewardAddress(Credential.fromScript(scriptHash), network).getAddress();
    }

    private static List<PlutusData> fieldsOf(PlutusData data, String what) {
        if (!(data instanceof ConstrPlutusData constr)) {
            throw new IllegalStateException(what + " must be a Constr, was "
                    + (data == null ? "null" : data.getClass().getSimpleName()));
        }
        return constr.getData().getPlutusDataList();
    }

    private static String strip(String maybePrefixed) {
        return maybePrefixed.startsWith("0x") ? maybePrefixed.substring(2) : maybePrefixed;
    }

    private static TransactionInput inputOf(Utxo utxo) {
        return new TransactionInput(utxo.getTxHash(), utxo.getOutputIndex());
    }

    private static void structural(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("POOL_CANCEL_STRUCTURE_ASSERTION_FAILED: " + message);
        }
    }
}
