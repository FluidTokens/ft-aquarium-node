package com.fluidtokens.aquarium.offchain.controller;

import java.math.BigInteger;

import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration.Action;
import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration.Market;
import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration.Mode;
import com.fluidtokens.aquarium.offchain.service.loans.PoolUsability;

/**
 * ⛔ <b>What the bot would do about THIS loan, right now, on THIS node.</b>
 *
 * <h2>Why it is separate from the route</h2>
 * The route says which transaction a liquidation would build; it is a property of the market and the
 * bond, and it is true whether or not anything is armed. Operators read it as intent, and on a
 * disabled node that reading is wrong — which is the whole reason {@link OperationalStatus} exists.
 * This answers the other question, and it is the one an operator actually acts on.
 *
 * <h2>⚠ The route decides which action question is asked</h2>
 * A plain bond ignores market routing and asks only whether plain liquidation would run. A missing
 * bond has no executor candidate. The node-mode ceiling below applies to every route: the PLAIN
 * answer reads the effective mode, not the market's configured mode. Items 2 and 3 concern the
 * convert route only:
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
 * <h2>⚠ A {@code CONVERT_BEFORE_ANTICIPATE} answer is an ESTIMATE</h2>
 * Such a market converts when it can and pays in advance only when convert is impossible, so its answer
 * follows the pool verdict: {@code USABLE} converts; {@code NO_POOL}, {@code TOO_THIN}, {@code NOT_CONFIGURED}
 * or convert switched off take the {@code ANTICIPATE} answers; {@code WRONG_PAIR} and {@code CANNOT_QUOTE} are
 * refused and never fall back; {@code CHECK_FAILED} and {@code UNKNOWN} are unknown, never ADVANCE. The page
 * tests every indexed pool at render time while the bot resolves one pool when it builds, so the two can
 * disagree for a block — every such detail says so ({@link #ESTIMATE_NOTE}).
 *
 * <p>⚠ <b>Health is checked FIRST and deliberately.</b> Most loans are healthy, and "the bot is off"
 * is not the interesting answer for a loan that would not be touched anyway — it buries the arming
 * problem under rows where it does not matter.
 */
public record ActionNow(String text, String detail, boolean wouldAct) {

    /** Ends every {@code CONVERT_BEFORE_ANTICIPATE} detail: the page and the bot may consult different pools. */
    public static final String ESTIMATE_NOTE = " — an estimate: this page tests every indexed pool now, while the "
            + "bot resolves one pool when it builds";

    private static final String CBA_PREFIX = "this market is action: CONVERT_BEFORE_ANTICIPATE";

    private static ActionNow none(String text, String detail) {
        return new ActionNow(text, detail, false);
    }

    /**
     * ⛔ FAB-115: the live Lending v4 config no longer matches this node, so the bot refuses EVERY
     * lending transaction whatever the loan's health — a row must not say "would act" beside a banner
     * saying nothing will.
     */
    public static ActionNow refusedByLendingConfig(String reason) {
        return none("REFUSED", "LENDING_CONFIG_MISMATCH — " + reason);
    }

    /**
     * FAB-117: a liquidatable loan with ADA collateral whose bond ASKS FOR CONVERSION. Both routes such a
     * bond can take refuse it (convert and pay-in-advance each need a collateral oracle, and ada has
     * none), so whatever the market's action the row must not say ADVANCE or CONVERT.
     *
     * <p>⚠ NOT for a plain bond: the PLAIN route does liquidate ada-collateral loans (the claim's oracle
     * check short-circuits for ada), and a row saying "nothing will happen" there is false.
     */
    public static ActionNow adaCollateralNotLiquidated() {
        return none("NONE", "ada collateral on a convert bond — neither the convert nor the pay-in-advance route "
                + "builds a liquidation for it, whatever the market's action");
    }

    /**
     * @param liquidatable  null when health could not be computed — which is NOT "no"
     * @param effectiveMode the market's mode after the node's ceiling is applied
     * @param poolVerdict   the page's pool verdict for this loan; null is read as {@code UNKNOWN}, never
     *                      as {@code USABLE}
     * @param advance       what an ANTICIPATE would have to front, null when unknown
     */
    public static ActionNow of(Boolean liquidatable, Mode effectiveMode, Action action,
                               Market market, boolean convertEnabled, PoolUsability.Verdict poolVerdict,
                               BigInteger advance) {
        ActionNow head = sharedHead(liquidatable, effectiveMode, market);
        if (head != null) {
            return head;
        }

        PoolUsability.Verdict verdict = poolVerdict == null ? PoolUsability.Verdict.UNKNOWN : poolVerdict;
        Action effectiveAction = action == null ? Action.CONVERT : action;
        // Exhaustive, no default: a new action does not compile until it says what it would do here.
        return switch (effectiveAction) {
            case CONVERT -> convert(effectiveMode, convertEnabled, verdict);
            case ANTICIPATE -> anticipate(effectiveMode, market, advance);
            case CONVERT_BEFORE_ANTICIPATE ->
                    convertBeforeAnticipate(effectiveMode, market, convertEnabled, verdict, advance);
        };
    }

    private static ActionNow convert(Mode effectiveMode, boolean convertEnabled, PoolUsability.Verdict verdict) {
        if (!convertEnabled) {
            return none("NONE — convert off", "this market routes to CONVERT, but convert is "
                    + "globally disabled on this node, so no DEX order would be created");
        }
        if (verdict != PoolUsability.Verdict.USABLE) {
            return none("NONE — pool", "no Minswap pool can clear this loan's debt, so the "
                    + "convert order would be refused before it was built");
        }
        return acting("CONVERT", effectiveMode);
    }

    private static ActionNow anticipate(Mode effectiveMode, Market market, BigInteger advance) {
        if (market != null && market.getCap() != null && advance != null
                && market.getCap().compareTo(advance) < 0) {
            // ⛔ PER-LOAN, not per-market. The market is valid and the cap is deliberate; it simply
            // does not reach THIS loan, and nothing about the market's own display would show that.
            return none("NONE — cap", "this market's cap of " + market.getCap() + " is below the "
                    + advance + " this loan would need fronted");
        }
        return acting("ADVANCE", effectiveMode);
    }

    private static ActionNow acting(String verb, Mode effectiveMode) {
        if (effectiveMode == Mode.SHADOW) {
            return none("WOULD " + verb, "shadow mode: the transaction would be built and "
                    + "size-checked, and NOT submitted");
        }
        return new ActionNow(verb, "live: this loan would be liquidated on the next scan", true);
    }

    /**
     * ⛔ FAB-194-4: the executor's route for a {@code CONVERT_BEFORE_ANTICIPATE} market, read from the page's
     * pool verdict. Only convert switched off or a verdict saying convert is IMPOSSIBLE ({@code NO_POOL},
     * {@code TOO_THIN}, {@code NOT_CONFIGURED}) pays in advance. A refused pool ({@code WRONG_PAIR},
     * {@code CANNOT_QUOTE}) is never a reason to front capital, and a verdict that could not be reached
     * ({@code CHECK_FAILED}, {@code UNKNOWN}) is unknown — never ADVANCE, because "could not ask" is not
     * "no pool".
     */
    private static ActionNow convertBeforeAnticipate(Mode effectiveMode, Market market, boolean convertEnabled,
                                                     PoolUsability.Verdict verdict, BigInteger advance) {
        if (!convertEnabled) {
            return estimate(anticipate(effectiveMode, market, advance), CBA_PREFIX
                    + " and convert is disabled on this node, so it pays in advance: ");
        }
        return switch (verdict) {
            case NO_POOL, TOO_THIN, NOT_CONFIGURED ->
                    estimate(anticipate(effectiveMode, market, advance), CBA_PREFIX
                            + " and convert is impossible for this loan (" + verdict + "), so it pays in "
                            + "advance: ");
            case USABLE -> estimate(acting("CONVERT", effectiveMode), CBA_PREFIX
                    + " and a pool can clear this loan's debt (" + verdict + "), so it converts first: ");
            case WRONG_PAIR, CANNOT_QUOTE -> none("NONE — pool", CBA_PREFIX
                    + " and the convert route refuses this loan's pool (" + verdict + "); that refusal is "
                    + "not a reason to pay in advance, so nothing would be attempted" + ESTIMATE_NOTE);
            case CHECK_FAILED, UNKNOWN -> none("UNKNOWN", CBA_PREFIX
                    + " and whether convert is possible is not known (" + verdict + "), so whether the bot "
                    + "would convert or pay in advance is not known either — this is not the same as 'no'"
                    + ESTIMATE_NOTE);
        };
    }

    private static ActionNow estimate(ActionNow answer, String prefix) {
        return new ActionNow(answer.text(), prefix + answer.detail() + ESTIMATE_NOTE, answer.wouldAct());
    }

    public static ActionNow forRoute(BondRoute route, Boolean liquidatable, Mode effectiveMode,
                                     Action action, Market market, boolean convertEnabled,
                                     PoolUsability.Verdict poolVerdict, BigInteger advance) {
        if (route == BondRoute.CONVERT) {
            return of(liquidatable, effectiveMode, action, market, convertEnabled, poolVerdict, advance);
        }

        ActionNow head = sharedHead(liquidatable, effectiveMode, market);
        if (head != null) {
            return head;
        }
        if (route == BondRoute.PLAIN) {
            return effectiveMode == Mode.SHADOW
                    ? none("WOULD LIQUIDATE", "shadow mode: the plain liquidation would be built and "
                            + "size-checked, and NOT submitted")
                    : new ActionNow("LIQUIDATE", "live: the bond forbids conversion, so the plain route "
                            + "takes its fee in collateral and fronts nothing; market action, convert "
                            + "switch, pool and cap do not apply to this route", true);
        }
        return none("NONE — no bond", BondRoute.NO_BOND_DETAIL);
    }

    private static ActionNow sharedHead(Boolean liquidatable, Mode effectiveMode, Market market) {
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
        return null;
    }
}
