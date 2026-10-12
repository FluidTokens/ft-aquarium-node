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
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * ⛔ FAB-138 T3a: the oracle reference-input probe, on its own.
 *
 * <p>Liveness is "the out-ref is in the LIVE UTxO set at its address, holding the NFT"
 * ({@code ccl-transaction-building-traps} §12) — never {@code getTxOutput}, which answers a spent output
 * forever. Every case below therefore pins both the verdict AND that {@code getTxOutput} was never asked,
 * and the refusal texts are checked for the two markers {@code LiquidationExecutor} reads as "a wallet
 * input is spent" (§13): a probe refusal that carried one would drop an innocent wallet UTxO.
 */
class OracleReferenceInputProbeTest {

    private static final AssetType PRICED = new AssetType("c0".repeat(28), "544f4b");
    private static final AssetType FEED_NFT = new AssetType("b0".repeat(28), "4f52434c");
    private static final AssetType FEED_NFT_V2 = new AssetType("b1".repeat(28), "4f52434c");
    private static final AssetType C3_NFT = new AssetType("d0".repeat(28), "4f7261636c6546656564");

    private static final TransactionInput FEED_REF = LoanFixtures.input("9a".repeat(32), 0);
    private static final TransactionInput FEED_REF_V2 = LoanFixtures.input("9d".repeat(32), 1);
    private static final TransactionInput SCRIPT_REF = LoanFixtures.input("9b".repeat(32), 0);
    private static final TransactionInput PROVIDER_REF = LoanFixtures.input("9c".repeat(32), 0);

    private static final String FEED_ADDRESS =
            "addr_test1wpqzexzdvwtl2zxw6pn5v34m9lxk0avnckmemy0puhqtzfqw4jw8q";
    private static final String PROVIDER_ADDRESS =
            "addr_test1wzgy7cu7mnnjau2qn5th8932tr27f83tfgusm60sklwppmgh6re39";

    private static final Utxo FEED_UTXO = holding(FEED_REF, FEED_ADDRESS, FEED_NFT);
    private static final Utxo FEED_V2_UTXO = holding(FEED_REF_V2, FEED_ADDRESS, FEED_NFT_V2);
    private static final Utxo PROVIDER_UTXO = holding(PROVIDER_REF, PROVIDER_ADDRESS, C3_NFT);

    private final List<String> refusals = new ArrayList<>();

    private static Utxo holding(TransactionInput ref, String address, AssetType nft) {
        return LoanFixtures.utxo(ref.getTransactionId(), ref.getIndex(), address,
                List.of(Amount.lovelace(BigInteger.valueOf(2_000_000)), LoanFixtures.token(nft, 1)), null);
    }

    private static OraclePriceFeed feed() {
        return OraclePriceFeed.priceDataCharlie(PRICED, BigInteger.valueOf(50), BigInteger.ONE, 0L, 1L);
    }

    /** A c3 entry whose provider NFT is the registry's c3 policy + "OracleFeed". */
    private static OracleEntry c3(AssetType oracleNft, TransactionInput feedRef, AssetType providerNft) {
        return new OracleEntry(PRICED, oracleNft, "", "a0".repeat(28), feedRef, SCRIPT_REF, List.of(), 0,
                feed(), List.of(), PROVIDER_REF, 2, providerNft);
    }

    /** A multisig entry: no provider out-ref at all. */
    private static OracleEntry multisig() {
        return new OracleEntry(PRICED, FEED_NFT, "", "a0".repeat(28), FEED_REF, SCRIPT_REF,
                List.of("00".repeat(32)), 1, OraclePriceFeed.aggregated(PRICED, BigInteger.ONE, BigInteger.ONE,
                0L, 1L), List.of(), null, 2, null);
    }

    private static OracleReferenceInputProbe probe(UtxoService blockfrost, Utxo... content) {
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

    private OracleReferenceInputProbe.OracleReferenceInputNotLiveException refused(Runnable call) {
        var e = assertThrows(OracleReferenceInputProbe.OracleReferenceInputNotLiveException.class, call::run);
        refusals.add(e.getMessage());
        assertNoSpentInputMarker(e.getMessage());
        return e;
    }

    private static void assertNoSpentInputMarker(String message) {
        assertFalse(message.contains("BadInputs"), "a probe refusal must never carry BadInputs: " + message);
        assertFalse(message.contains("ScriptFailures"),
                "a probe refusal must never carry ScriptFailures — LiquidationExecutor would drop the "
                        + "nominated wallet utxo for it: " + message);
    }

    private static void assertNeverAskedForATxOutput(UtxoService blockfrost) throws ApiException {
        verify(blockfrost, never()).getTxOutput(anyString(), anyInt());
    }

    @Test
    void aLiveFeedAndALiveProviderPass() throws Exception {
        UtxoService blockfrost = mock(UtxoService.class);
        when(blockfrost.getUtxos(FEED_ADDRESS, FEED_NFT.toUnit(), 100, 1)).thenReturn(ok(FEED_UTXO));
        when(blockfrost.getUtxos(PROVIDER_ADDRESS, C3_NFT.toUnit(), 100, 1)).thenReturn(ok(PROVIDER_UTXO));

        assertDoesNotThrow(() -> probe(blockfrost, FEED_UTXO, PROVIDER_UTXO)
                .requireLive(List.of(c3(FEED_NFT, FEED_REF, C3_NFT))));

        verify(blockfrost).getUtxos(FEED_ADDRESS, FEED_NFT.toUnit(), 100, 1);
        verify(blockfrost).getUtxos(PROVIDER_ADDRESS, C3_NFT.toUnit(), 100, 1);
        assertNeverAskedForATxOutput(blockfrost);
        verifyNoMoreInteractions(blockfrost);
    }

    @Test
    void aMultisigEntryProbesOnlyItsFeed() throws Exception {
        UtxoService blockfrost = mock(UtxoService.class);
        when(blockfrost.getUtxos(FEED_ADDRESS, FEED_NFT.toUnit(), 100, 1)).thenReturn(ok(FEED_UTXO));

        assertDoesNotThrow(() -> probe(blockfrost, FEED_UTXO).requireLive(List.of(multisig())));

        verify(blockfrost).getUtxos(FEED_ADDRESS, FEED_NFT.toUnit(), 100, 1);
        assertNeverAskedForATxOutput(blockfrost);
        verifyNoMoreInteractions(blockfrost);
    }

    @Test
    void a404IsSpentOrTheNftIsNotThere() throws Exception {
        UtxoService blockfrost = mock(UtxoService.class);
        when(blockfrost.getUtxos(FEED_ADDRESS, FEED_NFT.toUnit(), 100, 1))
                .thenReturn(failed(404, "{\"status_code\":404,\"error\":\"Not Found\"}"));

        var e = refused(() -> probe(blockfrost, FEED_UTXO).requireLive(List.of(multisig())));

        assertTrue(e.getMessage().contains(FEED_REF.getTransactionId() + "#0"), e.getMessage());
        assertTrue(e.getMessage().contains(FEED_NFT.toUnit()), e.getMessage());
        assertTrue(e.getMessage().contains(FEED_ADDRESS), e.getMessage());
        assertTrue(e.getMessage().contains("spent"), e.getMessage());
        assertNeverAskedForATxOutput(blockfrost);
    }

    @Test
    void aRateLimitAndAServerErrorAreProviderErrors() throws Exception {
        for (int code : new int[]{429, 500}) {
            UtxoService blockfrost = mock(UtxoService.class);
            when(blockfrost.getUtxos(FEED_ADDRESS, FEED_NFT.toUnit(), 100, 1))
                    .thenReturn(failed(code, "{\"status_code\":" + code + "}"));

            var e = refused(() -> probe(blockfrost, FEED_UTXO).requireLive(List.of(multisig())));

            assertTrue(e.getMessage().contains("provider error"), e.getMessage());
            assertTrue(e.getMessage().contains(String.valueOf(code)), e.getMessage());
            assertTrue(e.getMessage().contains(FEED_REF.getTransactionId() + "#0"), e.getMessage());
            assertFalse(e.getMessage().contains("spent"), "a provider error is not a verdict: " + e.getMessage());
            assertNeverAskedForATxOutput(blockfrost);
        }
    }

    @Test
    void aThrowingProviderIsAProviderErrorNotAnEscapingException() throws Exception {
        UtxoService blockfrost = mock(UtxoService.class);
        when(blockfrost.getUtxos(FEED_ADDRESS, FEED_NFT.toUnit(), 100, 1))
                .thenThrow(new ApiException("connect timed out"));

        var e = refused(() -> probe(blockfrost, FEED_UTXO).requireLive(List.of(multisig())));

        assertTrue(e.getMessage().contains("provider error"), e.getMessage());
        assertTrue(e.getMessage().contains("connect timed out"), e.getMessage());
        assertNeverAskedForATxOutput(blockfrost);
    }

    @Test
    void theNftLiveElsewhereMeansThisOutRefIsSpent() throws Exception {
        Utxo moved = holding(LoanFixtures.input("77".repeat(32), 3), FEED_ADDRESS, FEED_NFT);
        UtxoService blockfrost = mock(UtxoService.class);
        when(blockfrost.getUtxos(FEED_ADDRESS, FEED_NFT.toUnit(), 100, 1)).thenReturn(ok(moved));

        var e = refused(() -> probe(blockfrost, FEED_UTXO).requireLive(List.of(multisig())));

        assertTrue(e.getMessage().contains("spent"), e.getMessage());
        assertTrue(e.getMessage().contains("77".repeat(32) + "#3"),
                "the refusal names where the NFT sits now: " + e.getMessage());
        assertNeverAskedForATxOutput(blockfrost);
    }

    @Test
    void twoEntriesExpectingDifferentNftsAtOneOutRefRefuseBeforeAnyCall() throws Exception {
        UtxoService blockfrost = mock(UtxoService.class);

        var e = refused(() -> probe(blockfrost, FEED_UTXO, PROVIDER_UTXO).requireLive(List.of(
                c3(FEED_NFT, FEED_REF, C3_NFT), c3(FEED_NFT_V2, FEED_REF, C3_NFT))));

        assertTrue(e.getMessage().contains(FEED_NFT.toUnit()) && e.getMessage().contains(FEED_NFT_V2.toUnit()),
                e.getMessage());
        verifyNoMoreInteractions(blockfrost);
    }

    @Test
    void aC3EntryWithNoProviderNftRefuses() throws Exception {
        UtxoService blockfrost = mock(UtxoService.class);
        when(blockfrost.getUtxos(FEED_ADDRESS, FEED_NFT.toUnit(), 100, 1)).thenReturn(ok(FEED_UTXO));

        var e = refused(() -> probe(blockfrost, FEED_UTXO, PROVIDER_UTXO)
                .requireLive(List.of(c3(FEED_NFT, FEED_REF, null))));

        assertTrue(e.getMessage().contains(PROVIDER_REF.getTransactionId() + "#0"), e.getMessage());
        verify(blockfrost, never()).getUtxos(anyString(), anyString(), anyInt(), anyInt());
        assertNeverAskedForATxOutput(blockfrost);
    }

    @Test
    void anOutRefWhoseContentIsUnknownRefuses() throws Exception {
        UtxoService blockfrost = mock(UtxoService.class);
        when(blockfrost.getUtxos(FEED_ADDRESS, FEED_NFT.toUnit(), 100, 1)).thenReturn(ok(FEED_UTXO));

        var e = refused(() -> probe(blockfrost, FEED_UTXO).requireLive(List.of(c3(FEED_NFT, FEED_REF, C3_NFT))));

        assertTrue(e.getMessage().contains(PROVIDER_REF.getTransactionId() + "#0"), e.getMessage());
        assertTrue(e.getMessage().contains(C3_NFT.toUnit()), e.getMessage());
        verify(blockfrost, never()).getUtxos(anyString(), anyString(), anyInt(), anyInt());
        assertNeverAskedForATxOutput(blockfrost);
    }

    /** FLDT-shaped: a v1 and a v2 oracle, two NFTs, one Charli3 provider out-ref — probed ONCE. */
    @Test
    void aProviderSharedByTwoVersionsIsProbedOnce() throws Exception {
        UtxoService blockfrost = mock(UtxoService.class);
        when(blockfrost.getUtxos(FEED_ADDRESS, FEED_NFT.toUnit(), 100, 1)).thenReturn(ok(FEED_UTXO));
        when(blockfrost.getUtxos(FEED_ADDRESS, FEED_NFT_V2.toUnit(), 100, 1)).thenReturn(ok(FEED_V2_UTXO));
        when(blockfrost.getUtxos(PROVIDER_ADDRESS, C3_NFT.toUnit(), 100, 1)).thenReturn(ok(PROVIDER_UTXO));

        assertDoesNotThrow(() -> probe(blockfrost, FEED_UTXO, FEED_V2_UTXO, PROVIDER_UTXO).requireLive(List.of(
                c3(FEED_NFT, FEED_REF, C3_NFT), c3(FEED_NFT_V2, FEED_REF_V2, C3_NFT))));

        verify(blockfrost, times(1)).getUtxos(FEED_ADDRESS, FEED_NFT.toUnit(), 100, 1);
        verify(blockfrost, times(1)).getUtxos(FEED_ADDRESS, FEED_NFT_V2.toUnit(), 100, 1);
        verify(blockfrost, times(1)).getUtxos(PROVIDER_ADDRESS, C3_NFT.toUnit(), 100, 1);
        assertNeverAskedForATxOutput(blockfrost);
        verifyNoMoreInteractions(blockfrost);
    }

    @Test
    void theProviderIsProbedWithTheC3NftNotTheFeedNft() throws Exception {
        UtxoService blockfrost = mock(UtxoService.class);
        when(blockfrost.getUtxos(FEED_ADDRESS, FEED_NFT.toUnit(), 100, 1)).thenReturn(ok(FEED_UTXO));
        when(blockfrost.getUtxos(PROVIDER_ADDRESS, C3_NFT.toUnit(), 100, 1)).thenReturn(ok(PROVIDER_UTXO));
        // anything else answers 404, so probing the provider with the FEED nft refuses
        when(blockfrost.getUtxos(PROVIDER_ADDRESS, FEED_NFT.toUnit(), 100, 1)).thenReturn(failed(404, "nf"));

        probe(blockfrost, FEED_UTXO, PROVIDER_UTXO).requireLive(List.of(c3(FEED_NFT, FEED_REF, C3_NFT)));

        verify(blockfrost).getUtxos(PROVIDER_ADDRESS, C3_NFT.toUnit(), 100, 1);
        verify(blockfrost, never()).getUtxos(PROVIDER_ADDRESS, FEED_NFT.toUnit(), 100, 1);
    }

    // ---- FAB-135 T4 amendment 2: the T3a audit residue ------------------------------------------------

    /**
     * What {@code LiquidationExecutor.spentInputEffect} would read as "a wallet input is spent": the text
     * with ALL whitespace removed, then either marker. Mirrored here with the executor's own constants, so
     * a whitespace-split marker that survives a naive scrub is caught exactly as the executor would catch it.
     */
    private static void assertExecutorReadsNoSpentInput(String message) {
        String compact = message.replaceAll("\\s", "");
        assertFalse(compact.contains(LiquidationExecutor.BAD_INPUTS_MARKER),
                "after the executor's whitespace compaction this refusal carries "
                        + LiquidationExecutor.BAD_INPUTS_MARKER + ": " + message);
        assertFalse(compact.contains(LiquidationExecutor.EMPTY_SCRIPT_FAILURES_MARKER),
                "after the executor's whitespace compaction this refusal carries "
                        + LiquidationExecutor.EMPTY_SCRIPT_FAILURES_MARKER + ": " + message);
    }

    /**
     * (a) CCL's real shape: {@code DefaultUtxoService} wraps the transport failure, so the outer message is
     * generic and the CAUSE is the one that says what happened. The refusal must keep it.
     */
    @Test
    void aWrappedProviderFailureKeepsItsRootCause() throws Exception {
        UtxoService blockfrost = mock(UtxoService.class);
        when(blockfrost.getUtxos(FEED_ADDRESS, FEED_NFT.toUnit(), 100, 1)).thenThrow(new ApiException(
                "Error getting utxos for address: " + FEED_ADDRESS,
                new SocketTimeoutException("Read timed out")));

        var e = refused(() -> probe(blockfrost, FEED_UTXO).requireLive(List.of(multisig())));

        assertTrue(e.getMessage().contains("Error getting utxos"), e.getMessage());
        assertTrue(e.getMessage().contains("SocketTimeoutException") && e.getMessage().contains("Read timed out"),
                "the root cause of a wrapped provider failure is lost: " + e.getMessage());
        assertExecutorReadsNoSpentInput(e.getMessage());
    }

    /** (a)+(c) every link of the cause chain is scrubbed, not just the outer one. */
    @Test
    void everyLinkOfTheCauseChainIsScrubbed() throws Exception {
        UtxoService blockfrost = mock(UtxoService.class);
        when(blockfrost.getUtxos(FEED_ADDRESS, FEED_NFT.toUnit(), 100, 1)).thenThrow(new ApiException(
                "Error getting utxos: BadInputsUTxO",
                new IllegalStateException("{\"ScriptFailures\":{}}",
                        new RuntimeException("Bad Inputs UTxO and \"Script Failures\" : { }"))));

        var e = refused(() -> probe(blockfrost, FEED_UTXO).requireLive(List.of(multisig())));

        assertTrue(e.getMessage().contains("IllegalStateException"), "the middle link is missing: " + e.getMessage());
        assertTrue(e.getMessage().contains("RuntimeException"), "the root link is missing: " + e.getMessage());
        assertExecutorReadsNoSpentInput(e.getMessage());
    }

    /** (c) a provider's 500 body carrying both markers — plain and whitespace-split — is defused. */
    @Test
    void aServerErrorBodyCarryingTheMarkersIsDefused() throws Exception {
        for (String body : List.of(
                "{\"error\":\"BadInputsUTxO\",\"result\":{\"ScriptFailures\":{}}}",
                "{\"error\":\"Bad Inputs\nUTxO\"}",
                "{\"result\": {\"Script\tFailures\" : { } } }")) {
            UtxoService blockfrost = mock(UtxoService.class);
            when(blockfrost.getUtxos(FEED_ADDRESS, FEED_NFT.toUnit(), 100, 1)).thenReturn(failed(500, body));

            var e = refused(() -> probe(blockfrost, FEED_UTXO).requireLive(List.of(multisig())));

            assertTrue(e.getMessage().contains("500"), e.getMessage());
            assertExecutorReadsNoSpentInput(e.getMessage());
        }
    }

    /** (c) an exception message carrying a whitespace-split marker is defused too. */
    @Test
    void anExceptionMessageWithAWhitespaceSplitMarkerIsDefused() throws Exception {
        for (String text : List.of("BadInputsUTxO", "Bad  Inputs UTxO", "\"ScriptFailures\":{}",
                "\"Script Failures\": {}")) {
            UtxoService blockfrost = mock(UtxoService.class);
            when(blockfrost.getUtxos(FEED_ADDRESS, FEED_NFT.toUnit(), 100, 1)).thenThrow(new ApiException(text));

            var e = refused(() -> probe(blockfrost, FEED_UTXO).requireLive(List.of(multisig())));

            assertExecutorReadsNoSpentInput(e.getMessage());
        }
    }

    /** (b) the same transaction, a DIFFERENT output holding the NFT: this out-ref is spent. */
    @Test
    void theSameTxHashAtAnotherIndexIsNotLive() throws Exception {
        Utxo sibling = holding(LoanFixtures.input(FEED_REF.getTransactionId(), FEED_REF.getIndex() + 1),
                FEED_ADDRESS, FEED_NFT);
        UtxoService blockfrost = mock(UtxoService.class);
        when(blockfrost.getUtxos(FEED_ADDRESS, FEED_NFT.toUnit(), 100, 1)).thenReturn(ok(sibling));

        var e = refused(() -> probe(blockfrost, FEED_UTXO).requireLive(List.of(multisig())));

        assertTrue(e.getMessage().contains("spent"), e.getMessage());
        assertTrue(e.getMessage().contains(FEED_REF.getTransactionId() + "#" + (FEED_REF.getIndex() + 1)),
                e.getMessage());
    }

    /** (b) a registry out-ref written in UPPERCASE is the same out-ref Blockfrost answers in lowercase. */
    @Test
    void theTxHashIsComparedCaseInsensitively() throws Exception {
        String upper = "9A".repeat(32);
        TransactionInput upperRef = LoanFixtures.input(upper, 0);
        Utxo content = holding(upperRef, FEED_ADDRESS, FEED_NFT);
        UtxoService blockfrost = mock(UtxoService.class);
        when(blockfrost.getUtxos(FEED_ADDRESS, FEED_NFT.toUnit(), 100, 1)).thenReturn(ok(FEED_UTXO));
        OracleEntry entry = new OracleEntry(PRICED, FEED_NFT, "", "a0".repeat(28), upperRef, SCRIPT_REF,
                List.of("00".repeat(32)), 1, OraclePriceFeed.aggregated(PRICED, BigInteger.ONE, BigInteger.ONE,
                0L, 1L), List.of(), null, 2, null);

        assertDoesNotThrow(() -> probe(blockfrost, content).requireLive(List.of(entry)));
    }

    @Test
    void noEntriesMeansNoCalls() throws Exception {
        UtxoService blockfrost = mock(UtxoService.class);

        probe(blockfrost).requireLive(List.of());

        verifyNoMoreInteractions(blockfrost);
    }

    /** Every refusal text this class can produce, gathered and checked for the two §13 markers. */
    @Test
    void noRefusalTextCarriesASpentInputMarker() throws Exception {
        a404IsSpentOrTheNftIsNotThere();
        aRateLimitAndAServerErrorAreProviderErrors();
        aThrowingProviderIsAProviderErrorNotAnEscapingException();
        theNftLiveElsewhereMeansThisOutRefIsSpent();
        twoEntriesExpectingDifferentNftsAtOneOutRefRefuseBeforeAnyCall();
        aC3EntryWithNoProviderNftRefuses();
        anOutRefWhoseContentIsUnknownRefuses();

        assertTrue(refusals.size() >= 8, "every refusal case ran: " + refusals);
        refusals.forEach(OracleReferenceInputProbeTest::assertNoSpentInputMarker);
    }
}
