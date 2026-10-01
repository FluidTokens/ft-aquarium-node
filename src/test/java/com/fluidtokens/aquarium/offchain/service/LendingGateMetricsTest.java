package com.fluidtokens.aquarium.offchain.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** FAB-116: the gate as a gauge -- {@code aquarium_lending_gate_closed} 0 while open, 1 once closed. */
class LendingGateMetricsTest {

    @Test
    void theGaugeFollowsTheGate() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LendingConfigGate gate = new LendingConfigGate();
        new LendingGateMetrics(registry, gate);

        assertEquals(0.0, registry.get(LendingGateMetrics.GATE_CLOSED).gauge().value());
        gate.block("mismatch");
        assertEquals(1.0, registry.get(LendingGateMetrics.GATE_CLOSED).gauge().value());
    }
}
