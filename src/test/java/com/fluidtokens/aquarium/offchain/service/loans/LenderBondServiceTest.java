package com.fluidtokens.aquarium.offchain.service.loans;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.util.HexUtil;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link LenderBondService#toLenderBond(Utxo, String)} — the per-UTxO conversion — with
 * constructed {@link Utxo} objects rather than a mocked {@code UtxoRepository}. Package-private
 * on purpose, mirroring {@code LoanService.toLoan}.
 */
class LenderBondServiceTest {

    private static final String LENDER_BOND_POLICY = "aa11bb22cc33dd44ee55ff66aa11bb22cc33dd44ee55ff66aa11bb2c";
    private static final String FOREIGN_POLICY = "ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff0e";
    private static final String LOAN_ID = "112233";

    private final LenderBondService service = new LenderBondService(null, null);

    private static String syntheticDatumHex() {
        return syntheticDatumHex(0, BytesPlutusData.of(new byte[0]));
    }

    /**
     * Same shape as {@link #syntheticDatumHex()} but with the outer alternative and the
     * {@code poolId} field parameterised, for the malformed- and chunked-input tests below.
     */
    private static String syntheticDatumHex(int outerAlternative, BytesPlutusData poolId) {
        var lenderAuth = ConstrPlutusData.of(0, BytesPlutusData.of(HexUtil.decodeHexString("aa")));
        var stakeCredential = ConstrPlutusData.of(1);
        var asset = ConstrPlutusData.of(0, BytesPlutusData.of(new byte[0]), BytesPlutusData.of(new byte[0]));
        var datum = ConstrPlutusData.of(outerAlternative,
                lenderAuth, stakeCredential, ConstrPlutusData.of(0),
                BigIntPlutusData.of(0), poolId, asset);
        return datum.serializeToHex();
    }

    private static Utxo utxo(List<Amount> amounts, String inlineDatum) {
        return Utxo.builder()
                .txHash("deadbeef")
                .outputIndex(0)
                .address("addr_test1...")
                .amount(amounts)
                .inlineDatum(inlineDatum)
                .build();
    }

    @Test
    void zeroBondNftsAreSkipped() {
        var utxo = utxo(List.of(Amount.lovelace(BigInteger.valueOf(2_000_000))), syntheticDatumHex());

        assertTrue(service.toLenderBond(utxo, LENDER_BOND_POLICY).isEmpty());
    }

    @Test
    void twoBondNftsAreSkipped() {
        var amounts = List.of(
                Amount.lovelace(BigInteger.valueOf(2_000_000)),
                Amount.asset(LENDER_BOND_POLICY + LOAN_ID, BigInteger.ONE),
                Amount.asset(LENDER_BOND_POLICY + "445566", BigInteger.ONE));
        var utxo = utxo(amounts, syntheticDatumHex());

        assertTrue(service.toLenderBond(utxo, LENDER_BOND_POLICY).isEmpty());
    }

    /**
     * A token under a different policy sitting alongside the genuine bond NFT must not affect
     * the count. A mutant that counts "any non-ada asset" instead of "assets under
     * {@code lenderBondPolicyId}" would see two assets here and wrongly skip.
     */
    @Test
    void bondNftCountIsScopedToItsOwnPolicyForeignTokenIsIgnored() {
        var amounts = List.of(
                Amount.lovelace(BigInteger.valueOf(2_000_000)),
                Amount.asset(LENDER_BOND_POLICY + LOAN_ID, BigInteger.ONE),
                Amount.asset(FOREIGN_POLICY + "445566", BigInteger.ONE));
        var utxo = utxo(amounts, syntheticDatumHex());

        var bond = service.toLenderBond(utxo, LENDER_BOND_POLICY);

        assertTrue(bond.isPresent(), "a token under a different policy must not count toward the bond-NFT check");
        assertEquals(LOAN_ID, bond.get().loanId());
    }

    /**
     * Distinguishes the explicit no-inline-datum guard from the generic decode-failure catch
     * block: with {@code inlineDatum == null}, deleting the guard would have
     * {@code converter.deserialize(null)} NPE inside the try block, which the catch-all also
     * turns into a skip — so a plain "isEmpty()" assertion is green either way. Capturing the
     * actual log line and asserting it is the guard's own message (not the catch block's) is
     * what proves the guard is the mechanism.
     */
    @Test
    void noInlineDatumIsSkippedViaItsOwnGuardNotTheCatchAll() {
        var amounts = List.of(
                Amount.lovelace(BigInteger.valueOf(2_000_000)),
                Amount.asset(LENDER_BOND_POLICY + LOAN_ID, BigInteger.ONE));
        var utxo = utxo(amounts, null);

        var logger = (Logger) LoggerFactory.getLogger(LenderBondService.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertTrue(service.toLenderBond(utxo, LENDER_BOND_POLICY).isEmpty());

            assertEquals(1, appender.list.size(), "expected exactly one log line for the skipped utxo");
            ILoggingEvent event = appender.list.get(0);
            assertEquals(Level.WARN, event.getLevel());
            assertTrue(event.getFormattedMessage().contains("no inline datum"),
                    "the explicit no-inline-datum guard must fire — got: " + event.getFormattedMessage());
            assertFalse(event.getFormattedMessage().contains("could not decode"),
                    "a null inline datum must not fall through to the generic decode-failure branch");
        } finally {
            logger.detachAppender(appender);
        }
    }

    /**
     * A genuine bond NFT with an inline datum that fails to decode (wrong outer alternative
     * here — structurally valid CBOR, just not a {@code LenderManagerDatum}) must be skipped,
     * not thrown: "one bad UTxO must not blank the scan" — anyone can pay junk to a script
     * address.
     */
    @Test
    void undecodableInlineDatumIsSkippedNotThrown() {
        var amounts = List.of(
                Amount.lovelace(BigInteger.valueOf(2_000_000)),
                Amount.asset(LENDER_BOND_POLICY + LOAN_ID, BigInteger.ONE));
        var badDatum = syntheticDatumHex(1, BytesPlutusData.of(new byte[0])); // wrong outer alternative
        var utxo = utxo(amounts, badDatum);

        assertTrue(service.toLenderBond(utxo, LENDER_BOND_POLICY).isEmpty());
    }

    @Test
    void goodUtxoDecodesWithCorrectLoanIdAndRawInlineDatum() {
        var amounts = List.of(
                Amount.lovelace(BigInteger.valueOf(2_000_000)),
                Amount.asset(LENDER_BOND_POLICY + LOAN_ID, BigInteger.ONE));
        var hex = syntheticDatumHex();
        var utxo = utxo(amounts, hex);

        var bond = service.toLenderBond(utxo, LENDER_BOND_POLICY).orElseThrow();

        assertEquals(LOAN_ID, bond.loanId());
        assertEquals(hex, bond.inlineDatum(), "inlineDatum must be preserved raw for the byte-identical echo");
        assertEquals("deadbeef", bond.txHash());
        assertEquals(0, bond.outputIndex());
        assertEquals("addr_test1...", bond.address());
    }

    /**
     * A datum built entirely from short fields round-trips through cardano-client-lib unchanged
     * whether or not {@code inlineDatum} is actually preserved raw, so it cannot catch a
     * regression that decodes and re-serialises instead of echoing the input bytes. A
     * bytestring over 64 bytes ({@code PlutusData.BYTES_LIMIT}) forces CBOR chunking, and the
     * library always splits it at a maximal 64-byte boundary
     * ({@code Bytes.getChunks(value, 64)}). Hand-chunking the same 65-byte poolId as 60 + 5
     * instead produces different bytes than the library would ever emit for it, so if this
     * inline datum were ever decoded and re-serialised on its way into {@link
     * com.fluidtokens.aquarium.offchain.model.loans.LenderBond#inlineDatum()}, the mismatch
     * would be caught here.
     */
    @Test
    void inlineDatumSurvivesAChunkedBytestringByteIdentical() {
        var poolIdBytes = "ab".repeat(65);
        var libraryCanonicalHex = syntheticDatumHex(0, BytesPlutusData.of(HexUtil.decodeHexString(poolIdBytes)));

        // cardano-client-lib's own chunking of a 65-byte string: one 64-byte chunk, one 1-byte chunk.
        var libraryChunk = "5f5840" + "ab".repeat(64) + "41ab" + "ff";
        assertTrue(libraryCanonicalHex.contains(libraryChunk),
                "test assumption: cardano-client-lib chunks a 65-byte bytestring as 64+1 — got " + libraryCanonicalHex);

        // Hand-rolled alternate chunking of the same 65 bytes: 60 + 5. The library would never
        // produce this split on its own, so a decode/re-encode mutant reverting to 64+1 is caught.
        var handRolledChunk = "5f583c" + "ab".repeat(60) + "45" + "ab".repeat(5) + "ff";
        var rawHex = libraryCanonicalHex.replace(libraryChunk, handRolledChunk);
        assertNotEquals(libraryCanonicalHex, rawHex,
                "hand-rolled chunking must differ from the library's own, or this test proves nothing");

        var amounts = List.of(
                Amount.lovelace(BigInteger.valueOf(2_000_000)),
                Amount.asset(LENDER_BOND_POLICY + LOAN_ID, BigInteger.ONE));
        var utxo = utxo(amounts, rawHex);

        var bond = service.toLenderBond(utxo, LENDER_BOND_POLICY)
                .orElseThrow(() -> new AssertionError("hand-rolled chunked datum must still decode"));

        assertEquals(rawHex, bond.inlineDatum(),
                "inlineDatum must be preserved byte-identical, not decoded and re-serialised");
    }
}
