package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.api.exception.ApiException;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.UtxoService;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.service.loans.OracleOutRefIndex.Answer;
import com.fluidtokens.aquarium.offchain.service.loans.OracleReferenceInputProbe.OracleReferenceInputNotLiveException;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * ⛔ FAB-196-2: the oracle reference-input probe asks THIS NODE'S INDEX first, per out-ref, and Blockfrost only
 * when the index does not know.
 *
 * <ul>
 *   <li>LIVE — the out-ref passes with no Blockfrost call;</li>
 *   <li>SPENT — a refusal with no Blockfrost call, never carrying the executor's spent-input markers, and never a
 *       wait ({@code notYetVisible} false);</li>
 *   <li>UNKNOWN — the Blockfrost check of a two-argument probe, verdict for verdict and call for call.</li>
 * </ul>
 * The index never decides content: the address of every out-ref is still resolved first, and the
 * {@code expect} refusals still come before it, so the index is not asked when either refuses.
 */
class OracleReferenceInputProbeIndexFirstTest {

    private static final AssetType PRICED = new AssetType("c0".repeat(28), "544f4b");
    private static final AssetType FEED_NFT = new AssetType("b0".repeat(28), "4f52434c");
    private static final AssetType FEED_NFT_V2 = new AssetType("b1".repeat(28), "4f52434c");
    private static final AssetType C3_NFT = new AssetType("d0".repeat(28), "4f7261636c6546656564");

    private static final String FEED_TX = "9a".repeat(32);
    private static final String FEED_2_TX = "9d".repeat(32);
    private static final String SCRIPT_TX = "9b".repeat(32);
    private static final String PROVIDER_TX = "9c".repeat(32);
    private static final String MOVED_TX = "77".repeat(32);
    private static final String SPENDER_TX = "5e".repeat(32);

    private static final TransactionInput FEED_REF = LoanFixtures.input(FEED_TX, 0);
    private static final TransactionInput FEED_2_REF = LoanFixtures.input(FEED_2_TX, 0);
    private static final TransactionInput SCRIPT_REF = LoanFixtures.input(SCRIPT_TX, 0);
    private static final TransactionInput PROVIDER_REF = LoanFixtures.input(PROVIDER_TX, 0);

    private static final String FEED_ADDRESS =
            "addr_test1wpqzexzdvwtl2zxw6pn5v34m9lxk0avnckmemy0puhqtzfqw4jw8q";
    private static final String PROVIDER_ADDRESS =
            "addr_test1wzgy7cu7mnnjau2qn5th8932tr27f83tfgusm60sklwppmgh6re39";

    /** The units, spelled out rather than derived. */
    private static final String FEED_UNIT = "b0".repeat(28) + "4f52434c";
    private static final String FEED_V2_UNIT = "b1".repeat(28) + "4f52434c";
    private static final String C3_UNIT = "d0".repeat(28) + "4f7261636c6546656564";

    private static final Utxo FEED_UTXO = holding(FEED_REF, FEED_ADDRESS, FEED_NFT);
    private static final Utxo FEED_2_UTXO = holding(FEED_2_REF, FEED_ADDRESS, FEED_NFT_V2);
    private static final Utxo PROVIDER_UTXO = holding(PROVIDER_REF, PROVIDER_ADDRESS, C3_NFT);

    private static Utxo holding(TransactionInput ref, String address, AssetType nft) {
        return LoanFixtures.utxo(ref.getTransactionId(), ref.getIndex(), address,
                List.of(Amount.lovelace(BigInteger.valueOf(2_000_000)), LoanFixtures.token(nft, 1)), null);
    }

    private static OraclePriceFeed charlie() {
        return OraclePriceFeed.priceDataCharlie(PRICED, BigInteger.valueOf(50), BigInteger.ONE, 0L, 1L);
    }

    /** A c3 entry: feed {@code feedRef} holding {@code oracleNft}, provider {@code PROVIDER_REF} holding {@code providerNft}. */
    private static OracleEntry c3(AssetType oracleNft, TransactionInput feedRef, AssetType providerNft) {
        return new OracleEntry(PRICED, oracleNft, "", "a0".repeat(28), feedRef, SCRIPT_REF, List.of(), 0,
                charlie(), List.of(), PROVIDER_REF, 2, providerNft);
    }

    private static OracleEntry c3() {
        return c3(FEED_NFT, FEED_REF, C3_NFT);
    }

    /** A multisig entry: no provider out-ref at all. */
    private static OracleEntry multisig(AssetType oracleNft, TransactionInput feedRef) {
        return new OracleEntry(PRICED, oracleNft, "", "a0".repeat(28), feedRef, SCRIPT_REF,
                List.of("00".repeat(32)), 1, OraclePriceFeed.aggregated(PRICED, BigInteger.ONE, BigInteger.ONE,
                0L, 1L), List.of(), null, 2, null);
    }

    private static OracleEntry multisig() {
        return multisig(FEED_NFT, FEED_REF);
    }

    /**
     * A stub index that answers per out-ref (UNKNOWN for any out-ref it was not told about) and RECORDS every
     * question, in order, as {@code txHash#index -> unit}.
     */
    private static final class RecordingIndex implements OracleOutRefIndex {

        private final Map<TransactionInput, Answer> answers = new HashMap<>();

        private final List<String> asked = new ArrayList<>();

        private RuntimeException failure;

        private boolean answerNull;

        RecordingIndex answer(TransactionInput outRef, Answer answer) {
            answers.put(outRef, answer);
            return this;
        }

        RecordingIndex failing(RuntimeException failure) {
            this.failure = failure;
            return this;
        }

        RecordingIndex answeringNull() {
            this.answerNull = true;
            return this;
        }

        @Override
        public Answer lookup(TransactionInput outRef, AssetType nft) {
            asked.add(outRef.getTransactionId() + "#" + outRef.getIndex() + " -> " + nft.toUnit());
            if (failure != null) {
                throw failure;
            }
            if (answerNull) {
                return null;
            }
            return answers.getOrDefault(outRef, Answer.unknown());
        }
    }

    private static OracleReferenceInputProbe probe(UtxoService blockfrost, OracleOutRefIndex index, Utxo... content) {
        return new OracleReferenceInputProbe(LoanFixtures.utxoSupplier(List.of(content)), blockfrost, index);
    }

    private static OracleReferenceInputProbe twoArgumentProbe(UtxoService blockfrost, Utxo... content) {
        return new OracleReferenceInputProbe(LoanFixtures.utxoSupplier(List.of(content)), blockfrost);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Result<List<Utxo>> ok(Utxo... live) {
        return (Result<List<Utxo>>) Result.success("ok").code(200).withValue(new ArrayList<>(List.of(live)));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Result<List<Utxo>> failed(int code, String body) {
        return (Result<List<Utxo>>) Result.error(body).code(code);
    }

    /** The refusal, checked for the executor's spent-input markers exactly as the executor would read it. */
    private static OracleReferenceInputNotLiveException refused(Runnable call) {
        var e = assertThrows(OracleReferenceInputNotLiveException.class, call::run);
        String compact = e.getMessage().replaceAll("\\s", "");
        assertFalse(compact.contains("BadInputs"), "a probe refusal carries BadInputs: " + e.getMessage());
        assertFalse(compact.contains("ScriptFailures"), "a probe refusal carries ScriptFailures: " + e.getMessage());
        assertFalse(compact.contains(LiquidationExecutor.BAD_INPUTS_MARKER), e.getMessage());
        assertFalse(compact.contains(LiquidationExecutor.EMPTY_SCRIPT_FAILURES_MARKER), e.getMessage());
        return e;
    }

    private static String spentByIndex(TransactionInput outRef, String unit, String address) {
        return "oracle out-ref " + outRef.getTransactionId() + "#" + outRef.getIndex() + " (expected NFT " + unit
                + " at " + address + "): spent in transaction " + SPENDER_TX + ", as recorded by this node's index";
    }

    // ---- (a)/(b) LIVE: no Blockfrost call --------------------------------------------------------------

    @Test
    void aLiveMultisigFeedPassesWithNoBlockfrostCall() {
        UtxoService blockfrost = mock(UtxoService.class);
        var index = new RecordingIndex().answer(FEED_REF, Answer.live());

        assertDoesNotThrow(() -> probe(blockfrost, index, FEED_UTXO).requireLive(List.of(multisig())));

        verifyNoInteractions(blockfrost);
        assertEquals(List.of(FEED_TX + "#0 -> " + FEED_UNIT), index.asked);
    }

    @Test
    void aLiveC3FeedAndALiveProviderPassWithNoBlockfrostCall() {
        UtxoService blockfrost = mock(UtxoService.class);
        var index = new RecordingIndex().answer(FEED_REF, Answer.live()).answer(PROVIDER_REF, Answer.live());

        assertDoesNotThrow(() -> probe(blockfrost, index, FEED_UTXO, PROVIDER_UTXO).requireLive(List.of(c3())));

        verifyNoInteractions(blockfrost);
        assertEquals(2, index.asked.size());
    }

    // ---- (c)/(d) SPENT: a refusal with no Blockfrost call ------------------------------------------------

    @Test
    void aFeedSpentInTheIndexIsRefusedWithNoBlockfrostCall() {
        UtxoService blockfrost = mock(UtxoService.class);
        var index = new RecordingIndex().answer(FEED_REF, Answer.spent(SPENDER_TX));

        var e = refused(() -> probe(blockfrost, index, FEED_UTXO).requireLive(List.of(multisig())));

        assertEquals("oracle out-ref " + FEED_TX + "#0 (expected NFT " + FEED_UNIT + " at " + FEED_ADDRESS
                + "): spent in transaction " + SPENDER_TX + ", as recorded by this node's index", e.getMessage());
        assertFalse(e.notYetVisible());
        verifyNoInteractions(blockfrost);
    }

    @Test
    void aLiveFeedAndASpentProviderIsRefusedNamingTheProviderWithNoBlockfrostCall() {
        UtxoService blockfrost = mock(UtxoService.class);
        var index = new RecordingIndex().answer(FEED_REF, Answer.live()).answer(PROVIDER_REF, Answer.spent(SPENDER_TX));

        var e = refused(() -> probe(blockfrost, index, FEED_UTXO, PROVIDER_UTXO).requireLive(List.of(c3())));

        assertEquals("oracle out-ref " + PROVIDER_TX + "#0 (expected NFT " + C3_UNIT + " at " + PROVIDER_ADDRESS
                + "): spent in transaction " + SPENDER_TX + ", as recorded by this node's index", e.getMessage());
        assertEquals(spentByIndex(PROVIDER_REF, C3_UNIT, PROVIDER_ADDRESS), e.getMessage());
        assertFalse(e.notYetVisible());
        verifyNoInteractions(blockfrost);
    }

    // ---- (e) UNKNOWN everywhere: exactly a two-argument probe ---------------------------------------------

    /** What a probe did: the refusal text and wait flag (or null on a pass), and its Blockfrost interactions. */
    private record Outcome(String message, Boolean notYetVisible) {
    }

    private static Outcome run(OracleReferenceInputProbe probe, List<OracleEntry> entries) {
        try {
            probe.requireLive(entries);
            return new Outcome(null, null);
        } catch (OracleReferenceInputNotLiveException e) {
            refused(() -> {
                throw e;
            });
            return new Outcome(e.getMessage(), e.notYetVisible());
        }
    }

    /**
     * Runs {@code entries} through an all-UNKNOWN three-argument probe and a two-argument probe, each over its own
     * Blockfrost mock stubbed by {@code stubbing}, and requires the same outcome and the same calls: exactly one
     * {@code getUtxos(address, unit, 100, 1)} per out-ref in {@code expectedCalls}, nothing else.
     */
    private static void sameAsTwoArgument(Consumer<UtxoService> stubbing, List<OracleEntry> entries,
                                          Map<String, String> expectedCalls, Utxo... content) throws ApiException {
        UtxoService indexed = mock(UtxoService.class);
        UtxoService plain = mock(UtxoService.class);
        stubbing.accept(indexed);
        stubbing.accept(plain);
        var index = new RecordingIndex();

        Outcome withIndex = run(probe(indexed, index, content), entries);
        Outcome without = run(twoArgumentProbe(plain, content), entries);

        assertEquals(without, withIndex, "an all-UNKNOWN index must change nothing");
        assertEquals(expectedCalls.size(), index.asked.size(), "the index is asked once per out-ref: " + index.asked);
        for (UtxoService blockfrost : List.of(indexed, plain)) {
            for (var call : expectedCalls.entrySet()) {
                verify(blockfrost, times(1)).getUtxos(call.getKey(), call.getValue(), 100, 1);
            }
            verify(blockfrost, never()).getTxOutput(anyString(), anyInt());
            verifyNoMoreInteractions(blockfrost);
        }
    }

    private static Consumer<UtxoService> answering(String address, String unit, Result<List<Utxo>> answer) {
        return blockfrost -> {
            try {
                when(blockfrost.getUtxos(address, unit, 100, 1)).thenReturn(answer);
            } catch (ApiException e) {
                throw new AssertionError(e);
            }
        };
    }

    @Test
    void allUnknownAndLiveOnBlockfrostIsExactlyTheTwoArgumentProbe() throws Exception {
        sameAsTwoArgument(answering(FEED_ADDRESS, FEED_UNIT, ok(FEED_UTXO))
                        .andThen(answering(PROVIDER_ADDRESS, C3_UNIT, ok(PROVIDER_UTXO))),
                List.of(c3()), Map.of(FEED_ADDRESS, FEED_UNIT, PROVIDER_ADDRESS, C3_UNIT), FEED_UTXO, PROVIDER_UTXO);
    }

    @Test
    void allUnknownAndA404IsExactlyTheTwoArgumentProbe() throws Exception {
        sameAsTwoArgument(answering(FEED_ADDRESS, FEED_UNIT, failed(404, "{\"status_code\":404}")),
                List.of(multisig()), Map.of(FEED_ADDRESS, FEED_UNIT), FEED_UTXO);
    }

    @Test
    void allUnknownAndTheNftElsewhereIsExactlyTheTwoArgumentProbe() throws Exception {
        sameAsTwoArgument(answering(FEED_ADDRESS, FEED_UNIT,
                        ok(holding(LoanFixtures.input(MOVED_TX, 3), FEED_ADDRESS, FEED_NFT))),
                List.of(multisig()), Map.of(FEED_ADDRESS, FEED_UNIT), FEED_UTXO);
    }

    @Test
    void allUnknownAndAProviderErrorIsExactlyTheTwoArgumentProbe() throws Exception {
        sameAsTwoArgument(answering(FEED_ADDRESS, FEED_UNIT, ok(FEED_UTXO))
                        .andThen(answering(PROVIDER_ADDRESS, C3_UNIT, failed(500, "{\"error\":\"Bad Inputs UTxO\"}"))),
                List.of(c3()), Map.of(FEED_ADDRESS, FEED_UNIT, PROVIDER_ADDRESS, C3_UNIT), FEED_UTXO, PROVIDER_UTXO);
    }

    // ---- (f) mixed: only the UNKNOWN out-ref reaches Blockfrost ---------------------------------------------

    @Test
    void aLiveFeedAndAnUnknownProviderMakeExactlyOneCallForTheProvider() throws Exception {
        UtxoService blockfrost = mock(UtxoService.class);
        when(blockfrost.getUtxos(PROVIDER_ADDRESS, C3_UNIT, 100, 1)).thenReturn(ok(PROVIDER_UTXO));
        var index = new RecordingIndex().answer(FEED_REF, Answer.live());

        assertDoesNotThrow(() -> probe(blockfrost, index, FEED_UTXO, PROVIDER_UTXO).requireLive(List.of(c3())));

        verify(blockfrost, times(1)).getUtxos(PROVIDER_ADDRESS, C3_UNIT, 100, 1);
        verify(blockfrost, never()).getTxOutput(anyString(), anyInt());
        verifyNoMoreInteractions(blockfrost);
    }

    // ---- (g) what the index is asked ------------------------------------------------------------------------

    @Test
    void theIndexIsAskedWithEachOutRefsOwnNftOncePerDistinctOutRefInInsertionOrder() {
        UtxoService blockfrost = mock(UtxoService.class);
        var index = new RecordingIndex()
                .answer(FEED_REF, Answer.live()).answer(FEED_2_REF, Answer.live()).answer(PROVIDER_REF, Answer.live());

        assertDoesNotThrow(() -> probe(blockfrost, index, FEED_UTXO, FEED_2_UTXO, PROVIDER_UTXO)
                .requireLive(List.of(c3(), c3(FEED_NFT_V2, FEED_2_REF, C3_NFT))));

        assertEquals(List.of(
                FEED_TX + "#0 -> " + FEED_UNIT,
                PROVIDER_TX + "#0 -> " + C3_UNIT,
                FEED_2_TX + "#0 -> " + FEED_V2_UNIT), index.asked,
                "the feed with the feed NFT, the provider with the c3 NFT, and the shared provider only once");
        verifyNoInteractions(blockfrost);
    }

    // ---- (h)/(i) refusals decided before the index is asked ------------------------------------------------

    @Test
    void anUnknownProviderContentWinsOverAFeedTheIndexHasSpent() {
        UtxoService blockfrost = mock(UtxoService.class);
        var index = new RecordingIndex().answer(FEED_REF, Answer.spent(SPENDER_TX));

        var e = refused(() -> probe(blockfrost, index, FEED_UTXO).requireLive(List.of(c3())));

        assertEquals("oracle out-ref " + PROVIDER_TX + "#0 (expected NFT " + C3_UNIT
                + "): its address is unknown — no content for it", e.getMessage());
        assertTrue(e.notYetVisible());
        assertEquals(List.of(), index.asked, "every address is resolved before the index is asked");
        verifyNoInteractions(blockfrost);
    }

    @Test
    void conflictingNftsAreRefusedBeforeTheIndexIsAsked() {
        UtxoService blockfrost = mock(UtxoService.class);
        var index = new RecordingIndex().answer(FEED_REF, Answer.live()).answer(PROVIDER_REF, Answer.live());

        var e = refused(() -> probe(blockfrost, index, FEED_UTXO, PROVIDER_UTXO).requireLive(List.of(
                c3(), c3(FEED_NFT_V2, FEED_REF, C3_NFT))));

        assertEquals("oracle feed " + FEED_TX + "#0: two oracle entries expect different NFTs there ("
                + FEED_UNIT + " and " + FEED_V2_UNIT + ")", e.getMessage());
        assertFalse(e.notYetVisible());
        assertEquals(List.of(), index.asked);
        verifyNoInteractions(blockfrost);
    }

    @Test
    void aMissingNftIsRefusedBeforeTheIndexIsAsked() {
        UtxoService blockfrost = mock(UtxoService.class);
        var index = new RecordingIndex().answer(FEED_REF, Answer.live()).answer(PROVIDER_REF, Answer.live());

        var e = refused(() -> probe(blockfrost, index, FEED_UTXO, PROVIDER_UTXO)
                .requireLive(List.of(multisig(), c3(FEED_NFT, FEED_REF, null))));

        assertEquals("Charli3 provider " + PROVIDER_TX
                + "#0: the registry names no NFT for it, so its liveness cannot be checked", e.getMessage());
        assertFalse(e.notYetVisible());
        assertEquals(List.of(), index.asked);
        verifyNoInteractions(blockfrost);
    }

    @Test
    void aMissingOutRefIsRefusedBeforeTheIndexIsAsked() {
        UtxoService blockfrost = mock(UtxoService.class);
        var index = new RecordingIndex().answer(FEED_REF, Answer.live());

        var e = refused(() -> probe(blockfrost, index, FEED_UTXO)
                .requireLive(List.of(multisig(), multisig(FEED_NFT, null))));

        assertEquals("the oracle feed names no out-ref, so its liveness cannot be checked", e.getMessage());
        assertFalse(e.notYetVisible());
        assertEquals(List.of(), index.asked);
        verifyNoInteractions(blockfrost);
    }

    // ---- (j)/(k) an index that fails is not a verdict -------------------------------------------------------

    @Test
    void anIndexFailureEscapesUnchangedWithNoBlockfrostCall() {
        UtxoService blockfrost = mock(UtxoService.class);
        var failure = new IllegalStateException("db down");
        var index = new RecordingIndex().failing(failure);

        var e = assertThrows(IllegalStateException.class,
                () -> probe(blockfrost, index, FEED_UTXO).requireLive(List.of(multisig())));

        assertSame(failure, e, "the index's own failure, not a probe verdict");
        verifyNoInteractions(blockfrost);
    }

    @Test
    void aNullAnswerIsAProgrammingErrorWithNoBlockfrostCall() {
        UtxoService blockfrost = mock(UtxoService.class);
        var index = new RecordingIndex().answeringNull();

        var e = assertThrows(NullPointerException.class,
                () -> probe(blockfrost, index, FEED_UTXO).requireLive(List.of(multisig())));

        assertNotNull(e);
        verifyNoInteractions(blockfrost);
    }
}
