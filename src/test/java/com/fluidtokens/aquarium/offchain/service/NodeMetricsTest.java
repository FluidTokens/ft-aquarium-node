package com.fluidtokens.aquarium.offchain.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NodeMetricsTest {

    @Test
    void recordBlockProcessedIncrementsCounter() {
        var registry = new SimpleMeterRegistry();
        var metrics = new NodeMetrics(registry);

        metrics.recordBlockProcessed();
        metrics.recordBlockProcessed();

        assertEquals(2.0, registry.counter("aquarium.blocks.processed").count());
    }

    @Test
    void setSyncingUpdatesGauge() {
        var registry = new SimpleMeterRegistry();
        var metrics = new NodeMetrics(registry);

        metrics.setSyncing(true);
        assertEquals(1.0, registry.get("aquarium.sync.status").gauge().value());

        metrics.setSyncing(false);
        assertEquals(0.0, registry.get("aquarium.sync.status").gauge().value());
    }

    @Test
    void setChainTipSlotUpdatesGauge() {
        var registry = new SimpleMeterRegistry();
        var metrics = new NodeMetrics(registry);

        metrics.setChainTipSlot(123L);

        assertEquals(123.0, registry.get("aquarium.chain.tip.slot").gauge().value());
    }

    @Test
    void scheduledTxCountersTrackOutcomeSeparately() {
        var registry = new SimpleMeterRegistry();
        var metrics = new NodeMetrics(registry);

        metrics.recordScheduledTxSuccess();
        metrics.recordScheduledTxFailure();

        assertEquals(1.0, registry.get("aquarium.scheduled_tx.processed").tag("outcome", "success").counter().count());
        assertEquals(1.0, registry.get("aquarium.scheduled_tx.processed").tag("outcome", "failure").counter().count());
    }

}
