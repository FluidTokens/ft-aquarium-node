package com.fluidtokens.aquarium.offchain.controller;

import java.math.BigInteger;

import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration.Action;
import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration.Market;
import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration.Mode;

/**
 * ⛔ <b>What the bot would do about THIS loan, right now, on THIS node.</b>
 *
 * <h2>Why it is separate from the route</h2>
 * The route says which transaction a liquidation would build; it is a property of the market and the
 * bond, and it is true whether or not anything is armed. Operators read it as intent, and on a
 * disabled node that reading is wrong — which is the whole reason {@link OperationalStatus} exists.
 * This answers the other question, and it is the one an operator actually acts on.
 *
 * <h2>⚠ The three ways a loan looks actionable and is not</h2>
 * Each of these renders a route and a positive margin while the bot does nothing, and each sends an
 * operator somewhere different, so none may stand in for another:
 * <ol>
 *   <li><b>The node mode is a CEILING.</b> A market configured {@code LIVE} on a {@code shadow} node
 *       runs as {@code SHADOW}. Reading the market's own mode here would report a submission that
 *       cannot happen.</li>
 *   <li><b>The cap is below what the loan needs.</b> An {@code ANTICIPATE} market with a cap under
 *       the advance is configured, valid, and cannot act on this particular loan — a per-loan fact
 *       that no per-market display reveals.</li>
 *   <li><b>Convert is globally off.</b> {@code LOANS_LIQUIDATION_CONVERT_ENABLED=false} overrides
 *       every market's {@code CONVERT}, and the market still reads as CONVERT.</li>
 * </ol>
 *
 * <p>⚠ <b>Health is checked FIRST and deliberately.</b> Most loans are healthy, and "the bot is off"
 * is not the interesting answer for a loan that would not be touched anyway — it buries the arming
 * problem under rows where it does not matter.
 */
public record ActionNow(String text, String detail, boolean wouldAct) {

    private static ActionNow none(String text, String detail) {
        return new ActionNow(text, detail, false);
    }

    /**
     * @param liquidatable  null when health could not be computed — which is NOT "no"
     * @param effectiveMode the market's mode after the node's ceiling is applied
     * @param advance       what an ANTICIPATE would have to front, null when unknown
     */
    public static ActionNow of(Boolean liquidatable, Mode effectiveMode, Action action,
                               Market market, boolean convertEnabled, boolean poolUsable,
                               BigInteger advance) {
        if (liquidatable == null) {
            return none("UNKNOWN", "this loan's health could not be computed, so whether the bot "
                    + "would act on it is not known either — this is not the same as 'no'");
        }
        if (!liquidatable) {
            return none("NONE", "this loan is not liquidatable yet; nothing would be attempted "
                    + "whatever the bot's configuration says");
        }
        if (effectiveMode == Mode.DISABLED) {
            return none("NONE — disabled", market != null && market.getMode() != null
                    ? "this market is configured mode: DISABLED"
                    : "liquidation is disabled on this node");
        }

        Action effectiveAction = action == null ? Action.CONVERT : action;
        if (effectiveAction == Action.CONVERT) {
            if (!convertEnabled) {
                return none("NONE — convert off", "this market routes to CONVERT, but convert is "
                        + "globally disabled on this node, so no DEX order would be created");
            }
            if (!poolUsable) {
                return none("NONE — pool", "no Minswap pool can clear this loan's debt, so the "
                        + "convert order would be refused before it was built");
            }
        } else if (market != null && market.getCap() != null && advance != null
                && market.getCap().compareTo(advance) < 0) {
            // ⛔ PER-LOAN, not per-market. The market is valid and the cap is deliberate; it simply
            // does not reach THIS loan, and nothing about the market's own display would show that.
            return none("NONE — cap", "this market's cap of " + market.getCap() + " is below the "
                    + advance + " this loan would need fronted");
        }

        String verb = effectiveAction == Action.CONVERT ? "CONVERT" : "ADVANCE";
        if (effectiveMode == Mode.SHADOW) {
            return none("WOULD " + verb, "shadow mode: the transaction would be built and "
                    + "size-checked, and NOT submitted");
        }
        return new ActionNow(verb, "live: this loan would be liquidated on the next scan", true);
    }
}
