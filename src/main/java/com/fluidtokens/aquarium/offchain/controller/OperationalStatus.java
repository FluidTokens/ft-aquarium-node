package com.fluidtokens.aquarium.offchain.controller;

import java.util.ArrayList;
import java.util.List;

import com.fluidtokens.aquarium.offchain.config.AppConfig;

/**
 * ⛔ <b>What this node WILL DO, as distinct from what the numbers below it describe.</b>
 *
 * <h2>The defect this exists to close</h2>
 * Until 2026-09-21 the readiness page rendered {@code CAPITAL IN ADVANCE} and a positive margin on a
 * node whose {@code AQUARIUM_LIQUIDATION_MODE} was {@code disabled}, with <b>nothing anywhere saying
 * the bot would do none of it</b>. The only banner on the page fired when a Spring bean failed to
 * build; arming was never mentioned. A page describing what the bot <i>would</i> do read as a
 * description of what it <i>was</i> doing.
 *
 * <p>That is the mirror of the failure this codebase keeps cataloguing. The documented one is
 * <b>armed and idle</b> — three silent ways a running bot does nothing. This is <b>idle and looking
 * armed</b>, and it is the more expensive of the two on a page an operator trusts to tell them
 * whether to intervene.
 *
 * <h2>⚠ EFFECTIVE, never configured</h2>
 * The node mode is a <b>ceiling</b>: a market asking for {@code LIVE} on a {@code shadow} node runs
 * as {@code SHADOW}. Reporting the configured value would make the banner a new way to be
 * confidently wrong, so {@link #of} counts markets whose <i>effective</i> mode differs from the
 * node's and says so — a number an operator can act on, rather than a claim they cannot check.
 */
public record OperationalStatus(String headline,
                                String liquidationMode,
                                boolean convertEnabled,
                                boolean compoundEnabled,
                                boolean processorEnabled,
                                int marketsOverridden,
                                List<String> notes) {

    /** True when nothing on this node can submit anything — the common case, and it must be loud. */
    public boolean monitoringOnly() {
        return "MONITORING ONLY".equals(headline);
    }

    public static OperationalStatus of(AppConfig.LiquidationConfiguration liquidation,
                                       boolean convertEnabled,
                                       boolean compoundEnabled,
                                       boolean processorEnabled) {
        AppConfig.LiquidationConfiguration.Mode mode =
                liquidation == null || liquidation.getMode() == null
                        ? AppConfig.LiquidationConfiguration.Mode.DISABLED
                        : liquidation.getMode();

        int overridden = 0;
        if (liquidation != null && liquidation.getMarkets() != null) {
            for (var market : liquidation.getMarkets()) {
                if (liquidation.effectiveMode(market) != mode) {
                    overridden++;
                }
            }
        }

        List<String> notes = new ArrayList<>();
        // ⚠ Each note names something that makes the figures below NOT a description of intent. They
        // are separate because an operator acts on them differently: a mode is one restart away, a
        // market override is a per-asset decision, and convert being off is a global posture.
        if (mode == AppConfig.LiquidationConfiguration.Mode.DISABLED) {
            notes.add("liquidation is DISABLED — the figures below are a simulation, not an intention");
        } else if (mode == AppConfig.LiquidationConfiguration.Mode.SHADOW) {
            notes.add("liquidation is in SHADOW — every transaction is built and size-checked, and "
                    + "NONE is submitted");
        }
        if (!convertEnabled) {
            notes.add("convert is globally off, so no loan will be routed to a DEX whatever its "
                    + "market says");
        }
        if (overridden > 0) {
            notes.add(overridden + " market(s) run at a different mode from the node — the node mode "
                    + "is a ceiling, never a floor");
        }
        if (!processorEnabled) {
            notes.add("the scheduled-transaction processor is off — this is the half you stake FLDT "
                    + "for, and it is unrelated to liquidation");
        }

        // ⛔ LIVE is claimed only when something can actually reach the chain. A node in `live` with
        // convert off and every market disabled submits nothing, and must not announce otherwise.
        String headline;
        if (mode == AppConfig.LiquidationConfiguration.Mode.LIVE) {
            headline = "LIVE — this node can submit";
        } else if (mode == AppConfig.LiquidationConfiguration.Mode.SHADOW) {
            headline = "SHADOW — rehearsing, submitting nothing";
        } else if (processorEnabled) {
            headline = "PROCESSOR ONLY — no liquidation";
        } else {
            headline = "MONITORING ONLY";
        }

        return new OperationalStatus(headline, mode.name(), convertEnabled, compoundEnabled,
                processorEnabled, overridden, notes);
    }
}
