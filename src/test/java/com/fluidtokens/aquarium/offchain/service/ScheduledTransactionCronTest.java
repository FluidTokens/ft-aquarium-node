package com.fluidtokens.aquarium.offchain.service;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.CronExpression;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>THE PROCESSOR RUNS ON A CLOCK NOW, AND A CLOCK CAN OVERLAP.</b>
 *
 * <p>The schedule moved from {@code fixedDelay} — which measures the gap between a run
 * <em>finishing</em> and the next <em>starting</em>, and therefore cannot overlap — to a cron
 * expression, which fires on the wall clock regardless of what is still running. A cycle processes
 * every due tank, so it can outlast its own period.
 *
 * <p>⚠ <b>Overlap here would not look like overlap.</b> Two cycles read the same tank set and build
 * two transactions spending the same tank; one wins, the other is rejected for an input that no
 * longer exists, and the loser's tank is recorded as a failure it never had. The symptom points at
 * the tank, not at concurrency.
 *
 * <p>This pins the three things that make the change safe: the expression parses, it means what it
 * is meant to mean, and the re-entrancy guard is still in place.
 */
class ScheduledTransactionCronTest {

    private static final Path SERVICE = Path.of(
            "src/main/java/com/fluidtokens/aquarium/offchain/service/ScheduledTransactionService.java");
    private static final Path CONFIG = Path.of("src/main/resources/application.yaml");

    /** The shipped default, read out of the config rather than restated here. */
    private static String configuredCron() throws IOException {
        for (String line : Files.readAllLines(CONFIG)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("cron: ${SCHEDULING_TRANSACTION_PROCESSOR_CRON:")) {
                return trimmed.substring(trimmed.indexOf(':', trimmed.indexOf("CRON")) + 1,
                        trimmed.lastIndexOf('}'));
            }
        }
        return null;
    }

    @Test
    void theShippedCronIsValidAndFiresTwoSecondsPastEveryMinute() throws IOException {
        String cron = configuredCron();
        assertNotNull(cron, "no cron default found in application.yaml — the processor would fail "
                + "to start, because @Scheduled(cron = ...) has no fallback of its own");

        // ⚠ Parsed, not eyeballed. A malformed expression is a STARTUP failure, and the operator
        // sees a context that will not load rather than a processor that will not run.
        CronExpression expression = CronExpression.parse(cron.trim());

        ZonedDateTime from = LocalDateTime.of(2026, 9, 22, 14, 30, 30).atZone(ZoneOffset.UTC);
        ZonedDateTime next = expression.next(from);
        assertNotNull(next);
        assertEquals(2, next.getSecond(),
                "the processor must fire at 2 seconds past the minute, not on the boundary: block "
                        + "production and the indexer both cluster at :00, so a read there sees a "
                        + "view that is still settling");
        assertEquals(31, next.getMinute(), "and it must fire every minute, not every hour");

        ZonedDateTime after = expression.next(next);
        assertEquals(60, java.time.Duration.between(next, after).toSeconds(),
                "consecutive firings must be one minute apart");
    }

    /**
     * ⛔ The guard that {@code fixedDelay} used to make redundant. Cron removed that redundancy, so
     * this is now the only thing preventing two cycles over the same tanks.
     */
    @Test
    void theReentrancyGuardSurvivedTheScheduleChange() throws IOException {
        String source = Files.readString(SERVICE);

        assertTrue(source.contains("cycleInProgress.compareAndSet(false, true)"),
                "⛔ the cron schedule can fire while the previous cycle is still running. Without "
                        + "this guard two cycles read the same tanks and spend the same inputs, and "
                        + "the loser's tank is logged as a failure that never happened");
        assertTrue(source.contains("cycleInProgress.set(false)")
                        && source.contains("} finally {"),
                "the guard must be released in a finally block — a cycle that throws would "
                        + "otherwise leave the processor permanently skipping, silently");
        assertTrue(source.contains("@Scheduled(cron ="),
                "this test is about the cron schedule; if the annotation moved back to a fixed "
                        + "delay, re-read whether the guard is still the thing holding the line");
    }
}
