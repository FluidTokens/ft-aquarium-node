package com.fluidtokens.aquarium.offchain.service;

import com.bloxbean.cardano.yaci.store.events.EventMetadata;
import com.bloxbean.cardano.yaci.store.events.internal.CommitEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.cardanofoundation.conversions.CardanoConverters;
import org.cardanofoundation.conversions.ClasspathConversionsFactory;
import org.cardanofoundation.conversions.domain.NetworkType;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockEventListenerTest {

    @Test
    void processBlockRecordsMetricsAndSetsSyncingWhenFarBehindTip() {
        var converters = ClasspathConversionsFactory.createConverters(NetworkType.MAINNET);
        var registry = new SimpleMeterRegistry();
        var metrics = new NodeMetrics(registry);
        var listener = new BlockEventListener(converters, metrics);

        var metadata = EventMetadata.builder().slot(1L).build();
        var event = new CommitEvent<>(metadata, List.<Object>of());

        listener.processBlock(event);

        assertEquals(1.0, registry.counter("aquarium.blocks.processed").count());
        assertEquals(1.0, registry.get("aquarium.chain.tip.slot").gauge().value());
        assertEquals(1.0, registry.get("aquarium.sync.status").gauge().value());
    }

    @Test
    void processBlockClearsSyncingWhenAtTip() {
        var converters = ClasspathConversionsFactory.createConverters(NetworkType.MAINNET);
        var registry = new SimpleMeterRegistry();
        var metrics = new NodeMetrics(registry);
        var listener = new BlockEventListener(converters, metrics);

        long tip = converters.time().toSlot(LocalDateTime.now(ZoneOffset.UTC));
        var metadata = EventMetadata.builder().slot(tip).build();
        var event = new CommitEvent<>(metadata, List.<Object>of());

        listener.processBlock(event);

        assertEquals(0.0, registry.get("aquarium.sync.status").gauge().value());
    }

}
