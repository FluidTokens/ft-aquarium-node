package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.exception.CborRuntimeException;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.AssetManagerDatumWithToken;

import java.util.List;

/**
 * Decodes the inline datum of an asset-manager UTxO into {@link AssetManagerDatumWithToken}.
 *
 * <p>The read side of {@code LiquidationTxEncoder}, which writes this shape. Decoding matters for the
 * compound path: a repaid loan's principal is escrowed at the asset manager, and
 * {@code lm_compound_action} collects it by matching the datum's {@code ownerAsset} to a lender bond
 * (findings §20.1).
 *
 * <h2>⚠ Two things the encoder's assumptions do not survive contact with the chain</h2>
 * <ol>
 *   <li><b>{@code data} is not always {@code None}.</b> The encoder writes {@code None} there, so
 *       {@link AssetManagerDatumWithToken} does not model the field. Escrows written by FluidTokens'
 *       own off-chain <em>do</em> carry content — the 2026-09-01 repayment escrow held the loan's
 *       terms. This decoder therefore <b>skips field 2 without inspecting it</b>, and must keep doing
 *       so: it is {@code Data}, i.e. deliberately unconstrained, and anything we decoded from it
 *       would be a guess about someone else's builder.</li>
 *   <li><b>Constructor 1 is a real variant, not corruption.</b> {@code AssetManagerDatumWithHash}
 *       is owned by an {@code AuthorizationMethod} rather than by a token. It cannot be compounded —
 *       {@code lm_compound_action} does {@code expect AssetManagerDatumWithToken}, which aborts —
 *       so callers must classify it rather than treat it as junk. {@link #isTokenOwned(String)}
 *       exists for exactly that, so a legitimate variant is never logged as a decode failure.</li>
 * </ol>
 */
public class AssetManagerDatumConverter {

    /** {@code AssetManagerDatumWithToken} is constructor 0 of the {@code AssetManagerDatum} sum. */
    private static final int WITH_TOKEN = 0;

    /** {@code OutputReference}, {@code action}, {@code data}, {@code ownerAsset}. */
    private static final int FIELD_COUNT = 4;

    public AssetManagerDatumWithToken deserialize(String hex) {
        return deserialize(HexUtil.decodeHexString(hex));
    }

    public AssetManagerDatumWithToken deserialize(byte[] bytes) {
        try {
            return fromPlutusData(ConstrPlutusData.deserialize(CborSerializationUtil.deserialize(bytes)));
        } catch (CborRuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new CborRuntimeException("could not decode AssetManagerDatumWithToken", e);
        }
    }

    /**
     * Whether this datum is the token-owned variant, without throwing on the hash-owned one.
     * Returns {@code false} for anything that is not a constructor at all, so a caller can treat
     * "not compoundable" and "not a datum" alike without a try/catch around the common path.
     */
    public boolean isTokenOwned(String hex) {
        try {
            var constr = ConstrPlutusData.deserialize(
                    CborSerializationUtil.deserialize(HexUtil.decodeHexString(hex)));
            return constr.getAlternative() == WITH_TOKEN;
        } catch (Exception e) {
            return false;
        }
    }

    public AssetManagerDatumWithToken fromPlutusData(ConstrPlutusData constr) {
        if (constr.getAlternative() != WITH_TOKEN) {
            throw new CborRuntimeException(
                    ("AssetManagerDatum is constructor %d, not %d — this is AssetManagerDatumWithHash, "
                            + "a legitimate variant that no token-owned action can collect. Callers "
                            + "should classify it with isTokenOwned rather than decode it")
                            .formatted(constr.getAlternative(), WITH_TOKEN));
        }
        var f = fields(constr);
        if (f.size() != FIELD_COUNT) {
            throw new CborRuntimeException(
                    "AssetManagerDatumWithToken has %d fields, expected %d — the on-chain type changed"
                            .formatted(f.size(), FIELD_COUNT));
        }
        var outputReference = constr(f.get(0), "OutputReference");
        var ref = fields(outputReference);
        if (ref.size() != 2) {
            throw new CborRuntimeException(
                    "OutputReference has %d fields, expected 2".formatted(ref.size()));
        }
        return new AssetManagerDatumWithToken(
                hex(ref.get(0)),
                integer(ref.get(1)).intValueExact(),
                hex(f.get(1)),
                // f.get(2) is `data: Data` — deliberately not read. See the class javadoc.
                asset(f.get(3)));
    }

    /** {@code Asset { policyId, assetName }} — Constr 0. An empty policy id means ada. */
    private static AssetType asset(PlutusData data) {
        var constr = constr(data, "Asset");
        if (constr.getAlternative() != 0) {
            throw new CborRuntimeException("Asset is constructor %d, expected 0"
                    .formatted(constr.getAlternative()));
        }
        var f = fields(constr);
        return AssetType.fromPlutusData(bytes(f.get(0)), bytes(f.get(1)));
    }

    private static ConstrPlutusData constr(PlutusData data, String what) {
        if (data instanceof ConstrPlutusData c) {
            return c;
        }
        throw new CborRuntimeException("expected %s to be a constructor, got %s"
                .formatted(what, data == null ? "null" : data.getClass().getSimpleName()));
    }

    private static List<PlutusData> fields(ConstrPlutusData constr) {
        return constr.getData().getPlutusDataList();
    }

    private static java.math.BigInteger integer(PlutusData data) {
        if (data instanceof BigIntPlutusData i) {
            return i.getValue();
        }
        throw new CborRuntimeException("expected an Int, got "
                + (data == null ? "null" : data.getClass().getSimpleName()));
    }

    private static byte[] bytes(PlutusData data) {
        if (data instanceof BytesPlutusData b) {
            return b.getValue();
        }
        throw new CborRuntimeException("expected a ByteArray, got "
                + (data == null ? "null" : data.getClass().getSimpleName()));
    }

    private static String hex(PlutusData data) {
        return HexUtil.encodeHexString(bytes(data));
    }
}
