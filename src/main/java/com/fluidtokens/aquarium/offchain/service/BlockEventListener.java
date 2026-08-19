package com.fluidtokens.aquarium.offchain.service;

import com.bloxbean.cardano.yaci.store.events.internal.CommitEvent;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.conversions.CardanoConverters;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
@Slf4j
public class BlockEventListener {

    private final CardanoConverters cardanoConverters;

    private final NodeMetrics nodeMetrics;

    @Getter
    private final AtomicBoolean isSyncing = new AtomicBoolean(true);

    @EventListener
    public void processBlock(CommitEvent<?> commitEvent) {

        var currentRealSlot = cardanoConverters.time().toSlot(LocalDateTime.now(ZoneOffset.UTC));

        long slot = commitEvent.getMetadata().getSlot();
        boolean syncing = slot < currentRealSlot - 60 * 10;
        isSyncing.set(syncing);
        nodeMetrics.recordBlockProcessed();
        nodeMetrics.setChainTipSlot(slot);
        nodeMetrics.setSyncing(syncing);

    }


}
