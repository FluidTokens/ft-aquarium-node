package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
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
 * Builds <b>TX A′</b> — the {@code Cancel} that undoes {@link RequestMintTransactionBuilder}'s TX A:
 * it spends the request UTxO, burns the request NFT, and returns the collateral to the borrower.
 *
 * <h2>Why this exists before anything is submitted</h2>
 * <b>Never lock value you have no code to unlock.</b> TX A locks roughly 5 ada and 300,000,000 tFLDT
 * base units at the request spend script. If TX A were posted and this transaction turned out not to
 * be constructible, that value would simply sit there. Building and dry-evaluating the escape hatch
 * first makes the origination reversible before it is real.
 * <p>
 * It is also the <b>first</b> transaction in this epic whose validator decodes the
 * {@code RequestDatum}: the withdraw handler does {@code expect datum: RequestDatum = inputDatum}
 * ({@code validators/request.ak:80-81}, deployed pin {@code bbe9c1a}) before it authorises anything,
 * whereas {@code check_mint} never looks at the datum at all. A green evaluation here is therefore
 * the first chain-level evidence that the datum S2 authored is well-formed.
 *
 * <h2>Three script purposes, two scripts</h2>
 * See {@link RequestTxEncoder}'s <em>Consequence D</em>. In order of redeemer tag:
 * <ol>
 *   <li><b>Spend</b> — {@code general_spend} applied to the request policy id, i.e. the script the
 *       request UTxO's address is. Its redeemer is ignored ({@code _redeemer: Data}), so the Aiken
 *       unit is passed. With an inline datum present its only check is that some withdrawal in this
 *       transaction is at {@code Script(requestPolicyId)} ({@code general_spend.ak:31-41}) — which is
 *       why leg 2 is not optional decoration but the thing that unlocks leg 1.</li>
 *   <li><b>Withdraw</b> (reward, amount 0) at the reward address of the request policy id —
 *       {@code request.request}'s {@code withdraw} handler, carrying
 *       {@code RequestWithdrawRedeemer { configRefInputIndex, [Cancel { requestId }] }}.</li>
 *   <li><b>Mint</b> (burn, −1) under the request policy id — the same {@code request.request}
 *       compiled script, carrying a {@code RequestMintRedeemer}. {@code ScriptTx} keeps one witness
 *       copy of a script attached under two purposes.</li>
 * </ol>
 * The burn's redeemer names <b>the request UTxO's own output reference</b> as its {@code inputRef}.
 * That is spent by construction — it is the input this transaction is built around — so it cannot
 * drift from the body the way a separately chosen seed could. <b>That is a property of today's
 * {@code assemble}, not of the shape</b>: a batched Cancel, or an S6 that sources the seed from
 * elsewhere, breaks it silently. {@link #assertStructure} therefore decodes the {@code inputRef}
 * back out of the finished witness set instead of taking the construction's word for it.
 *
 * <h2>What {@code check_cancel} does NOT constrain: the outputs</h2>
 * {@code check_cancel} ({@code request.ak:197-217}) is three conjuncts and <b>not one of them is an
 * output</b>: the spent input holds exactly one of the named request NFT, the mint field burns
 * exactly one of it, and the borrower authorised the action. It says nothing about where the
 * collateral goes — by address, by datum or by value. <b>"The collateral comes back to the borrower"
 * is a property of this builder's construction and of nothing on chain.</b> The validator would
 * accept a Cancel that hands all 300,000,000 tFLDT to a stranger. That makes the change address the
 * single place where a wrong value silently loses real money once S6 submits, so it is asserted on
 * the finished body by {@link RequestCancelDryEvalTest} rather than trusted here.
 *
 * <h2>No validity interval</h2>
 * {@code check_cancel} reads no validity range, so this transaction is built with <b>no
 * {@code validFrom} and no {@code validTo}</b>. Inventing one would impose a constraint the validator
 * does not, and would have to be justified against something. ({@code CancelAfterExpiration} is the
 * branch that does read the range — {@code request.ak:219-245} — and it is out of scope.)
 *
 * <h2>Structurally incapable of submitting</h2>
 * The {@link QuickTxBuilder} below is constructed with a <b>null</b> {@code TransactionProcessor} —
 * the third constructor argument, and the only thing in cardano-client-lib that can put a
 * transaction on a network. A builder that never has one cannot submit, whatever a caller asks of
 * it. No evaluator is wired in either, so every redeemer carries cardano-client-lib's placeholder
 * ex-units and the fee is understated. That is fine for an offline rig and is <b>not</b> fine for
 * submission; S6 has to price it against a real evaluator first.
 */
public final class RequestCancelTransactionBuilder {

    /**
     * How many {@code Cancel} actions this builder emits — one, because it cancels one request. It is
     * a named constant rather than a literal because two places have to agree on it: the redeemer's
     * {@code actionsForEachInput} list, and the count of inputs at the request spend credential that
     * {@link #assertStructure} re-derives from the finished body.
     */
    private static final int CANCEL_ACTIONS = 1;

    private final LoansContractRegistry registry;
    private final UtxoSupplier utxoSupplier;
    private final ProtocolParamsSupplier protocolParamsSupplier;

    public RequestCancelTransactionBuilder(LoansContractRegistry registry,
                                           UtxoSupplier utxoSupplier,
                                           ProtocolParamsSupplier protocolParamsSupplier) {
        this.registry = registry;
        this.utxoSupplier = utxoSupplier;
        this.protocolParamsSupplier = protocolParamsSupplier;
    }

    /**
     * Everything TX A′ needs.
     *
     * @param requestUtxo             the request UTxO TX A produced — see
     *                                {@link RequestFixtures#requestUtxoFrom}, which derives it from a
     *                                real TX A rather than hand-rolling one
     * @param configUtxo              the main config reference input; <b>mandatory</b>, because both
     *                                the withdraw handler ({@code request.ak:54-59}) and
     *                                {@code check_mint} ({@code :144-149}) resolve the config through
     *                                {@code get_config_as_data_list}, which hard-{@code expect}s the
     *                                config NFT and an inline datum ({@code utils.ak:36-49})
     * @param borrowerAddress         fee payer, collateral payer and change address — and therefore,
     *                                by construction and not by validation, where the collateral goes
     * @param borrowerPaymentKeyHash  the borrower's 28-byte payment key hash, declared as a required
     *                                signer so {@code authorize_action}'s
     *                                {@code list.has(extra_signatories, hash)} can find it.
     *                                {@code extra_signatories} is the body's {@code required_signers}
     *                                field (key 14), <b>not</b> the witness set, so no key and no
     *                                signature are involved
     * @param requestAssetNameHex     the 29-byte request NFT name — the {@code requestId} of the
     *                                {@code Cancel} action and the token the mint field burns
     */
    public record Request(Utxo requestUtxo,
                          Utxo configUtxo,
                          String borrowerAddress,
                          byte[] borrowerPaymentKeyHash,
                          String requestAssetNameHex) {
    }

    /**
     * The four knobs {@link RequestCancelDryEvalTest}'s adversarial mutations turn. Every field of
     * the default instance is inert, so {@link #build} and a default-mutated build are the same
     * transaction.
     *
     * @param omitRequiredSigner      drop the borrower from {@code required_signers} (M1)
     * @param cancelRequestIdOverride what the {@code Cancel} action claims the request id is (M2)
     * @param mintInputRefOverride    what the mint redeemer claims was spent (M3)
     * @param omitWithdrawal          build with no withdrawal leg and no reward validator (M4)
     */
    record Mutation(boolean omitRequiredSigner,
                    String cancelRequestIdOverride,
                    TransactionInput mintInputRefOverride,
                    boolean omitWithdrawal) {

        static Mutation none() {
            return new Mutation(false, null, null, false);
        }
    }

    /**
     * Assembles and completes TX A′, then re-derives every index it claims from the finished body.
     */
    public Transaction build(Request request) {
        Transaction built = complete(request, assemble(request, Mutation.none()), Mutation.none());
        assertStructure(request, built);
        return built;
    }

    /**
     * As {@link #build}, but with one of the four adversarial knobs turned — the seam
     * {@link RequestCancelDryEvalTest} uses to hand the machine a transaction this builder would
     * never emit on its own.
     * <p>
     * The post-build structural assertions are <b>skipped</b> here: their whole job is to refuse the
     * transactions the mutations exist to produce, so running them would turn a red evaluation into a
     * green {@code IllegalStateException} and prove nothing about the validators.
     */
    Transaction buildWithMutation(Request request, Mutation mutation) {
        return complete(request, assemble(request, mutation), mutation);
    }

    // ---- assembly ---------------------------------------------------------------------------------

    private ScriptTx assemble(Request request, Mutation mutation) {
        ScriptTx tx = new ScriptTx();

        TransactionInput requestRef = new TransactionInput(request.requestUtxo().getTxHash(),
                request.requestUtxo().getOutputIndex());
        String cancelRequestId = mutation.cancelRequestIdOverride() != null
                ? mutation.cancelRequestIdOverride()
                : request.requestAssetNameHex();
        TransactionInput mintInputRef = mutation.mintInputRefOverride() != null
                ? mutation.mintInputRefOverride()
                : requestRef;

        // The spend leg. general_spend ignores its redeemer, so the Aiken unit goes in; the inline
        // datum already on the UTxO is what steers it into the withdrawal branch.
        tx.collectFrom(request.requestUtxo(), RequestTxEncoder.unit());

        // The burn leg. No receiver overload for the same reason TX A avoids it: that overload
        // re-derives the amount through Amount.asset(policyId, name, value) and re-hex-encodes the
        // name. A burn has no receiver anyway.
        tx.mintAsset(registry.getRequestScript(),
                List.of(new Asset("0x" + request.requestAssetNameHex(), BigInteger.valueOf(-1))),
                RequestTxEncoder.requestMintRedeemer(0, mintInputRef));

        // The withdraw leg — the one that unlocks the spend leg. One action, because this builder
        // cancels one request; assertStructure re-derives the matching input count from the body.
        List<PlutusData> actions = List.of(RequestTxEncoder.cancelAction(cancelRequestId));
        if (actions.size() != CANCEL_ACTIONS) {
            throw new IllegalStateException("actionsForEachInput must have " + CANCEL_ACTIONS
                    + " element(s), had " + actions.size());
        }
        if (!mutation.omitWithdrawal()) {
            tx.withdraw(rewardAddress(registry.getRequestPolicyId()), BigInteger.ZERO,
                    RequestTxEncoder.requestWithdrawRedeemer(0, actions));
        }

        tx.readFrom(new TransactionInput(request.configUtxo().getTxHash(),
                request.configUtxo().getOutputIndex()));

        tx.attachSpendingValidator(registry.getRequestSpendScript());
        if (!mutation.omitWithdrawal()) {
            tx.attachRewardValidator(registry.getRequestScript());
        }

        return tx.withChangeAddress(request.borrowerAddress());
    }

    private Transaction complete(Request request, ScriptTx tx, Mutation mutation) {
        try {
            QuickTxBuilder.TxContext context =
                    // The third argument is the TransactionProcessor and it stays null; see the
                    // class javadoc. No evaluator either, so the redeemers keep placeholder ex-units.
                    new QuickTxBuilder(utxoSupplier, protocolParamsSupplier, null)
                            .compose(tx)
                            .feePayer(request.borrowerAddress())
                            .collateralPayer(request.borrowerAddress())
                            // A script supplier that resolves nothing: cardano-client-lib otherwise
                            // walks every reference input looking for a script to fetch, and this
                            // builder has no remote source. Both Cancel validators travel in the
                            // witness set.
                            .withScriptSupplier(scriptHash -> Optional.empty())
                            .mergeOutputs(false);
            if (!mutation.omitRequiredSigner()) {
                context = context.withRequiredSigners(request.borrowerPaymentKeyHash());
            }
            // No validFrom / validTo — check_cancel reads no validity range.
            return context.build();
        } catch (Exception e) {
            throw new IllegalStateException("cannot build the request-cancel transaction", e);
        }
    }

    // ---- post-build structural assertions (V5: re-derive, never predict) ---------------------------

    /**
     * Every index this transaction claims, re-derived from the finished body rather than assumed.
     * <p>
     * The two {@code configRefInputIndex} values are <b>independent</b> — the withdraw handler and
     * {@code check_mint} each resolve the config themselves — so both are checked against the same
     * canonically sorted reference-input list. With one reference input the answer is 0, asserted
     * rather than taken on faith, because a second reference input appearing here would silently move
     * it. The request-input count is checked too: {@code actionsForEachInput} is positional against
     * the <em>filtered</em> request-input list, so one extra input at the request spend credential
     * would silently make the single {@code Cancel} action answer for the wrong one.
     * <p>
     * <b>That on-chain consequence is an abort, not a {@code False}.</b> {@code utils.indexed_all}
     * ({@code lib/fluidtokens/utils.ak:203-209}) calls the predicate at every index of the filtered
     * request-input list, and the predicate's first move is
     * {@code safe_list_at(redeemer.actionsForEachInput, index)} ({@code request.ak:82}) —
     * {@code safe_list_at} → {@code do_list_at} → {@code builtin.head_list} on an emptied list
     * ({@code utils.ak:113-124}), which aborts rather than returning. The upstream source carries a
     * comment claiming the opposite at {@code request.ak:76} ("we DO NOT need to ensure that the
     * number of actions is equal to the number of inputs"); it holds only in the direction of
     * <em>more</em> actions than inputs, never fewer.
     * <p>
     * Both re-derivations below read the <b>finished artefact</b>. In particular the mint redeemer's
     * {@code inputRef} is decoded back out of the witness set rather than re-derived from
     * {@link Request}: re-deriving it would compare {@code build}'s own construction with itself and
     * the check could never fail, while the thing that has to hold is that whatever ended up in the
     * redeemer is among the inputs — which is exactly what {@code check_mint}'s
     * {@code isInputRefSpent} ({@code request.ak:161-162}, conjoined at {@code :191-194}) evaluates.
     */
    void assertStructure(Request request, Transaction built) {
        int configIndex = configRefIndexOf(built, request.configUtxo());
        if (configIndex != 0) {
            throw new IllegalStateException(
                    "both redeemers claim configRefInputIndex 0 but the config reference input "
                            + "landed at " + configIndex + " in the finished body");
        }

        TransactionInput mintInputRef = mintRedeemerInputRef(built);
        if (!built.getBody().getInputs().contains(mintInputRef)) {
            throw new IllegalStateException("the mint redeemer's inputRef " + mintInputRef
                    + " is not among the body's inputs, so check_mint's isInputRefSpent would fail");
        }

        // Counted by resolving every input's address against the same UtxoSupplier the build used,
        // not by pattern-matching the one input we happen to know about: an extra request UTxO
        // arriving through coin selection is exactly the case this has to catch.
        long atRequestCredential = built.getBody().getInputs().stream()
                .filter(this::isAtRequestSpendCredential)
                .count();
        if (atRequestCredential != CANCEL_ACTIONS) {
            throw new IllegalStateException("actionsForEachInput has " + CANCEL_ACTIONS
                    + " element(s) but the finished body has " + atRequestCredential
                    + " input(s) at the request spend credential — indexed_all indexes "
                    + "actionsForEachInput by position in that filtered list, and safe_list_at "
                    + "aborts once it runs off the end of it");
        }
    }

    /**
     * The {@code inputRef} the finished transaction's <b>mint redeemer actually carries</b>, decoded
     * out of the witness set.
     * <p>
     * {@code RequestMintRedeemer { configRefInputIndex, inputRef } } is constructor 0 with two fields
     * ({@link RequestTxEncoder#requestMintRedeemer}), and {@code inputRef} is an
     * {@code OutputReference} constr whose {@code transaction_id} is a <b>flat</b> {@code ByteArray}
     * in PlutusV3 — not nested in a wrapper constructor — followed by an {@code Int}
     * {@code output_index}. Every shape assumption is checked rather than cast blindly, because a
     * decoder that silently mis-reads would put {@link #assertStructure} back where it started.
     */
    private static TransactionInput mintRedeemerInputRef(Transaction built) {
        List<Redeemer> mintRedeemers = built.getWitnessSet().getRedeemers().stream()
                .filter(redeemer -> redeemer.getTag() == RedeemerTag.Mint)
                .toList();
        if (mintRedeemers.size() != 1) {
            throw new IllegalStateException("expected exactly one Mint redeemer in the finished "
                    + "witness set, found " + mintRedeemers.size());
        }
        List<PlutusData> redeemerFields = fieldsOf(mintRedeemers.get(0).getData(),
                "the mint redeemer");
        if (redeemerFields.size() != 2) {
            throw new IllegalStateException("RequestMintRedeemer has 2 fields, the finished mint "
                    + "redeemer had " + redeemerFields.size());
        }
        List<PlutusData> refFields = fieldsOf(redeemerFields.get(1), "the mint redeemer's inputRef");
        if (refFields.size() != 2) {
            throw new IllegalStateException("OutputReference has 2 fields, the mint redeemer's "
                    + "inputRef had " + refFields.size());
        }
        if (!(refFields.get(0) instanceof BytesPlutusData transactionId)) {
            throw new IllegalStateException("OutputReference.transaction_id must be a flat ByteArray, "
                    + "the mint redeemer's inputRef carried " + refFields.get(0).getClass().getSimpleName());
        }
        if (!(refFields.get(1) instanceof BigIntPlutusData outputIndex)) {
            throw new IllegalStateException("OutputReference.output_index must be an Int, the mint "
                    + "redeemer's inputRef carried " + refFields.get(1).getClass().getSimpleName());
        }
        return new TransactionInput(HexUtil.encodeHexString(transactionId.getValue()),
                outputIndex.getValue().intValueExact());
    }

    /** The fields of a {@code Constr}, refusing anything that is not one. */
    private static List<PlutusData> fieldsOf(PlutusData data, String what) {
        if (!(data instanceof ConstrPlutusData constr)) {
            throw new IllegalStateException(what + " must be a Constr, was "
                    + (data == null ? "null" : data.getClass().getSimpleName()));
        }
        return constr.getData().getPlutusDataList();
    }

    /** Whether an input of the finished body sits at {@code requestSpendScriptHash}. */
    boolean isAtRequestSpendCredential(TransactionInput input) {
        Utxo resolved = utxoSupplier.getTxOutput(input.getTransactionId(), input.getIndex())
                .orElseThrow(() -> new IllegalStateException(
                        "cannot resolve input " + input + " against the supplied UTxO set"));
        return new Address(resolved.getAddress()).getPaymentCredentialHash()
                .map(HexUtil::encodeHexString)
                .filter(hash -> hash.equals(registry.getRequestSpendScriptHash()))
                .isPresent();
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

    /** The preview reward address of a script hash — where a withdraw-0 leg is made. */
    static String rewardAddress(String scriptHash) {
        return LoanFixtures.rewardAddress(scriptHash);
    }
}
