package com.fluidtokens.aquarium.offchain.controller;

import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration.Action;
import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration.Market;
import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration.Mode;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>The only column on the readiness page that describes INTENT.</b>
 *
 * <p>Everything else says what could happen. This says what this node would do on the next scan, and
 * it is therefore the cell an operator trusts most — which makes each of the three ways it can be
 * quietly wrong worth its own test. Each renders a route and a positive margin while the bot does
 * nothing, and each sends an operator somewhere different.
 */
class ActionNowTest {

    private static Market market(Mode mode, BigInteger cap) {
        Market m = new Market();
        m.setMode(mode);
        m.setCap(cap);
        return m;
    }

    /**
     * ⛔ CASE 1 — <b>the node mode is a CEILING.</b> A market asking for LIVE on a shadow node runs
     * as SHADOW, so reading the market's own mode here would promise a submission that cannot happen.
     * The caller passes the EFFECTIVE mode for exactly this reason.
     */
    @Test
    void aMarketAskingForLiveUnderAShadowNodeOnlyEverRehearses() {
        ActionNow shadowed = ActionNow.of(true, Mode.SHADOW, Action.CONVERT,
                market(Mode.LIVE, null), true, true, null);

        assertFalse(shadowed.wouldAct(), "a shadow node must never report that it would submit");
        assertTrue(shadowed.text().startsWith("WOULD "), "and it must say what it WOULD do: " + shadowed);
        assertTrue(shadowed.detail().contains("NOT submitted"), shadowed.detail());
    }

    /**
     * ⛔ CASE 2 — <b>the cap is below what THIS loan needs.</b> The market is configured, valid, and
     * simply cannot act on this one loan. No per-market display reveals that; it is a per-loan fact.
     */
    @Test
    void anAnticipateMarketWhoseCapIsBelowThisLoanCannotAct() {
        ActionNow capped = ActionNow.of(true, Mode.LIVE, Action.ANTICIPATE,
                market(null, BigInteger.valueOf(100L)), true, true, BigInteger.valueOf(500L));

        assertFalse(capped.wouldAct(), "a cap below the advance must not read as actionable");
        assertEquals("NONE — cap", capped.text());
        assertTrue(capped.detail().contains("100") && capped.detail().contains("500"),
                "the operator needs BOTH numbers to fix it: " + capped.detail());
    }

    /** And the same market acts when the cap does reach. */
    @Test
    void theSameMarketActsWhenTheCapCoversTheLoan() {
        ActionNow ok = ActionNow.of(true, Mode.LIVE, Action.ANTICIPATE,
                market(null, BigInteger.valueOf(900L)), true, true, BigInteger.valueOf(500L));

        assertTrue(ok.wouldAct(), "the cap covers it, so this must be actionable: " + ok);
        assertEquals("ADVANCE", ok.text());
    }

    /**
     * ⛔ CASE 3 — <b>convert is globally off.</b> It overrides every market's CONVERT, and the market
     * still reads as CONVERT everywhere else on the page.
     */
    @Test
    void aConvertMarketDoesNothingWhenConvertIsGloballyDisabled() {
        ActionNow off = ActionNow.of(true, Mode.LIVE, Action.CONVERT, market(null, null),
                false, true, null);

        assertFalse(off.wouldAct());
        assertEquals("NONE — convert off", off.text());
    }

    /** A usable pool is still required, even with everything armed. */
    @Test
    void aConvertMarketDoesNothingWhenNoPoolCanFill() {
        ActionNow thin = ActionNow.of(true, Mode.LIVE, Action.CONVERT, market(null, null),
                true, false, null);

        assertFalse(thin.wouldAct());
        assertEquals("NONE — pool", thin.text());
    }

    /**
     * ⚠ <b>Health is checked FIRST, deliberately.</b> Most loans are healthy, and "the bot is off" is
     * not the interesting answer for a loan nothing would touch anyway — it would bury the arming
     * problem under rows where it does not matter.
     */
    @Test
    void aHealthyLoanSaysSoRatherThanBlamingTheConfiguration() {
        ActionNow healthy = ActionNow.of(false, Mode.DISABLED, Action.CONVERT, null, false, false, null);

        assertEquals("NONE", healthy.text());
        assertTrue(healthy.detail().contains("not liquidatable"),
                "a healthy loan must not be reported as a configuration problem: " + healthy.detail());
    }

    /**
     * ⛔ UNKNOWN IS NOT "NO". A loan whose health could not be computed might be liquidatable; saying
     * "NONE" would be a claim this node cannot support.
     */
    @Test
    void anUncomputableLoanIsUnknownRatherThanNothingToDo() {
        ActionNow unknown = ActionNow.of(null, Mode.LIVE, Action.CONVERT, market(null, null),
                true, true, null);

        assertEquals("UNKNOWN", unknown.text());
        assertFalse(unknown.wouldAct());
        assertTrue(unknown.detail().contains("not the same as 'no'"), unknown.detail());
    }

    /** The one case that is genuinely armed — and the only one allowed to say so. */
    @Test
    void aLiveConvertOnAUsablePoolIsTheOnlyThingThatReportsItWouldAct() {
        ActionNow live = ActionNow.of(true, Mode.LIVE, Action.CONVERT, market(null, null),
                true, true, null);

        assertTrue(live.wouldAct());
        assertEquals("CONVERT", live.text());
    }

    /** A market switched off individually names itself, rather than blaming the node. */
    @Test
    void aMarketDisabledOnItsOwnSaysSoRatherThanBlamingTheNode() {
        ActionNow disabled = ActionNow.of(true, Mode.DISABLED, Action.CONVERT,
                market(Mode.DISABLED, null), true, true, null);

        assertEquals("NONE — disabled", disabled.text());
        assertTrue(disabled.detail().contains("market"), disabled.detail());
    }
}
