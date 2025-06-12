package com.fluidtokens.aquarium.offchain.util;

import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.yaci.store.common.domain.AddressUtxo;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.AddressUtxoEntity;

public class UtxoUtil {

    public static Utxo toUtxo(AddressUtxoEntity addressUtxoEntity) {
        return Utxo.builder()
                .txHash(addressUtxoEntity.getTxHash())
                .outputIndex(addressUtxoEntity.getOutputIndex())
                .address(addressUtxoEntity.getOwnerAddr())
                .amount(addressUtxoEntity.getAmounts().stream().map(AmountUtil::toAmountCore).toList())
                .dataHash(addressUtxoEntity.getDataHash())
                .inlineDatum(addressUtxoEntity.getInlineDatum())
                .referenceScriptHash(addressUtxoEntity.getReferenceScriptHash())
                .build();
    }

    public static Utxo toUtxo(AddressUtxo addressUtxo) {
        return Utxo.builder()
                .txHash(addressUtxo.getTxHash())
                .outputIndex(addressUtxo.getOutputIndex())
                .address(addressUtxo.getOwnerAddr())
                .amount(addressUtxo.getAmounts().stream().map(AmountUtil::toAmountCore).toList())
                .dataHash(addressUtxo.getDataHash())
                .inlineDatum(addressUtxo.getInlineDatum())
                .referenceScriptHash(addressUtxo.getScriptRef())
                .build();
    }

}
