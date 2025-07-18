package com.fluidtokens.aquarium.offchain.controller.health;

import com.fluidtokens.aquarium.offchain.service.StakerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class StakingHealthIndicator implements HealthIndicator {

    private final StakerService stakerService;

    @Override
    public Health health() {
        try {
            var stakerRefInputs = stakerService.findStakerRefInput();
            boolean hasStaking = !stakerRefInputs.isEmpty();
            
            if (hasStaking) {
                return Health.up()
                        .withDetail("status", "Staking is active")
                        .withDetail("staker_ref_inputs", stakerRefInputs.size())
                        .withDetail("component", "staking")
                        .build();
            } else {
                log.warn("[HEALTH] The current wallet does not have any FLDT delegated. Ensure you're staking FLDT to the Node's wallet.");
                return Health.down()
                        .withDetail("status", "No staking found")
                        .withDetail("message", "Ensure you're staking FLDT to the Node's wallet")
                        .withDetail("component", "staking")
                        .build();
            }
        } catch (Exception e) {
            log.warn("[HEALTH] Staking health check failed", e);
            return Health.down()
                    .withDetail("status", "Unable to check staking status")
                    .withDetail("error", e.getMessage())
                    .withDetail("component", "staking")
                    .build();
        }
    }
}