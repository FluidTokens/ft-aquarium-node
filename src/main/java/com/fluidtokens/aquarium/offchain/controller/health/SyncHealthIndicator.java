package com.fluidtokens.aquarium.offchain.controller.health;

import com.fluidtokens.aquarium.offchain.service.BlockEventListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SyncHealthIndicator implements HealthIndicator {

    private final BlockEventListener blockEventListener;

    @Override
    public Health health() {
        boolean isSyncing = blockEventListener.getIsSyncing().get();
        
        if (isSyncing) {
            log.info("[HEALTH] Aquarium Node is correctly syncing the blockchain.");
            return Health.up()
                    .withDetail("status", "Syncing blockchain")
                    .withDetail("is_syncing", true)
                    .withDetail("component", "sync")
                    .build();
        } else {
            return Health.up()
                    .withDetail("status", "Sync completed")
                    .withDetail("is_syncing", false)
                    .withDetail("component", "sync")
                    .build();
        }
    }
}