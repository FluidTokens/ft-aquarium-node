package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.exception.CborRuntimeException;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.AuthorizationMethod;
import com.fluidtokens.aquarium.offchain.model.loans.LenderManagerDatum;

import java.math.BigInteger;
import java.util.List;

/**
 * Decodes the inline datum of a Lending v4 lender-bond UTxO into {@link LenderManagerDatum}.
 * <p>
 * Hand-written for the same reason as {@code LoanDatumConverter}: decode only, the bot never
 * writes a {@code LenderManagerDatum}, the lender-manager validators do.
 * <p>
 * Every constructor index here is taken from {@code lib/fluidtokens/types/lender_manager.ak}
 * and {@code lib/fluidtokens/types/general.ak}, cross-checked against the blueprint alltypes
 * oracle. Getting one wrong yields a plausible-looking but wrong bond, so
 * {@code LenderManagerDatumConverterTest} pins them against synthetic CBOR.
 */
public class LenderManagerDatumConverter {

    public LenderManagerDatum deserialize(String hex) {
        return deserialize(HexUtil.decodeHexString(hex));
    }

    public LenderManagerDatum deserialize(byte[] bytes) {
        try {
            var constr = ConstrPlutusData.deserialize(CborSerializationUtil.deserialize(bytes));
            return fromPlutusData(constr);
        } catch (CborRuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new CborRuntimeException("could not decode LenderManagerDatum", e);
        }
    }

    public LenderManagerDatum fromPlutusData(ConstrPlutusData constr) {
        expectAlternative(constr, 0, "LenderManagerDatum");
        var f = fields(constr);
        if (f.size() != LenderManagerDatum.FIELD_COUNT) {
            throw new CborRuntimeException(
                    "LenderManagerDatum has %d fields, expected %d — the on-chain type changed"
                            .formatted(f.size(), LenderManagerDatum.FIELD_COUNT));
        }
        return new LenderManagerDatum(
                authorizationMethod(f.get(0)),
                f.get(1),
                bool(f.get(2)),
                integer(f.get(3)),
                hex(f.get(4)),
                asset(f.get(5)));
    }

    // ---- component types ---------------------------------------------------------------

    /** {@code Asset { policyId, assetName }} — Constr 0. Empty policy id means ada. */
    private static AssetType asset(PlutusData data) {
        var constr = constr(data, "Asset");
        expectAlternative(constr, 0, "Asset");
        var f = fields(constr);
        return AssetType.fromPlutusData(bytes(f.get(0)), bytes(f.get(1)));
    }

    private static AuthorizationMethod authorizationMethod(PlutusData data) {
        var constr = constr(data, "AuthorizationMethod");
        var f = fields(constr);
        return switch ((int) constr.getAlternative()) {
            case 0 -> new AuthorizationMethod.CardanoSignature(hex(f.get(0)));
            case 1 -> new AuthorizationMethod.CardanoSpendScript(hex(f.get(0)));
            case 2 -> new AuthorizationMethod.CardanoWithdrawScript(hex(f.get(0)));
            case 3 -> new AuthorizationMethod.CardanoMintScript(hex(f.get(0)));
            default -> throw new CborRuntimeException(
                    "unknown AuthorizationMethod constructor " + constr.getAlternative());
        };
    }

    // ---- primitives --------------------------------------------------------------------

    /** Aiken {@code Bool}: Constr 0 = False, Constr 1 = True. */
    private static boolean bool(PlutusData data) {
        var constr = constr(data, "Bool");
        return switch ((int) constr.getAlternative()) {
            case 0 -> false;
            case 1 -> true;
            default -> throw new CborRuntimeException(
                    "unknown Bool constructor " + constr.getAlternative());
        };
    }

    private static BigInteger integer(PlutusData data) {
        if (data instanceof BigIntPlutusData i) {
            return i.getValue();
        }
        throw new CborRuntimeException("expected an Int, got " + typeName(data));
    }

    private static byte[] bytes(PlutusData data) {
        if (data instanceof BytesPlutusData b) {
            return b.getValue();
        }
        throw new CborRuntimeException("expected a ByteArray, got " + typeName(data));
    }

    private static String hex(PlutusData data) {
        return HexUtil.encodeHexString(bytes(data));
    }

    private static ConstrPlutusData constr(PlutusData data, String what) {
        if (data instanceof ConstrPlutusData c) {
            return c;
        }
        throw new CborRuntimeException("expected %s to be a constructor, got %s".formatted(what, typeName(data)));
    }

    private static List<PlutusData> fields(ConstrPlutusData constr) {
        return constr.getData() == null ? List.of() : constr.getData().getPlutusDataList();
    }

    private static void expectAlternative(ConstrPlutusData constr, int expected, String what) {
        if (constr.getAlternative() != expected) {
            throw new CborRuntimeException(
                    "%s should be constructor %d but was %d".formatted(what, expected, constr.getAlternative()));
        }
    }

    private static String typeName(PlutusData data) {
        return data == null ? "null" : data.getClass().getSimpleName();
    }
}
