package com.fluidtokens.aquarium.offchain.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Publishes the {@link LendingConfigGate} as a gauge, so a closed gate is visible to monitoring rather
 * than only in one ERROR line at boot. Prometheus name {@code aquarium_lending_gate_closed}: {@code 1}
 * while every Lending v4 transaction is refused, {@code 0} otherwise.
 */
@Component
public class LendingGateMetrics {

    static final String GATE_CLOSED = "aquarium.lending.gate.closed";

    public LendingGateMetrics(MeterRegistry registry, LendingConfigGate gate) {
        Gauge.builder(GATE_CLOSED, gate, g -> g.isBlocked() ? 1 : 0)
                .description("1 while the Lending v4 config gate is closed and every lending transaction is "
                        + "refused (LENDING_CONFIG_MISMATCH); 0 while lending may build")
                .register(registry);
    }
}
