package com.fluidtokens.aquarium.offchain.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class NodeMetrics {

    private final Counter blocksProcessed;

    private final AtomicInteger syncing = new AtomicInteger(0);

    private final AtomicLong chainTipSlot = new AtomicLong(0);

    private final Counter scheduledTxSuccess;

    private final Counter scheduledTxFailure;

    public NodeMetrics(MeterRegistry registry) {

        this.blocksProcessed = Counter.builder("aquarium.blocks.processed")
                .description("Number of blocks processed by the node's block event listener")
                .register(registry);

        Gauge.builder("aquarium.sync.status", syncing, Number::doubleValue)
                .description("Whether the node is currently syncing (1) or caught up (0)")
                .register(registry);

        Gauge.builder("aquarium.chain.tip.slot", chainTipSlot, Number::doubleValue)
                .description("The slot of the most recently processed block")
                .register(registry);

        this.scheduledTxSuccess = Counter.builder("aquarium.scheduled_tx.processed")
                .description("Number of scheduled transactions processed, by outcome")
                .tag("outcome", "success")
                .register(registry);

        this.scheduledTxFailure = Counter.builder("aquarium.scheduled_tx.processed")
                .description("Number of scheduled transactions processed, by outcome")
                .tag("outcome", "failure")
                .register(registry);
    }

    public void recordBlockProcessed() {
        blocksProcessed.increment();
    }

    public void setSyncing(boolean isSyncing) {
        syncing.set(isSyncing ? 1 : 0);
    }

    public void setChainTipSlot(long slot) {
        chainTipSlot.set(slot);
    }

    public void recordScheduledTxSuccess() {
        scheduledTxSuccess.increment();
    }

    public void recordScheduledTxFailure() {
        scheduledTxFailure.increment();
    }

}
