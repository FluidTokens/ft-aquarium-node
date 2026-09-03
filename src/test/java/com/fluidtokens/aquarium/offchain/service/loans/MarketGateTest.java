package com.fluidtokens.aquarium.offchain.service.loans;

import com.fluidtokens.aquarium.offchain.model.AssetType;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The anticipatable-principal rule, exactly as Giovanni specified it 2026-09-03:
 * <i>"min(balance, market cap) and only enabled if market is enabled."</i>
 *
 * <p>⚠ Note what the boundary cases are really testing: because the protocol fixes the amount to be
 * fronted, {@code min()} <b>gates</b> rather than clamps. A cap below the requirement does not buy a
 * smaller liquidation — it buys a refusal.
 */
class MarketGateTest {

    private static final AssetType ADA = AssetType.ada();
    private static final AssetType TOKEN =
            new AssetType("0b77d150c275bd0a600633e4be7d09f83c4b9f00981e22ac9c9d3f62", "0014df1074464c4454");

    private static BigInteger n(long v) {
        return BigInteger.valueOf(v);
    }

    /** ⛔ THE DEFAULT. Nothing configured ⇒ every market disabled ⇒ zero anticipatable. */
    @Test
    void anUnlistedMarketIsDisabledAndAnticipatesNothing() {
        for (String configured : new String[] {null, "", "   "}) {
            MarketGate.Decision d = new MarketGate(configured).decide(ADA, n(1_000_000));
            assertFalse(d.allowed(), "configured=" + configured);
            assertEquals(MarketGate.Refusal.MARKET_DISABLED, d.refusal());
            assertEquals(BigInteger.ZERO, d.anticipatable(),
                    "a disabled market must anticipate ZERO, not 'whatever was asked'");
        }
    }

    /** A listed market that does not cover this candidate is still a refusal, not a partial. */
    @Test
    void aMarketListedButTooSmallRefusesRatherThanReducing() {
        MarketGate.Decision d = new MarketGate("lovelace:500000000").decide(ADA, n(500_000_001));

        assertFalse(d.allowed());
        assertEquals(MarketGate.Refusal.ABOVE_MARKET_CAP, d.refusal());
        assertEquals(n(500_000_000), d.anticipatable(), "min(required, cap) is the cap here");
        assertEquals(n(500_000_001), d.required(),
                "and the REQUIRED figure is still reported, or the operator cannot see by how much");
    }

    /** ⛔ THE BOUNDARY, both directions — one lovelace either side of the cap. */
    @Test
    void theBoundaryIsInclusiveOnTheCap() {
        MarketGate gate = new MarketGate("lovelace:500000000");

        MarketGate.Decision exactly = gate.decide(ADA, n(500_000_000));
        assertTrue(exactly.allowed(), "required == cap must be ALLOWED; min() returns the requirement");
        assertEquals(n(500_000_000), exactly.anticipatable());

        assertTrue(gate.decide(ADA, n(499_999_999)).allowed(), "one under the cap");
        assertFalse(gate.decide(ADA, n(500_000_001)).allowed(), "one over the cap");
    }

    /** An explicit zero is a documented disable: min(required, 0) == 0. */
    @Test
    void anExplicitZeroCapIsADocumentedDisable() {
        MarketGate.Decision d = new MarketGate("lovelace:0").decide(ADA, n(1));
        assertFalse(d.allowed());
        assertEquals(BigInteger.ZERO, d.anticipatable());
    }

    /** Markets are independent: enabling one must not enable another. */
    @Test
    void enablingOneMarketDoesNotEnableAnother() {
        MarketGate gate = new MarketGate("lovelace:500000000");

        assertTrue(gate.decide(ADA, n(1_000)).allowed());
        assertFalse(gate.decide(TOKEN, n(1_000)).allowed(),
                "a token market nobody listed must stay disabled");
        assertEquals(MarketGate.Refusal.MARKET_DISABLED, gate.decide(TOKEN, n(1_000)).refusal());
    }

    /** A token market keys on its full unit, so two assets of one policy are separate markets. */
    @Test
    void aTokenMarketKeysOnTheFullUnit() {
        MarketGate gate = new MarketGate(TOKEN.toUnit() + ":900");

        assertTrue(gate.decide(TOKEN, n(900)).allowed());
        assertFalse(gate.decide(TOKEN, n(901)).allowed());
        assertFalse(gate.decide(ADA, n(1)).allowed(), "ada was never listed");
    }

    /**
     * ⚠ A malformed entry must leave its market DISABLED — never widen anything. A parser that
     * shrugged and continued could turn a typo into an uncapped market.
     */
    @Test
    void malformedEntriesLeaveTheirMarketDisabled() {
        for (String bad : new String[] {"lovelace", "lovelace:", ":500", "lovelace:abc", "lovelace:-1"}) {
            MarketGate.Decision d = new MarketGate(bad).decide(ADA, n(1));
            assertFalse(d.allowed(), "entry '" + bad + "' must not enable anything");
            assertEquals(MarketGate.Refusal.MARKET_DISABLED, d.refusal());
        }
    }

    /** A good entry beside a bad one still works — one typo must not disable the whole config. */
    @Test
    void aBadEntryDoesNotTakeTheGoodOnesWithIt() {
        MarketGate gate = new MarketGate("lovelace:500000000, nonsense, " + TOKEN.toUnit() + ":900");

        assertTrue(gate.decide(ADA, n(1_000)).allowed());
        assertTrue(gate.decide(TOKEN, n(900)).allowed());
        assertEquals(2, gate.caps().size());
    }
}
