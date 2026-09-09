package com.fluidtokens.aquarium.offchain.model.loans;

import com.fluidtokens.aquarium.offchain.model.AssetType;

/**
 * {@code AssetManagerDatumWithToken} from {@code lib/fluidtokens/types/asset_manager.ak} —
 * constructor index 0 of the {@code AssetManagerDatum} sum type (index 1 is
 * {@code AssetManagerDatumWithHash}, out of scope here — see {@code
 * com.fluidtokens.aquarium.offchain.service.loans.LiquidationTxEncoder}).
 * <p>
 * This is the datum the liquidation bot writes on the asset-manager output that carries the
 * claimed collateral or the partial-liquidation compensation forward, keyed by the asset it owns
 * rather than by an authorization-method hash.
 * <p>
 * Field order below is the on-chain constructor order, taken from the blueprint definition
 * {@code fluidtokens/types/asset_manager/AssetManagerDatum} in {@code loans-v4-alltypes.plutus.json}
 * (see {@code LiquidationTxEncoderSchemaTest}) and must not be reordered.
 * <p>
 * The on-chain {@code data: Data} field is not modelled: for this ticket it is always the Aiken
 * {@code None} the encoder writes on its own — see {@code LiquidationTxEncoder
 * #assetManagerDatumWithToken}.
 *
 * @param transactionId hex; together with {@link #outputIndex} the {@code OutputReference} of the
 *                       loan input this datum descends from
 * @param outputIndex    the loan input's output index — small and always non-negative, hence
 *                        {@code int} rather than {@link java.math.BigInteger}
 * @param action         hex; the opaque action byte string the asset-manager validator checks,
 *                        e.g. {@link com.fluidtokens.aquarium.offchain.service.loans.LiquidationTxEncoder
 *                        #CLAIMED_COLLATERAL_ACTION_HEX}
 * @param ownerAsset     the asset that must be present to unlock this datum's value
 */
public record AssetManagerDatumWithToken(String transactionId,
                                         int outputIndex,
                                         String action,
                                         AssetType ownerAsset) {
}
