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
import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * ⛔ FAB-196-1: the oracle reference-input probe's verdicts, PINNED EXACTLY as they stand before FAB-196 moves
 * liveness onto the index.
 *
 * <p>{@link OracleReferenceInputProbeTest} checks each verdict by fragments ({@code contains("spent")}). This class
 * pins the whole of it, per case: the COMPLETE refusal message, {@code notYetVisible()}, and every Blockfrost
 * interaction — how many {@code getUtxos(address, unit, 100, 1)} calls, that {@code getTxOutput} is never asked,
 * and nothing else. So "no change in verdicts" is a mechanical gate: any reworded refusal, any extra or missing
 * provider call, any flipped wait/fault flag reddens a named case here.
 *
 * <p>⛔ <b>Slices FAB-196-2 and FAB-196-3 may not edit this class.</b> A verdict that must change is a contract
 * decision, not a test fix.
 *
 * <p>Every refusal is also checked against what {@code LiquidationExecutor} reads as a spent WALLET input (its
 * own markers, after its own whitespace compaction): a probe refusal that carried one would drop an innocent
 * nominated wallet utxo ({@code ccl-transaction-building-traps} §13).
 */
class OracleReferenceInputProbeVerdictPinTest {

    private static final AssetType PRICED = new AssetType("c0".repeat(28), "544f4b");
    private static final AssetType FEED_NFT = new AssetType("b0".repeat(28), "4f52434c");
    private static final AssetType FEED_NFT_V2 = new AssetType("b1".repeat(28), "4f52434c");
    private static final AssetType C3_NFT = new AssetType("d0".repeat(28), "4f7261636c6546656564");
    private static final AssetType C3_NFT_V2 = new AssetType("d1".repeat(28), "4f7261636c6546656564");

    private static final String FEED_TX = "9a".repeat(32);
    private static final String SCRIPT_TX = "9b".repeat(32);
    private static final String PROVIDER_TX = "9c".repeat(32);
    private static final String MOVED_TX = "77".repeat(32);

    private static final TransactionInput FEED_REF = LoanFixtures.input(FEED_TX, 0);
    private static final TransactionInput SCRIPT_REF = LoanFixtures.input(SCRIPT_TX, 0);
    private static final TransactionInput PROVIDER_REF = LoanFixtures.input(PROVIDER_TX, 0);

    private static final String FEED_ADDRESS =
            "addr_test1wpqzexzdvwtl2zxw6pn5v34m9lxk0avnckmemy0puhqtzfqw4jw8q";
    private static final String PROVIDER_ADDRESS =
            "addr_test1wzgy7cu7mnnjau2qn5th8932tr27f83tfgusm60sklwppmgh6re39";

    /** The units, spelled out rather than derived, so a change to {@code AssetType.toUnit} cannot move a pin. */
    private static final String FEED_UNIT = "b0".repeat(28) + "4f52434c";
    private static final String FEED_V2_UNIT = "b1".repeat(28) + "4f52434c";
    private static final String C3_UNIT = "d0".repeat(28) + "4f7261636c6546656564";
    private static final String C3_V2_UNIT = "d1".repeat(28) + "4f7261636c6546656564";

    private static final Utxo FEED_UTXO = holding(FEED_REF, FEED_ADDRESS, FEED_NFT);
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

    /** A multisig entry: no provider out-ref at all. */
    private static OracleEntry multisig(AssetType oracleNft, TransactionInput feedRef) {
        return new OracleEntry(PRICED, oracleNft, "", "a0".repeat(28), feedRef, SCRIPT_REF,
                List.of("00".repeat(32)), 1, OraclePriceFeed.aggregated(PRICED, BigInteger.ONE, BigInteger.ONE,
                0L, 1L), List.of(), null, 2, null);
    }

    private static OracleEntry multisig() {
        return multisig(FEED_NFT, FEED_REF);
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

    /** The refusal, checked for the executor's spent-input markers exactly as the executor would read it. */
    private static OracleReferenceInputProbe.OracleReferenceInputNotLiveException refused(Runnable call) {
        var e = assertThrows(OracleReferenceInputProbe.OracleReferenceInputNotLiveException.class, call::run);
        String compact = e.getMessage().replaceAll("\\s", "");
        assertFalse(compact.contains("BadInputs"), "a probe refusal carries BadInputs: " + e.getMessage());
        assertFalse(compact.contains("ScriptFailures"), "a probe refusal carries ScriptFailures: " + e.getMessage());
        assertFalse(compact.contains(LiquidationExecutor.BAD_INPUTS_MARKER),
                "after the executor's whitespace compaction this refusal carries "
                        + LiquidationExecutor.BAD_INPUTS_MARKER + ": " + e.getMessage());
        assertFalse(compact.contains(LiquidationExecutor.EMPTY_SCRIPT_FAILURES_MARKER),
                "after the executor's whitespace compaction this refusal carries "
                        + LiquidationExecutor.EMPTY_SCRIPT_FAILURES_MARKER + ": " + e.getMessage());
        return e;
    }

    /** Exactly {@code k} live-UTxO reads of {@code (address, unit)}, never a {@code getTxOutput}. */
    private static void asked(UtxoService blockfrost, int k, String address, String unit) throws ApiException {
        verify(blockfrost, times(k)).getUtxos(address, unit, 100, 1);
    }

    /** Never a {@code getTxOutput}, and no call beyond those already verified. */
    private static void nothingElse(UtxoService blockfrost) throws ApiException {
        verify(blockfrost, never()).getTxOutput(anyString(), anyInt());
        verifyNoMoreInteractions(blockfrost);
    }

    /** A refusal decided without the network: no call of any kind. */
    private static void noCallAtAll(UtxoService blockfrost) throws ApiException {
        verify(blockfrost, never()).getUtxos(anyString(), anyString(), anyInt(), anyInt());
        nothingElse(blockfrost);
    }

    // ---- passes ------------------------------------------------------------------------------------

    @Test
    void aLiveFeedAndALiveProviderPassWithTwoCalls() throws Exception {
        UtxoService blockfrost = mock(UtxoService.class);
        when(blockfrost.getUtxos(FEED_ADDRESS, FEED_UNIT, 100, 1)).thenReturn(ok(FEED_UTXO));
        when(blockfrost.getUtxos(PROVIDER_ADDRESS, C3_UNIT, 100, 1)).thenReturn(ok(PROVIDER_UTXO));

        assertDoesNotThrow(() -> probe(blockfrost, FEED_UTXO, PROVIDER_UTXO)
                .requireLive(List.of(c3(FEED_NFT, FEED_REF, C3_NFT))));

        asked(blockfrost, 1, FEED_ADDRESS, FEED_UNIT);
        asked(blockfrost, 1, PROVIDER_ADDRESS, C3_UNIT);
        nothingElse(blockfrost);
    }

    @Test
    void aLiveMultisigFeedPassesWithOneCall() throws Exception {
        UtxoService blockfrost = mock(UtxoService.class);
        when(blockfrost.getUtxos(FEED_ADDRESS, FEED_UNIT, 100, 1)).thenReturn(ok(FEED_UTXO));

        assertDoesNotThrow(() -> probe(blockfrost, FEED_UTXO).requireLive(List.of(multisig())));

        asked(blockfrost, 1, FEED_ADDRESS, FEED_UNIT);
        nothingElse(blockfrost);
    }

    @Test
    void anEmptyListPassesWithNoCall() throws Exception {
        UtxoService blockfrost = mock(UtxoService.class);

        assertDoesNotThrow(() -> probe(blockfrost).requireLive(List.of()));

        noCallAtAll(blockfrost);
    }

    // ---- refusals after one live-UTxO read ---------------------------------------------------------

    @Test
    void a404() throws Exception {
        UtxoService blockfrost = mock(UtxoService.class);
        when(blockfrost.getUtxos(FEED_ADDRESS, FEED_UNIT, 100, 1))
                .thenReturn(failed(404, "{\"status_code\":404,\"error\":\"Not Found\"}"));

        var e = refused(() -> probe(blockfrost, FEED_UTXO).requireLive(List.of(multisig())));

        assertEquals("oracle out-ref " + FEED_TX + "#0 (expected NFT " + FEED_UNIT + " at " + FEED_ADDRESS
                + "): spent, or the NFT is not at " + FEED_ADDRESS + " (404)", e.getMessage());
        assertFalse(e.notYetVisible());
        asked(blockfrost, 1, FEED_ADDRESS, FEED_UNIT);
        nothingElse(blockfrost);
    }

    @Test
    void a429WithABody() throws Exception {
        String body = "{\"status_code\":429,\"error\":\"Project Over Limit\",\"message\":\"Usage is over limit.\"}";
        UtxoService blockfrost = mock(UtxoService.class);
        when(blockfrost.getUtxos(FEED_ADDRESS, FEED_UNIT, 100, 1)).thenReturn(failed(429, body));

        var e = refused(() -> probe(blockfrost, FEED_UTXO).requireLive(List.of(multisig())));

        assertEquals("oracle out-ref " + FEED_TX + "#0 (expected NFT " + FEED_UNIT + " at " + FEED_ADDRESS
                + "): live-UTxO check failed, provider error 429 "
                + "{\"status_code\":429,\"error\":\"Project Over Limit\",\"message\":\"Usage is over limit.\"}",
                e.getMessage());
        assertFalse(e.notYetVisible());
        asked(blockfrost, 1, FEED_ADDRESS, FEED_UNIT);
        nothingElse(blockfrost);
    }

    /** CCL's real shape: the transport failure wrapped in an {@code ApiException}. The whole chain is the text. */
    @Test
    void aThrownApiException() throws Exception {
        UtxoService blockfrost = mock(UtxoService.class);
        when(blockfrost.getUtxos(FEED_ADDRESS, FEED_UNIT, 100, 1)).thenThrow(new ApiException(
                "Error getting utxos for address: " + FEED_ADDRESS, new SocketTimeoutException("Read timed out")));

        var e = refused(() -> probe(blockfrost, FEED_UTXO).requireLive(List.of(multisig())));

        assertEquals("oracle out-ref " + FEED_TX + "#0 (expected NFT " + FEED_UNIT + " at " + FEED_ADDRESS
                + "): live-UTxO check failed, provider error "
                + "com.bloxbean.cardano.client.api.exception.ApiException: Error getting utxos for address: "
                + FEED_ADDRESS + " <- caused by java.net.SocketTimeoutException: Read timed out", e.getMessage());
        assertFalse(e.notYetVisible());
        asked(blockfrost, 1, FEED_ADDRESS, FEED_UNIT);
        nothingElse(blockfrost);
    }

    @Test
    void aNullAnswer() throws Exception {
        UtxoService blockfrost = mock(UtxoService.class);
        when(blockfrost.getUtxos(FEED_ADDRESS, FEED_UNIT, 100, 1)).thenReturn(null);

        var e = refused(() -> probe(blockfrost, FEED_UTXO).requireLive(List.of(multisig())));

        assertEquals("oracle out-ref " + FEED_TX + "#0 (expected NFT " + FEED_UNIT + " at " + FEED_ADDRESS
                + "): live-UTxO check failed, provider error: no answer", e.getMessage());
        assertFalse(e.notYetVisible());
        asked(blockfrost, 1, FEED_ADDRESS, FEED_UNIT);
        nothingElse(blockfrost);
    }

    @Test
    void theNftSittingAtAnotherOutRef() throws Exception {
        UtxoService blockfrost = mock(UtxoService.class);
        when(blockfrost.getUtxos(FEED_ADDRESS, FEED_UNIT, 100, 1))
                .thenReturn(ok(holding(LoanFixtures.input(MOVED_TX, 3), FEED_ADDRESS, FEED_NFT)));

        var e = refused(() -> probe(blockfrost, FEED_UTXO).requireLive(List.of(multisig())));

        assertEquals("oracle out-ref " + FEED_TX + "#0 (expected NFT " + FEED_UNIT + " at " + FEED_ADDRESS
                + "): spent: the NFT now sits at [" + MOVED_TX + "#3]", e.getMessage());
        assertFalse(e.notYetVisible());
        asked(blockfrost, 1, FEED_ADDRESS, FEED_UNIT);
        nothingElse(blockfrost);
    }

    @Test
    void theSameTxHashAtAnotherIndex() throws Exception {
        UtxoService blockfrost = mock(UtxoService.class);
        when(blockfrost.getUtxos(FEED_ADDRESS, FEED_UNIT, 100, 1))
                .thenReturn(ok(holding(LoanFixtures.input(FEED_TX, 1), FEED_ADDRESS, FEED_NFT)));

        var e = refused(() -> probe(blockfrost, FEED_UTXO).requireLive(List.of(multisig())));

        assertEquals("oracle out-ref " + FEED_TX + "#0 (expected NFT " + FEED_UNIT + " at " + FEED_ADDRESS
                + "): spent: the NFT now sits at [" + FEED_TX + "#1]", e.getMessage());
        assertFalse(e.notYetVisible());
        asked(blockfrost, 1, FEED_ADDRESS, FEED_UNIT);
        nothingElse(blockfrost);
    }

    /** A 500 whose body spells {@code Bad Inputs UTxO} with a space: defused to {@code Bad-Inputs}. */
    @Test
    void aServerErrorBodyContainingBadInputsUtxo() throws Exception {
        UtxoService blockfrost = mock(UtxoService.class);
        when(blockfrost.getUtxos(FEED_ADDRESS, FEED_UNIT, 100, 1))
                .thenReturn(failed(500, "{\"error\":\"Bad Inputs UTxO\"}"));

        var e = refused(() -> probe(blockfrost, FEED_UTXO).requireLive(List.of(multisig())));

        assertEquals("oracle out-ref " + FEED_TX + "#0 (expected NFT " + FEED_UNIT + " at " + FEED_ADDRESS
                + "): live-UTxO check failed, provider error 500 {\"error\":\"Bad-Inputs UTxO\"}", e.getMessage());
        assertFalse(e.notYetVisible());
        asked(blockfrost, 1, FEED_ADDRESS, FEED_UNIT);
        nothingElse(blockfrost);
    }

    // ---- refusals decided before any call ----------------------------------------------------------

    /** The one wait: no content for the out-ref, so its address is unknown. */
    @Test
    void contentUnknownIsNotYetVisible() throws Exception {
        UtxoService blockfrost = mock(UtxoService.class);

        var e = refused(() -> probe(blockfrost).requireLive(List.of(multisig())));

        assertEquals("oracle out-ref " + FEED_TX + "#0 (expected NFT " + FEED_UNIT
                + "): its address is unknown — no content for it", e.getMessage());
        assertTrue(e.notYetVisible());
        noCallAtAll(blockfrost);
    }

    /**
     * Ordering: every address is resolved BEFORE any live read, so a provider whose content is unknown wins
     * over a feed that would have answered 404 — the wait, with no {@code getUtxos} at all.
     */
    @Test
    void anUnknownProviderWinsOverAFeedThatWould404() throws Exception {
        UtxoService blockfrost = mock(UtxoService.class);
        when(blockfrost.getUtxos(FEED_ADDRESS, FEED_UNIT, 100, 1)).thenReturn(failed(404, "{\"status_code\":404}"));

        var e = refused(() -> probe(blockfrost, FEED_UTXO).requireLive(List.of(c3(FEED_NFT, FEED_REF, C3_NFT))));

        assertEquals("oracle out-ref " + PROVIDER_TX + "#0 (expected NFT " + C3_UNIT
                + "): its address is unknown — no content for it", e.getMessage());
        assertTrue(e.notYetVisible());
        noCallAtAll(blockfrost);
    }

    @Test
    void twoEntriesExpectingDifferentNftsAtOneOutRef() throws Exception {
        UtxoService blockfrost = mock(UtxoService.class);

        var e = refused(() -> probe(blockfrost, FEED_UTXO, PROVIDER_UTXO).requireLive(List.of(
                c3(FEED_NFT, FEED_REF, C3_NFT), c3(FEED_NFT_V2, FEED_REF, C3_NFT))));

        assertEquals("oracle feed " + FEED_TX + "#0: two oracle entries expect different NFTs there ("
                + FEED_UNIT + " and " + FEED_V2_UNIT + ")", e.getMessage());
        assertFalse(e.notYetVisible());
        noCallAtAll(blockfrost);
    }

    @Test
    void aC3EntryWithANullProviderNft() throws Exception {
        UtxoService blockfrost = mock(UtxoService.class);

        var e = refused(() -> probe(blockfrost, FEED_UTXO, PROVIDER_UTXO)
                .requireLive(List.of(c3(FEED_NFT, FEED_REF, null))));

        assertEquals("Charli3 provider " + PROVIDER_TX
                + "#0: the registry names no NFT for it, so its liveness cannot be checked", e.getMessage());
        assertFalse(e.notYetVisible());
        noCallAtAll(blockfrost);
    }

    @Test
    void aNullFeedNft() throws Exception {
        UtxoService blockfrost = mock(UtxoService.class);

        var e = refused(() -> probe(blockfrost, FEED_UTXO).requireLive(List.of(multisig(null, FEED_REF))));

        assertEquals("oracle feed " + FEED_TX
                + "#0: the registry names no NFT for it, so its liveness cannot be checked", e.getMessage());
        assertFalse(e.notYetVisible());
        noCallAtAll(blockfrost);
    }

    @Test
    void aNullFeedOutRef() throws Exception {
        UtxoService blockfrost = mock(UtxoService.class);

        var e = refused(() -> probe(blockfrost, FEED_UTXO).requireLive(List.of(multisig(FEED_NFT, null))));

        assertEquals("the oracle feed names no out-ref, so its liveness cannot be checked", e.getMessage());
        assertFalse(e.notYetVisible());
        noCallAtAll(blockfrost);
    }

    // ---- FAB-196-2 Task 1b: four more of today's verdicts, pinned before the index is consulted ---------

    /** (p17) A SUCCESSFUL answer with an empty list: the NFT is nowhere live at the address. */
    @Test
    void aSuccessfulEmptyAnswer() throws Exception {
        UtxoService blockfrost = mock(UtxoService.class);
        when(blockfrost.getUtxos(FEED_ADDRESS, FEED_UNIT, 100, 1)).thenReturn(ok());

        var e = refused(() -> probe(blockfrost, FEED_UTXO).requireLive(List.of(multisig())));

        assertEquals("oracle out-ref " + FEED_TX + "#0 (expected NFT " + FEED_UNIT + " at " + FEED_ADDRESS
                + "): spent: the NFT now sits at []", e.getMessage());
        assertFalse(e.notYetVisible());
        asked(blockfrost, 1, FEED_ADDRESS, FEED_UNIT);
        nothingElse(blockfrost);
    }

    /** (p18) A 500 whose body spells {@code Script Failures} with a space: defused to {@code Script-Failures}. */
    @Test
    void aServerErrorBodyContainingScriptFailures() throws Exception {
        UtxoService blockfrost = mock(UtxoService.class);
        when(blockfrost.getUtxos(FEED_ADDRESS, FEED_UNIT, 100, 1))
                .thenReturn(failed(500, "{\"error\":\"Script Failures {}\"}"));

        var e = refused(() -> probe(blockfrost, FEED_UTXO).requireLive(List.of(multisig())));

        assertEquals("oracle out-ref " + FEED_TX + "#0 (expected NFT " + FEED_UNIT + " at " + FEED_ADDRESS
                + "): live-UTxO check failed, provider error 500 {\"error\":\"Script-Failures {}\"}", e.getMessage());
        assertFalse(e.notYetVisible());
        asked(blockfrost, 1, FEED_ADDRESS, FEED_UNIT);
        nothingElse(blockfrost);
    }

    /** (p19) Two entries expecting different NFTs at one Charli3 PROVIDER out-ref: refused with no call. */
    @Test
    void twoEntriesExpectingDifferentNftsAtOneProviderOutRef() throws Exception {
        UtxoService blockfrost = mock(UtxoService.class);

        var e = refused(() -> probe(blockfrost, FEED_UTXO, PROVIDER_UTXO).requireLive(List.of(
                c3(FEED_NFT, FEED_REF, C3_NFT), c3(FEED_NFT, FEED_REF, C3_NFT_V2))));

        assertEquals("Charli3 provider " + PROVIDER_TX + "#0: two oracle entries expect different NFTs there ("
                + C3_UNIT + " and " + C3_V2_UNIT + ")", e.getMessage());
        assertFalse(e.notYetVisible());
        noCallAtAll(blockfrost);
    }

    /** (p20) Content present but with a null address: the same wait as no content at all, with no call. */
    @Test
    void contentWithANullAddressIsNotYetVisible() throws Exception {
        UtxoService blockfrost = mock(UtxoService.class);
        Utxo addressless = holding(FEED_REF, null, FEED_NFT);

        var e = refused(() -> probe(blockfrost, addressless).requireLive(List.of(multisig())));

        assertEquals("oracle out-ref " + FEED_TX + "#0 (expected NFT " + FEED_UNIT
                + "): its address is unknown — no content for it", e.getMessage());
        assertTrue(e.notYetVisible());
        noCallAtAll(blockfrost);
    }
}
