package com.fluidtokens.aquarium.offchain.service.loans;

import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * When the oracle stream (FAB-174) tries again after an attempt ends, and under which state key that end is
 * reported. Pure: no clock, no thread, no I/O — the jitter comes from the injected {@link RandomGenerator}, and
 * the caller applies {@link OracleStreamClient.Timings#retryScale()} to every delay this returns.
 *
 * <p>⚠ <b>These values govern connection attempts against FluidTokens' authorizer</b> (lane trigger G5), so
 * each is pinned by {@code OracleStreamRetryPolicyTest}:
 * <ul>
 *   <li><b>Transport</b> — a connect error, a timeout, a server close, an error, no pong, or a handshake
 *       refused with any status this class has no rule for (5xx included):
 *       {@link #TRANSPORT_LADDER_SECONDS}, then its last step repeated, ±{@value #LADDER_JITTER} jitter. The
 *       ladder restarts at its first step when the connection that ended had been open for at least
 *       {@link #LADDER_RESET_AFTER}.</li>
 *   <li><b>401</b> — the first after a successful open (or the first ever) retries at once with a fresh
 *       signature; a 401 on that re-signed attempt, and every 401 after it until the next open, moves along
 *       {@link #BACKOFF_LADDER_SECONDS}.</li>
 *   <li><b>400</b> — the same auth ladder: a malformed request will not fix itself by being repeated.</li>
 *   <li><b>403</b> — no bond for the stake key: {@link #REFUSED_403_HOLD} ±{@value #HOLD_JITTER}, or at once
 *       when the stake presence read every {@link #STAKE_RECHECK_INTERVAL} differs from the one recorded at
 *       the refusal ({@link #holdStarted}, {@link #stakeChanged}).</li>
 *   <li><b>429</b> — another node on this bond holds the one connection it grants:
 *       {@link #BACKOFF_LADDER_SECONDS}, its own ladder.</li>
 *   <li><b>Proactive reconnect</b> — no wait, and no ladder moves.</li>
 * </ul>
 * A successful open ({@link #onConnected}) restarts the auth and 429 ladders and re-arms the immediate 401
 * re-sign. It does NOT restart the transport ladder: only a connection that stayed open long enough does.
 *
 * <p>Not thread-safe: the client calls it from its single {@code oracle-stream} thread only.
 */
public final class OracleStreamRetryPolicy {

    /** Seconds before transport retry n (then the last step repeated). */
    static final int[] TRANSPORT_LADDER_SECONDS = {1, 2, 5, 10, 20, 30, 60};

    /** Seconds before auth (400, re-signed 401) or 429 retry n (then the 1800 s cap repeated). */
    static final int[] BACKOFF_LADDER_SECONDS = {60, 120, 240, 480, 960, 1800};

    /** A connection that had been open this long restarts the transport ladder when it ends. */
    static final Duration LADDER_RESET_AFTER = Duration.ofSeconds(60);

    /** How long a 403 holds before the next attempt, unless the stake presence changes first. */
    static final Duration REFUSED_403_HOLD = Duration.ofHours(1);

    /** How often the stake presence is re-read during a 403 hold. */
    static final Duration STAKE_RECHECK_INTERVAL = Duration.ofMinutes(5);

    /** ± this fraction on every ladder step. */
    static final double LADDER_JITTER = 0.20;

    /** ± this fraction on the 403 hold. */
    static final double HOLD_JITTER = 0.05;

    /** Which rule produced a {@link Decision}. */
    enum Rule { TRANSPORT, RESIGN, AUTH, HOLD_403, BACKOFF_429, PROACTIVE }

    enum Kind { TRANSPORT, REFUSED, PROACTIVE }

    /**
     * How the last attempt ended.
     *
     * @param reason for a transport end, its reason token ({@code server-close(1000)}, {@code no-pong}, …)
     * @param status for a refusal, the handshake's HTTP status
     * @param up     for a transport end, how long the connection had been open; zero if it never opened
     */
    record Outcome(Kind kind, String reason, int status, Duration up) {

        static Outcome transport(String reason, Duration up) {
            return new Outcome(Kind.TRANSPORT, reason, 0, up == null ? Duration.ZERO : up);
        }

        static Outcome refused(int status) {
            return new Outcome(Kind.REFUSED, null, status, Duration.ZERO);
        }

        static Outcome proactive() {
            return new Outcome(Kind.PROACTIVE, null, 0, Duration.ZERO);
        }
    }

    /**
     * The next attempt.
     *
     * @param delay    how long to wait, before {@code retryScale}
     * @param stateKey what the end is reported under: a repeat of the same key is the same state
     * @param rule     the rule that decided it
     */
    record Decision(Duration delay, String stateKey, Rule rule) {
    }

    private final RandomGenerator random;

    private int transportStep;
    private int authStep;
    private int backoff429Step;
    /** A 401 has already been retried at once since the last successful open. */
    private boolean resigned;

    /** The stake presence recorded for the current 403 hold; null while unknown. */
    private Boolean stakeAtRefusal;

    public OracleStreamRetryPolicy(RandomGenerator random) {
        this.random = random;
    }

    /** The next attempt after {@code outcome}; advances the ladder it used. */
    Decision next(Outcome outcome) {
        return switch (outcome.kind()) {
            case PROACTIVE -> new Decision(Duration.ZERO, "proactive", Rule.PROACTIVE);
            case TRANSPORT -> transport(outcome.up(), "closed:" + outcome.reason());
            case REFUSED -> refused(outcome.status());
        };
    }

    /** A connection opened: the auth and 429 ladders restart and the next 401 is re-signed at once. */
    void onConnected() {
        authStep = 0;
        backoff429Step = 0;
        resigned = false;
    }

    /** A 403 hold began; {@code stakePresent} is what the node read then, null when it could not tell. */
    void holdStarted(Boolean stakePresent) {
        stakeAtRefusal = stakePresent;
    }

    /**
     * True when {@code stakePresent} differs from the value recorded at the refusal. Unknown (null) is never a
     * change; when the value at the refusal was unknown, the first known value becomes the one compared with.
     */
    boolean stakeChanged(Boolean stakePresent) {
        if (stakePresent == null) {
            return false;
        }
        if (stakeAtRefusal == null) {
            stakeAtRefusal = stakePresent;
            return false;
        }
        return !stakeAtRefusal.equals(stakePresent);
    }

    private Decision refused(int status) {
        String key = "refused:" + status;
        switch (status) {
            case 401 -> {
                if (!resigned) {
                    resigned = true;
                    return new Decision(Duration.ZERO, key, Rule.RESIGN);
                }
                return new Decision(ladder(BACKOFF_LADDER_SECONDS, authStep++), key, Rule.AUTH);
            }
            case 400 -> {
                return new Decision(ladder(BACKOFF_LADDER_SECONDS, authStep++), key, Rule.AUTH);
            }
            case 403 -> {
                return new Decision(jitter(REFUSED_403_HOLD.toMillis(), HOLD_JITTER), key, Rule.HOLD_403);
            }
            case 429 -> {
                return new Decision(ladder(BACKOFF_LADDER_SECONDS, backoff429Step++), key, Rule.BACKOFF_429);
            }
            default -> {
                return transport(Duration.ZERO, key);
            }
        }
    }

    private Decision transport(Duration up, String key) {
        if (up.compareTo(LADDER_RESET_AFTER) >= 0) {
            transportStep = 0;
        }
        return new Decision(ladder(TRANSPORT_LADDER_SECONDS, transportStep++), key, Rule.TRANSPORT);
    }

    private Duration ladder(int[] seconds, int step) {
        int index = Math.min(Math.max(step, 0), seconds.length - 1);
        return jitter(seconds[index] * 1000L, LADDER_JITTER);
    }

    /** {@code millis} × a uniform factor in [1 − fraction, 1 + fraction). */
    private Duration jitter(long millis, double fraction) {
        double factor = 1.0 + fraction * (2.0 * random.nextDouble() - 1.0);
        return Duration.ofMillis(Math.round(millis * factor));
    }
}
