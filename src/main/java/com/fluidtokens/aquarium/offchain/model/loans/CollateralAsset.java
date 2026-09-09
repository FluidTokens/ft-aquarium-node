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

    /**
     * The collateral as an {@link AssetType}, for pricing and for reading quantities out of a UTxO.
     * <p>
     * An empty policy id means <b>ada</b>, and that is not a special case we invented: it is the
     * {@code expectedTokenPolicyId == ""} branch of {@code retrieve_oracle_data}, which returns a
     * synthesised 1:1 feed without consulting any oracle. Building
     * {@code new AssetType(policyId, name)} directly instead yields {@code ("", "")}, which is not
     * the ada this codebase spells {@code ("lovelace", "")} — so the loan silently loses its price.
     * <p>
     * A collection (no asset name) keeps the empty name: the oracle prices the policy.
     */
    public AssetType assetType() {
        return policyId.isEmpty() ? AssetType.ada() : new AssetType(policyId, assetName.orElse(""));
    }

    /** Whether the collateral is ada rather than a token. */
    public boolean isAda() {
        return policyId.isEmpty();
    }

    public boolean usesOracle() {
        return !(NO_ORACLE_SENTINEL_HEX.equalsIgnoreCase(oracleTokenAsset.policyId())
                && NO_ORACLE_SENTINEL_HEX.equalsIgnoreCase(oracleTokenAsset.assetName()));
    }
}
