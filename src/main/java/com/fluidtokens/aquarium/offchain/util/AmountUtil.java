package com.fluidtokens.aquarium.offchain.util;

import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.yaci.store.common.domain.Amt;
import com.fluidtokens.aquarium.offchain.model.AssetType;

import java.util.List;

public class AmountUtil {

    public static Value toValue(List<Amt> assetAmounts) {
        return assetAmounts
                .stream()
                .reduce(Value.builder().build(), (a, b) -> {
                    System.out.println("unit: " + b.getUnit());
                    var assetType = AssetType.fromUnit(b.getUnit());
                    Value value;
                    if (assetType.isAda()) {
                        System.out.println("is ada");
                        value = Value.builder().coin(b.getQuantity()).build();
                    } else {
                        System.out.println("not ada");
                        value = Value.builder()
                                .multiAssets(List.of(MultiAsset.builder()
                                        .policyId(assetType.policyId())
                                        .assets(List.of(Asset.builder()
                                                .name(assetType.assetName())
                                                .value(b.getQuantity())
                                                .build()))
                                        .build()))
                                .build();
                    }
                    return a.plus(value);
                }, Value::plus);
    }

}
