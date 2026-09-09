package com.fluidtokens.aquarium.offchain;

import com.bloxbean.cardano.client.exception.CborRuntimeException;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.AuthorizationMethod;
import com.fluidtokens.aquarium.offchain.service.loans.LenderManagerDatumConverter;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link LenderManagerDatumConverter} against synthetic CBOR built field by field, the same
 * technique as {@code LoanDatumConverterTest} except there is no live preview fixture yet
 * (capturing one needs a Blockfrost key — see the ticket's out-of-scope notes).
 * <p>
 * {@code LenderManagerDatum} is hand-decoded — it is absent from the blueprint's validator
 * signatures, so there is no generated converter and no compiler check that the field order
 * matches {@code lib/fluidtokens/types/lender_manager.ak}. A transposed field would still
 * decode, just into a silently wrong bond, so every field is exercised here independently.
 */
class LenderManagerDatumConverterTest {

    private static final String TFLDT_POLICY = "0b77d150c275bd0a600633e4be7d09f83c4b9f00981e22ac9c9d3f62";
    private static final String TFLDT_NAME = "0014df1074464c4454";
    private static final String POOL_ID = "504f4f4c00c2d59645bd027265";

    private final LenderManagerDatumConverter converter = new LenderManagerDatumConverter();

    /** A plausible {@code Option<StakeCredential>} shape — content is irrelevant, it stays raw. */
    private static PlutusData someStakeCredential() {
        var vkh = BytesPlutusData.of("a1b2c3d4e5f60718293a4b5c6d7e8f9001122334455667788990011");
        var credential = ConstrPlutusData.of(0, vkh);
        return ConstrPlutusData.of(0, credential);
    }

    private static PlutusData datum(PlutusData lenderAuth, PlutusData lenderStakeCredential,
                                    boolean shouldConvert, long liquidationFeePerMille,
                                    String poolIdHex, PlutusData principalAsset) {
        return ConstrPlutusData.of(0,
                lenderAuth,
                lenderStakeCredential,
                ConstrPlutusData.of(shouldConvert ? 1 : 0),
                BigIntPlutusData.of(liquidationFeePerMille),
                BytesPlutusData.of(poolIdHex.isEmpty() ? new byte[0] : hexToBytes(poolIdHex)),
                principalAsset);
    }

    private static byte[] hexToBytes(String hex) {
        return com.bloxbean.cardano.client.util.HexUtil.decodeHexString(hex);
    }

    private static PlutusData asset(String policyId, String assetName) {
        return ConstrPlutusData.of(0,
                BytesPlutusData.of(hexToBytes(policyId)),
                BytesPlutusData.of(hexToBytes(assetName)));
    }

    private static PlutusData authSignature(String hash) {
        return ConstrPlutusData.of(0, BytesPlutusData.of(hexToBytes(hash)));
    }

    // ---- Bool ----------------------------------------------------------------------------

    @Test
    void decodesShouldLiquidationConvertToPrincipalFalse() {
        var stakeCred = someStakeCredential();
        var hex = datum(authSignature("aa"), stakeCred, false, 5,
                "", asset("", "")).serializeToHex();

        var d = converter.deserialize(hex);
        assertFalse(d.shouldLiquidationConvertToPrincipal());
    }

    @Test
    void decodesShouldLiquidationConvertToPrincipalTrue() {
        var stakeCred = someStakeCredential();
        var hex = datum(authSignature("aa"), stakeCred, true, 5,
                "", asset("", "")).serializeToHex();

        var d = converter.deserialize(hex);
        assertTrue(d.shouldLiquidationConvertToPrincipal());
    }

    // ---- AuthorizationMethod ---------------------------------------------------------------

    @Test
    void decodesCardanoSignature() {
        var hex = datum(ConstrPlutusData.of(0, BytesPlutusData.of(hexToBytes("aabb"))),
                someStakeCredential(), false, 0, "", asset("", "")).serializeToHex();

        var auth = assertInstanceOf(AuthorizationMethod.CardanoSignature.class, converter.deserialize(hex).lenderAuth());
        assertEquals("aabb", auth.hash());
    }

    @Test
    void decodesCardanoSpendScript() {
        var hex = datum(ConstrPlutusData.of(1, BytesPlutusData.of(hexToBytes("bbcc"))),
                someStakeCredential(), false, 0, "", asset("", "")).serializeToHex();

        var auth = assertInstanceOf(AuthorizationMethod.CardanoSpendScript.class, converter.deserialize(hex).lenderAuth());
        assertEquals("bbcc", auth.hash());
    }

    @Test
    void decodesCardanoWithdrawScript() {
        var hex = datum(ConstrPlutusData.of(2, BytesPlutusData.of(hexToBytes("ccdd"))),
                someStakeCredential(), false, 0, "", asset("", "")).serializeToHex();

        var auth = assertInstanceOf(AuthorizationMethod.CardanoWithdrawScript.class, converter.deserialize(hex).lenderAuth());
        assertEquals("ccdd", auth.hash());
    }

    @Test
    void decodesCardanoMintScript() {
        var hex = datum(ConstrPlutusData.of(3, BytesPlutusData.of(hexToBytes("ddee"))),
                someStakeCredential(), false, 0, "", asset("", "")).serializeToHex();

        var auth = assertInstanceOf(AuthorizationMethod.CardanoMintScript.class, converter.deserialize(hex).lenderAuth());
        assertEquals("ddee", auth.hash());
    }

    // ---- lenderStakeCredential stays raw -------------------------------------------------

    @Test
    void lenderStakeCredentialIsPreservedRawAndUndecoded() {
        var stakeCred = someStakeCredential();
        var hex = datum(authSignature("aa"), stakeCred, false, 0, "", asset("", "")).serializeToHex();

        var d = converter.deserialize(hex);
        assertEquals(stakeCred, d.lenderStakeCredential(),
                "lenderStakeCredential must round-trip byte-identical, not be re-derived structurally");
    }

    // ---- poolId ----------------------------------------------------------------------------

    @Test
    void decodesEmptyPoolIdAsNoPool() {
        var hex = datum(authSignature("aa"), someStakeCredential(), false, 0,
                "", asset("", "")).serializeToHex();

        assertEquals("", converter.deserialize(hex).poolId());
    }

    @Test
    void decodesNonEmptyPoolId() {
        var hex = datum(authSignature("aa"), someStakeCredential(), false, 0,
                POOL_ID, asset("", "")).serializeToHex();

        assertEquals(POOL_ID, converter.deserialize(hex).poolId());
    }

    // ---- principalAsset ----------------------------------------------------------------------

    @Test
    void decodesAdaPrincipalAsset() {
        var hex = datum(authSignature("aa"), someStakeCredential(), false, 0,
                "", asset("", "")).serializeToHex();

        assertTrue(converter.deserialize(hex).principalAsset().isAda());
    }

    @Test
    void decodesTokenPrincipalAsset() {
        var hex = datum(authSignature("aa"), someStakeCredential(), false, 0,
                "", asset(TFLDT_POLICY, TFLDT_NAME)).serializeToHex();

        assertEquals(new AssetType(TFLDT_POLICY, TFLDT_NAME), converter.deserialize(hex).principalAsset());
    }

    // ---- liquidationFeePerMille -------------------------------------------------------------

    @Test
    void decodesLiquidationFeePerMille() {
        var hex = datum(authSignature("aa"), someStakeCredential(), false, 25,
                "", asset("", "")).serializeToHex();

        assertEquals(BigInteger.valueOf(25), converter.deserialize(hex).liquidationFeePerMille());
    }

    // ---- error paths -------------------------------------------------------------------------

    @Test
    void wrongOuterAlternativeThrows() {
        var wrong = ConstrPlutusData.of(1,
                authSignature("aa"), someStakeCredential(), ConstrPlutusData.of(0),
                BigIntPlutusData.of(0), BytesPlutusData.of(new byte[0]), asset("", ""));

        assertThrows(CborRuntimeException.class, () -> converter.deserialize(wrong.serializeToHex()));
    }

    @Test
    void wrongFieldCountThrows() {
        var tooFew = ConstrPlutusData.of(0,
                authSignature("aa"), someStakeCredential(), ConstrPlutusData.of(0),
                BigIntPlutusData.of(0), BytesPlutusData.of(new byte[0]));

        assertThrows(CborRuntimeException.class, () -> converter.deserialize(tooFew.serializeToHex()));
    }

    /**
     * {@code wrongFieldCountThrows} above only proves <em>something</em> throws — with one
     * field missing, indexing the 6th field (index 5) throws its own
     * {@code IndexOutOfBoundsException} inside the generic catch-all before the field-count
     * guard's message is ever inspected, so that test would still pass with the guard deleted.
     * A datum with an <em>extra</em> field is never indexed out of bounds — nothing downstream
     * would throw at all without the guard — so asserting the guard's own message is what
     * actually pins it.
     */
    @Test
    void tooManyFieldsThrowsWithTheGuardsOwnMessage() {
        var tooMany = ConstrPlutusData.of(0,
                authSignature("aa"), someStakeCredential(), ConstrPlutusData.of(0),
                BigIntPlutusData.of(0), BytesPlutusData.of(new byte[0]), asset("", ""),
                BytesPlutusData.of(new byte[0])); // a 7th field the type does not have

        var ex = assertThrows(CborRuntimeException.class, () -> converter.deserialize(tooMany.serializeToHex()));
        assertTrue(ex.getMessage().contains("has 7 fields, expected 6"),
                "the field-count guard itself must fire — got: " + ex.getMessage());
    }

    @Test
    void unknownBoolConstructorThrows() {
        var badBool = ConstrPlutusData.of(0,
                authSignature("aa"), someStakeCredential(), ConstrPlutusData.of(2),
                BigIntPlutusData.of(0), BytesPlutusData.of(new byte[0]), asset("", ""));

        assertThrows(CborRuntimeException.class, () -> converter.deserialize(badBool.serializeToHex()));
    }

    @Test
    void unknownAuthorizationMethodConstructorThrows() {
        var badAuth = ConstrPlutusData.of(0,
                ConstrPlutusData.of(4, BytesPlutusData.of(hexToBytes("aa"))), someStakeCredential(),
                ConstrPlutusData.of(0), BigIntPlutusData.of(0), BytesPlutusData.of(new byte[0]), asset("", ""));

        assertThrows(CborRuntimeException.class, () -> converter.deserialize(badAuth.serializeToHex()));
    }

    @Test
    void wrongTypeWhereIntExpectedThrows() {
        var badInt = ConstrPlutusData.of(0,
                authSignature("aa"), someStakeCredential(), ConstrPlutusData.of(0),
                BytesPlutusData.of(new byte[0]), // liquidationFeePerMille should be an Int, not bytes
                BytesPlutusData.of(new byte[0]), asset("", ""));

        assertThrows(CborRuntimeException.class, () -> converter.deserialize(badInt.serializeToHex()));
    }
}
