package com.fluidtokens.aquarium.offchain.service;

import com.bloxbean.cardano.yaci.store.events.internal.CommitEvent;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.conversions.CardanoConverters;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.time.ZoneOffset.UTC;

@Service
@RequiredArgsConstructor
@Slf4j
public class BlockEventListener {

    private final CardanoConverters cardanoConverters;
    private final MetricsService metricsService;

    @Getter
    private final AtomicBoolean isSyncing = new AtomicBoolean(true);

    @EventListener
    public void processBlock(CommitEvent<?> commitEvent) {
        // Update metrics
        metricsService.incrementBlocksProcessed();
        metricsService.updateCurrentSlot(commitEvent.getMetadata().getSlot());

        var currentRealSlot = cardanoConverters.time().toSlot(LocalDateTime.now(UTC));

        boolean syncing = commitEvent.getMetadata().getSlot() < currentRealSlot - 60 * 10;
        isSyncing.set(syncing);

        metricsService.updateSyncStatus(syncing);
    }

}
