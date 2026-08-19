package com.fluidtokens.aquarium.offchain.service;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the Prometheus TEXT rendering that {@link NodeMetricsTest} (backed by a
 * {@link io.micrometer.core.instrument.simple.SimpleMeterRegistry}) cannot exercise: the
 * dots-to-underscores name translation, the counter {@code _total} suffix, the
 * two-counters-same-name/different-tag rendering, and the gauge/counter suffix contrast.
 * Pure unit test — no Spring context, no network.
 */
class NodeMetricsPrometheusTest {

    @Test
    void scrapeRendersAquariumMetricsInPrometheusTextFormat() {
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        var m = new NodeMetrics(registry);

        m.recordBlockProcessed();
        m.recordBlockProcessed();
        m.recordScheduledTxSuccess();
        m.recordScheduledTxFailure();
        m.setSyncing(true);
        m.setChainTipSlot(42L);

        String scraped = registry.scrape();

        // Dots -> underscores, counter gets a _total suffix.
        assertTrue(
                Pattern.compile("aquarium_blocks_processed_total(\\{[^}]*})?\\s+2\\.0").matcher(scraped).find(),
                () -> "expected aquarium_blocks_processed_total at 2.0 in:\n" + scraped);

        // Same metric name, different "outcome" tag, rendered as two separate series.
        assertTrue(scraped.contains("aquarium_scheduled_tx_processed_total"),
                () -> "expected aquarium_scheduled_tx_processed_total in:\n" + scraped);
        assertTrue(
                Pattern.compile("aquarium_scheduled_tx_processed_total\\{[^}]*outcome=\"success\"[^}]*}\\s+1\\.0")
                        .matcher(scraped).find(),
                () -> "expected outcome=\"success\" at 1.0 in:\n" + scraped);
        assertTrue(
                Pattern.compile("aquarium_scheduled_tx_processed_total\\{[^}]*outcome=\"failure\"[^}]*}\\s+1\\.0")
                        .matcher(scraped).find(),
                () -> "expected outcome=\"failure\" at 1.0 in:\n" + scraped);

        // Gauge: no _total suffix — the contrast with the counter suffix above is the point.
        assertTrue(
                Pattern.compile("aquarium_sync_status(\\{[^}]*})?\\s+1\\.0").matcher(scraped).find(),
                () -> "expected aquarium_sync_status at 1.0 in:\n" + scraped);

        assertTrue(
                Pattern.compile("aquarium_chain_tip_slot(\\{[^}]*})?\\s+42\\.0").matcher(scraped).find(),
                () -> "expected aquarium_chain_tip_slot at 42.0 in:\n" + scraped);
    }

}
