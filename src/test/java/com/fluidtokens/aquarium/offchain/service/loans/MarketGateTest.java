package com.fluidtokens.aquarium.offchain.service.loans;

import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration.Action;
import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration.Market;
import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration.Mode;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-market policy: the execution state, the strategy, and the anticipatable-principal rule.
 *
 * <p>⚠ Note what the cap's boundary cases are really testing: because the protocol fixes the amount to
 * be fronted, {@code min()} <b>gates</b> rather than clamps. A cap below the requirement does not buy a
 * smaller liquidation — it buys a refusal.
 */
class MarketGateTest {

    private static final AssetType ADA = AssetType.ada();
    private static final AssetType TOKEN =
            new AssetType("0b77d150c275bd0a600633e4be7d09f83c4b9f00981e22ac9c9d3f62", "0014df1074464c4454");

    private static BigInteger n(long v) {
        return BigInteger.valueOf(v);
    }

    /**
     * An "unlimited" wallet balance — every test above the balance-check tests below exists to prove
     * something about the CAP, not the balance, so it is given a balance that can never be the
     * limiting term. {@code min(AMPLE, cap) == cap} for every cap these tests use (max 500,000,000),
     * which is exactly what makes {@link MarketGate.Refusal#ABOVE_MARKET_CAP} (not
     * {@link MarketGate.Refusal#INSUFFICIENT_BALANCE}) the refusal those tests still see.
     */
    private static final BigInteger AMPLE = BigInteger.valueOf(1_000_000_000_000L);

    private static Market market(String unit, Mode mode, Action action, Long cap) {
        Market m = new Market();
        m.setUnit(unit);
        m.setMode(mode);
        m.setAction(action);
        m.setCap(cap == null ? null : BigInteger.valueOf(cap));
        return m;
    }

    /** A node at the given global mode with the given market list. */
    private static AppConfig.LiquidationConfiguration node(Mode global, Market... markets) {
        var cfg = new AppConfig.LiquidationConfiguration();
        ReflectionTestUtils.setField(cfg, "mode", global);
        cfg.setMarkets(List.of(markets));
        return cfg;
    }

    private static MarketGate gate(Mode global, Market... markets) {
        return new MarketGate(node(global, markets));
    }

    // ---- the unlisted market ---------------------------------------------------------------------

    /**
     * ⛔ THE DEFAULT, and it is the opposite of the previous design's. An unlisted market is
     * {@code CONVERT} at the node's own mode — so pay-in-advance is refused, but not because the market
     * is off: because convert is the chosen mechanism there.
     */
    @Test
    void anUnlistedMarketConvertsAtTheNodeModeAndSoRefusesPayInAdvance() {
        MarketGate g = gate(Mode.LIVE);

        assertEquals(Mode.LIVE, g.effectiveMode(ADA), "an unlisted market inherits the node's posture");
        assertEquals(Action.CONVERT, g.actionFor(ADA));

        MarketGate.Decision d = g.decide(ADA, n(1_000_000), AMPLE);
        assertFalse(d.allowed());
        assertEquals(MarketGate.Refusal.MARKET_ACTION_IS_CONVERT, d.refusal());
    }

    /**
     * ⚑ And the reason that default is safe to ship: putting the NODE in shadow shadows every market
     * with no list at all. If an unlisted market could not shadow, the operator would have to enumerate
     * every market before rehearsing — and shadow is convert's only mainnet rehearsal.
     */
    @Test
    void aNodeInShadowShadowsEveryUnlistedMarket() {
        assertEquals(Mode.SHADOW, gate(Mode.SHADOW).effectiveMode(ADA));
        assertEquals(Mode.SHADOW, gate(Mode.SHADOW).effectiveMode(TOKEN));
        assertEquals(Mode.DISABLED, gate(Mode.DISABLED).effectiveMode(ADA));
    }

    // ---- the ceiling ------------------------------------------------------------------------------

    /**
     * ⛔ THE LOAD-BEARING SAFETY RULE: the node mode is a CEILING, not a default. A market may be more
     * restrictive than the node and never less — otherwise a per-market {@code LIVE} would let a node
     * whose posture is {@code shadow} submit, and the node-level dial would be a lie.
     */
    @Test
    void aMarketCanBeMoreRestrictiveThanTheNodeButNeverLess() {
        // Market wants LIVE, node says SHADOW -> clamped DOWN to SHADOW.
        assertEquals(Mode.SHADOW,
                gate(Mode.SHADOW, market("lovelace", Mode.LIVE, Action.CONVERT, null)).effectiveMode(ADA));
        // Market wants LIVE, node says DISABLED -> clamped all the way DOWN.
        assertEquals(Mode.DISABLED,
                gate(Mode.DISABLED, market("lovelace", Mode.LIVE, Action.CONVERT, null)).effectiveMode(ADA));
        // Market wants SHADOW on a LIVE node -> honoured, because it is MORE restrictive.
        assertEquals(Mode.SHADOW,
                gate(Mode.LIVE, market("lovelace", Mode.SHADOW, Action.CONVERT, null)).effectiveMode(ADA));
        // Market wants DISABLED on a LIVE node -> honoured.
        assertEquals(Mode.DISABLED,
                gate(Mode.LIVE, market("lovelace", Mode.DISABLED, Action.CONVERT, null)).effectiveMode(ADA));
        // No market mode -> inherit.
        assertEquals(Mode.LIVE,
                gate(Mode.LIVE, market("lovelace", null, Action.CONVERT, null)).effectiveMode(ADA));
    }

    @Test
    void aDisabledMarketRefusesBeforeAnythingElse() {
        MarketGate.Decision d = gate(Mode.LIVE, market("lovelace", Mode.DISABLED, Action.ANTICIPATE, 1_000L))
                .decide(ADA, n(1), AMPLE);

        assertFalse(d.allowed());
        assertEquals(MarketGate.Refusal.MARKET_DISABLED, d.refusal());
        assertEquals(BigInteger.ZERO, d.anticipatable());
    }

    /**
     * ⛔ <b>A SHADOW market must BUILD — on either scope.</b> This gate refuses nothing for shadow; the
     * executor's {@code S4 MARKET_NOT_LIVE} veto withholds the submission after the transaction exists.
     * That is the whole point of the mode: <b>shadow shows you the transactions that would have gone</b>,
     * so a gate that refused before the build would turn the rehearsal back into a refusal.
     *
     * <p>⚑ The stage before this one DID refuse here, deliberately, because no veto existed yet — and
     * the first version of that refusal fired on the effective mode, which would have deleted node-wide
     * shadow mode as well. A fail-closed guard that destroys the feature it guards is not fail-closed.
     */
    @Test
    void aShadowMarketBuildsAndIsHeldByTheExecutorVetoInstead() {
        assertTrue(gate(Mode.LIVE, market("lovelace", Mode.SHADOW, Action.ANTICIPATE, 1_000L))
                        .decide(ADA, n(1), AMPLE).allowed(),
                "a market-level shadow on a live node must reach the builder");
        assertTrue(gate(Mode.SHADOW, market("lovelace", null, Action.ANTICIPATE, 1_000L))
                        .decide(ADA, n(1), AMPLE).allowed(),
                "and so must a node-wide shadow, which always could");

        assertEquals(Mode.SHADOW, gate(Mode.LIVE, market("lovelace", Mode.SHADOW, Action.CONVERT, null))
                .effectiveMode(ADA), "the mode is still reported, for the executor to veto on");
        assertEquals(Mode.SHADOW, gate(Mode.SHADOW).effectiveMode(ADA));
    }

    // ---- the action -------------------------------------------------------------------------------

    /** Pay-in-advance is reachable ONLY through an explicit {@code action: ANTICIPATE}. */
    @Test
    void payInAdvanceNeedsTheMarketToSayAnticipateExplicitly() {
        assertEquals(MarketGate.Refusal.MARKET_ACTION_IS_CONVERT,
                gate(Mode.LIVE, market("lovelace", Mode.LIVE, Action.CONVERT, 1_000L))
                        .decide(ADA, n(1), AMPLE).refusal(),
                "a cap alone must NOT select anticipate — that is the positional inference the object "
                        + "shape exists to kill");

        assertTrue(gate(Mode.LIVE, market("lovelace", Mode.LIVE, Action.ANTICIPATE, 1_000L))
                .decide(ADA, n(1), AMPLE).allowed());
    }

    // ---- the cap ----------------------------------------------------------------------------------

    @Test
    void aCapBelowTheRequirementRefusesRatherThanReducing() {
        MarketGate.Decision d = gate(Mode.LIVE, market("lovelace", Mode.LIVE, Action.ANTICIPATE, 500_000_000L))
                .decide(ADA, n(500_000_001), AMPLE);

        assertFalse(d.allowed());
        assertEquals(MarketGate.Refusal.ABOVE_MARKET_CAP, d.refusal());
        assertEquals(n(500_000_000), d.anticipatable(), "min(required, cap) is the cap here");
        assertEquals(n(500_000_001), d.required());
    }

    /**
     * ⛔ THE BOUNDARY, both directions.
     *
     * <p><b>Inclusive, and that is the validator's answer rather than a preference.</b> Giovanni's
     * wording was <i>"the cap is higher than the principal repayment due"</i>, which reads as a strict
     * {@code >}. Read at the deployed sha {@code e0b818e},
     * {@code lm_liquidate_and_pay_in_advance_action.ak}'s {@code validate_repayment_output} requires
     * <pre>quantity_of(repaymentOutput.value, …) >= repaymentAmount</pre>
     * so a deposit exactly equal to the requirement is <b>valid on chain</b>. A strict {@code >} here
     * would refuse a candidate sitting exactly on the operator's stated bound — silently raising every
     * cap by one lovelace, the same defect a strict profit floor has.
     *
     * <p>⚠ And the figure the cap is compared against is
     * {@code convertedLoanCollateralToPrincipalAmount}, <b>not {@code remainingDebt}</b> — "the
     * principal repayment due" is loose for the same reason.
     */
    @Test
    void theBoundaryIsInclusiveOnTheCap() {
        MarketGate g = gate(Mode.LIVE, market("lovelace", Mode.LIVE, Action.ANTICIPATE, 500_000_000L));

        MarketGate.Decision exactly = g.decide(ADA, n(500_000_000), AMPLE);
        assertTrue(exactly.allowed(), "required == cap must be ALLOWED; min() returns the requirement");
        assertEquals(n(500_000_000), exactly.anticipatable());

        assertTrue(g.decide(ADA, n(499_999_999), AMPLE).allowed(), "one under the cap");
        assertFalse(g.decide(ADA, n(500_000_001), AMPLE).allowed(), "one over the cap");
    }

    /** An explicit zero cap is a documented disable: min(required, 0) == 0. */
    @Test
    void anExplicitZeroCapIsADocumentedDisable() {
        MarketGate.Decision d = gate(Mode.LIVE, market("lovelace", Mode.LIVE, Action.ANTICIPATE, 0L))
                .decide(ADA, n(1), AMPLE);

        assertFalse(d.allowed());
        assertEquals(BigInteger.ZERO, d.anticipatable());
        assertEquals(MarketGate.Refusal.ABOVE_MARKET_CAP, d.refusal());
    }

    /** Markets are independent, and a token market keys on the full unit. */
    @Test
    void marketsAreIndependentAndKeyOnTheFullUnit() {
        MarketGate g = gate(Mode.LIVE,
                market("lovelace", Mode.LIVE, Action.ANTICIPATE, 1_000L),
                market(TOKEN.toUnit(), Mode.LIVE, Action.ANTICIPATE, 900L));

        assertTrue(g.decide(ADA, n(1_000), AMPLE).allowed());
        assertFalse(g.decide(ADA, n(1_001), AMPLE).allowed());
        assertTrue(g.decide(TOKEN, n(900), AMPLE).allowed());
        assertFalse(g.decide(TOKEN, n(901), AMPLE).allowed(), "each market carries its own cap");
    }

    // ---- the balance (Part 3, token-principals slice) ----------------------------------------------

    /**
     * ⛔ THE BUG THIS PART EXISTS TO FIX, pinned as a positive control before the fix is exercised
     * below: a market with a generous cap and a bot holding ZERO of the principal must be REFUSED,
     * naming the shortfall — "holds X, needs Y" — not silently allowed because {@code required} was
     * being compared against the cap alone.
     */
    @Test
    void aZeroBalanceIsRefusedByNameEvenUnderAGenerousCap() {
        MarketGate.Decision d = gate(Mode.LIVE, market(TOKEN.toUnit(), Mode.LIVE, Action.ANTICIPATE, 1_500_000_000L))
                .decide(TOKEN, n(1_033_850_765), BigInteger.ZERO);

        assertFalse(d.allowed(), "zero balance must never be allowed, whatever the cap says");
        assertEquals(MarketGate.Refusal.INSUFFICIENT_BALANCE, d.refusal());
        assertEquals(BigInteger.ZERO, d.anticipatable(), "min(balance, cap) with balance 0 is 0");
        assertTrue(d.detail().contains("holds 0") && d.detail().contains("needs 1033850765"),
                "the refusal must name both the balance and the requirement: " + d.detail());
    }

    /** A balance strictly between zero and the requirement is refused the same way, still by name. */
    @Test
    void aPartialBalanceIsRefusedByName() {
        MarketGate.Decision d = gate(Mode.LIVE, market(TOKEN.toUnit(), Mode.LIVE, Action.ANTICIPATE, 1_500_000_000L))
                .decide(TOKEN, n(1_033_850_765), n(500_000_000));

        assertFalse(d.allowed());
        assertEquals(MarketGate.Refusal.INSUFFICIENT_BALANCE, d.refusal());
        assertEquals(n(500_000_000), d.anticipatable());
    }

    /** A balance that covers the requirement (and sits under the cap) is allowed. */
    @Test
    void aSufficientBalanceUnderTheCapIsAllowed() {
        MarketGate.Decision d = gate(Mode.LIVE, market(TOKEN.toUnit(), Mode.LIVE, Action.ANTICIPATE, 1_500_000_000L))
                .decide(TOKEN, n(1_033_850_765), n(1_033_850_765));

        assertTrue(d.allowed());
        assertEquals(n(1_033_850_765), d.anticipatable());
    }

    /**
     * ⛔ THE TWO REFUSALS ARE DISTINCT, and this is the boundary between them: a balance ABOVE the cap
     * but a requirement above BOTH must still say {@code ABOVE_MARKET_CAP} — the operator's remedy is
     * "raise the cap", not "fund the wallet", and a bot already holding more than the cap allows is
     * proof the wallet is not the problem.
     */
    @Test
    void aBalanceAboveTheCapStillRefusesAsAboveMarketCapNotInsufficientBalance() {
        MarketGate.Decision d = gate(Mode.LIVE, market(TOKEN.toUnit(), Mode.LIVE, Action.ANTICIPATE, 500_000_000L))
                .decide(TOKEN, n(600_000_000), n(2_000_000_000L));

        assertFalse(d.allowed());
        assertEquals(MarketGate.Refusal.ABOVE_MARKET_CAP, d.refusal(),
                "the wallet holds plenty — the cap, not the balance, is what refuses this candidate");
        assertEquals(n(500_000_000), d.anticipatable(), "min(balance, cap) with an ample balance is the cap");
    }

    /**
     * F6 (round 2) — THE ORDERING THAT ACTUALLY DISTINGUISHES THE TWO REFUSALS: {@code balance < cap
     * < required}. The sibling test above ({@code aBalanceAboveTheCapStillRefusesAsAboveMarketCap...})
     * has {@code balance >= cap}, so {@code balance.compareTo(cap) < 0} was already false there under
     * the PRE-FIX code too — it could not have caught a mutant that checked the balance-vs-cap
     * ordering instead of the cap-vs-required one. Here the balance is BELOW the cap (100M < 500M)
     * AND the cap is itself below the requirement (500M < 600M): the cap is the binding constraint
     * regardless of the balance, so the refusal must be {@code ABOVE_MARKET_CAP}, never {@code
     * INSUFFICIENT_BALANCE} — "fund the wallet" would be true but useless advice when the cap itself
     * could never have covered the requirement.
     */
    @Test
    void aCapBelowTheRequirementRefusesAsAboveMarketCapEvenWhenTheBalanceIsAlsoBelowTheCap() {
        MarketGate.Decision d = gate(Mode.LIVE, market(TOKEN.toUnit(), Mode.LIVE, Action.ANTICIPATE, 500_000_000L))
                .decide(TOKEN, n(600_000_000), n(100_000_000));

        assertFalse(d.allowed());
        assertEquals(MarketGate.Refusal.ABOVE_MARKET_CAP, d.refusal(),
                "the cap is below the requirement, so it is the binding constraint — the balance being "
                        + "ALSO short must not relabel this as INSUFFICIENT_BALANCE");
        assertEquals(n(100_000_000), d.anticipatable(), "min(balance, cap) with balance the smaller of the two");
    }

    // ---- startup validation ------------------------------------------------------------------------

    /**
     * ⛔ A broken entry ABORTS STARTUP, which the delimited-string form could not do. With named fields
     * every failure is unambiguous, so quietly disabling the market would hide an operator's error
     * rather than protect them from it.
     */
    @Test
    void aMalformedMarketAbortsStartupRatherThanResolvingToSomethingSafeLooking() {
        assertThrows(IllegalStateException.class,
                () -> node(Mode.LIVE, market(null, Mode.LIVE, Action.CONVERT, null)).validateMarkets(),
                "no unit");
        assertThrows(IllegalStateException.class,
                () -> node(Mode.LIVE, market("not-a-unit", Mode.LIVE, Action.CONVERT, null)).validateMarkets(),
                "a unit that is neither lovelace nor a hex unit");
        assertThrows(IllegalStateException.class,
                () -> node(Mode.LIVE, market("lovelace", Mode.LIVE, Action.ANTICIPATE, null)).validateMarkets(),
                "ANTICIPATE with no cap is unbounded exposure");
        assertThrows(IllegalStateException.class,
                () -> node(Mode.LIVE, market("lovelace", Mode.LIVE, Action.ANTICIPATE, -1L)).validateMarkets(),
                "a negative cap");
        assertThrows(IllegalStateException.class,
                () -> node(Mode.LIVE, market("lovelace", Mode.LIVE, Action.CONVERT, null),
                        market("lovelace", Mode.LIVE, Action.ANTICIPATE, 5L)).validateMarkets(),
                "a duplicate unit: two entries for one market cannot both apply");
    }

    @Test
    void aWellFormedListValidatesAndAnEmptyOneIsLegal() {
        node(Mode.LIVE).validateMarkets();
        node(Mode.LIVE,
                market("lovelace", Mode.SHADOW, Action.CONVERT, null),
                market(TOKEN.toUnit(), Mode.LIVE, Action.ANTICIPATE, 1_000L)).validateMarkets();
    }
}
