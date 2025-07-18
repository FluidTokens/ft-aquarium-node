package com.fluidtokens.aquarium.offchain.controller.health;

import com.fluidtokens.aquarium.offchain.service.ParametersService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ParametersHealthIndicator implements HealthIndicator {

    private final ParametersService parametersService;

    @Override
    public Health health() {
        try {
            // Test parameters loading
            parametersService.loadParameters();
            return Health.up()
                    .withDetail("status", "Parameters loaded successfully")
                    .withDetail("component", "parameters")
                    .build();
        } catch (Exception e) {
            log.warn("[HEALTH] Parameters health check failed", e);
            return Health.down()
                    .withDetail("status", "Parameters loading failed")
                    .withDetail("error", e.getMessage())
                    .withDetail("component", "parameters")
                    .build();
        }
    }
}