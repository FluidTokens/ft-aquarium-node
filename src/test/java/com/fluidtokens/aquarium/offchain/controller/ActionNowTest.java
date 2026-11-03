package com.fluidtokens.aquarium.offchain.controller;

import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration.Action;
import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration.Market;
import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration.Mode;
import com.fluidtokens.aquarium.offchain.service.loans.PoolUsability.Verdict;
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
                market(Mode.LIVE, null), true, Verdict.USABLE, null);

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
                market(null, BigInteger.valueOf(100L)), true, Verdict.USABLE, BigInteger.valueOf(500L));

        assertFalse(capped.wouldAct(), "a cap below the advance must not read as actionable");
        assertEquals("NONE — cap", capped.text());
        assertTrue(capped.detail().contains("100") && capped.detail().contains("500"),
                "the operator needs BOTH numbers to fix it: " + capped.detail());
    }

    /** And the same market acts when the cap does reach. */
    @Test
    void theSameMarketActsWhenTheCapCoversTheLoan() {
        ActionNow ok = ActionNow.of(true, Mode.LIVE, Action.ANTICIPATE,
                market(null, BigInteger.valueOf(900L)), true, Verdict.USABLE, BigInteger.valueOf(500L));

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
                false, Verdict.USABLE, null);

        assertFalse(off.wouldAct());
        assertEquals("NONE — convert off", off.text());
    }

    /** A usable pool is still required, even with everything armed. */
    @Test
    void aConvertMarketDoesNothingWhenNoPoolCanFill() {
        ActionNow thin = ActionNow.of(true, Mode.LIVE, Action.CONVERT, market(null, null),
                true, Verdict.NO_POOL, null);

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
        ActionNow healthy = ActionNow.of(false, Mode.DISABLED, Action.CONVERT, null, false, Verdict.NO_POOL, null);

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
                true, Verdict.USABLE, null);

        assertEquals("UNKNOWN", unknown.text());
        assertFalse(unknown.wouldAct());
        assertTrue(unknown.detail().contains("not the same as 'no'"), unknown.detail());
    }

    /** The one case that is genuinely armed — and the only one allowed to say so. */
    @Test
    void aLiveConvertOnAUsablePoolIsTheOnlyThingThatReportsItWouldAct() {
        ActionNow live = ActionNow.of(true, Mode.LIVE, Action.CONVERT, market(null, null),
                true, Verdict.USABLE, null);

        assertTrue(live.wouldAct());
        assertEquals("CONVERT", live.text());
    }

    /** A market switched off individually names itself, rather than blaming the node. */
    @Test
    void aMarketDisabledOnItsOwnSaysSoRatherThanBlamingTheNode() {
        ActionNow disabled = ActionNow.of(true, Mode.DISABLED, Action.CONVERT,
                market(Mode.DISABLED, null), true, Verdict.USABLE, null);

        assertEquals("NONE — disabled", disabled.text());
        assertTrue(disabled.detail().contains("market"), disabled.detail());
    }

    @Test
    void aPlainRouteIgnoresEveryConvertAndAdvanceGate() {
        ActionNow plain = ActionNow.forRoute(BondRoute.PLAIN, true, Mode.LIVE, Action.CONVERT,
                market(null, BigInteger.valueOf(100L)), false, Verdict.NO_POOL, BigInteger.valueOf(500L));

        assertEquals("LIQUIDATE", plain.text());
        assertTrue(plain.wouldAct());
    }

    @Test
    void aPlainRouteInShadowBuildsButDoesNotSubmit() {
        ActionNow plain = ActionNow.forRoute(BondRoute.PLAIN, true, Mode.SHADOW, Action.ANTICIPATE,
                market(null, BigInteger.ONE), false, Verdict.NO_POOL, BigInteger.TEN);

        assertEquals("WOULD LIQUIDATE", plain.text());
        assertFalse(plain.wouldAct());
    }

    @Test
    void aPlainRouteOnADisabledNodeIsDisabled() {
        ActionNow plain = ActionNow.forRoute(BondRoute.PLAIN, true, Mode.DISABLED, Action.CONVERT,
                market(Mode.DISABLED, null), true, Verdict.USABLE, null);

        assertEquals("NONE — disabled", plain.text());
    }

    @Test
    void aMissingBondNeverActsAcrossModesAndMarketActions() {
        Market capped = market(null, BigInteger.valueOf(1_000L));
        for (Mode mode : new Mode[] {Mode.LIVE, Mode.SHADOW}) {
            for (Action action : new Action[] {Action.CONVERT, Action.ANTICIPATE}) {
                ActionNow missing = ActionNow.forRoute(BondRoute.NO_BOND, true, mode, action,
                        capped, true, Verdict.USABLE, null);

                assertFalse(missing.wouldAct(), mode + " / " + action + ": " + missing);
                assertEquals("NONE — no bond", missing.text());
                assertEquals(BondRoute.NO_BOND_DETAIL, missing.detail());
            }
        }
    }

    @Test
    void aNullRouteIsTheSameAsAMissingBond() {
        ActionNow missing = ActionNow.forRoute(null, true, Mode.LIVE, Action.CONVERT,
                market(null, BigInteger.TEN), true, Verdict.USABLE, null);

        assertFalse(missing.wouldAct());
        assertEquals("NONE — no bond", missing.text());
        assertEquals(BondRoute.NO_BOND_DETAIL, missing.detail());
    }

    @Test
    void aConvertRouteIsExactlyTheExistingConvertAnswer() {
        Market capped = market(null, BigInteger.valueOf(100L));
        assertRouteEqualsOf(Mode.LIVE, Action.ANTICIPATE, capped, true, Verdict.USABLE, BigInteger.valueOf(500L));
        assertRouteEqualsOf(Mode.LIVE, Action.CONVERT, market(null, null), true, Verdict.NO_POOL, null);
        assertRouteEqualsOf(Mode.LIVE, Action.CONVERT, market(null, null), false, Verdict.USABLE, null);
        assertRouteEqualsOf(Mode.LIVE, Action.CONVERT, market(null, null), true, Verdict.USABLE, null);
    }

    @Test
    void healthPrecedesThePlainAndMissingBondRoutes() {
        for (BondRoute route : new BondRoute[] {BondRoute.PLAIN, BondRoute.NO_BOND}) {
            ActionNow unknown = ActionNow.forRoute(route, null, Mode.LIVE, Action.CONVERT,
                    market(null, null), true, Verdict.USABLE, null);
            ActionNow healthy = ActionNow.forRoute(route, false, Mode.LIVE, Action.CONVERT,
                    market(null, null), true, Verdict.USABLE, null);

            assertEquals("UNKNOWN", unknown.text(), route.toString());
            assertEquals("NONE", healthy.text(), route.toString());
        }
    }

    // ---- FAB-194-4: CONVERT_BEFORE_ANTICIPATE reads the pool verdict, and says it is an estimate --------

    private static final BigInteger ADVANCE = BigInteger.valueOf(500L);

    /** A cap that covers {@link #ADVANCE}, so only the verdict and the switch decide. */
    private static ActionNow convertBeforeAnticipate(Mode mode, boolean convertEnabled, Verdict verdict) {
        return ActionNow.of(true, mode, Action.CONVERT_BEFORE_ANTICIPATE, market(null, BigInteger.valueOf(900L)),
                convertEnabled, verdict, ADVANCE);
    }

    private static void assertConvertBeforeAnticipateDetail(ActionNow answer, String names) {
        assertTrue(answer.detail().contains("CONVERT_BEFORE_ANTICIPATE"), answer.detail());
        assertTrue(answer.detail().contains(names), names + " in " + answer.detail());
        assertTrue(answer.detail().endsWith(ActionNow.ESTIMATE_NOTE), answer.detail());
    }

    /** ⛔ FAB-194-4 r2: the note every CONVERT_BEFORE_ANTICIPATE detail ends with, pinned verbatim. */
    @Test
    void theEstimateNoteSaysThePageAndTheBotMayConsultDifferentPools() {
        assertEquals(" — an estimate: this page tests every indexed pool now, while the bot resolves one pool "
                + "when it builds", ActionNow.ESTIMATE_NOTE);
    }

    /**
     * ⛔ All eight verdicts, LIVE and SHADOW: impossible-to-convert verdicts pay in advance, USABLE converts,
     * a refused pool never falls back, and a verdict that could not be reached is UNKNOWN.
     */
    @Test
    void convertBeforeAnticipateAnswersEveryVerdictInLiveAndShadow() {
        java.util.Map<Verdict, String> verbs = new java.util.EnumMap<>(Verdict.class);
        verbs.put(Verdict.USABLE, "CONVERT");
        verbs.put(Verdict.NO_POOL, "ADVANCE");
        verbs.put(Verdict.TOO_THIN, "ADVANCE");
        verbs.put(Verdict.NOT_CONFIGURED, "ADVANCE");
        verbs.put(Verdict.WRONG_PAIR, "NONE — pool");
        verbs.put(Verdict.CANNOT_QUOTE, "NONE — pool");
        verbs.put(Verdict.CHECK_FAILED, "UNKNOWN");
        verbs.put(Verdict.UNKNOWN, "UNKNOWN");
        assertEquals(Verdict.values().length, verbs.size(), "every verdict must be pinned");

        for (Verdict verdict : Verdict.values()) {
            String verb = verbs.get(verdict);
            boolean acts = verb.equals("CONVERT") || verb.equals("ADVANCE");

            ActionNow live = convertBeforeAnticipate(Mode.LIVE, true, verdict);
            assertEquals(verb, live.text(), verdict + ": " + live);
            assertEquals(acts, live.wouldAct(), verdict + ": " + live);
            assertConvertBeforeAnticipateDetail(live, verdict.name());

            ActionNow shadow = convertBeforeAnticipate(Mode.SHADOW, true, verdict);
            assertEquals(acts ? "WOULD " + verb : verb, shadow.text(), verdict + ": " + shadow);
            assertFalse(shadow.wouldAct(), verdict + ": " + shadow);
            assertConvertBeforeAnticipateDetail(shadow, verdict.name());

            assertEquals(live, ActionNow.forRoute(BondRoute.CONVERT, true, Mode.LIVE,
                    Action.CONVERT_BEFORE_ANTICIPATE, market(null, BigInteger.valueOf(900L)), true, verdict, ADVANCE),
                    "the CONVERT route is exactly of()'s answer: " + verdict);
        }
    }

    /** Convert switched off pays in advance whatever the verdict — it names the switch, not the pool. */
    @Test
    void convertBeforeAnticipateWithConvertOffPaysInAdvanceWhateverTheVerdict() {
        for (Verdict verdict : Verdict.values()) {
            ActionNow live = convertBeforeAnticipate(Mode.LIVE, false, verdict);
            assertEquals("ADVANCE", live.text(), verdict + ": " + live);
            assertTrue(live.wouldAct(), verdict + ": " + live);
            assertConvertBeforeAnticipateDetail(live, "convert is disabled");

            ActionNow shadow = convertBeforeAnticipate(Mode.SHADOW, false, verdict);
            assertEquals("WOULD ADVANCE", shadow.text(), verdict + ": " + shadow);
            assertFalse(shadow.wouldAct(), verdict + ": " + shadow);
            assertConvertBeforeAnticipateDetail(shadow, "convert is disabled");
        }
    }

    /** The fallback is ANTICIPATE's answer, cap included: a cap below this loan's advance cannot act. */
    @Test
    void convertBeforeAnticipateOnAThinPoolIsCappedLikeAnticipate() {
        for (boolean convertEnabled : new boolean[] {true, false}) {
            ActionNow capped = ActionNow.of(true, Mode.LIVE, Action.CONVERT_BEFORE_ANTICIPATE,
                    market(null, BigInteger.valueOf(100L)), convertEnabled, Verdict.TOO_THIN, ADVANCE);

            assertEquals("NONE — cap", capped.text(), capped.toString());
            assertFalse(capped.wouldAct(), capped.toString());
            assertTrue(capped.detail().contains("100") && capped.detail().contains("500"), capped.detail());
            assertConvertBeforeAnticipateDetail(capped, convertEnabled ? "TOO_THIN" : "convert is disabled");
        }
    }

    /** ⛔ D15: "could not ask" is never ADVANCE — whatever the cap, the advance or the mode. */
    @Test
    void convertBeforeAnticipateNeverAdvancesOnAVerdictItCouldNotReach() {
        for (Verdict verdict : new Verdict[] {Verdict.CHECK_FAILED, Verdict.UNKNOWN, null}) {
            for (Mode mode : new Mode[] {Mode.LIVE, Mode.SHADOW}) {
                for (BigInteger advance : new BigInteger[] {ADVANCE, null}) {
                    for (BigInteger cap : new BigInteger[] {BigInteger.valueOf(100L), BigInteger.valueOf(900L), null}) {
                        ActionNow answer = ActionNow.of(true, mode, Action.CONVERT_BEFORE_ANTICIPATE,
                                market(null, cap), true, verdict, advance);
                        assertFalse(answer.text().contains("ADVANCE"), verdict + "/" + mode + ": " + answer);
                        assertEquals("UNKNOWN", answer.text(), verdict + "/" + mode + ": " + answer);
                        assertFalse(answer.wouldAct(), answer.toString());
                    }
                }
            }
        }
    }

    /**
     * ⛔ P-G1 / P-G2 / P-G3: on a CONVERT market only USABLE is usable. Every other verdict — and a null one,
     * which is UNKNOWN — reads NONE — pool, so a {@code != NO_POOL} or {@code != CHECK_FAILED} reading, or a
     * null read as USABLE, cannot pass.
     */
    @Test
    void aConvertMarketActsOnlyOnAUsableVerdict() {
        for (Verdict verdict : java.util.Arrays.asList(Verdict.NO_POOL, Verdict.CHECK_FAILED, Verdict.NOT_CONFIGURED,
                Verdict.TOO_THIN, Verdict.CANNOT_QUOTE, Verdict.WRONG_PAIR, Verdict.UNKNOWN, null)) {
            ActionNow answer = ActionNow.of(true, Mode.LIVE, Action.CONVERT, market(null, null), true, verdict, null);
            assertEquals("NONE — pool", answer.text(), verdict + ": " + answer);
            assertFalse(answer.wouldAct(), verdict + ": " + answer);
        }
        ActionNow usable = ActionNow.of(true, Mode.LIVE, Action.CONVERT, market(null, null), true, Verdict.USABLE, null);
        assertEquals(new ActionNow("CONVERT", "live: this loan would be liquidated on the next scan", true), usable);
    }

    /** CONVERT and ANTICIPATE answers carry no estimate note: only the verdict-driven action is an estimate. */
    @Test
    void convertAndAnticipateAnswersAreNotLabelledAnEstimate() {
        for (Verdict verdict : Verdict.values()) {
            for (Action action : new Action[] {Action.CONVERT, Action.ANTICIPATE}) {
                ActionNow answer = ActionNow.of(true, Mode.LIVE, action, market(null, BigInteger.valueOf(900L)),
                        true, verdict, ADVANCE);
                assertFalse(answer.detail().contains("an estimate"), action + "/" + verdict + ": " + answer);
            }
        }
    }

    private static void assertRouteEqualsOf(Mode mode, Action action, Market market,
                                            boolean convertEnabled, Verdict poolVerdict,
                                            BigInteger advance) {
        assertEquals(ActionNow.of(true, mode, action, market, convertEnabled, poolVerdict, advance),
                ActionNow.forRoute(BondRoute.CONVERT, true, mode, action, market,
                        convertEnabled, poolVerdict, advance));
    }
}
