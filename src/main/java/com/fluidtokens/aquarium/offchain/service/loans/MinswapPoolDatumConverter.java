package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.exception.CborRuntimeException;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.MinswapPoolDatum;

import java.math.BigInteger;
import java.util.List;

/**
 * Decodes Minswap V2's {@code PoolDatum} — the reference input
 * {@code lm_liquidate_and_convert_action} reads to learn the pair and its direction.
 *
 * <p>⛔ <b>Hand-written for the same reason {@code LoanDatumConverter} is:</b> the type is not in any
 * blueprint this project ships. It is not in {@code loans-v4.plutus.json} (the validator only
 * constructs it internally, so it never reaches a redeemer or datum signature) and not in the
 * all-types oracle either. The field order below was decoded from the <b>live mainnet ADA/FLDT
 * pool</b> and agrees with Minswap's upstream declaration — two independent sources, which is what
 * makes it evidence rather than a guess.
 *
 * <p>⚠ <b>The arity check is the load-bearing part.</b> Every field this converter reads is at a fixed
 * position, so a Minswap type change that inserted a field would otherwise be read as a pool with
 * different assets — a well-formed order for the wrong pair.
 */
public class MinswapPoolDatumConverter {

    public MinswapPoolDatum deserialize(String hex) {
        return deserialize(HexUtil.decodeHexString(hex));
    }

    public MinswapPoolDatum deserialize(byte[] bytes) {
        try {
            return fromPlutusData(ConstrPlutusData.deserialize(CborSerializationUtil.deserialize(bytes)));
        } catch (CborRuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new CborRuntimeException("could not decode Minswap PoolDatum", e);
        }
    }

    public MinswapPoolDatum fromPlutusData(ConstrPlutusData constr) {
        if (constr.getAlternative() != 0) {
            throw new CborRuntimeException("PoolDatum must be constructor 0, was "
                    + constr.getAlternative());
        }
        List<PlutusData> f = constr.getData().getPlutusDataList();
        if (f.size() != MinswapPoolDatum.FIELD_COUNT) {
            throw new CborRuntimeException(
                    ("Minswap PoolDatum has %d fields, expected %d — Minswap changed the type, and "
                            + "every field this reads is positional, so continuing would decode a "
                            + "DIFFERENT pair and build a well-formed order for the wrong pool")
                            .formatted(f.size(), MinswapPoolDatum.FIELD_COUNT));
        }
        return new MinswapPoolDatum(
                asset(f.get(1)),
                asset(f.get(2)),
                integer(f.get(3)),
                integer(f.get(4)),
                integer(f.get(5)));
    }

    /** {@code Asset { policy_id, asset_name }} — constructor 0. ADA is the empty/empty pair. */
    private static AssetType asset(PlutusData d) {
        if (!(d instanceof ConstrPlutusData c) || c.getAlternative() != 0
                || c.getData().getPlutusDataList().size() != 2) {
            throw new CborRuntimeException("expected a Minswap Asset constructor, got " + d);
        }
        return AssetType.fromPlutusData(
                ((BytesPlutusData) c.getData().getPlutusDataList().get(0)).getValue(),
                ((BytesPlutusData) c.getData().getPlutusDataList().get(1)).getValue());
    }

    private static BigInteger integer(PlutusData d) {
        if (!(d instanceof BigIntPlutusData i)) {
            throw new CborRuntimeException("expected an Int, got " + d);
        }
        return i.getValue();
    }
}
