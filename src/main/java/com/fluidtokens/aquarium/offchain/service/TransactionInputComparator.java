package com.fluidtokens.aquarium.offchain.service;

import com.bloxbean.cardano.client.transaction.spec.TransactionInput;

import java.util.Comparator;

public class TransactionInputComparator implements Comparator<TransactionInput> {

    @Override
    public int compare(TransactionInput tInput1, TransactionInput tInput2) {
        int i = tInput1.getTransactionId().compareTo(tInput2.getTransactionId());

        if (i == 0) {

            if (tInput1.getIndex() == tInput2.getIndex()) {
                return 0;
            } else {
                return tInput1.getIndex() - tInput2.getIndex();
            }

        } else {
            return i;
        }
    }

}
