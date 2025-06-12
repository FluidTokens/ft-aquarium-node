package com.fluidtokens.aquarium.offchain.util;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.yaci.store.common.domain.Amt;

public class AmountUtil {

    public static Amount toAmountCore(com.bloxbean.cardano.yaci.core.model.Amount amount) {
        return Amount.builder()
                .unit(amount.getUnit().replaceAll("\\.", ""))
                .quantity(amount.getQuantity())
                .build();
    }

    public static Amount toAmountCore(Amt amount) {
        return Amount.builder()
                .unit(amount.getUnit().replaceAll("\\.", ""))
                .quantity(amount.getQuantity())
                .build();
    }

}
