package com.fluidtokens.aquarium.offchain.model.loans;

import com.fluidtokens.aquarium.offchain.model.AssetType;

import java.util.Optional;

/**
 * {@code CollateralAsset} from {@code lib/fluidtokens/types/general.ak}.
 *
 * @param assetName        empty for an NFT <em>collection</em> as collateral. Such loans cannot be
 *                         liquidated by the bot — {@code lm_liquidate_action.ak:108} does
 *                         {@code expect Some(collateralAssetName)}.
 * @param oracleTokenAsset the dummy {@code Asset{"NONE","NONE"}} when the loan uses no oracle.
 */
public record CollateralAsset(String policyId,
                              Optional<String> assetName,
                              AssetType oracleTokenAsset) {

    /**
     * {@code constants.no_oracle_token_asset = Asset { policyId: "NONE", assetName: "NONE" }} —
     * a ByteArray literal, so both fields are the hex of the ASCII "NONE".
     */
    private static final String NO_ORACLE_SENTINEL_HEX = "4e4f4e45";

    /** The unit string for this collateral, or empty for a collection. */
    public Optional<String> unit() {
        return assetName.map(name -> policyId + name);
    }

    public boolean usesOracle() {
        return !(NO_ORACLE_SENTINEL_HEX.equalsIgnoreCase(oracleTokenAsset.policyId())
                && NO_ORACLE_SENTINEL_HEX.equalsIgnoreCase(oracleTokenAsset.assetName()));
    }
}
