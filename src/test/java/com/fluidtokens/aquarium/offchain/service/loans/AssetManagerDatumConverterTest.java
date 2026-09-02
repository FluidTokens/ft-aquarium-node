package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.exception.CborRuntimeException;
import com.fluidtokens.aquarium.offchain.model.loans.AssetManagerDatumWithToken;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes a <b>real</b> repayment escrow, not a synthetic one.
 *
 * <p>The fixture is the inline datum of output 0 of preview transaction
 * {@code 9088270f2d941354cb52594a33a6aba0dc29eb34a2d85fa6a852256d6ceefa59} (2026-09-01 11:22:05Z) —
 * the final repayment of loan {@code 2e2048fc…}, whose 45,000,000 lovelace it escrows for the lender
 * (findings §19.7, §20). Synthetic CBOR would only prove the decoder agrees with whatever this repo
 * believes the shape is; this proves it agrees with what FluidTokens' own builder actually wrote.
 */
class AssetManagerDatumConverterTest {

    private static final String LOAN_ID = "2e2048fcc960c6ee63c11fb4f231eac6e197a7c15a2585c3205de35e";
    private static final String LENDER_BOND_POLICY = "bcd713bb7858d4b08738bed90ee7068d8f9b38d02e0cae0b45ac7a9b";

    private final AssetManagerDatumConverter converter = new AssetManagerDatumConverter();

    private static String escrow() throws IOException {
        try (InputStream is = AssetManagerDatumConverterTest.class
                .getResourceAsStream("/loans-v4/repayment-escrow-datum-2026-09-01.hex")) {
            if (is == null) {
                throw new IllegalStateException("escrow fixture missing from the test classpath");
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    @Test
    void decodesTheRealRepaymentEscrow() throws IOException {
        AssetManagerDatumWithToken d = converter.deserialize(escrow());

        assertEquals("1281429c1ab09ef78894b3f67f47dcb7c80cb2f9a60128e84e143639c395f461",
                d.transactionId(), "the OutputReference points at the loan's own mint transaction");
        assertEquals(1, d.outputIndex());
        assertEquals(LENDER_BOND_POLICY, d.ownerAsset().policyId(),
                "ownerAsset is the LENDER bond — the escrow is owed to the lender, not the borrower");
        assertEquals(LOAN_ID, d.ownerAsset().assetName());
    }

    /**
     * The action is an opaque {@code ByteArray} label chosen by whoever built the transaction. It
     * decodes to {@code "installment_repayment"} here, and that string is a comment rather than a
     * protocol concept — the loan had {@code totalInstallments = 0} (findings §19.7). Pinned as hex
     * so nobody is tempted to branch on the text.
     */
    @Test
    void theActionIsAnOpaqueLabelAndIsKeptAsHex() throws IOException {
        assertEquals("696e7374616c6c6d656e745f72657061796d656e74", converter.deserialize(escrow()).action());
    }

    /**
     * ⚠ The {@code data} field carries the loan's terms on this real escrow, not the {@code None}
     * the bot's own encoder writes. Decoding must ignore it — it is {@code Data} and anything read
     * from it would be a guess about someone else's builder.
     */
    @Test
    void theUnmodelledDataFieldDoesNotBreakDecoding() throws IOException {
        assertTrue(escrow().contains("1a02625a00"),
                "the fixture really does carry a populated data field (40 ADA principal)");
        converter.deserialize(escrow());
    }

    /**
     * {@code AssetManagerDatumWithHash} is a legitimate variant that no token-owned action can
     * collect, so it must be classifiable without being mistaken for corruption.
     */
    @Test
    void theHashOwnedVariantIsClassifiedNotTreatedAsJunk() throws IOException {
        // Same datum, constructor 1 (d87a) instead of 0 (d879).
        String hashOwned = "d87a9f" + escrow().substring("d8799f".length());

        assertFalse(converter.isTokenOwned(hashOwned));
        assertTrue(converter.isTokenOwned(escrow()));

        CborRuntimeException e = assertThrows(CborRuntimeException.class,
                () -> converter.deserialize(hashOwned));
        assertTrue(e.getMessage().contains("AssetManagerDatumWithHash"), e.getMessage());
    }

    @Test
    void junkIsRejectedAndIsNotReportedAsTokenOwned() {
        assertFalse(converter.isTokenOwned("deadbeef"));
        assertFalse(converter.isTokenOwned(""));
        assertThrows(Exception.class, () -> converter.deserialize("deadbeef"));
    }

    /** A shape change must fail loudly rather than shift every field by one. */
    @Test
    void aWrongFieldCountIsRejected() {
        assertThrows(CborRuntimeException.class,
                () -> converter.deserialize("d8799fd8799f4101" + "01ff4100ff"));
    }
}
