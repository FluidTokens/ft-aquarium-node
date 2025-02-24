package com.fluidtokens.aquarium.offchain.model;

import com.bloxbean.cardano.client.util.HexUtil;

public record AssetType(String policyId, String assetName) {

    public static final String LOVELACE = "lovelace";

    private static final AssetType Ada = new AssetType(LOVELACE, "");

    public String toUnit() {
        return policyId + assetName;
    }

    public boolean isAda() {
        return this.equals(Ada);
    }

    public String unsafeHumanAssetName() {
        return new String(HexUtil.decodeHexString(assetName));
    }

    public static AssetType fromUnit(String unit) {
        if (unit.equalsIgnoreCase(LOVELACE)) {
            return Ada;
        } else {
            String sanitizedUnit = unit.replaceAll("\\.", "");
            return new AssetType(sanitizedUnit.substring(0, 56), sanitizedUnit.substring(56));
        }
    }

    public static AssetType ada() {
        return Ada;
    }

    public byte[] getPlutusDataPolicyId() {
        if (this.equals(Ada)) {
            return new byte[0];
        } else {
            return HexUtil.decodeHexString(policyId);
        }
    }

    public byte[] getPlutusDataAssetName() {
        if (this.equals(Ada)) {
            return new byte[0];
        } else {
            return HexUtil.decodeHexString(assetName);
        }
    }

    public static AssetType fromPlutusData(byte[] policyId, byte[] assetName) {
        if (policyId.length == 0) {
            return Ada;
        } else {
            return new AssetType(HexUtil.encodeHexString(policyId), HexUtil.encodeHexString(assetName));
        }
    }

}
