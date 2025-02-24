package com.fluidtokens.aquarium.offchain.util;

import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.fluidtokens.aquarium.offchain.blueprint.types.general.model.CardanoToken;
import com.fluidtokens.aquarium.offchain.model.AssetType;

import java.util.List;

public class AssetAmountUtil {

    public static Value toValue(List<CardanoToken> assetAmounts) {
        return assetAmounts
                .stream()
                .reduce(Value.builder().build(), (a, b) -> {
                    var assetType = AssetType.fromPlutusData(b.getPolicyid(), b.getAssetname());
                    Value value;
                    if (assetType.isAda()) {
                        value = Value.builder().coin(b.getAmount()).build();
                    } else {
                        value = Value.builder()
                                .multiAssets(List.of(MultiAsset.builder()
                                        .policyId(assetType.policyId())
                                        .assets(List.of(Asset.builder()
                                                .name("0x" + assetType.assetName())
                                                .value(b.getAmount())
                                                .build()))
                                        .build()))
                                .build();
                    }
                    return a.plus(value);
                }, Value::plus);
    }

}
