package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.exception.CborRuntimeException;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.CollateralAsset;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationMode;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.model.loans.RepaymentMode;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

/**
 * Decodes the inline datum of a Lending v4 loan UTxO into {@link LoanDatum}.
 * <p>
 * Hand-written because {@code LoanDatum} is absent from the blueprint (see that class).
 * Decode only: the bot never writes a {@code LoanDatum}, the loan validators do.
 * <p>
 * Every constructor index here is taken from {@code lib/fluidtokens/types/loan.ak} and
 * {@code lib/fluidtokens/types/general.ak}, and cross-checked against the blueprint
 * definitions that <em>are</em> present ({@code LiquidationMode}, {@code Asset}, {@code Bool},
 * {@code Option}). Getting one wrong yields a plausible-looking but wrong loan, so
 * {@code LoanDatumConverterTest} pins them against real preview datums.
 */
public class LoanDatumConverter {

    public LoanDatum deserialize(String hex) {
        return deserialize(HexUtil.decodeHexString(hex));
    }

    public LoanDatum deserialize(byte[] bytes) {
        try {
            var constr = ConstrPlutusData.deserialize(CborSerializationUtil.deserialize(bytes));
            return fromPlutusData(constr);
        } catch (CborRuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new CborRuntimeException("could not decode LoanDatum", e);
        }
    }

    public LoanDatum fromPlutusData(ConstrPlutusData constr) {
        expectAlternative(constr, 0, "LoanDatum");
        var f = fields(constr);
        if (f.size() != LoanDatum.FIELD_COUNT) {
            throw new CborRuntimeException(
                    "LoanDatum has %d fields, expected %d — the on-chain type changed"
                            .formatted(f.size(), LoanDatum.FIELD_COUNT));
        }
        return new LoanDatum(
                integer(f.get(0)),
                integer(f.get(1)),
                integer(f.get(2)),
                integer(f.get(3)),
                integer(f.get(4)),
                integer(f.get(5)),
                asset(f.get(6)),
                asset(f.get(7)),
                integer(f.get(8)),
                integer(f.get(9)),
                liquidationMode(f.get(10)),
                repaymentMode(f.get(11)),
                integer(f.get(12)),
                integer(f.get(13)),
                bool(f.get(14)),
                hex(f.get(15)),
                collateralAsset(f.get(16)));
    }

    // ---- component types ---------------------------------------------------------------

    /** {@code Asset { policyId, assetName }} — Constr 0. Empty policy id means ada. */
    private static AssetType asset(PlutusData data) {
        var constr = constr(data, "Asset");
        expectAlternative(constr, 0, "Asset");
        var f = fields(constr);
        return AssetType.fromPlutusData(bytes(f.get(0)), bytes(f.get(1)));
    }

    /** {@code CollateralAsset { policyId, maybeAssetName, oracleTokenAsset }} — Constr 0. */
    private static CollateralAsset collateralAsset(PlutusData data) {
        var constr = constr(data, "CollateralAsset");
        expectAlternative(constr, 0, "CollateralAsset");
        var f = fields(constr);
        return new CollateralAsset(hex(f.get(0)), optionalHex(f.get(1)), asset(f.get(2)));
    }

    private static LiquidationMode liquidationMode(PlutusData data) {
        var constr = constr(data, "LiquidationMode");
        var f = fields(constr);
        return switch ((int) constr.getAlternative()) {
            case 0 -> new LiquidationMode.NoLiquidationFullCollateralClaim();
            case 1 -> new LiquidationMode.NoLiquidationDutchAuctionClaim();
            case 2 -> new LiquidationMode.Liquidation(
                    integer(f.get(0)), integer(f.get(1)), integer(f.get(2)), bool(f.get(3)));
            default -> throw new CborRuntimeException(
                    "unknown LiquidationMode constructor " + constr.getAlternative());
        };
    }

    private static RepaymentMode repaymentMode(PlutusData data) {
        var constr = constr(data, "RepaymentMode");
        var f = fields(constr);
        return switch ((int) constr.getAlternative()) {
            case 0 -> new RepaymentMode.InterestOnRemainingPrincipal(integer(f.get(0)));
            case 1 -> new RepaymentMode.PrincipalAndInterestOnInstallments();
            case 2 -> new RepaymentMode.PerpetualLoan(integer(f.get(0)), integer(f.get(1)));
            default -> throw new CborRuntimeException(
                    "unknown RepaymentMode constructor " + constr.getAlternative());
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

    /** Aiken {@code Option}: Constr 0 = Some(x), Constr 1 = None. */
    private static Optional<String> optionalHex(PlutusData data) {
        var constr = constr(data, "Option");
        return switch ((int) constr.getAlternative()) {
            case 0 -> Optional.of(hex(fields(constr).get(0)));
            case 1 -> Optional.empty();
            default -> throw new CborRuntimeException(
                    "unknown Option constructor " + constr.getAlternative());
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
