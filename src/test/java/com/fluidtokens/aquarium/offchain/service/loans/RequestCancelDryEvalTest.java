package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>TX A′ — the Cancel</b>, the escape hatch that undoes {@link RequestMintTransactionBuilder}'s
 * TX A: it spends the request UTxO, burns the request NFT and returns the collateral to the
 * borrower. Handed to the real PlutusV3 machine and run against the <em>real deployed</em>
 * {@code request.request} and {@code general_spend} validators, in the discipline of
 * {@link RequestMintDryEvalTest} and {@link LiquidateDryEvalTest}. Everything is synthetic and the
 * test runs cold: no network, no key, no wallet. See {@link EvalFixtures} for the rig and its
 * <em>Harness limitations</em>.
 *
 * <h2>Why this lands before anything is submitted</h2>
 * <b>Never lock value you have no code to unlock.</b> TX A locks ~5 ada and 300,000,000 tFLDT base
 * units at the request spend script; a TX A that succeeded while its Cancel turned out not to be
 * constructible would simply strand them.
 *
 * <h2>This is the first evaluation-level arbitration of the {@code RequestDatum}</h2>
 * {@code check_mint} never reads the request output's datum ({@code validators/request.ak:137-195},
 * deployed pin {@code bbe9c1a}) — {@link RequestMintDryEvalTest}'s <em>Consequence B</em> — so
 * nothing on chain had yet looked at what S2 wrote. The withdraw handler does:
 * {@code expect datum: RequestDatum = inputDatum} ({@code request.ak:80-81}) runs <em>before</em> it
 * authorises anything. <b>A green run of this class is therefore the first chain-level evidence that
 * the datum S2 authored is well-formed</b> — a decode by the deployed validator, not a comparison
 * against our own encoder.
 * <p>
 * The corollary matters for whoever changes this next: if the positive test ever fails with the
 * {@code Withdraw} redeemer refusing, the datum is a live suspect, not merely the assembly. The
 * {@code REQUEST_DATUM_HEX} golden is pinned by two independent fixtures and changing it is an
 * epic-level decision — report the finding, do not "fix" the datum.
 *
 * <h2>Three script purposes, and why the withdrawal is not decoration</h2>
 * See {@link RequestTxEncoder}'s <em>Consequence D</em> and
 * {@link RequestCancelTransactionBuilder}'s class javadoc. Spend ({@code general_spend}), Withdraw
 * ({@code request.request}, reward, amount 0) and Mint ({@code request.request} again, burning −1).
 * The spend leg's only check is that a withdrawal exists at {@code Script(requestPolicyId)}
 * ({@code general_spend.ak:31-41}), so removing the withdrawal breaks the <em>spend</em> — which is
 * M4. Note the vocabulary mismatch: cardano-client-lib calls the third purpose
 * {@link RedeemerTag#Reward} while the Aiken evaluator's message prints {@code tag: "Withdraw"}.
 *
 * <h2>The outputs are ours, not the validator's</h2>
 * {@code check_cancel} ({@code request.ak:197-217}) is three conjuncts and <b>not one of them is an
 * output</b>. The validator would equally accept a Cancel that sends all 300,000,000 tFLDT to a
 * stranger. {@link #theCancelEvaluatesAgainstTheDeployedRequestValidators}'s collateral assertion is
 * therefore a check on <em>this repo's construction</em> — that the change address is the borrower —
 * and it is asserted here precisely because the chain will not do it for us when S6 submits.
 *
 * <h2>Assertions read the artefact, never the report</h2>
 * The {@link EvaluationResult} list is used only for the redeemer count and tags. Every structural
 * claim is read off the <em>deserialised</em> {@link Transaction}. And no assertion depends on an
 * output's absolute position: cardano-client-lib adds an output to carry a withdrawal's receiver on
 * top of whatever change it appends, so outputs are located by address.
 *
 * <h2>The rig can fail</h2>
 * Four mutations, each of which must be refused by a <em>named</em> redeemer — a bare "it failed" is
 * satisfied by a fault anywhere earlier, and the evaluator reports only the first refusal
 * ({@link EvalFixtures} limitation 1) in the order Spend(0) &lt; Mint(1) &lt; Reward/Withdraw. Each
 * evaluator message is transcribed verbatim into its test's javadoc so the claim is checkable without
 * re-running anything. <b>Comment the mutations out and this class is no longer green evidence of
 * anything.</b>
 *
 * <h2>And the guards can fail too</h2>
 * A last section probes {@link RequestCancelTransactionBuilder}'s own post-build self-checks
 * directly. They are the only thing standing between a malformed Cancel and a paid-for refusal on
 * chain, and a self-check that can never fire is worse than none — it reads as coverage. Each of the
 * three tests there is falsifiable in the same sense the mutations are: neutralise the guard it
 * probes and it goes red.
 */
@Slf4j
class RequestCancelDryEvalTest {

    private static final LoansContractRegistry REGISTRY = LoanFixtures.registry();

    /** The real ledger limit. Not the rig's raised {@link EvalFixtures#protocolParams()} ceiling. */
    private static final int MAX_TX_SIZE = 16_384;

    private static final String BORROWER = LoanFixtures.botAddress();

    /** The same figure {@link RequestMintDryEvalTest} locks; not load-bearing here. */
    private static final long REQUEST_LOVELACE = 5_000_000L;

    // ======================================================================================
    // The positive case
    // ======================================================================================

    /**
     * The request UTxO spent, the request NFT burnt, the collateral back at the borrower.
     * <p>
     * <b>Measured size: 11,526 bytes</b> against a real {@code maxTxSize} of {@value #MAX_TX_SIZE}.
     * This is the largest transaction in the epic, and for a structural reason: <em>both</em>
     * validators travel in the witness set, because no reference script has been published on preview
     * for either — {@code request.request} is 9,628 bytes unapplied in {@code loans-v4.plutus.json}
     * and {@code general_spend} is 1,073. The figure is taken from {@link Transaction#serialize()} on
     * the built transaction, the CBOR the ledger would count; it was measured, never predicted, and
     * never copied from an earlier run.
     * <p>
     * <b>What this number is not.</b> The transaction is unpriced and unsigned — no evaluator is
     * wired into {@link RequestCancelTransactionBuilder}, so every redeemer carries placeholder
     * ex-units, and no vkey witness is present. Real ex-units and one witness add on the order of a
     * couple of hundred bytes against 4,858 of remaining headroom, so the verdict does not move.
     * Publishing a reference script for {@code request.request} would still be the right call before
     * mainnet.
     */
    @Test
    void theCancelEvaluatesAgainstTheDeployedRequestValidators() {
        Utxo requestUtxo = requestUtxo();
        List<Utxo> universe = RequestFixtures.cancelUniverse(BORROWER, requestUtxo);
        Transaction tx = cancelBuilder(universe).build(cancelRequest(requestUtxo));

        List<EvaluationResult> results = EvalFixtures.evaluate(tx, universe, REGISTRY);

        // The only things read off the report: how many scripts ran, and which purposes.
        assertEquals(3, results.size(), "a Cancel runs three script purposes: spend, mint, withdraw");
        assertEquals(Set.of(RedeemerTag.Spend, RedeemerTag.Mint, RedeemerTag.Reward),
                results.stream().map(EvaluationResult::getRedeemerTag)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)),
                "cardano-client-lib names the withdrawal purpose Reward; the Aiken evaluator's "
                        + "message prints tag: \"Withdraw\" for the same thing");

        // Everything else is read off the artefact, after a full CBOR round trip.
        Transaction onChain = roundTrip(tx);

        // --- the burn ---
        List<MultiAsset> mint = onChain.getBody().getMint();
        assertEquals(1, mint.size(), "one policy in the mint field: the request policy");
        assertEquals(REGISTRY.getRequestPolicyId(), mint.get(0).getPolicyId());
        assertEquals(1, mint.get(0).getAssets().size(), "one asset name under the request policy");
        Asset burnt = mint.get(0).getAssets().get(0);
        assertEquals(requestAssetName(), strip(burnt.getNameAsHex()));
        assertEquals(BigInteger.valueOf(-1), burnt.getValue(),
                "check_cancel wants quantity_of(self.mint, requestPolicyId, requestId) == -1 exactly");

        // --- the request UTxO really is spent, and it is the only request input ---
        TransactionInput requestRef = new TransactionInput(requestUtxo.getTxHash(),
                requestUtxo.getOutputIndex());
        assertTrue(onChain.getBody().getInputs().contains(requestRef),
                "the mint redeemer's inputRef is the request UTxO's own reference, so it must be spent");
        assertEquals(1, onChain.getBody().getInputs().stream()
                        .filter(input -> atRequestAddress(universe, input)).count(),
                "exactly one input at the request spend address — actionsForEachInput is positional "
                        + "against the filtered request-input list and carries a single Cancel");

        // --- the withdrawal that unlocks the spend leg ---
        List<Withdrawal> withdrawals = onChain.getBody().getWithdrawals();
        assertEquals(1, withdrawals.size(), "exactly one withdrawal");
        assertEquals(LoanFixtures.rewardAddress(REGISTRY.getRequestPolicyId()),
                withdrawals.get(0).getRewardAddress(),
                "general_spend matches the withdrawal against Script(requestPolicyId)");
        assertEquals(BigInteger.ZERO, withdrawals.get(0).getCoin(),
                "a withdraw-0 invocation: the script runs, no rewards move");

        // --- the reference input ---
        assertEquals(List.of(new TransactionInput(RequestFixtures.TX_CONFIG, 0)),
                onChain.getBody().getReferenceInputs(),
                "one reference input, the config — which is what makes both configRefInputIndex 0 right");

        // --- authorisation ---
        byte[] borrowerHash = borrowerPaymentKeyHash();
        assertTrue(onChain.getBody().getRequiredSigners().stream()
                        .anyMatch(signer -> HexUtil.encodeHexString(signer)
                                .equals(HexUtil.encodeHexString(borrowerHash))),
                "authorize_action is list.has(extra_signatories, hash) and extra_signatories is the "
                        + "body's required_signers field, not the witness set");

        // --- I6: the collateral comes home. OUR guarantee, not the validator's. ---
        BigInteger toBorrower = BigInteger.ZERO;
        for (TransactionOutput output : onChain.getBody().getOutputs()) {
            if (BORROWER.equals(output.getAddress())) {
                toBorrower = toBorrower.add(quantityOf(output, RequestFixtures.TFLDT.policyId(),
                        RequestFixtures.TFLDT.assetName()));
            }
        }
        assertEquals(BigInteger.valueOf(RequestFixtures.COLLATERAL_QUANTITY), toBorrower,
                "every one of the 300,000,000 tFLDT base units must land in an output addressed to "
                        + "the borrower — check_cancel constrains no output at all, so this is the "
                        + "off-chain construction's guarantee and nothing else's");
        for (TransactionOutput output : onChain.getBody().getOutputs()) {
            assertEquals(BigInteger.ZERO,
                    quantityOf(output, REGISTRY.getRequestPolicyId(), requestAssetName()),
                    "the request NFT was burnt; no output may still carry it");
        }

        // --- size ---
        int size = serializedSize(tx);
        log.info("Request cancel TX A': {} bytes (maxTxSize {})", size, MAX_TX_SIZE);
        assertTrue(size < MAX_TX_SIZE,
                "the request cancel is " + size + " bytes, maxTxSize is " + MAX_TX_SIZE);
        assertEquals(2, tx.getWitnessSet().getPlutusV3Scripts().size(),
                "both request.request and general_spend travel in the witness set — no reference "
                        + "script is published for either, which is why this is the epic's largest tx");
    }

    // ======================================================================================
    // Falsifiability — four mutations, each refused by a named redeemer
    // ======================================================================================

    /**
     * <b>M1 — no required signer.</b> The borrower's payment key hash is dropped from the body's
     * {@code required_signers}, everything else left alone. This kills
     * {@code authorize_action(create_auth(CardanoSignature { hash }, ..))}, which
     * {@code lib/fluidtokens/authorizer.ak:36-38} reduces to
     * {@code list.has(extra_signatories, hash)}.
     * <p>
     * <b>This is also the proof of harness.</b> {@link EvalFixtures} checks no ledger rules, so it was
     * an open question whether the Aiken evaluator populates {@code extra_signatories} from the body's
     * {@code required_signers} at all. If this mutation ever <em>passes</em>, the positive test's
     * authorisation claim is vacuous and the rig proves less than it appears to. It does not pass —
     * the evaluator said, verbatim:
     * <pre>
     * ApiException: Error evaluating transaction
     * TxEvaluationException: AIKEN: RedeemerError { tag: "Withdraw", index: 0,
     *     err: Machine(EvaluationFailure, ExBudget { mem: 377348, cpu: 118158996 }, []) }
     * </pre>
     * so {@code required_signers} is modelled and the authorisation conjunct is genuinely exercised.
     * <p>
     * <b>It proves one more thing, and it is the bigger one: the handler is actually reached.</b>
     * {@code get_inputs_from_smart_credential} filters the inputs against
     * {@code Script(requestSpendScriptHash)}, and that hash is read from the <em>config datum</em>,
     * index 9 ({@code request.ak:63-64}) — not from this repo's {@link LoansContractRegistry}. Had the
     * two disagreed, {@code requestInputs} would be the empty list, {@code utils.indexed_all([], _)}
     * returns {@code True} for an empty list, and the whole withdraw handler — {@code check_cancel}
     * included — would pass <em>vacuously</em>. A refusal at {@code Withdraw} can only come from
     * inside the predicate, so it is also evidence that the filtered request-input list is non-empty
     * and that the positive test's green {@code Withdraw} is arbitration rather than an empty fold.
     */
    @Test
    void aMissingRequiredSignerIsRejectedByTheRequestWithdrawScript() {
        Utxo requestUtxo = requestUtxo();
        List<Utxo> universe = RequestFixtures.cancelUniverse(BORROWER, requestUtxo);

        Transaction mutated = cancelBuilder(universe).buildWithMutation(cancelRequest(requestUtxo),
                new RequestCancelTransactionBuilder.Mutation(true, null, null, false));
        assertTrue(mutated.getBody().getRequiredSigners() == null
                        || mutated.getBody().getRequiredSigners().isEmpty(),
                "the mutation must really drop the required signer, or it proves nothing");

        assertRefusedBy(mutated, universe, "Withdraw", 0,
                "a Cancel the borrower did not authorise");
    }

    /**
     * <b>M2 — the wrong {@code requestId} in the {@code Cancel} action.</b> The action names
     * {@code 01 ‖ hash} where the real NFT is {@code 00 ‖ hash}; the mint field still burns the real
     * name, so {@code check_mint} is untouched and only {@code check_cancel}'s two {@code quantity_of}
     * conjuncts break — the spent input holds none of the claimed token, and neither does the mint.
     * <p>
     * The evaluator said, verbatim:
     * <pre>
     * ApiException: Error evaluating transaction
     * TxEvaluationException: AIKEN: RedeemerError { tag: "Withdraw", index: 0,
     *     err: Machine(EvaluationFailure, ExBudget { mem: 335152, cpu: 105342592 }, []) }
     * </pre>
     */
    @Test
    void aCancelActionNamingTheWrongRequestIdIsRejectedByTheRequestWithdrawScript() {
        Utxo requestUtxo = requestUtxo();
        List<Utxo> universe = RequestFixtures.cancelUniverse(BORROWER, requestUtxo);

        String wrongId = "01" + requestAssetName().substring(2);
        assertFalse(wrongId.equals(requestAssetName()),
                "the override must really differ from the real asset name, or it proves nothing");

        Transaction mutated = cancelBuilder(universe).buildWithMutation(cancelRequest(requestUtxo),
                new RequestCancelTransactionBuilder.Mutation(false, wrongId, null, false));

        assertRefusedBy(mutated, universe, "Withdraw", 0,
                "a Cancel action naming a request id the input does not hold");
    }

    /**
     * <b>M3 — the mint redeemer's {@code inputRef} names an unspent UTxO.</b> The findings §11.3 pin:
     * a <em>burn</em> really does run {@code check_mint}, and really does need a spent seed. The
     * token accounting degenerates — {@code check_mint} filters the minted tokens to
     * {@code quantity > 0} ({@code request.ak:164-168}), and a burn leaves that list empty — but
     * {@code isInputRefSpent} sits outside the filter ({@code :161-162}) and is conjoined at
     * {@code :191-194}. So this is the one conjunct left standing, and it is enough to refuse.
     * <p>
     * The evaluator said, verbatim:
     * <pre>
     * ApiException: Error evaluating transaction
     * TxEvaluationException: AIKEN: RedeemerError { tag: "Mint", index: 0,
     *     err: Machine(EvaluationFailure, ExBudget { mem: 126082, cpu: 49623014 }, []) }
     * </pre>
     */
    @Test
    void anUnspentMintInputRefIsRejectedByTheRequestPolicyEvenOnABurn() {
        Utxo requestUtxo = requestUtxo();
        List<Utxo> universe = RequestFixtures.cancelUniverse(BORROWER, requestUtxo);

        TransactionInput unspent = new TransactionInput("cd".repeat(32), 3);
        Transaction clean = roundTrip(cancelBuilder(universe).build(cancelRequest(requestUtxo)));
        assertFalse(clean.getBody().getInputs().contains(unspent),
                "the mutation's inputRef must really be absent from the inputs");

        Transaction mutated = cancelBuilder(universe).buildWithMutation(cancelRequest(requestUtxo),
                new RequestCancelTransactionBuilder.Mutation(false, null, unspent, false));

        assertRefusedBy(mutated, universe, "Mint", 0,
                "a burn whose redeemer names an inputRef the transaction does not spend");
    }

    /**
     * <b>M4 — no withdrawal.</b> Both the withdraw leg and its reward validator are dropped, so
     * {@code general_spend}'s {@code list.any(self.withdrawals, ..)} ({@code general_spend.ak:31-41})
     * finds nothing to match against {@code Script(requestPolicyId)}. This is the <em>only</em>
     * mutation that proves the withdraw-0 coupling the whole smart-tokens pattern rests on: without
     * it, a green positive test would be equally consistent with a spend validator that checks
     * nothing.
     * <p>
     * Note the refusal comes from {@code Spend}, at the lowest redeemer index of the three, and its
     * budget is tiny — {@code general_spend} is a small script and it fails almost immediately.
     * <p>
     * The evaluator said, verbatim:
     * <pre>
     * ApiException: Error evaluating transaction
     * TxEvaluationException: AIKEN: RedeemerError { tag: "Spend", index: 0,
     *     err: Machine(EvaluationFailure, ExBudget { mem: 1001, cpu: 3405977 }, []) }
     * </pre>
     */
    @Test
    void removingTheWithdrawalIsRejectedByTheGeneralSpendWrapper() {
        Utxo requestUtxo = requestUtxo();
        List<Utxo> universe = RequestFixtures.cancelUniverse(BORROWER, requestUtxo);

        Transaction mutated = cancelBuilder(universe).buildWithMutation(cancelRequest(requestUtxo),
                new RequestCancelTransactionBuilder.Mutation(false, null, null, true));
        assertTrue(mutated.getBody().getWithdrawals() == null
                        || mutated.getBody().getWithdrawals().isEmpty(),
                "the mutation must really drop the withdrawal, or it proves nothing");

        assertRefusedBy(mutated, universe, "Spend", 0,
                "a request spend with no withdrawal at the request policy credential");
    }

    // ======================================================================================
    // The builder's own structural self-checks — guards need defending too
    // ======================================================================================

    /**
     * <b>{@code assertStructure} refuses a mint redeemer whose {@code inputRef} is not spent.</b>
     * <p>
     * This is the guard that stands between S6 (or a batched Cancel) sourcing the {@code inputRef}
     * from somewhere other than the request UTxO — a seed, the borrower's spare, the first of two
     * request inputs — and a fee paid for a transaction the node refuses at
     * {@code isInputRefSpent} ({@code request.ak:161-162}, conjoined at {@code :191-194}).
     * <p>
     * The transaction fed to it is the real M3 artefact: {@link RequestCancelTransactionBuilder}
     * built with {@code mintInputRefOverride}, so the {@code inputRef} in the finished witness set
     * genuinely names a UTxO the body does not spend. The refusal must therefore come from
     * <b>decoding the witness set</b> — a guard that re-derived the reference from the {@code Request}
     * record instead would find the request UTxO among the inputs, stay silent, and let this
     * transaction through. The assertion on the message pins that: it must name {@code cd..cd/3},
     * the value the redeemer carries, not the request UTxO's own hash.
     * <p>
     * Note the contrast with {@link #anUnspentMintInputRefIsRejectedByTheRequestPolicyEvenOnABurn},
     * which hands the same transaction to the evaluator. That one proves the <em>chain</em> refuses
     * it; this one proves <em>we</em> refuse it first, before anything is signed.
     */
    @Test
    void theSelfCheckRefusesAMintRedeemerNamingAnUnspentInputRef() {
        Utxo requestUtxo = requestUtxo();
        List<Utxo> universe = RequestFixtures.cancelUniverse(BORROWER, requestUtxo);
        RequestCancelTransactionBuilder builder = cancelBuilder(universe);
        RequestCancelTransactionBuilder.Request request = cancelRequest(requestUtxo);

        TransactionInput unspent = new TransactionInput("cd".repeat(32), 3);
        Transaction mutated = builder.buildWithMutation(request,
                new RequestCancelTransactionBuilder.Mutation(false, null, unspent, false));
        assertFalse(mutated.getBody().getInputs().contains(unspent),
                "the mutation's inputRef must really be absent from the inputs, or this proves nothing");

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> builder.assertStructure(request, mutated),
                "assertStructure must refuse a mint redeemer naming an inputRef the body does not spend");
        assertTrue(refused.getMessage().contains("cd".repeat(32)),
                "the guard must report the inputRef it decoded out of the witness set, not one it "
                        + "re-derived from the Request record; got: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("isInputRefSpent"),
                "the guard must name the conjunct it is standing in for; got: " + refused.getMessage());
    }

    /**
     * <b>The request-spend-credential predicate discriminates.</b> The finished Cancel body holds
     * exactly one input, so the count guard in {@code assertStructure} is 1 whatever this predicate
     * answers — which leaves the predicate itself, the {@code Address} parse and the comparison
     * against {@code requestSpendScriptHash} completely untested by the happy path. Probed directly
     * here: {@code true} for the request UTxO, {@code false} for the borrower's spare.
     * <p>
     * The {@code false} half is the load-bearing one. {@code getRequestSpendScriptHash} and
     * {@code getRequestPolicyId} sit two lines apart in {@link LoansContractRegistry} and this
     * slice's vocabulary conflates "request script" with "request spend wrapper"; a predicate that
     * compared against the wrong one, or one whose {@code Optional} filter silently fell through,
     * would let a second request input go uncounted.
     */
    @Test
    void theRequestSpendCredentialPredicateTellsTheRequestUtxoFromTheBorrowersSpare() {
        Utxo requestUtxo = requestUtxo();
        List<Utxo> universe = RequestFixtures.cancelUniverse(BORROWER, requestUtxo);
        RequestCancelTransactionBuilder builder = cancelBuilder(universe);

        assertTrue(builder.isAtRequestSpendCredential(
                        new TransactionInput(requestUtxo.getTxHash(), requestUtxo.getOutputIndex())),
                "the request UTxO sits at an enterprise address of requestSpendScriptHash");

        Utxo spare = RequestFixtures.spareUtxo(BORROWER);
        assertFalse(builder.isAtRequestSpendCredential(
                        new TransactionInput(spare.getTxHash(), spare.getOutputIndex())),
                "the borrower's spare is a key-payment address and must not be counted as a "
                        + "request input");
    }

    /**
     * <b>A second input at the request spend credential is refused by {@code assertStructure}.</b>
     * <p>
     * The universe carries {@link RequestFixtures#secondRequestUtxo()} throughout, and the builder
     * still emits a one-input body — it sits at the request script, not at the borrower's address,
     * so coin selection never reaches for it. That is asserted first, because it is what makes the
     * happy-path count guard green for a reason that has nothing to do with the credential. The
     * second input is then added to the finished body by hand, which is the shape a batched Cancel
     * or a stray coin selection would produce, and the guard must refuse it.
     * <p>
     * On chain this shape does not fail cleanly, which is why the guard exists: {@code indexed_all}
     * would iterate two request inputs while {@code actionsForEachInput} holds one element, and
     * {@code safe_list_at} → {@code do_list_at} → {@code builtin.head_list} on the emptied list
     * <b>aborts</b>. Refused after fees.
     */
    @Test
    void aSecondInputAtTheRequestSpendCredentialIsRefusedByTheSelfCheck() {
        Utxo requestUtxo = requestUtxo();
        Utxo second = RequestFixtures.secondRequestUtxo();
        List<Utxo> universe = new ArrayList<>(RequestFixtures.cancelUniverse(BORROWER, requestUtxo));
        universe.add(second);

        RequestCancelTransactionBuilder builder = cancelBuilder(universe);
        RequestCancelTransactionBuilder.Request request = cancelRequest(requestUtxo);

        Transaction tx = builder.build(request);
        assertEquals(1, tx.getBody().getInputs().size(),
                "the decoy sits at the request script, not the borrower's address, so coin selection "
                        + "leaves it alone and the happy path is undisturbed");

        List<TransactionInput> withSecond = new ArrayList<>(tx.getBody().getInputs());
        withSecond.add(new TransactionInput(second.getTxHash(), second.getOutputIndex()));
        tx.getBody().setInputs(withSecond);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> builder.assertStructure(request, tx),
                "assertStructure must refuse a body with more request inputs than Cancel actions");
        assertTrue(refused.getMessage().contains("2 input(s) at the request spend credential"),
                "the guard must count the inputs at the request spend credential; got: "
                        + refused.getMessage());
    }

    // ======================================================================================
    // plumbing
    // ======================================================================================

    /**
     * The rejection must be named, not merely observed. {@link EvalFixtures} reports only the first
     * refusing redeemer, so a bare "it failed" would be satisfied by a fault anywhere earlier —
     * including one in our own assembly.
     */
    private static void assertRefusedBy(Transaction mutated, List<Utxo> universe,
                                        String tag, int index, String what) {
        EvalFixtures.Outcome outcome = EvalFixtures.evaluateRaw(mutated, universe, REGISTRY);
        assertFalse(outcome.successful(), "the deployed validators must reject " + what);
        assertTrue(outcome.detail().contains("RedeemerError { tag: \"" + tag + "\", index: " + index),
                "expected the " + tag + " redeemer at index " + index + " to be the rejecting script, "
                        + "got: " + outcome.detail());
    }

    // ---- TX A, and the request UTxO it produces ---------------------------------------------------

    /**
     * The request UTxO, chained off a <b>real</b> TX A built by the real
     * {@link RequestMintTransactionBuilder} — never hand-rolled. A hand-built request UTxO would be
     * this test agreeing with itself; this way the Cancel is arbitrated against the output the
     * origination transaction genuinely produces, hash and all.
     */
    private static Utxo requestUtxo() {
        return RequestFixtures.requestUtxoFrom(mintBuilder().build(mintRequest()));
    }

    private static RequestMintTransactionBuilder mintBuilder() {
        return new RequestMintTransactionBuilder(REGISTRY,
                LoanFixtures.utxoSupplier(RequestFixtures.universe(BORROWER)),
                LoanFixtures.protocolParams());
    }

    private static RequestMintTransactionBuilder.Request mintRequest() {
        return new RequestMintTransactionBuilder.Request(
                RequestFixtures.seedUtxo(BORROWER),
                RequestFixtures.configUtxo(),
                BORROWER,
                RequestFixtures.requestAddress(),
                requestAssetName(),
                RequestTxEncoder.requestDatum(RequestFixtures.requestDatum(BORROWER)),
                LoanFixtures.token(RequestFixtures.TFLDT, RequestFixtures.COLLATERAL_QUANTITY),
                REQUEST_LOVELACE);
    }

    // ---- TX A′ ------------------------------------------------------------------------------------

    private static RequestCancelTransactionBuilder cancelBuilder(List<Utxo> universe) {
        return new RequestCancelTransactionBuilder(REGISTRY,
                LoanFixtures.utxoSupplier(universe), LoanFixtures.protocolParams());
    }

    private static RequestCancelTransactionBuilder.Request cancelRequest(Utxo requestUtxo) {
        return new RequestCancelTransactionBuilder.Request(requestUtxo, RequestFixtures.configUtxo(),
                BORROWER, borrowerPaymentKeyHash(), requestAssetName());
    }

    private static String requestAssetName() {
        return RequestFixtures.requestAssetName(RequestFixtures.seed());
    }

    private static byte[] borrowerPaymentKeyHash() {
        return new Address(BORROWER).getPaymentCredentialHash().orElseThrow();
    }

    /** Whether an input of the body resolves, in the given universe, to the request spend address. */
    private static boolean atRequestAddress(List<Utxo> universe, TransactionInput input) {
        return universe.stream()
                .anyMatch(utxo -> utxo.getTxHash().equals(input.getTransactionId())
                        && utxo.getOutputIndex() == input.getIndex()
                        && RequestFixtures.requestAddress().equals(utxo.getAddress()));
    }

    /** The CBOR the ledger would count, measured rather than estimated. */
    private static int serializedSize(Transaction tx) {
        try {
            return tx.serialize().length;
        } catch (Exception e) {
            throw new AssertionError("cannot serialize the built transaction", e);
        }
    }

    /**
     * Re-reads the transaction from its own serialized bytes, so every structural assertion is made
     * against what a node would parse rather than against the builder's in-memory objects.
     */
    private static Transaction roundTrip(Transaction tx) {
        try {
            return Transaction.deserialize(tx.serialize());
        } catch (Exception e) {
            throw new AssertionError("cannot round-trip the built transaction", e);
        }
    }

    /**
     * The quantity of one token in an output's value, matched on <b>hex</b> asset names.
     * <p>
     * Deliberately not {@code Value.amountOf(policyId, assetName)}: that resolves the name through
     * {@code Asset.hasName}, which treats a bare string as literal UTF-8 bytes and hex-encodes it
     * again. Passing a 29-byte hex name to it returns zero, silently — see
     * {@link RequestMintDryEvalTest}'s own copy of this helper and {@code LoanFixtures.unit()}.
     */
    private static BigInteger quantityOf(TransactionOutput output, String policyId, String assetNameHex) {
        for (MultiAsset multiAsset : output.getValue().getMultiAssets()) {
            if (!multiAsset.getPolicyId().equals(policyId)) {
                continue;
            }
            for (Asset asset : multiAsset.getAssets()) {
                if (strip(asset.getNameAsHex()).equalsIgnoreCase(assetNameHex)) {
                    return asset.getValue();
                }
            }
        }
        return BigInteger.ZERO;
    }

    /** cardano-client-lib hands asset names back with a {@code 0x} prefix. */
    private static String strip(String maybePrefixed) {
        return maybePrefixed.startsWith("0x") ? maybePrefixed.substring(2) : maybePrefixed;
    }
}
