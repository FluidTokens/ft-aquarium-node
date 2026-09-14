package com.fluidtokens.aquarium.offchain.model;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * How long a loan has been running, in the two forms the page needs at once.
 *
 * <h2>Why both</h2>
 * The readable form wins the visible slot — the requirement was to replace a raw chain coordinate
 * with something a person can act on. But <b>the absolute instant is kept on hover</b>, because
 * anyone reconciling a loan against the chain needs the precise moment, and rounding it away makes
 * the page useless to exactly the person most likely to look closely at it.
 *
 * @param text      relative and coarse: "3d 4h", "12m"
 * @param iso       the absolute instant, ISO-8601 UTC
 */
public record LoanAge(String text, String iso) {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    /**
     * @param lendDateMillis POSIX milliseconds from the loan datum — the moment the loan began.
     *                       This is the loan's own record of when it started, which is what "age"
     *                       means; there is no UTxO slot on this page to take it from.
     */
    public static LoanAge since(java.math.BigInteger lendDateMillis, long nowMillis) {
        if (lendDateMillis == null) {
            return new LoanAge("unknown", null);
        }
        long lend = lendDateMillis.longValue();
        Instant lendAt = Instant.ofEpochMilli(lend);
        String iso = ISO.format(lendAt.atOffset(ZoneOffset.UTC).toInstant());
        if (lend > nowMillis) {
            // A loan dated in the future is a decoding or clock problem, not an age. Say so rather
            // than render a negative duration as if it meant something.
            return new LoanAge("not yet started", iso);
        }
        return new LoanAge(format(Duration.ofMillis(nowMillis - lend)), iso);
    }

    /**
     * Two units at most, largest first, and never a bare "0". Precision below the second unit is
     * noise on a page read to decide what to prepare for.
     */
    static String format(Duration age) {
        long days = age.toDays();
        long hours = age.toHoursPart();
        long minutes = age.toMinutesPart();
        if (days > 0) {
            return hours > 0 ? days + "d " + hours + "h" : days + "d";
        }
        if (hours > 0) {
            return minutes > 0 ? hours + "h " + minutes + "m" : hours + "h";
        }
        if (minutes > 0) {
            return minutes + "m";
        }
        return "just now";
    }
}
