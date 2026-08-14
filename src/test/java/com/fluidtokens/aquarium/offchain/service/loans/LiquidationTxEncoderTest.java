package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.AssetManagerDatumWithToken;
import com.fluidtokens.aquarium.offchain.model.loans.AuthorizationMethod;
import com.fluidtokens.aquarium.offchain.model.loans.ClaimData;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationMode;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.model.loans.OracleSignature;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Golden serialized-hex tests for {@link LiquidationTxEncoder}, in the {@code OracleFeedSchemaTest}
 * discipline: every expected hex below was derived once, independently of
 * {@code LiquidationTxEncoder} itself, by reasoning through the CBOR each field must produce
 * (constructor tags {@code 121 + alternative}, indefinite-length lists for non-empty field lists,
 * definite empty arrays — {@code 0x80} — for empty ones, minimal-length integer/bytestring
 * headers) and is transcribed here as a literal constant with a byte-by-byte breakdown comment.
 * None of them were captured by running the encoder and copying its output — a change to the
 * encoder that still satisfies {@code LiquidationTxEncoderSchemaTest} would still be caught here.
 * <p>
 * The {@code PriceDataCharlie} feed bytes reused in
 * {@link #oracleRedeemerWrapsAPriceDataCharlieFeedWithEmptySignatures()} are the exact bytes
 * already pinned independently in {@code OracleFeedSchemaTest#priceDataCharlieEncodesToTheExactPinnedBytes}.
 */
class LiquidationTxEncoderTest {

    private static String hex(byte[] plutusDataBytes) {
        return HexUtil.encodeHexString(plutusDataBytes);
    }

    /**
     * A full {@code ClaimData} — {@code Liquidation} mode, {@code CardanoSignature} auth — checked
     * field by field:
     * <pre>
     * d879                                     tag 121 = ClaimData constr 0
     *   9f                                        indefinite list, 8 fields
     *     d87b                                       tag 123 = Liquidation constr 2
     *       9f                                          indefinite list, 4 fields
     *         184b                                        uint(1 byte) 75  = ltv
     *         1864                                        uint(1 byte) 100 = ltvDivider
     *         1832                                        uint(1 byte) 50  = partialLiquidationPenaltyPerMille
     *         d879 80                                     tag 121 = Bool constr 0 (False), empty list = equityInPrincipalCurrency
     *       ff
     *     02                                         uint 2 = lenderBondOutputIndex
     *     03                                         uint 3 = collateralOracleRefInputIndex
     *     04                                         uint 4 = principalOracleRefInputIndex
     *     d879                                       tag 121 = CardanoSignature constr 0
     *       9f
     *         581c &lt;28 bytes&gt;                          bytestring(28) = hash
     *       ff
     *     1903e8                                     uint(2 bytes) 1000 = equity
     *     48 &lt;8 bytes&gt;                                bytestring(8) = loanId
     *     1901f4                                     uint(2 bytes) 500 = remainingDebt
     *   ff
     * </pre>
     */
    @Test
    void claimDataEncodesLiquidationModeWithCardanoSignatureAuth() {
        var hash = "0b121920272e353c434a51585f666d747b828990979ea5acb3bac1c8";
        var loanId = "a1b2c3d4e5f6a1b2";
        var claim = new ClaimData(
                new LiquidationMode.Liquidation(BigInteger.valueOf(75), BigInteger.valueOf(100),
                        BigInteger.valueOf(50), false),
                BigInteger.valueOf(2), BigInteger.valueOf(3), BigInteger.valueOf(4),
                new AuthorizationMethod.CardanoSignature(hash),
                BigInteger.valueOf(1000), loanId, BigInteger.valueOf(500));

        var expected = "d8799fd87b9f184b18641832d87980ff020304d8799f581c"
                + "0b121920272e353c434a51585f666d747b828990979ea5acb3bac1c8"
                + "ff1903e848a1b2c3d4e5f6a1b21901f4ff";

        assertEquals(expected, hex(LiquidationTxEncoder.claimData(claim).serializeToBytes()));
    }

    /**
     * The negative-integer case: {@code equity} and {@code remainingDebt} both negative, checked
     * field by field:
     * <pre>
     * d879 9f                                   ClaimData constr 0, indefinite list, 8 fields
     *   d879 80                                    NoLiquidationFullCollateralClaim constr 0, 0 fields
     *   00 00 00                                   lenderBondOutputIndex / collateralOracleRefInputIndex / principalOracleRefInputIndex = 0
     *   d879 9f 581c &lt;28 zero bytes&gt; ff             CardanoSignature(hash = 00*28)
     *   20                                         negative int, 1-byte form, n=0 -&gt; value = -1-0 = -1  (equity)
     *   44 aabbccdd                                bytestring(4) = loanId
     *   3901f3                                     negative int, 2-byte form, n=0x01f3=499 -&gt; value = -1-499 = -500 (remainingDebt)
     * ff
     * </pre>
     */
    @Test
    void claimDataEncodesNegativeEquityAndRemainingDebt() {
        var zeroHash = "0".repeat(56);
        var claim = new ClaimData(
                new LiquidationMode.NoLiquidationFullCollateralClaim(),
                BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO,
                new AuthorizationMethod.CardanoSignature(zeroHash),
                BigInteger.valueOf(-1), "aabbccdd", BigInteger.valueOf(-500));

        var expected = "d8799fd87980000000d8799f581c"
                + "00".repeat(28)
                + "ff2044aabbccdd3901f3ff";

        assertEquals(expected, hex(LiquidationTxEncoder.claimData(claim).serializeToBytes()));
    }

    /**
     * {@code LMLiquidateWithdrawRedeemer} with 2 lender-bond indexes:
     * <pre>
     * d879 9f                    LMLiquidateWithdrawRedeemer constr 0, indefinite list, 3 fields
     *   05                          uint 5 = configRefInputIndex
     *   9f 00 01 ff                 indefinite list [0, 1] = lenderBondInputIndexes
     *   9f 44deadbeef 44cafef00d ff indefinite list [bytes(4)deadbeef, bytes(4)cafef00d] = lenderBondAssetNames
     * ff
     * </pre>
     */
    @Test
    void lmLiquidateWithdrawRedeemerWithTwoIndexes() {
        var actual = LiquidationTxEncoder.lmLiquidateWithdrawRedeemer(
                5, List.of(0L, 1L), List.of("deadbeef", "cafef00d"));

        var expected = "d8799f059f0001ff9f44deadbeef44cafef00dffff";

        assertEquals(expected, hex(actual.serializeToBytes()));
    }

    /**
     * {@code AssetManagerDatumWithToken} with the {@code claimed_collateral} action and
     * {@code data = Constr 1 []} (Aiken {@code None}):
     * <pre>
     * d879 9f                                AssetManagerDatumWithToken constr 0, indefinite list, 4 fields
     *   d879 9f                                  OutputReference constr 0, indefinite list, 2 fields
     *     5820 &lt;32 bytes&gt;                          bytestring(32) = transaction_id
     *     01                                       uint 1 = output_index
     *   ff
     *   52 636c61696d65645f636f6c6c61746572616c    bytestring(18) = ASCII "claimed_collateral" = action
     *   d87a 80                                  tag 122 = constr 1, empty list -&gt; Constr 1 [] = data (None)
     *   d879 9f 581c &lt;28 bytes&gt; 424c51 ff          Asset constr 0: policyId bytestring(28), assetName bytestring(2) "4c51"
     * ff
     * </pre>
     */
    @Test
    void assetManagerDatumWithTokenClaimedCollateralActionAndNoneData() {
        var txId = "03101d2a3744515e6b7885929facb9c6d3e0edfa0714212e3b4855626f7c8996";
        var ownerPolicy = "0b121920272e353c434a51585f666d747b828990979ea5acb3bac1c8";
        var datum = new AssetManagerDatumWithToken(txId, 1,
                LiquidationTxEncoder.CLAIMED_COLLATERAL_ACTION_HEX, new AssetType(ownerPolicy, "4c51"));

        var expected = "d8799fd8799f5820" + txId + "01ff"
                + "52636c61696d65645f636f6c6c61746572616c"
                + "d87a80"
                + "d8799f581c" + ownerPolicy + "424c51ff"
                + "ff";

        assertEquals(expected, hex(LiquidationTxEncoder.assetManagerDatumWithToken(datum).serializeToBytes()));
    }

    /**
     * {@code OracleRedeemer} wrapping a {@code PriceDataCharlie} feed with empty signatures:
     * <pre>
     * d879 9f       OracleRedeemer constr 0, indefinite list, 2 fields
     *   &lt;feed&gt;         the exact bytes pinned by OracleFeedSchemaTest#priceDataCharlieEncodesToTheExactPinnedBytes
     *   80            definite empty array (0 fields) = signatures (empty)
     * ff
     * </pre>
     * The empty list encodes as the bare {@code 0x80}, not {@code 0x9fff}: {@code ListPlutusData}
     * only sets the indefinite-length flag for non-empty lists (see {@code OracleFeedConverter}).
     */
    @Test
    void oracleRedeemerWrapsAPriceDataCharlieFeedWithEmptySignatures() {
        var feed = OraclePriceFeed.priceDataCharlie(
                new AssetType("0b77d150c275bd0a600633e4be7d09f83c4b9f00981e22ac9c9d3f62", "0014df1074464c4454"),
                BigInteger.valueOf(338163), BigInteger.valueOf(1000000),
                1786351764415L, 1786352364415L);

        var pinnedFeedBytes = "d87c9f04d8799f1b0000019feadcc3bf1b0000019feae5eb7fd8799f581c0b77d150c275bd0a"
                + "600633e4be7d09f83c4b9f00981e22ac9c9d3f62490014df1074464c4454ffff1a000528f31a000f4240ff";

        var expected = "d8799f" + pinnedFeedBytes + "80ff";

        var actual = LiquidationTxEncoder.oracleRedeemer(feed, 4, List.of());

        assertEquals(expected, hex(actual.serializeToBytes()));
    }

    /**
     * F1 — {@code LenderManagerWithdrawRedeemer} with the action fixed to {@code Liquidate}
     * (constructor index 1, NOT {@code WithdrawBonds} = 0):
     * <pre>
     * d879 9f       LenderManagerWithdrawRedeemer constr 0, indefinite list, 2 fields
     *   07             uint 7 = configRefInputIndex
     *   d87a 80        tag 122 = constr 1 (Liquidate), empty list = action
     * ff
     * </pre>
     */
    @Test
    void lenderManagerWithdrawRedeemerFixesActionToLiquidate() {
        var expected = "d8799f07d87a80ff";

        assertEquals(expected, hex(LiquidationTxEncoder.lenderManagerWithdrawRedeemer(7).serializeToBytes()));
    }

    /**
     * F2 — {@code OracleRedeemer} with a NON-EMPTY signature list (one {@link OracleSignature}),
     * wrapping the smallest possible {@code Aggregated} feed (ada token, price 1/1, window 0..0)
     * so the signature's own byte layout is the only thing under test:
     * <pre>
     * d879 9f                              OracleRedeemer constr 0, indefinite list, 2 fields
     *   d879 9f                               Aggregated feed, constr 0, indefinite list, 3 fields
     *     d879 9f 00 00 d879 9f 40 40 ff ff       CommonFeedData(validFrom=0, validTo=0, Asset("",""))
     *     01                                      uint 1 = price_in_lovelaces
     *     01                                      uint 1 = price_denominator
     *   ff
     *   9f                                     indefinite list, 1 signature
     *     d879 9f 5840 &lt;64 bytes&gt; 02 ff           Signature constr 0: signature (64 bytes) BEFORE key_position=2
     *   ff
     * ff
     * </pre>
     * Signature-before-key_position is the exact transposition risk
     * {@code LiquidationTxEncoderSchemaTest#signatureFieldOrderMatchesTheContract}'s javadoc names
     * but, being a schema test, never actually calls the encoder to check it.
     */
    @Test
    void oracleRedeemerWithOneSignatureEncodesSignatureBeforeKeyPosition() {
        var feed = OraclePriceFeed.aggregated(AssetType.ada(), BigInteger.ONE, BigInteger.ONE, 0L, 0L);
        var signatureHex = "ab".repeat(64);
        var signature = new OracleSignature(2, signatureHex);

        var expected = "d8799f"
                + "d8799fd8799f0000d8799f4040ffff0101ff"
                + "9fd8799f5840" + signatureHex + "02ffff"
                + "ff";

        var actual = LiquidationTxEncoder.oracleRedeemer(feed, List.of(signature));

        assertEquals(expected, hex(actual.serializeToBytes()));
    }

    /**
     * F3 — {@code LoanWithdrawRedeemer} with the action fixed to {@code Claim} (constructor index
     * 0, not {@code Repay} = 1):
     * <pre>
     * d879 9f       LoanWithdrawRedeemer constr 0, indefinite list, 2 fields
     *   09             uint 9 = configRefInputIndex
     *   d879 80        tag 121 = constr 0 (Claim), empty list = action
     * ff
     * </pre>
     */
    @Test
    void loanWithdrawRedeemerFixesActionToClaim() {
        var expected = "d8799f09d87980ff";

        assertEquals(expected, hex(LiquidationTxEncoder.loanWithdrawRedeemer(9).serializeToBytes()));
    }

    /**
     * F3 — {@code LoanMintRedeemer}, both {@code isPoolOrigin} values, to defend against the field
     * being swapped with {@code originWithdrawRedeemerIndex}: the {@code true} case's second field
     * is {@code d87a80} (Bool constr 1), the {@code false} case's is {@code d87980} (Bool constr
     * 0) — a swap with the following {@code 06} (an Int) would not produce either.
     * <pre>
     * d879 9f          LoanMintRedeemer constr 0, indefinite list, 3 fields
     *   03                uint 3 = configRefInputIndex
     *   d87a80 / d87980   Bool True / False = isPoolOrigin
     *   06                uint 6 = originWithdrawRedeemerIndex
     * ff
     * </pre>
     */
    @Test
    void loanMintRedeemerEncodesBothPoolOriginValues() {
        var expectedTrue = "d8799f03d87a8006ff";
        var expectedFalse = "d8799f03d8798006ff";

        assertEquals(expectedTrue,
                hex(LiquidationTxEncoder.loanMintRedeemer(3, true, 6).serializeToBytes()));
        assertEquals(expectedFalse,
                hex(LiquidationTxEncoder.loanMintRedeemer(3, false, 6).serializeToBytes()));
    }

    /**
     * F3 — {@code LoanClaimActionWithdrawRedeemer}, the TOP-LEVEL redeemer of the whole
     * {@code Liquidate} transaction, with a single-element {@code actionsForEachInput}: defends
     * against {@code configRefInputIndex} and {@code actionsForEachInput} being swapped (an {@code
     * Int} field and a {@code List} field are not interchangeable CBOR, but a swap of which
     * argument feeds which parameter inside the encoder would not be caught by any schema check).
     * The embedded {@code ClaimData} bytes are exactly
     * {@link #claimDataEncodesNegativeEquityAndRemainingDebt()}'s pinned encoding.
     * <pre>
     * d879 9f                 LoanClaimActionWithdrawRedeemer constr 0, indefinite list, 2 fields
     *   08                       uint 8 = configRefInputIndex
     *   9f &lt;ClaimData&gt; ff         indefinite list, 1 element = actionsForEachInput
     * ff
     * </pre>
     */
    @Test
    void loanClaimActionWithdrawRedeemerWithOneClaim() {
        var claim = new ClaimData(
                new LiquidationMode.NoLiquidationFullCollateralClaim(),
                BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO,
                new AuthorizationMethod.CardanoSignature("0".repeat(56)),
                BigInteger.valueOf(-1), "aabbccdd", BigInteger.valueOf(-500));

        var claimDataBytes = "d8799fd87980000000d8799f581c"
                + "00".repeat(28)
                + "ff2044aabbccdd3901f3ff";

        var expected = "d8799f089f" + claimDataBytes + "ffff";

        var actual = LiquidationTxEncoder.loanClaimActionWithdrawRedeemer(8, List.of(claim));

        assertEquals(expected, hex(actual.serializeToBytes()));
    }

    /**
     * F3 — {@code authorizationMethod} on a non-{@code CardanoSignature} variant
     * ({@code CardanoWithdrawScript}, constructor index 2), defending against it being swapped
     * with {@code CardanoSpendScript} (index 1):
     * <pre>
     * d87b 9f 581c &lt;28 bytes&gt; ff   tag 123 = constr 2 (CardanoWithdrawScript), 1 field: hash
     * </pre>
     */
    @Test
    void authorizationMethodEncodesCardanoWithdrawScript() {
        var hash = "11".repeat(28);
        var auth = new AuthorizationMethod.CardanoWithdrawScript(hash);

        var expected = "d87b9f581c" + hash + "ff";

        assertEquals(expected, hex(LiquidationTxEncoder.authorizationMethod(auth).serializeToBytes()));
    }

    /** F3 — {@link LiquidationTxEncoder#PARTIAL_LIQUIDATION_ACTION_HEX} is exactly the ASCII bytes of {@code "partial_liquidation"}. */
    @Test
    void partialLiquidationActionHexIsTheAsciiBytesOfPartialLiquidation() {
        var expected = HexUtil.encodeHexString("partial_liquidation".getBytes(StandardCharsets.US_ASCII));

        assertEquals(expected, LiquidationTxEncoder.PARTIAL_LIQUIDATION_ACTION_HEX);
    }

    /** OC1 — {@code outputIndex} and {@code transactionId} are validated before encoding. */
    @Test
    void assetManagerDatumWithTokenRejectsMalformedOutputReference() {
        var validPolicy = "0b121920272e353c434a51585f666d747b828990979ea5acb3bac1c8";
        var ownerAsset = new AssetType(validPolicy, "4c51");

        var shortTxId = "aabbcc"; // not 64 hex chars
        assertThrows(IllegalArgumentException.class, () -> LiquidationTxEncoder.assetManagerDatumWithToken(
                new AssetManagerDatumWithToken(shortTxId, 1,
                        LiquidationTxEncoder.CLAIMED_COLLATERAL_ACTION_HEX, ownerAsset)));

        var validTxId = "03101d2a3744515e6b7885929facb9c6d3e0edfa0714212e3b4855626f7c8996";
        assertThrows(IllegalArgumentException.class, () -> LiquidationTxEncoder.assetManagerDatumWithToken(
                new AssetManagerDatumWithToken(validTxId, -1,
                        LiquidationTxEncoder.CLAIMED_COLLATERAL_ACTION_HEX, ownerAsset)));
    }

    /** OC2 — an ada {@code ownerAsset} can never gate a UTxO and is rejected rather than silently encoded. */
    @Test
    void assetManagerDatumWithTokenRejectsAdaOwnerAsset() {
        var validTxId = "03101d2a3744515e6b7885929facb9c6d3e0edfa0714212e3b4855626f7c8996";
        var datum = new AssetManagerDatumWithToken(validTxId, 1,
                LiquidationTxEncoder.CLAIMED_COLLATERAL_ACTION_HEX, AssetType.ada());

        assertThrows(IllegalArgumentException.class, () -> LiquidationTxEncoder.assetManagerDatumWithToken(datum));
    }
}
