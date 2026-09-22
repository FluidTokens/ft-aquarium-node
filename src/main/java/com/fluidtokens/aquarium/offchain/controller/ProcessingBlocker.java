package com.fluidtokens.aquarium.offchain.controller;

import java.math.BigInteger;

import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration.Action;
import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration.Market;
import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration.Mode;

/**
 * ⚠ <b>Why the bot could NOT act on this loan — if it had to, right now.</b>
 *
 * <h2>⛔ The question is deliberately NOT "is this loan liquidatable today"</h2>
 * Most loans are healthy, and a page that marked every healthy loan would say nothing: a warning on
 * every row is a warning on none. This asks the forward question instead — <b>if this loan crossed
 * the threshold in the next hour, could the bot handle it?</b>
 *
 * <p>That is the warning worth having EARLY, and it is the whole reason loans are listed before they
 * go sour: where the route is capital in advance, the principal has to be found <em>before</em> the
 * loan needs it. A "you cannot afford this one" that arrives at the moment of liquidation arrives
 * too late to act on.
 *
 * <h2>⚠ Marking the exceptions, not the passes</h2>
 * A clean row carries nothing. Only a row the bot could not process carries a mark, so the eye goes
 * to the few that need attention rather than scanning a column of green.
 *
 * <h2>⛔ "Processable" is not one test — it depends on the route</h2>
 * <ul>
 *   <li><b>ANTICIPATE</b> needs the PRINCIPAL: {@code min(balance, cap) >= advance}. This is
 *       {@code MarketGate.decide}'s arithmetic and is not restated here.</li>
 *   <li><b>CONVERT</b> needs NO principal — the collateral pays the lender. It needs a pool that can
 *       fill, and ada for the fee and the Minswap order.</li>
 *   <li><b>PLAIN</b> needs neither; the fee is taken in collateral.</li>
 * </ul>
 * A CONVERT loan on a wallet holding none of its collateral is <b>fine</b>, and a badge that did not
 * know this would mark it wrongly and teach an operator to ignore the column.
 */
public record ProcessingBlocker(String label, String detail) {

    /** Nothing in the way — rendered as nothing at all. */
    public static final ProcessingBlocker NONE = new ProcessingBlocker(null, null);

    public boolean blocked() {
        return label != null;
    }

    private static ProcessingBlocker of(String label, String detail) {
        return new ProcessingBlocker(label, detail);
    }

    /**
     * @param effectiveMode the market's mode AFTER the node's ceiling — a market asking for LIVE on a
     *                      shadow node is not live, and reporting its own mode would say it was
     * @param advance       what an ANTICIPATE would front, null when it could not be priced
     * @param balance       the operator's holding of the PRINCIPAL asset, already zero when unknown
     */
    public static ProcessingBlocker of(Mode effectiveMode, Action action, Market market,
                                       boolean convertEnabled, boolean bondAllowsConversion,
                                       PoolUsabilityView pool, BigInteger advance,
                                       BigInteger balance, boolean balanceKnown) {
        if (effectiveMode == Mode.DISABLED) {
            return of("bot off", market != null && market.getMode() == Mode.DISABLED
                    ? "this market is set to DISABLED"
                    : "liquidation is disabled on this node");
        }

        // ⛔ A bond that forbids conversion is PLAIN LIQUIDATE, which needs no capital and no pool.
        // Nothing can block it that is not already covered above, so it is clean by construction.
        if (!bondAllowsConversion) {
            return NONE;
        }

        Action effectiveAction = action == null ? Action.CONVERT : action;
        if (effectiveAction == Action.CONVERT) {
            if (!convertEnabled) {
                return of("convert off", "this market routes to CONVERT and convert is disabled "
                        + "node-wide, so no DEX order would be created");
            }
            if (pool == null || !pool.usable()) {
                return of("no pool", pool == null || pool.reason() == null
                        ? "no Minswap pool can clear this loan's debt"
                        : pool.reason());
            }
            return NONE;
        }

        // ---- ANTICIPATE: the only route that needs the operator's own capital ---------------------
        if (advance == null) {
            return of("unknown", "this loan's advance could not be priced, so whether it is "
                    + "affordable is not known either");
        }
        BigInteger cap = market == null ? null : market.getCap();
        if (cap != null && cap.compareTo(advance) < 0) {
            return of("cap", "this market's cap is below what this loan would need fronted");
        }
        if (!balanceKnown) {
            return of("unknown", "the wallet balance could not be read, so affordability is not "
                    + "known — this is not the same as being short");
        }
        // ⚠ min(balance, cap) is MarketGate.decide's rule; the cap half is checked above, so what is
        // left here is the balance half. Stated once, in the same shape, so the two cannot disagree.
        if (balance.compareTo(advance) < 0) {
            return of("funds", "the wallet holds less of this loan's principal than the advance "
                    + "would need — move capital before this one goes sour, not after");
        }
        return NONE;
    }

    /** The slice of {@code PoolUsability} this needs, so the controller can pass it without coupling. */
    public record PoolUsabilityView(boolean usable, String reason) { }
}
