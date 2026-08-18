package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
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
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import com.fluidtokens.aquarium.offchain.service.TransactionInputComparator;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
 * <h2>The four script purposes, three scripts (from source at {@code ff005fb})</h2>
 * {@code pool.pool} has no {@code spend} handler ({@code validators/pool.ak}); the pool UTxO sits at a
 * {@code general_spend} address whose spend defers to a withdrawal of {@code pool.pool} itself, whose
 * {@code Cancel} action in turn requires a withdrawal of {@code pool_cancel_action} — the validator
 * that does the real work. So the transaction carries, in redeemer-tag order:
 * <ol>
 *   <li><b>Mint</b> (burn, −1) under the {@code pool.pool} policy — {@code check_mint}
 *       ({@code validators/pool.ak}) filters {@code quantity > 0}, so a pure burn leaves
 *       {@code mintedTokens} empty and it reduces to {@code isInputRefSpent}: the mint redeemer's
 *       {@code inputRef} must be a spent input. This builder names the pool UTxO's own reference,
 *       spent by construction;</li>
 *   <li><b>Spend</b> — {@code general_spend} over the pool UTxO. Its redeemer is ignored
 *       ({@code _redeemer: Data}); with the pool's inline datum present its only check is that some
 *       withdrawal sits at {@code Script(pool.pool hash)} ({@code general_spend.ak} else-branch);</li>
 *   <li><b>Withdraw</b> (reward, amount 0) at {@code pool.pool} — {@code PoolWithdrawRedeemer{
 *       configRefInputIndex, Cancel}}. Its {@code Cancel} branch requires a withdrawal at
 *       {@code poolCancelActionScriptHash} (config index 22);</li>
 *   <li><b>Withdraw</b> (reward, amount 0) at {@code pool_cancel_action} —
 *       {@code PoolCancelActionWithdrawRedeemer{configRefInputIndex, [CancelData{poolId}]}}.</li>
 * </ol>
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
 * <h2>Lender authorisation is a required signer, not a spent token</h2>
 * {@link PoolFixtures#lenderAuth()} is {@code CardanoSignature(botKeyHash)}, so
 * {@code create_auth} yields {@code CardanoSignatureAuth} and {@code authorize_action} reduces to
 * {@code list.has(extra_signatories, botKeyHash)} ({@code lib/fluidtokens/authorizer.ak}).
 * {@code extra_signatories} is the body's {@code required_signers} field (key 14), so this builder
 * declares the lender key hash with {@code withRequiredSigners} — no key and no signature are involved.
 *
 * <h2>Every index is derived from the finished body</h2>
 * The only index any redeemer here reads is {@code configRefInputIndex} — shared by the two withdraws
 * and the mint, all resolving the same reference-input list. It is deterministic (the config's position
 * among the canonically sorted reference inputs), so no layout probe is needed, but {@link #assertStructure}
 * re-derives it from the FINISHED body and refuses on any mismatch. The {@code CancelData.poolId} and the
 * mint's {@code inputRef} are not indexes; they are re-derived and re-checked too. No other index appears
 * as a literal in any redeemer (invariant 4).
 *
 * <h2>Scripts travel by reference input; the witness set stays empty</h2>
 * The three validators are declared to {@code withReferenceScripts} and their witness copies stripped by
 * {@code removeDuplicateScriptWitnesses}, the proven idiom of {@link PoolBorrowTransactionBuilder}; their
 * coordinates are the published preview reference-script UTxOs. {@link #assertStructure} refuses if the
 * finished witness set holds any Plutus script.
 *
 * <h2>Structurally incapable of submitting</h2>
 * The {@link QuickTxBuilder} below is constructed with a <b>null</b> {@code TransactionProcessor} — the
 * only thing in cardano-client-lib that can put a transaction on a network — exactly as
 * {@link PoolBorrowTransactionBuilder} is. A builder that never has a processor cannot submit, whatever a
 * caller asks of it. No evaluator is wired in either, so the transaction carries placeholder ex-units and
 * an understated fee: fine for an offline rig, not fine for submission. The real ex-units are measured
 * separately by {@link PoolCancelDryEvalTest} through the UPLC machine.
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

    private final LoansContractRegistry registry;
    private final Network network;
    private final UtxoSupplier utxoSupplier;
    private final ProtocolParamsSupplier protocolParamsSupplier;

    public PoolCancelTransactionBuilder(LoansContractRegistry registry,
                                        Network network,
                                        UtxoSupplier utxoSupplier,
                                        ProtocolParamsSupplier protocolParamsSupplier) {
        this.registry = registry;
        this.network = network;
        this.utxoSupplier = utxoSupplier;
        this.protocolParamsSupplier = protocolParamsSupplier;
    }

    /**
     * Everything the pool-cancel transaction needs, all of it the honest values — the adversarial shapes
     * {@link PoolCancelDryEvalTest} feeds the machine are produced by byte-surgery on the finished body
     * (and, for the two leg-omission cases, by {@link #buildWithMutation}), not by mis-configuring this
     * request.
     *
     * @param poolUtxo             the pool input, spent through {@code general_spend}; the pool NFT it
     *                             holds is the token this transaction burns
     * @param funderUtxo           fees, collateral and the change source — and, by construction, the
     *                             lender who receives the recovered lovelace
     * @param configUtxo           the config reference input, read at {@code configRefInputIndex}
     * @param referenceScriptUtxos the three published reference-script UTxOs: {@code general_spend} over
     *                             the pool, {@code pool.pool}, {@code pool_cancel_action}
     * @param funderAddress        fee payer, collateral payer and change address
     * @param lenderPaymentKeyHash the lender's 28-byte payment key hash — declared a required signer so
     *                             {@code authorize_action}'s {@code list.has(extra_signatories, hash)}
     *                             finds it
     * @param poolAssetNameHex     the 29-byte pool NFT asset name — the {@code CancelData.poolId} and the
     *                             token the mint field burns
     */
    public record Request(Utxo poolUtxo,
                          Utxo funderUtxo,
                          Utxo configUtxo,
                          List<Utxo> referenceScriptUtxos,
                          String funderAddress,
                          byte[] lenderPaymentKeyHash,
                          String poolAssetNameHex) {
    }

    /**
     * The one leg-omission knob {@link PoolCancelDryEvalTest} turns to reach {@code general_spend}'s
     * withdrawal check. Dropping the {@code pool.pool(Cancel)} withdrawal leaves {@code pool.pool} still
     * needed by the burn (so no reference script dangles into a {@code RequiredRedeemersMismatch}) while
     * {@code general_spend} finds no withdrawal at its {@code withdrawScriptHash} and refuses. Byte-surgery
     * on the honest body cannot produce this without re-indexing the remaining redeemers. Every field of
     * the default instance is inert, so {@link #build} and a default-mutated build are the same transaction.
     *
     * @param omitPoolPolicyWithdrawal drop the {@code pool.pool(Cancel)} withdrawal leg
     */
    record Mutation(boolean omitPoolPolicyWithdrawal) {

        static Mutation none() {
            return new Mutation(false);
        }
    }

    /**
     * Assembles and completes the pool-cancel transaction, then re-derives every index it claims from the
     * finished body (V5).
     */
    public Transaction build(Request request) {
        long configRefIndex = configRefIndexAmong(request);
        Transaction built = complete(request, assemble(request, configRefIndex, Mutation.none()));
        assertStructure(request, built, configRefIndex);
        return built;
    }

    /**
     * As {@link #build}, but with one of the leg-omission knobs turned — the seam
     * {@link PoolCancelDryEvalTest} uses to hand the machine a transaction this builder would never emit
     * on its own. The post-build structural assertions are <b>skipped</b>: their whole job is to refuse
     * the transactions the mutations exist to produce.
     */
    Transaction buildWithMutation(Request request, Mutation mutation) {
        long configRefIndex = configRefIndexAmong(request);
        return complete(request, assemble(request, configRefIndex, mutation));
    }

    // ---- assembly ---------------------------------------------------------------------------------

    private ScriptTx assemble(Request request, long configRefIndex, Mutation mutation) {
        ScriptTx tx = new ScriptTx();

        // Inputs: the pool (through general_spend, unit redeemer — the real authorisation is the
        // pool.pool withdraw it defers to) and the funder (fees + collateral + change).
        tx.collectFrom(request.poolUtxo(), GENERAL_SPEND_REDEEMER);
        tx.collectFrom(request.funderUtxo());

        // The burn: −1 of the pool NFT under the pool.pool policy. The mint redeemer names the pool
        // UTxO's own reference as inputRef — spent by construction — which check_mint's isInputRefSpent
        // looks for with find_input.
        TransactionInput poolRef = inputOf(request.poolUtxo());
        tx.mintAsset(registry.getPoolScript(),
                List.of(new Asset("0x" + request.poolAssetNameHex(), BigInteger.valueOf(-1))),
                PoolTxEncoder.poolMintRedeemer(configRefIndex, poolRef));

        // The two withdraw-0 legs. pool.pool(Cancel) is what general_spend requires present;
        // pool_cancel_action carries the CancelData and does the real work.
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

        // Reference inputs: config + the three published reference scripts.
        tx.readFrom(inputOf(request.configUtxo()));
        for (Utxo refScript : request.referenceScriptUtxos()) {
            tx.readFrom(inputOf(refScript));
        }

        return tx.withChangeAddress(request.funderAddress());
    }

    private Transaction complete(Request request, ScriptTx tx) {
        try {
            // Third argument (TransactionProcessor) stays null; see the class javadoc. No evaluator is
            // wired in either, so the redeemers keep placeholder ex-units.
            return new QuickTxBuilder(utxoSupplier, protocolParamsSupplier, null)
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
                    .withReferenceScripts(referencedScripts())
                    .removeDuplicateScriptWitnesses(true)
                    // Offline: cardano-client-lib would otherwise walk every reference input for a script
                    // to fetch and NPE on the missing supplier.
                    .withScriptSupplier(scriptHash -> Optional.empty())
                    // No validFrom / validTo — pool_cancel_action reads no validity range.
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("cannot build the pool-cancel transaction", e);
        }
    }

    private PlutusScript[] referencedScripts() {
        return new PlutusScript[]{
                registry.getPoolSpendScript(),
                registry.getPoolScript(),
                registry.getPoolCancelActionScript()};
    }

    // ---- V5: re-derive every claim from the finished body -----------------------------------------

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

        // The mint redeemer's inputRef is among the body's inputs — check_mint's isInputRefSpent.
        TransactionInput mintInputRef = mintRedeemerInputRef(transaction);
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
    private static TransactionInput mintRedeemerInputRef(Transaction tx) {
        List<Redeemer> mintRedeemers = tx.getWitnessSet().getRedeemers().stream()
                .filter(r -> r.getTag() == RedeemerTag.Mint)
                .toList();
        if (mintRedeemers.size() != 1) {
            throw new IllegalStateException("expected exactly one Mint redeemer, found " + mintRedeemers.size());
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

    /** True if {@code r} is a {@code PoolWithdrawRedeemer}: two fields, the second a fieldless Constr. */
    static boolean isPoolPolicyWithdrawRedeemer(Redeemer r) {
        return r.getData() instanceof ConstrPlutusData constr
                && constr.getData().getPlutusDataList().size() == 2
                && constr.getData().getPlutusDataList().get(1) instanceof ConstrPlutusData;
    }

    private boolean poolInputHoldsNft(Request request) {
        return request.poolUtxo().getAmount().stream()
                .anyMatch(a -> (registry.getPoolPolicyId() + request.poolAssetNameHex())
                        .equalsIgnoreCase(a.getUnit()) && BigInteger.ONE.equals(a.getQuantity()));
    }

    /** Whether an input of the finished body sits at {@code poolSpendScriptHash}. */
    private boolean isAtPoolSpendCredential(TransactionInput input) {
        Utxo resolved = utxoSupplier.getTxOutput(input.getTransactionId(), input.getIndex())
                .orElseThrow(() -> new IllegalStateException(
                        "cannot resolve input " + input + " against the supplied UTxO set"));
        return new com.bloxbean.cardano.client.address.Address(resolved.getAddress())
                .getPaymentCredentialHash()
                .map(HexUtil::encodeHexString)
                .filter(hash -> hash.equals(registry.getPoolSpendScriptHash()))
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
