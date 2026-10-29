package com.fluidtokens.aquarium.offchain.service.loans;

import com.fluidtokens.aquarium.offchain.service.loans.OracleStreamRetryPolicy.Decision;
import com.fluidtokens.aquarium.offchain.service.loans.OracleStreamRetryPolicy.Outcome;
import com.fluidtokens.aquarium.offchain.service.loans.OracleStreamRetryPolicy.Rule;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FAB-174 (G5): every rule of {@link OracleStreamRetryPolicy}, and {@link OracleStreamClient.Timings#PRODUCTION},
 * pinned exactly. These values govern how often the node knocks on FluidTokens' authorizer.
 *
 * <p>The jitter source is fixed: {@link #MID} makes every factor exactly 1.0, so a ladder reads as its bare
 * steps, and {@link #LOW} / {@link #HIGH} put it at the two ends of its band.
 */
class OracleStreamRetryPolicyTest {

    /** nextDouble() = 0.5: factor 1.0. */
    private static final RandomGenerator MID = fixed(0.5);
    /** nextDouble() = 0.0: factor 1 − fraction. */
    private static final RandomGenerator LOW = fixed(0.0);
    /** nextDouble() just below 1.0: factor just below 1 + fraction. */
    private static final RandomGenerator HIGH = fixed(Math.nextDown(1.0));

    private static final Duration SHORT = Duration.ofSeconds(5);

    // ======================================================================================
    // The pinned constants
    // ======================================================================================

    @Test
    void theProductionTimingsArePinnedExactly() {
        assertEquals(new OracleStreamClient.Timings(Duration.ofSeconds(10), Duration.ofSeconds(20),
                Duration.ofSeconds(45), Duration.ofSeconds(10), Duration.ofMinutes(115), 1.0),
                OracleStreamClient.Timings.PRODUCTION);
        assertEquals(Duration.ofSeconds(45), OracleStreamClient.Timings.PRODUCTION.pingInterval());
        assertEquals(Duration.ofSeconds(10), OracleStreamClient.Timings.PRODUCTION.pongTimeout());
        assertEquals(Duration.ofMinutes(115), OracleStreamClient.Timings.PRODUCTION.proactiveReconnectAfter(),
                "1h55m: under the server's 2 h cap");
    }

    @Test
    void theLaddersAndHoldsArePinnedExactly() {
        assertArrayEquals(new int[]{1, 2, 5, 10, 20, 30, 60}, OracleStreamRetryPolicy.TRANSPORT_LADDER_SECONDS);
        assertArrayEquals(new int[]{60, 120, 240, 480, 960, 1800}, OracleStreamRetryPolicy.BACKOFF_LADDER_SECONDS);
        assertEquals(Duration.ofSeconds(60), OracleStreamRetryPolicy.LADDER_RESET_AFTER);
        assertEquals(Duration.ofHours(1), OracleStreamRetryPolicy.REFUSED_403_HOLD);
        assertEquals(Duration.ofMinutes(5), OracleStreamRetryPolicy.STAKE_RECHECK_INTERVAL);
        assertEquals(0.20, OracleStreamRetryPolicy.LADDER_JITTER);
        assertEquals(0.05, OracleStreamRetryPolicy.HOLD_JITTER);
    }

    // ======================================================================================
    // Transport
    // ======================================================================================

    @Test
    void theTransportLadderIsOneTwoFiveTenTwentyThirtySixtyThenSixty() {
        OracleStreamRetryPolicy policy = new OracleStreamRetryPolicy(MID);
        List<Long> seconds = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Decision decision = policy.next(Outcome.transport("server-close(1000)", SHORT));
            assertEquals(Rule.TRANSPORT, decision.rule());
            assertEquals("closed:server-close(1000)", decision.stateKey());
            seconds.add(decision.delay().toMillis());
        }
        assertEquals(List.of(1_000L, 2_000L, 5_000L, 10_000L, 20_000L, 30_000L, 60_000L, 60_000L, 60_000L, 60_000L),
                seconds);
    }

    @Test
    void everyTransportStepStaysWithinTwentyPercent() {
        int[] steps = OracleStreamRetryPolicy.TRANSPORT_LADDER_SECONDS;
        OracleStreamRetryPolicy low = new OracleStreamRetryPolicy(LOW);
        OracleStreamRetryPolicy high = new OracleStreamRetryPolicy(HIGH);
        for (int i = 0; i < steps.length + 2; i++) {
            long step = steps[Math.min(i, steps.length - 1)] * 1000L;
            long lo = low.next(Outcome.transport("no-pong", SHORT)).delay().toMillis();
            long hi = high.next(Outcome.transport("no-pong", SHORT)).delay().toMillis();
            assertEquals(Math.round(step * 0.8), lo, "step " + i + " at the bottom of its band");
            assertTrue(hi <= Math.round(step * 1.2) && hi >= Math.round(step * 1.2) - 1,
                    "step " + i + " at the top of its band: " + hi);
        }
    }

    @Test
    void theTransportLadderResetsOnlyAfterAConnectionOpenForAtLeastSixtySeconds() {
        OracleStreamRetryPolicy policy = new OracleStreamRetryPolicy(MID);
        assertEquals(1_000, transport(policy, Duration.ZERO));
        assertEquals(2_000, transport(policy, Duration.ofSeconds(59)));
        assertEquals(5_000, transport(policy, Duration.ofMillis(59_999)), "just under 60 s does not reset");
        assertEquals(1_000, transport(policy, Duration.ofSeconds(60)), "exactly 60 s resets");
        assertEquals(2_000, transport(policy, Duration.ZERO));
        assertEquals(1_000, transport(policy, Duration.ofHours(2)));
    }

    @Test
    void aSuccessfulOpenAloneDoesNotResetTheTransportLadder() {
        OracleStreamRetryPolicy policy = new OracleStreamRetryPolicy(MID);
        transport(policy, Duration.ZERO);
        transport(policy, Duration.ZERO);
        policy.onConnected();
        assertEquals(5_000, transport(policy, Duration.ofSeconds(3)),
                "a connection that dropped within 60 s continues the ladder");
    }

    @Test
    void fiveHundredAndAnyOtherStatusRideTheTransportLadder() {
        OracleStreamRetryPolicy policy = new OracleStreamRetryPolicy(MID);
        Decision first = policy.next(Outcome.refused(500));
        assertEquals(Rule.TRANSPORT, first.rule());
        assertEquals("refused:500", first.stateKey());
        assertEquals(1_000, first.delay().toMillis());
        assertEquals(2_000, policy.next(Outcome.refused(503)).delay().toMillis());
        assertEquals(5_000, policy.next(Outcome.refused(418)).delay().toMillis());
        assertEquals(10_000, transport(policy, Duration.ZERO), "one shared ladder with the transport ends");
    }

    // ======================================================================================
    // 401 and 400
    // ======================================================================================

    @Test
    void aFirst401RetriesAtOnceThenTheAuthLadder() {
        OracleStreamRetryPolicy policy = new OracleStreamRetryPolicy(MID);
        Decision resign = policy.next(Outcome.refused(401));
        assertEquals(Rule.RESIGN, resign.rule());
        assertEquals(Duration.ZERO, resign.delay(), "the first 401 re-signs at once");
        assertEquals("refused:401", resign.stateKey());

        List<Long> seconds = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Decision decision = policy.next(Outcome.refused(401));
            assertEquals(Rule.AUTH, decision.rule());
            seconds.add(decision.delay().toSeconds());
        }
        assertEquals(List.of(60L, 120L, 240L, 480L, 960L, 1800L, 1800L, 1800L), seconds);

        policy.onConnected();
        assertEquals(Duration.ZERO, policy.next(Outcome.refused(401)).delay(), "re-armed by a success");
        assertEquals(60, policy.next(Outcome.refused(401)).delay().toSeconds(), "and the ladder restarted");
    }

    @Test
    void anotherOutcomeBetweenTwo401sDoesNotReArmTheImmediateResign() {
        OracleStreamRetryPolicy policy = new OracleStreamRetryPolicy(MID);
        assertEquals(Duration.ZERO, policy.next(Outcome.refused(401)).delay());
        policy.next(Outcome.refused(429));
        policy.next(Outcome.transport("error(IOException)", Duration.ZERO));
        assertEquals(Rule.AUTH, policy.next(Outcome.refused(401)).rule(), "only a success re-arms it");
    }

    @Test
    void a400RidesTheAuthLadderFromItsFirstStep() {
        OracleStreamRetryPolicy policy = new OracleStreamRetryPolicy(MID);
        Decision first = policy.next(Outcome.refused(400));
        assertEquals(Rule.AUTH, first.rule());
        assertEquals("refused:400", first.stateKey());
        assertEquals(60, first.delay().toSeconds());
        assertEquals(120, policy.next(Outcome.refused(400)).delay().toSeconds());
    }

    @Test
    void theAuthLadderStaysWithinTwentyPercent() {
        OracleStreamRetryPolicy low = new OracleStreamRetryPolicy(LOW);
        OracleStreamRetryPolicy high = new OracleStreamRetryPolicy(HIGH);
        for (int step : OracleStreamRetryPolicy.BACKOFF_LADDER_SECONDS) {
            assertEquals(Math.round(step * 1000L * 0.8), low.next(Outcome.refused(400)).delay().toMillis());
            long hi = high.next(Outcome.refused(400)).delay().toMillis();
            assertTrue(hi <= Math.round(step * 1000L * 1.2) && hi > step * 1000L * 1.19, step + ": " + hi);
        }
    }

    // ======================================================================================
    // 403
    // ======================================================================================

    @Test
    void a403HoldsAnHourWithinFivePercent() {
        Decision mid = new OracleStreamRetryPolicy(MID).next(Outcome.refused(403));
        assertEquals(Rule.HOLD_403, mid.rule());
        assertEquals("refused:403", mid.stateKey());
        assertEquals(Duration.ofHours(1), mid.delay());
        assertEquals(Duration.ofMillis(3_420_000), new OracleStreamRetryPolicy(LOW).next(Outcome.refused(403)).delay());
        long hi = new OracleStreamRetryPolicy(HIGH).next(Outcome.refused(403)).delay().toMillis();
        assertTrue(hi <= 3_780_000 && hi > 3_779_000, "the top of ±5%: " + hi);

        OracleStreamRetryPolicy repeated = new OracleStreamRetryPolicy(MID);
        for (int i = 0; i < 5; i++) {
            assertEquals(Duration.ofHours(1), repeated.next(Outcome.refused(403)).delay(), "a hold, not a ladder");
        }
    }

    @Test
    void aStakeFlipDuringTheHoldIsAChangeAndNoFlipIsNot() {
        OracleStreamRetryPolicy policy = new OracleStreamRetryPolicy(MID);
        policy.next(Outcome.refused(403));
        policy.holdStarted(false);
        assertFalse(policy.stakeChanged(false), "unchanged");
        assertTrue(policy.stakeChanged(true), "absent → present: retry at once");

        policy.holdStarted(true);
        assertFalse(policy.stakeChanged(true));
        assertTrue(policy.stakeChanged(false), "present → absent is a change too");
    }

    @Test
    void anUnknownStakeIsNeverAChange() {
        OracleStreamRetryPolicy policy = new OracleStreamRetryPolicy(MID);
        policy.holdStarted(false);
        assertFalse(policy.stakeChanged(null), "an exception from the stake check is unknown, never a flip");

        policy.holdStarted(null);
        assertFalse(policy.stakeChanged(true), "unknown at the refusal: the first known value is the baseline");
        assertFalse(policy.stakeChanged(null));
        assertFalse(policy.stakeChanged(true));
        assertTrue(policy.stakeChanged(false), "a later change from that baseline is a change");
    }

    // ======================================================================================
    // 429
    // ======================================================================================

    @Test
    void a429BacksOffExponentiallyToTheCapAndResetsOnSuccess() {
        OracleStreamRetryPolicy policy = new OracleStreamRetryPolicy(MID);
        List<Long> seconds = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Decision decision = policy.next(Outcome.refused(429));
            assertEquals(Rule.BACKOFF_429, decision.rule());
            assertEquals("refused:429", decision.stateKey());
            seconds.add(decision.delay().toSeconds());
        }
        assertEquals(List.of(60L, 120L, 240L, 480L, 960L, 1800L, 1800L, 1800L), seconds);

        policy.onConnected();
        assertEquals(60, policy.next(Outcome.refused(429)).delay().toSeconds(), "reset by a success");
    }

    @Test
    void the429LadderIsItsOwn() {
        OracleStreamRetryPolicy policy = new OracleStreamRetryPolicy(MID);
        policy.next(Outcome.refused(400));
        policy.next(Outcome.refused(400));
        transport(policy, Duration.ZERO);
        assertEquals(60, policy.next(Outcome.refused(429)).delay().toSeconds(), "neither the auth nor transport ladder");
        assertEquals(240, policy.next(Outcome.refused(400)).delay().toSeconds(), "and the auth ladder kept its place");
        assertEquals(2_000, transport(policy, Duration.ZERO), "and so did the transport ladder");
    }

    @Test
    void the429LadderStaysWithinTwentyPercent() {
        OracleStreamRetryPolicy low = new OracleStreamRetryPolicy(LOW);
        OracleStreamRetryPolicy high = new OracleStreamRetryPolicy(HIGH);
        for (int step : OracleStreamRetryPolicy.BACKOFF_LADDER_SECONDS) {
            assertEquals(Math.round(step * 1000L * 0.8), low.next(Outcome.refused(429)).delay().toMillis());
            long hi = high.next(Outcome.refused(429)).delay().toMillis();
            assertTrue(hi <= Math.round(step * 1000L * 1.2) && hi > step * 1000L * 1.19, step + ": " + hi);
        }
    }

    // ======================================================================================
    // Proactive
    // ======================================================================================

    @Test
    void aProactiveReconnectIsImmediateAndAdvancesNoLadder() {
        OracleStreamRetryPolicy policy = new OracleStreamRetryPolicy(MID);
        transport(policy, Duration.ZERO);
        policy.next(Outcome.refused(429));
        policy.next(Outcome.refused(400));

        Decision proactive = policy.next(Outcome.proactive());
        assertEquals(Duration.ZERO, proactive.delay());
        assertEquals(Rule.PROACTIVE, proactive.rule());
        assertEquals("proactive", proactive.stateKey());
        policy.next(Outcome.proactive());

        assertEquals(2_000, transport(policy, Duration.ZERO), "the transport ladder did not move");
        assertEquals(120, policy.next(Outcome.refused(429)).delay().toSeconds(), "nor the 429 ladder");
        assertEquals(120, policy.next(Outcome.refused(400)).delay().toSeconds(), "nor the auth ladder");
    }

    // ======================================================================================
    // Helpers
    // ======================================================================================

    private static long transport(OracleStreamRetryPolicy policy, Duration up) {
        return policy.next(Outcome.transport("server-close(1000)", up)).delay().toMillis();
    }

    /** A generator whose {@code nextDouble()} is always {@code value}. */
    private static RandomGenerator fixed(double value) {
        return new RandomGenerator() {
            @Override
            public long nextLong() {
                return Double.doubleToLongBits(value);
            }

            @Override
            public double nextDouble() {
                return value;
            }
        };
    }
}
