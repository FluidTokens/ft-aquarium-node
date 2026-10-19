package com.fluidtokens.aquarium.offchain.config;

// ⚠ In the CONFIG package on purpose, though the class under test lives in `controller`:
// LiquidationConfiguration.setModeName/parseMode are package-private, and building a real
// configuration is the whole point — a hand-rolled stub would not exercise the ceiling logic in
// effectiveMode(), which is precisely what this banner can get wrong.
import com.fluidtokens.aquarium.offchain.controller.OperationalStatus;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>The banner that was missing, and the defect it closes.</b>
 *
 * <p>Until 2026-09-21 the readiness page rendered a route and a positive margin on a node whose
 * liquidation mode was {@code disabled}, with <b>nothing anywhere saying the bot would do none of
 * it</b>. The only banner fired when a Spring bean failed to build. A page describing what the bot
 * <i>would</i> do read as a description of what it <i>was</i> doing.
 */
class OperationalStatusTest {

    private static AppConfig.LiquidationConfiguration config(
            AppConfig.LiquidationConfiguration.Mode mode,
            AppConfig.LiquidationConfiguration.Mode... marketModes) {
        var configuration = new AppConfig.LiquidationConfiguration();
        configuration.setModeName(mode.name());
        configuration.parseMode();
        var markets = new java.util.ArrayList<AppConfig.LiquidationConfiguration.Market>();
        for (var marketMode : marketModes) {
            var market = new AppConfig.LiquidationConfiguration.Market();
            market.setUnit("lovelace");
            market.setMode(marketMode);
            markets.add(market);
        }
        configuration.setMarkets(markets);
        return configuration;
    }

    @Test
    void aFullyDisabledNodeSaysMonitoringOnlyAndSaysWhy() {
        var status = OperationalStatus.of(config(AppConfig.LiquidationConfiguration.Mode.DISABLED),
                true, false, false);

        assertEquals("MONITORING ONLY", status.headline());
        assertTrue(status.monitoringOnly());
        assertTrue(status.notes().stream().anyMatch(n -> n.contains("simulation")),
                "the figures below must be named as a simulation: " + status.notes());
    }

    /**
     * ⚠ A node doing the scheduled-transaction half but no liquidation is NOT "monitoring only" —
     * it is submitting transactions, just not liquidations. Collapsing the two would understate what
     * a running node is doing.
     */
    @Test
    void aProcessorOnlyNodeIsNotDescribedAsMonitoringOnly() {
        var status = OperationalStatus.of(config(AppConfig.LiquidationConfiguration.Mode.DISABLED),
                true, false, true);

        assertEquals("PROCESSOR ONLY — no liquidation", status.headline());
        assertFalse(status.monitoringOnly());
    }

    /**
     * ⚠ SHADOW submits no LIQUIDATION — and only that. Since wallet rebalancing (FAB-131) a shadow node
     * signs and submits wallet self-sends, so "submitting nothing" stopped being true; the wording is
     * narrowed to what it can still promise.
     */
    @Test
    void shadowSaysItBuildsEveryLiquidationAndSubmitsNoLiquidation() {
        var status = OperationalStatus.of(config(AppConfig.LiquidationConfiguration.Mode.SHADOW),
                true, false, false);

        assertEquals("SHADOW — rehearsing liquidations, submitting none", status.headline());
        assertTrue(status.notes().contains("liquidation is in SHADOW — every liquidation is built and "
                + "size-checked, and NONE is submitted"), "" + status.notes());
        assertFalse(status.notes().stream().anyMatch(n -> n.contains("every transaction")),
                "the old note claimed every transaction, which a rebalance contradicts: " + status.notes());
    }

    private static final String ARMED_NOTE = "wallet rebalancing is ARMED — whenever the wallet drifts out of "
            + "shape, the node signs and submits a self-send of its whole wallet, in shadow as well as live; "
            + "it is housekeeping, never a liquidation";

    /** ⛔ A shadow node with the rebalancer armed DOES submit, and the banner must say so. */
    @Test
    void shadowWithRebalancingArmedSaysTheNodeSubmitsWalletSelfSends() {
        var status = OperationalStatus.of(config(AppConfig.LiquidationConfiguration.Mode.SHADOW),
                true, false, false, true);

        assertEquals("SHADOW — rehearsing liquidations, submitting none", status.headline());
        assertTrue(status.notes().contains(ARMED_NOTE), "" + status.notes());
    }

    @Test
    void shadowWithRebalancingIdleCarriesNoArmedNote() {
        var status = OperationalStatus.of(config(AppConfig.LiquidationConfiguration.Mode.SHADOW),
                true, false, false, false);

        assertFalse(status.notes().stream().anyMatch(n -> n.contains("rebalancing")), "" + status.notes());
        assertEquals(OperationalStatus.of(config(AppConfig.LiquidationConfiguration.Mode.SHADOW),
                true, false, false), status, "the four-argument of() is the overload with armed = false");
    }

    @Test
    void liveWithRebalancingArmedCarriesTheArmedNote() {
        var status = OperationalStatus.of(config(AppConfig.LiquidationConfiguration.Mode.LIVE),
                true, true, true, true);

        assertEquals("LIVE — this node can submit", status.headline());
        assertTrue(status.notes().contains(ARMED_NOTE), "" + status.notes());
    }

    /** The other headlines are untouched by the overload, armed or not. */
    @Test
    void theOtherHeadlinesAreUnchangedByTheOverload() {
        for (boolean armed : new boolean[] {false, true}) {
            assertEquals("MONITORING ONLY", OperationalStatus.of(
                    config(AppConfig.LiquidationConfiguration.Mode.DISABLED), true, false, false, armed).headline());
            assertEquals("PROCESSOR ONLY — no liquidation", OperationalStatus.of(
                    config(AppConfig.LiquidationConfiguration.Mode.DISABLED), true, false, true, armed).headline());
            assertEquals("LIVE — this node can submit", OperationalStatus.of(
                    config(AppConfig.LiquidationConfiguration.Mode.LIVE), true, false, false, armed).headline());
        }
        assertEquals(List.of("liquidation is DISABLED — the figures below are a simulation, not an intention",
                        "the scheduled-transaction processor is off — this is the half you stake FLDT for, and it "
                                + "is unrelated to liquidation"),
                OperationalStatus.of(config(AppConfig.LiquidationConfiguration.Mode.DISABLED), true, false, false,
                        false).notes(), "the DISABLED notes are unchanged");
    }

    @Test
    void liveSaysPlainlyThatThisNodeCanSubmit() {
        var status = OperationalStatus.of(config(AppConfig.LiquidationConfiguration.Mode.LIVE),
                true, true, true);

        assertTrue(status.headline().startsWith("LIVE"));
        assertFalse(status.monitoringOnly());
    }

    /**
     * ⛔ <b>EFFECTIVE, never configured.</b> The node mode is a ceiling: a market asking for LIVE on
     * a shadow node runs as SHADOW. Counting configured modes would make this banner a new way to be
     * confidently wrong, so what is counted is markets whose EFFECTIVE mode differs from the node's.
     */
    @Test
    void aMarketCappedByTheNodeCeilingCountsAsOverridden() {
        var status = OperationalStatus.of(
                config(AppConfig.LiquidationConfiguration.Mode.SHADOW,
                        AppConfig.LiquidationConfiguration.Mode.DISABLED),
                true, false, false);

        assertEquals(1, status.marketsOverridden(), "a DISABLED market under a SHADOW node differs");
        assertTrue(status.notes().stream().anyMatch(n -> n.contains("ceiling")), "" + status.notes());
    }

    /** ⚠ A market asking for LIVE under SHADOW runs as SHADOW — so it does NOT differ, and must not be counted. */
    @Test
    void aMarketAskingForMoreThanTheNodeAllowsIsNotCountedAsOverridden() {
        var status = OperationalStatus.of(
                config(AppConfig.LiquidationConfiguration.Mode.SHADOW,
                        AppConfig.LiquidationConfiguration.Mode.LIVE),
                true, false, false);

        assertEquals(0, status.marketsOverridden(),
                "the ceiling makes this market SHADOW, the same as the node — nothing is overridden");
    }

    /** Convert being globally off is one of the documented silent-idle causes, so the banner says it. */
    @Test
    void convertBeingGloballyOffIsCalledOut() {
        var status = OperationalStatus.of(config(AppConfig.LiquidationConfiguration.Mode.LIVE),
                false, false, false);

        assertTrue(status.notes().stream().anyMatch(n -> n.contains("convert is globally off")),
                "" + status.notes());
    }

    /** A null configuration must not throw on a page whose whole job is to report state. */
    @Test
    void anAbsentConfigurationDegradesToDisabledRatherThanFailing() {
        var status = OperationalStatus.of(null, true, false, false);

        assertEquals("MONITORING ONLY", status.headline());
        assertEquals("DISABLED", status.liquidationMode(), "an absent configuration is not armed");
        assertEquals(0, status.marketsOverridden());
    }
}
