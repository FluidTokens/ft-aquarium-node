package com.fluidtokens.aquarium.offchain.model;

import java.math.BigInteger;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class LoanAgeTest {

    private static final long NOW = 1_789_000_000_000L;

    @Test
    void aLoanDaysOldReadsInDaysAndHours() {
        BigInteger lend = BigInteger.valueOf(NOW - Duration.ofHours(76).toMillis());

        assertEquals("3d 4h", LoanAge.since(lend, NOW).text());
    }

    @Test
    void awholeNumberOfDaysDropsTheHours() {
        BigInteger lend = BigInteger.valueOf(NOW - Duration.ofDays(5).toMillis());

        assertEquals("5d", LoanAge.since(lend, NOW).text());
    }

    @Test
    void underADayReadsInHoursAndMinutes() {
        assertEquals("2h 30m", LoanAge.format(Duration.ofMinutes(150)));
        assertEquals("2h", LoanAge.format(Duration.ofHours(2)));
        assertEquals("45m", LoanAge.format(Duration.ofMinutes(45)));
        assertEquals("just now", LoanAge.format(Duration.ofSeconds(20)));
    }

    /**
     * ⛔ The absolute instant must survive. Anyone reconciling against the chain needs the exact
     * moment, and a page that rounds it away is useless to the person most likely to look hard at it.
     */
    @Test
    void theAbsoluteInstantIsAlwaysCarriedForTheHover() {
        BigInteger lend = BigInteger.valueOf(1_757_000_000_000L);

        LoanAge age = LoanAge.since(lend, NOW);

        assertEquals("2025-09-04T15:33:20Z", age.iso());
        assertNotNull(age.text());
    }

    /** A loan dated in the future is a clock or decoding problem, not a negative age. */
    @Test
    void aFutureLendDateIsNamedRatherThanRenderedAsANegativeDuration() {
        BigInteger lend = BigInteger.valueOf(NOW + Duration.ofDays(2).toMillis());

        LoanAge age = LoanAge.since(lend, NOW);

        assertEquals("not yet started", age.text());
        assertNotNull(age.iso(), "the instant is still worth showing, since it is the evidence");
    }

    /** No lend date at all reads as unknown, never as zero. */
    @Test
    void aMissingLendDateIsUnknown() {
        LoanAge age = LoanAge.since(null, NOW);

        assertEquals("unknown", age.text());
        assertNull(age.iso());
    }
}
