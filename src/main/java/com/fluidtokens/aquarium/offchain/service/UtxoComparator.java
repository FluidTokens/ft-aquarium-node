package com.fluidtokens.aquarium.offchain.service;

import com.bloxbean.cardano.client.api.model.Utxo;

import java.util.Comparator;

public class UtxoComparator implements Comparator<Utxo> {

    @Override
    public int compare(Utxo o1, Utxo o2) {
        int i = o1.getTxHash().compareTo(o2.getTxHash());

        if (i == 0) {

            if (o1.getOutputIndex() == o2.getOutputIndex()) {
                return 0;
            } else {
                return o1.getOutputIndex() - o2.getOutputIndex();
            }

        } else {
            return i;
        }
    }

}
