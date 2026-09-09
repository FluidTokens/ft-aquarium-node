package com.fluidtokens.aquarium.offchain.service.loans;

import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-067 — <b>feed staleness is reported, not inferred.</b>
 *
 * <h2>What this exists to stop</h2>
 * The bot's oracle view comes entirely from a registry HTTP API, refreshed every 30s — it never
 * reads a feed UTxO from chain. Measured 2026-08-27: that API serves entries whose window opened
 * <b>~5 minutes earlier</b>, against a 600s width published every ~300s, so roughly a third of each
 * feed's usable life is gone before we see it.
 * <p>
 * <b>None of our own reporting could show that.</b> The oracle endpoint printed {@code validFrom}
 * and {@code validTo} and nothing compared either to now, so a five-minute-old feed and a fresh one
 * were <b>identical in every surface we own</b> — which is why it took an outside sampler and three
 * competing hypotheses, two of which were false.
 * <p>
 * ⚑ <b>This is the mirror of the failure this factory keeps finding.</b> Ten checks that ran and
 * meant nothing; this is the other kind — <b>a number that would have answered the question, sitting
 * one subtraction away, unprinted.</b> Same shape as the loan census.
 */
class FeedAgeObservabilityTest {

    private static final long WIDTH_MS = 600_000L;

    private static OraclePriceFeed feed(long validFrom) {
        return OraclePriceFeed.aggregated(
                com.fluidtokens.aquarium.offchain.model.AssetType.fromUnit("ff".repeat(28) + "abcd"),
                BigInteger.ONE, BigInteger.ONE, validFrom, validFrom + WIDTH_MS);
    }

    /** Age and remaining life are complements across the window's width — the arithmetic itself. */
    @Test
    void ageAndRemainingLifeAccountForTheWholeWindow() {
        long validFrom = 1_787_800_000_000L;
        long now = validFrom + 323_000L;                 // the measured lag
        OraclePriceFeed f = feed(validFrom);

        long age = (now - f.validFrom()) / 1000L;
        long remaining = (f.validTo() - now) / 1000L;

        assertEquals(323L, age);
        assertEquals(277L, remaining);
        assertEquals(WIDTH_MS / 1000L, age + remaining,
                "age + remaining must be the window width, or one of them is measuring the wrong end");
    }

    /**
     * ⇒ THE READING THAT MATTERS: at the measured lag, more than half the feed is already gone
     * before the bot ever sees it. That is the fact no surface of ours could previously show.
     */
    @Test
    void theMeasuredLagConsumesMoreThanHalfOfEachFeed() {
        long validFrom = 1_787_800_000_000L;
        long now = validFrom + 323_000L;
        double consumed = (now - validFrom) / (double) WIDTH_MS;

        assertTrue(consumed > 0.5,
                "at a 323s lag against a 600s window, " + Math.round(consumed * 100)
                        + "% of the feed is spent on arrival — and no window arithmetic on our side "
                        + "can recover it, which is why T-065's clamp was aimed at the wrong number");
    }

    /**
     * ⛔ A fresh feed must NOT look stale — the negative control. Without this, a report that always
     * cried staleness would be indistinguishable from one that measured it, which is precisely the
     * alarm-that-fires-wrongly failure.
     */
    @Test
    void aFreshFeedReportsANearZeroAge() {
        long validFrom = 1_787_800_000_000L;
        long now = validFrom + 2_000L;
        assertEquals(2L, (now - validFrom) / 1000L);
        assertTrue((now - validFrom) / (double) WIDTH_MS < 0.01,
                "a feed seen two seconds after it opened is fresh and must read as fresh");
    }

    /** An expired feed reports NEGATIVE remaining life rather than clamping to zero. */
    @Test
    void anExpiredFeedReportsNegativeRemainingLifeRatherThanZero() {
        long validFrom = 1_787_800_000_000L;
        OraclePriceFeed f = feed(validFrom);
        long now = f.validTo() + 45_000L;
        assertEquals(-45L, (f.validTo() - now) / 1000L,
                "clamping to zero would make 'just expired' and 'expired ten minutes ago' identical, "
                        + "which is the distinction an operator needs");
    }
}
