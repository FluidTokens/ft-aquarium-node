package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TX A — the request mint of a Lending v4 loan origination — handed to the real PlutusV3 machine
 * and run against the <em>real deployed</em> {@code request.request} validator, in the discipline of
 * {@link LiquidateDryEvalTest}. The applied compiled code comes from {@link LoansContractRegistry}'s
 * derivation over the committed {@code loans-v4.plutus.json}; the config reference input carries the
 * {@code ConfigDatum} recorded off preview. Everything else is synthetic, so the test runs cold: no
 * network, no key, no wallet. See {@link EvalFixtures} for the rig and its <em>Harness
 * limitations</em>.
 *
 * <h2>Consequence B — a green run here does NOT say the datum is right</h2>
 * {@code check_mint} ({@code validators/request.ak:137-195}, deployed pin {@code bbe9c1a}) checks
 * exactly two things: that {@code redeemer.inputRef} names a UTxO this transaction really spends,
 * and that every minted token under the request policy is named
 * {@code index ‖ blake2b_224(serialise_data(inputRef))} and sits alone in the correspondingly
 * indexed output at the request spend credential. <b>It never looks at the request output's
 * datum</b> — not its shape, not its type, not even whether one is present. So this rig
 * <b>cannot and does not arbitrate the {@code RequestDatum}</b>. Reading "S2 evaluated green" as
 * "the datum is right" would be a mistake. Until S3's {@code Cancel} spends the request UTxO — the
 * first transaction whose validator actually decodes the datum — the datum's only defences are the
 * schema pins in {@link RequestTxEncoderSchemaTest} and the independently derived goldens in
 * {@link RequestTxEncoderTest}. The datum-hex assertion below is a pin against those goldens, not
 * chain arbitration.
 *
 * <h2>Consequence C — no validity interval</h2>
 * {@code check_mint} reads no validity range, no signature and no withdrawal, so TX A carries none.
 *
 * <h2>Assertions read the artefact, never the report</h2>
 * The {@link EvaluationResult} list is used only for the redeemer count and tag. Every structural
 * claim — the mint field, the inputs, the outputs, the reference inputs — is read off the
 * <em>deserialised</em> {@link Transaction}. That is the T-014 defect class: a 307-test suite once
 * missed a 352,000× ex-units error because every assertion read the evaluator's report.
 *
 * <h2>The rig can fail</h2>
 * Four mutations — three required, one bonus — each of which must be refused by the {@code Mint}
 * redeemer: a bare "it failed" would be satisfied by any fault anywhere, including one in our own
 * assembly. Each evaluator message is transcribed verbatim into the test's own javadoc so the claim
 * is checkable without re-running anything. Comment the mutations out and this class is no longer
 * green evidence of anything.
 */
@Slf4j
class RequestMintDryEvalTest {

    private static final LoansContractRegistry REGISTRY = LoanFixtures.registry();

    /** The real ledger limit. Not the rig's raised {@link EvalFixtures#protocolParams()} ceiling. */
    private static final int MAX_TX_SIZE = 16_384;

    private static final String BORROWER = LoanFixtures.botAddress();

    /** Min-ada headroom for a three-asset output; the exact figure is not load-bearing here. */
    private static final long REQUEST_LOVELACE = 5_000_000L;

    // ======================================================================================
    // The positive case
    // ======================================================================================

    /**
     * One request NFT minted, one request output carrying the collateral and that NFT.
     * <p>
     * <b>Measured size: 10,607 bytes</b> against a real {@code maxTxSize} of {@value #MAX_TX_SIZE}.
     * This is a real budget question rather than a formality: no reference script has been published
     * for {@code request.request}, so the applied validator — 9,628 bytes unapplied in
     * {@code loans-v4.plutus.json} — travels in the witness set and is most of the transaction. The
     * figure is taken from {@link Transaction#serialize()} on the built transaction, the CBOR the
     * ledger would count, never estimated and never copied from an earlier run.
     * <p>
     * <b>What this number is not.</b> The transaction is <em>unpriced and unsigned</em>: no
     * evaluator is wired into {@link RequestMintTransactionBuilder}, so every redeemer still carries
     * cardano-client-lib's placeholder ex-units, and no vkey witness is present. Real ex-units and
     * one witness add on the order of a hundred bytes, against 5,777 of remaining headroom — the
     * verdict does not move, which is why the honest framing costs nothing. Publishing a reference
     * script for {@code request.request} would still be the right call before mainnet.
     */
    @Test
    void theRequestMintEvaluatesAgainstTheDeployedRequestValidator() {
        Transaction tx = build();
        List<Utxo> universe = RequestFixtures.universe(BORROWER);

        List<EvaluationResult> results = EvalFixtures.evaluate(tx, universe, REGISTRY);

        // The only thing read off the report: one redeemer, and it is the mint.
        assertEquals(1, results.size(), "exactly one script runs: the request minting policy");
        assertEquals(RedeemerTag.Mint, results.get(0).getRedeemerTag());

        // Everything else is read off the artefact, after a full CBOR round trip.
        Transaction onChain = roundTrip(tx);

        // --- the mint field ---
        List<MultiAsset> mint = onChain.getBody().getMint();
        assertEquals(1, mint.size(), "one policy in the mint field");
        assertEquals(REGISTRY.getRequestPolicyId(), mint.get(0).getPolicyId());
        assertEquals(1, mint.get(0).getAssets().size(), "one asset name under the request policy");
        Asset minted = mint.get(0).getAssets().get(0);
        assertEquals(BigInteger.ONE, minted.getValue(), "an NFT is minted, not burnt or doubled");
        String mintedName = strip(minted.getNameAsHex());
        assertEquals(expectedAssetName(), mintedName);
        assertEquals(29, mintedName.length() / 2,
                "the request NFT asset name is 29 bytes: 1-byte index prefix + 28-byte hash");
        assertEquals("00", mintedName.substring(0, 2), "the index prefix of the only minted token");

        // --- the seed really is spent ---
        assertTrue(onChain.getBody().getInputs().contains(RequestFixtures.seed()),
                "check_mint's isInputRefSpent needs the redeemer's inputRef among the inputs");

        // --- the request output ---
        List<TransactionOutput> atRequestAddress = onChain.getBody().getOutputs().stream()
                .filter(output -> RequestFixtures.requestAddress().equals(output.getAddress()))
                .toList();
        assertEquals(1, atRequestAddress.size(), "exactly one output at the request spend address");
        TransactionOutput requestOutput = atRequestAddress.get(0);

        assertEquals(RequestTxEncoderTest.REQUEST_DATUM_HEX,
                requestOutput.getInlineDatum().serializeToHex(),
                "the request output must carry the golden RequestDatum — pinned against "
                        + "RequestTxEncoderTest's literals, NOT arbitrated by this evaluation");

        assertEquals(BigInteger.valueOf(REQUEST_LOVELACE), requestOutput.getValue().getCoin());
        List<MultiAsset> outputAssets = requestOutput.getValue().getMultiAssets();
        assertEquals(2, outputAssets.size(),
                "exactly two policies beside lovelace: the collateral token and the request NFT");
        assertEquals(BigInteger.valueOf(RequestFixtures.COLLATERAL_QUANTITY),
                quantityOf(requestOutput, RequestFixtures.TFLDT.policyId(),
                        RequestFixtures.TFLDT.assetName()));
        assertEquals(BigInteger.ONE,
                quantityOf(requestOutput, REGISTRY.getRequestPolicyId(), mintedName));
        assertEquals(3, flattenedAssetCount(requestOutput),
                "the request value is exactly { lovelace, collateral, request NFT } — S4's "
                        + "collateralUnchanged and dosProtection both count this");

        // --- the reference inputs ---
        assertEquals(List.of(new TransactionInput(RequestFixtures.TX_CONFIG, 0)),
                onChain.getBody().getReferenceInputs(),
                "one reference input, the config — which is what makes configRefInputIndex 0 right");

        // --- size ---
        int size = serializedSize(tx);
        log.info("Request mint TX A: {} bytes (maxTxSize {})", size, MAX_TX_SIZE);
        assertTrue(size < MAX_TX_SIZE,
                "the request mint is " + size + " bytes, maxTxSize is " + MAX_TX_SIZE);
        assertFalse(tx.getWitnessSet().getPlutusV3Scripts().isEmpty(),
                "no reference script is published for request.request, so it must travel in the "
                        + "witness set — the size above is only meaningful if it does");
    }

    // ======================================================================================
    // Falsifiability — three mutations, each of which must be refused by the Mint redeemer
    // ======================================================================================

    /**
     * <b>M1 — wrong index prefix.</b> The token is named {@code 01 ‖ hash} instead of
     * {@code 00 ‖ hash}, with the 28-byte hash and the redeemer both left correct. This kills
     * {@code bytearray.at(assetName, 0) == index}, {@code index} being 0 for the only minted token.
     * <p>
     * The evaluator said, verbatim:
     * <pre>
     * ApiException: Error evaluating transaction
     * TxEvaluationException: AIKEN: RedeemerError { tag: "Mint", index: 0,
     *     err: Machine(EvaluationFailure, ExBudget { mem: 146966, cpu: 54946087 }, []) }
     * </pre>
     */
    @Test
    void aWrongIndexPrefixIsRejectedByTheRequestPolicy() {
        String hash = RequestFixtures.hashOutputRef(RequestFixtures.seed());
        Transaction mutated = builder().buildWithMutation(request(), RequestFixtures.seed(), "01" + hash);

        assertRefusedByTheMint(mutated, "a 0x01 index prefix on the only minted token");
    }

    /**
     * <b>M2 — the wrong {@code OutputReference} hashed.</b> The token is named after
     * {@code blake2b_224} of the <em>request output's own prospective output reference</em> — TX A's
     * hash at the request output's index — rather than after the redeemer's {@code inputRef}. That is
     * exactly the confusion S4 is exposed to: {@code check_lend} ({@code request.ak:271}) really does
     * hash the request UTxO's own reference for the loan NFT and the two bonds, so the two hashes sit
     * one transaction apart and look interchangeable. Here it kills
     * {@code bytearray.drop(assetName, 1) == inputRefHash}.
     * <p>
     * The evaluator said, verbatim:
     * <pre>
     * ApiException: Error evaluating transaction
     * TxEvaluationException: AIKEN: RedeemerError { tag: "Mint", index: 0,
     *     err: Machine(EvaluationFailure, ExBudget { mem: 146983, cpu: 55119516 }, []) }
     * </pre>
     */
    @Test
    void namingTheTokenAfterTheRequestOutputsOwnProspectiveRefIsRejectedByTheRequestPolicy() {
        Transaction clean = build();
        TransactionInput prospective = new TransactionInput(TransactionUtil.getTxHash(clean),
                requestOutputIndex(clean));
        String wrongName = "00" + RequestFixtures.hashOutputRef(prospective);

        assertFalse(wrongName.equals(expectedAssetName()),
                "the mutation must really change the name, or it proves nothing");

        Transaction mutated = builder().buildWithMutation(request(), RequestFixtures.seed(), wrongName);
        assertRefusedByTheMint(mutated, "a token named after the wrong output reference");
    }

    /**
     * <b>M3 — a redeemer {@code inputRef} that is not spent.</b> The redeemer points at a UTxO
     * absent from the body's inputs, and the token is named consistently with <em>that</em>
     * reference, so {@code isEachMintedTokenAccountedFor} still holds and the only broken conjunct is
     * {@code isInputRefSpent}.
     * <p>
     * The evaluator said, verbatim:
     * <pre>
     * ApiException: Error evaluating transaction
     * TxEvaluationException: AIKEN: RedeemerError { tag: "Mint", index: 0,
     *     err: Machine(EvaluationFailure, ExBudget { mem: 125824, cpu: 47788675 }, []) }
     * </pre>
     */
    @Test
    void anUnspentInputRefIsRejectedByTheRequestPolicy() {
        TransactionInput unspent = new TransactionInput("cd".repeat(32), 3);
        Transaction onChain = roundTrip(build());
        assertFalse(onChain.getBody().getInputs().contains(unspent),
                "the mutation's inputRef must really be absent from the inputs");

        String consistentName = "00" + RequestFixtures.hashOutputRef(unspent);
        Transaction mutated = builder().buildWithMutation(request(), unspent, consistentName);

        assertRefusedByTheMint(mutated, "an inputRef the transaction does not spend");
    }

    /**
     * <b>M4 (bonus) — the request output paid somewhere else.</b> The NFT is minted correctly but
     * locked at the borrower's own address, so
     * {@code get_outputs_to_smart_credential(.., Script(requestSpendScriptHash), ..)} returns an
     * empty list and {@code safe_list_at(requestOutputs, 0)} has nothing to check the token against.
     * <p>
     * The evaluator said, verbatim — note that this one fails differently, with
     * {@code EmptyList} rather than {@code EvaluationFailure}, because {@code safe_list_at} is
     * indexing an empty list rather than a check returning False:
     * <pre>
     * ApiException: Error evaluating transaction
     * TxEvaluationException: AIKEN: RedeemerError { tag: "Mint", index: 0,
     *     err: Machine(EmptyList(Con(ProtoList(Data, []))),
     *                  ExBudget { mem: 147149, cpu: 56119610 }, []) }
     * </pre>
     */
    @Test
    void aRequestOutputPaidToTheWrongAddressIsRejectedByTheRequestPolicy() {
        RequestMintTransactionBuilder.Request misdirected =
                new RequestMintTransactionBuilder.Request(
                        RequestFixtures.seedUtxo(BORROWER),
                        RequestFixtures.configUtxo(),
                        BORROWER,
                        BORROWER,
                        expectedAssetName(),
                        RequestTxEncoder.requestDatum(RequestFixtures.requestDatum(BORROWER)),
                        LoanFixtures.token(RequestFixtures.TFLDT, RequestFixtures.COLLATERAL_QUANTITY),
                        REQUEST_LOVELACE);

        Transaction mutated = builder().buildWithMutation(misdirected, RequestFixtures.seed(),
                expectedAssetName());

        assertRefusedByTheMint(mutated, "a request output that is not at the request spend credential");
    }

    // ======================================================================================
    // plumbing
    // ======================================================================================

    /**
     * The rejection must be named, not merely observed: a bare "it failed" would be satisfied by any
     * fault at all, including one in our own assembly.
     */
    private static void assertRefusedByTheMint(Transaction mutated, String what) {
        EvalFixtures.Outcome outcome =
                EvalFixtures.evaluateRaw(mutated, RequestFixtures.universe(BORROWER), REGISTRY);
        assertFalse(outcome.successful(), "the request policy must reject " + what);
        assertTrue(outcome.detail().contains("RedeemerError { tag: \"Mint\", index: 0"),
                "expected the request minting policy to be the rejecting script, got: "
                        + outcome.detail());
    }

    private static RequestMintTransactionBuilder builder() {
        return new RequestMintTransactionBuilder(REGISTRY,
                LoanFixtures.utxoSupplier(RequestFixtures.universe(BORROWER)),
                LoanFixtures.protocolParams());
    }

    private static RequestMintTransactionBuilder.Request request() {
        return new RequestMintTransactionBuilder.Request(
                RequestFixtures.seedUtxo(BORROWER),
                RequestFixtures.configUtxo(),
                BORROWER,
                RequestFixtures.requestAddress(),
                expectedAssetName(),
                RequestTxEncoder.requestDatum(RequestFixtures.requestDatum(BORROWER)),
                LoanFixtures.token(RequestFixtures.TFLDT, RequestFixtures.COLLATERAL_QUANTITY),
                REQUEST_LOVELACE);
    }

    private static Transaction build() {
        return builder().build(request());
    }

    private static String expectedAssetName() {
        return RequestFixtures.requestAssetName(RequestFixtures.seed());
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

    private static int requestOutputIndex(Transaction tx) {
        List<TransactionOutput> outputs = tx.getBody().getOutputs();
        for (int i = 0; i < outputs.size(); i++) {
            if (RequestFixtures.requestAddress().equals(outputs.get(i).getAddress())) {
                return i;
            }
        }
        throw new AssertionError("no output at the request spend address");
    }

    /**
     * The quantity of one token in an output's value, matched on <b>hex</b> asset names.
     * <p>
     * Deliberately not {@code Value.amountOf(policyId, assetName)}: that resolves the name through
     * {@code Asset.hasName}, which treats a bare string as literal UTF-8 bytes and hex-encodes it
     * again — the same round-trip trap {@code LoanFixtures.unit()} documents. Passing a 29-byte hex
     * name to it returns zero, silently.
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

    /** {@code length(flatten(output.value))}, the way the Aiken side counts it: lovelace + each token. */
    private static int flattenedAssetCount(TransactionOutput output) {
        int count = output.getValue().getCoin().signum() > 0 ? 1 : 0;
        for (MultiAsset multiAsset : output.getValue().getMultiAssets()) {
            count += multiAsset.getAssets().size();
        }
        return count;
    }

    /** cardano-client-lib hands asset names back with a {@code 0x} prefix. */
    private static String strip(String maybePrefixed) {
        return maybePrefixed.startsWith("0x") ? maybePrefixed.substring(2) : maybePrefixed;
    }
}
