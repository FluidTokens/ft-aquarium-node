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
public class ParametersRefInputHealthIndicator implements HealthIndicator {

    private final ParametersService parametersService;
    private final BlockEventListener blockEventListener;

    @Override
    public Health health() {
        if (blockEventListener.getIsSyncing().get()) {
            return Health.up()
                    .withDetail("state", "starting")
                    .withDetail("component", "parameters-ref-input")
                    .build();
        }
        try {
            // Test parameters ref input loading
            parametersService.loadParametersRefInput();
            return Health.up()
                    .withDetail("status", "Parameters reference input loaded successfully")
                    .withDetail("component", "parameters-ref-input")
                    .build();
        } catch (Exception e) {
            log.warn("[HEALTH] Parameters ref input health check failed", e);
            return Health.down()
                    .withDetail("status", "Parameters reference input loading failed")
                    .withDetail("error", e.getMessage())
                    .withDetail("component", "parameters-ref-input")
                    .build();
        }
    }
}