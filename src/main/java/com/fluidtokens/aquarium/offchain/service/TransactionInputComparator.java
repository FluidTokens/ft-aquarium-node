package com.fluidtokens.aquarium.offchain.service;

import com.bloxbean.cardano.client.transaction.spec.TransactionInput;

import java.util.Comparator;

/**
 * Elegant comparator for TransactionInput objects.
 * Compares by transaction ID first, then by index for deterministic ordering.
 */
public class TransactionInputComparator implements Comparator<TransactionInput> {

    @Override
    public int compare(TransactionInput input1, TransactionInput input2) {
        return Comparator.comparing(TransactionInput::getTransactionId)
                .thenComparing(TransactionInput::getIndex)
                .compare(input1, input2);
    }

}
