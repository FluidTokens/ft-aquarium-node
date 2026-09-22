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
 * ⚠ <b>The mark answers a FORWARD question: if this loan crossed the threshold in the next hour,
 * could the bot handle it?</b> — deliberately not "is it liquidatable today".
 *
 * <p>Most loans are healthy. A mark on every healthy loan would say nothing, and a mark that only
 * appeared once a loan was ALREADY liquidatable would arrive too late to move capital — which is the
 * entire reason capital-in-advance loans are listed before they go sour.
 */
class ProcessingBlockerTest {

    private static final ProcessingBlocker.PoolUsabilityView GOOD_POOL =
            new ProcessingBlocker.PoolUsabilityView(true, null);
    private static final ProcessingBlocker.PoolUsabilityView THIN_POOL =
            new ProcessingBlocker.PoolUsabilityView(false, "the pool would return less than the debt");

    private static Market market(Mode mode, BigInteger cap) {
        Market m = new Market();
        m.setMode(mode);
        m.setCap(cap);
        return m;
    }

    /**
     * ⛔ <b>THE ONE THAT WOULD TEACH AN OPERATOR TO IGNORE THE COLUMN.</b> A CONVERT loan is paid for
     * by its own collateral — the operator fronts nothing. Marking it because the wallet holds none
     * of that collateral would be wrong on every convert row, and a column that is wrong routinely
     * is a column nobody reads.
     */
    @Test
    void aConvertLoanIsNotMarkedJustBecauseTheWalletIsEmpty() {
        ProcessingBlocker blocker = ProcessingBlocker.of(Mode.LIVE, Action.CONVERT, market(null, null),
                true, true, GOOD_POOL, null, BigInteger.ZERO, true);

        assertFalse(blocker.blocked(),
                "convert needs no capital from the operator: " + blocker.label());
    }

    /** But a convert with nowhere to sell IS blocked, and says so. */
    @Test
    void aConvertLoanWithNoUsablePoolIsMarked() {
        ProcessingBlocker blocker = ProcessingBlocker.of(Mode.LIVE, Action.CONVERT, market(null, null),
                true, true, THIN_POOL, null, BigInteger.ZERO, true);

        assertTrue(blocker.blocked());
        assertEquals("no pool", blocker.label());
        assertTrue(blocker.detail().contains("less than the debt"),
                "the pool's own reason must survive rather than being replaced: " + blocker.detail());
    }

    /** An ANTICIPATE loan the wallet cannot cover is the case the whole mark exists for. */
    @Test
    void anAnticipateLoanBeyondTheWalletIsMarkedWithTheReasonToActOnEarly() {
        ProcessingBlocker blocker = ProcessingBlocker.of(Mode.LIVE, Action.ANTICIPATE,
                market(null, BigInteger.valueOf(10_000L)), true, true, GOOD_POOL,
                BigInteger.valueOf(5_000L), BigInteger.valueOf(900L), true);

        assertEquals("funds", blocker.label());
        assertTrue(blocker.detail().contains("before this one goes sour"),
                "the point is to act EARLY, and the reason should say so: " + blocker.detail());
    }

    /** The same loan, affordable, is clean — no badge at all. */
    @Test
    void anAffordableAnticipateLoanCarriesNothing() {
        ProcessingBlocker blocker = ProcessingBlocker.of(Mode.LIVE, Action.ANTICIPATE,
                market(null, BigInteger.valueOf(10_000L)), true, true, GOOD_POOL,
                BigInteger.valueOf(5_000L), BigInteger.valueOf(9_000L), true);

        assertFalse(blocker.blocked());
        assertEquals(null, blocker.label());
    }

    /** ⛔ The cap is checked before the balance: it is the one the operator can fix without money. */
    @Test
    void aCapBelowTheAdvanceIsReportedAsTheCapRatherThanAsFunds() {
        ProcessingBlocker blocker = ProcessingBlocker.of(Mode.LIVE, Action.ANTICIPATE,
                market(null, BigInteger.valueOf(100L)), true, true, GOOD_POOL,
                BigInteger.valueOf(5_000L), BigInteger.valueOf(9_000L), true);

        assertEquals("cap", blocker.label(),
                "a rich wallet behind a small cap is a CONFIG problem, and saying 'funds' would send "
                        + "the operator to the wrong place");
    }

    /**
     * ⛔ AN UNKNOWN BALANCE IS NOT A ZERO ONE. Reporting "funds" for a wallet we failed to read tells
     * an operator they are short when they may not be, and sends them to move money they already have.
     */
    @Test
    void anUnreadableWalletIsUnknownRatherThanShort() {
        ProcessingBlocker blocker = ProcessingBlocker.of(Mode.LIVE, Action.ANTICIPATE,
                market(null, BigInteger.valueOf(10_000L)), true, true, GOOD_POOL,
                BigInteger.valueOf(5_000L), BigInteger.ZERO, false);

        assertEquals("unknown", blocker.label());
        assertTrue(blocker.detail().contains("not the same as being short"), blocker.detail());
    }

    /** A bond that forbids conversion is PLAIN LIQUIDATE: no capital, no pool, nothing to block. */
    @Test
    void aPlainLiquidateLoanIsCleanBecauseItNeedsNeitherCapitalNorAPool() {
        ProcessingBlocker blocker = ProcessingBlocker.of(Mode.LIVE, Action.ANTICIPATE,
                market(null, BigInteger.ONE), true, false, THIN_POOL, BigInteger.valueOf(9_999L),
                BigInteger.ZERO, true);

        assertFalse(blocker.blocked(),
                "plain liquidate takes its fee in collateral and fronts nothing: " + blocker.label());
    }

    /** Arming beats everything: a disabled node cannot process any of them, whatever the wallet says. */
    @Test
    void aDisabledNodeIsTheFirstThingReported() {
        ProcessingBlocker blocker = ProcessingBlocker.of(Mode.DISABLED, Action.CONVERT,
                market(null, null), true, true, GOOD_POOL, null, BigInteger.ZERO, true);

        assertEquals("bot off", blocker.label());
    }

    @Test
    void convertDisabledGloballyIsDistinctFromHavingNoPool() {
        ProcessingBlocker blocker = ProcessingBlocker.of(Mode.LIVE, Action.CONVERT, market(null, null),
                false, true, GOOD_POOL, null, BigInteger.ZERO, true);

        assertEquals("convert off", blocker.label(),
                "a setting and a market condition send an operator to different places");
    }
}
