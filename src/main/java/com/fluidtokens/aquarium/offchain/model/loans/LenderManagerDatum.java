package com.fluidtokens.aquarium.offchain.model.loans;

import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.fluidtokens.aquarium.offchain.model.AssetType;

import java.math.BigInteger;

/**
 * {@code LenderManagerDatum} from {@code lib/fluidtokens/types/lender_manager.ak} — the inline
 * datum on every lender-bond UTxO locked at the LenderManager spend credential.
 * <p>
 * Field order below is the on-chain constructor order, taken from the blueprint definition
 * {@code fluidtokens/types/lender_manager/LenderManagerDatum} in {@code loans-v4-alltypes.plutus.json}
 * (see {@code LenderManagerDatumSchemaTest}) and must not be reordered.
 * <p>
 * All integers are {@link BigInteger} because on-chain {@code Int} is unbounded and this
 * decodes untrusted chain data.
 *
 * @param lenderStakeCredential kept <b>raw, undecoded</b>. The on-chain validator requires the
 *                              bond output to be a byte-identical echo of the input, so T-008
 *                              must reuse the input's datum bytes verbatim
 *                              ({@code LenderBond.inlineDatum}) rather than re-serialise this
 *                              field. A structural decode/re-encode of the stake credential is
 *                              both unnecessary for that purpose and a re-serialization hazard —
 *                              cardano-client-lib's CBOR encoding is not guaranteed byte-identical
 *                              to what the chain produced.
 * @param poolId                hex; empty string means no pool
 */
public record LenderManagerDatum(AuthorizationMethod lenderAuth,
                                 PlutusData lenderStakeCredential,
                                 boolean shouldLiquidationConvertToPrincipal,
                                 BigInteger liquidationFeePerMille,
                                 String poolId,
                                 AssetType principalAsset) {

    /** Number of fields in the on-chain constructor; a mismatch means the contract type changed. */
    public static final int FIELD_COUNT = 6;
}
