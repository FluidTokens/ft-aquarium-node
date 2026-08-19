package com.fluidtokens.aquarium.offchain.controller.health;

import com.fluidtokens.aquarium.offchain.service.ParametersService;
import com.fluidtokens.aquarium.offchain.service.BlockEventListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DatabaseHealthIndicator implements HealthIndicator {

    private final ParametersService parametersService;
    private final BlockEventListener blockEventListener;

    @Override
    public Health health() {
        if (blockEventListener.getIsSyncing().get()) {
            return Health.up()
                    .withDetail("state", "starting")
                    .withDetail("component", "database")
                    .build();
        }
        try {
            // Test database connectivity by loading parameters
            parametersService.loadParameters();
            return Health.up()
                    .withDetail("status", "Database connection successful")
                    .withDetail("component", "database")
                    .build();
        } catch (Exception e) {
            log.warn("[HEALTH] Database health check failed", e);
            return Health.down()
                    .withDetail("status", "Database connection failed")
                    .withDetail("error", e.getMessage())
                    .withDetail("component", "database")
                    .build();
        }
    }
}