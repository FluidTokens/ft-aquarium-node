package com.fluidtokens.aquarium.offchain.config;

import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.conversions.CardanoConverters;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Protocol parameters, fetched once per epoch instead of once per build.
 *
 * <p>Protocol parameters change only at an epoch boundary, so asking Blockfrost for them on every
 * transaction and every tank cycle spends the provider budget on an answer that cannot have moved.
 * This wraps the real supplier (Blockfrost's in production) and serves the value it last returned
 * until the next {@link #REFRESH_DELAY_AFTER_EPOCH_START settling point}, ten minutes after an epoch
 * starts, then fetches again. The delay lets the boundary settle before the new epoch's values are
 * read. A value fetched after the current epoch's settling point is served until ten minutes into the
 * next epoch; a value fetched INSIDE the settling window (a boot, restart or empty-cache recovery in
 * an epoch's first ten minutes) is exactly the read the delay exists to avoid, so it is served only
 * until that window ends and then fetched again.
 *
 * <h2>⛔ Every value served is one the chain published</h2>
 * Protocol parameters and cost models must be the chain's own (CCL trap 7): a stale or invented value
 * is how a transaction evaluates clean offline and then fails on chain. So:
 * <ul>
 *   <li>a refresh that fails while a value is cached keeps serving <b>that</b> value, the last one
 *       the chain gave, and retries no sooner than {@link #RETRY_AFTER_FAILURE} later;</li>
 *   <li>a refresh that fails with nothing cached <b>rethrows</b>, exactly as the bare supplier did.
 *       Nothing here ever returns {@code null} or builds a default.</li>
 * </ul>
 *
 * <p>The first fetch happens at construction, and it is soft: a provider that is down at boot logs a
 * WARN and leaves the cache empty, so the node still starts and the first caller retries.
 *
 * <h2>A ledger rejection can bring the refresh forward</h2>
 * Parameters can move outside the epoch schedule, and the ledger says so at submission
 * ({@code PPViewHashesDontMatch}, {@code FeeTooSmallUTxO}; see {@link ProtocolParamsRejections}).
 * {@link #invalidate(String)} then makes the next call fetch — at most once per
 * {@link #RETRY_AFTER_FAILURE}, counted from the last fetch attempt, so a burst of rejections is one
 * provider call. A failed refetch keeps serving the last chain value, exactly as above.
 *
 * <p>UTxOs are NOT cached here or anywhere near here. This holds protocol parameters only.
 */
@Slf4j
public class EpochProtocolParamsSupplier implements ProtocolParamsSupplier {

    /** How long after an epoch starts the next fetch is due. */
    static final Duration REFRESH_DELAY_AFTER_EPOCH_START = Duration.ofMinutes(10);

    /** After a failed refresh with a value cached, the earliest the next attempt may happen. */
    static final Duration RETRY_AFTER_FAILURE = Duration.ofSeconds(60);

    private final ProtocolParamsSupplier delegate;

    private final CardanoConverters converters;

    private final Clock clock;

    /** Guarded by {@code this}. Only ever a value the delegate returned. */
    private ProtocolParams cached;

    /** Guarded by {@code this}. Meaningful only while {@link #cached} is set. */
    private Instant refreshAt;

    /** Guarded by {@code this}. When the delegate was last asked, successfully or not; the invalidation guard. */
    private Instant lastFetchAttempt;

    public EpochProtocolParamsSupplier(ProtocolParamsSupplier delegate,
                                       CardanoConverters converters,
                                       Clock clock) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.converters = Objects.requireNonNull(converters, "converters");
        this.clock = Objects.requireNonNull(clock, "clock");
        try {
            synchronized (this) {
                fetch(clock.instant());
            }
        } catch (RuntimeException e) {
            log.warn("Could not load protocol parameters at startup; the first caller will retry: {}",
                    describe(e));
        }
    }

    @Override
    public synchronized ProtocolParams getProtocolParams() {
        Instant now = clock.instant();
        if (cached != null && now.isBefore(refreshAt)) {
            return cached;
        }
        try {
            return fetch(now);
        } catch (RuntimeException e) {
            if (cached == null) {
                throw e;
            }
            refreshAt = now.plus(RETRY_AFTER_FAILURE);
            log.warn("Protocol parameters refresh failed; serving the last value the chain returned "
                    + "and retrying no sooner than {}: {}", refreshAt, describe(e));
            return cached;
        }
    }

    /**
     * The ledger rejected a transaction for a reason that names the protocol parameters: make the next
     * {@link #getProtocolParams()} fetch afresh — if a value is cached and the delegate was last asked at
     * least {@link #RETRY_AFTER_FAILURE} ago; otherwise nothing changes. Never fetches by itself, never
     * drops the cached value (a failed refetch still serves it), never throws.
     *
     * @param reason what triggered it, for the log
     */
    public synchronized void invalidate(String reason) {
        Instant now = clock.instant();
        if (cached == null) {
            log.warn("Protocol parameters invalidation ignored, nothing is cached (the next caller fetches "
                    + "anyway): {}", reason);
            return;
        }
        if (lastFetchAttempt != null && now.isBefore(lastFetchAttempt.plus(RETRY_AFTER_FAILURE))) {
            log.warn("Protocol parameters invalidation ignored, the last fetch was less than {} ago ({}): {}",
                    RETRY_AFTER_FAILURE, lastFetchAttempt, reason);
            return;
        }
        refreshAt = now;
        log.warn("Protocol parameters invalidated, the next request fetches afresh: {}", reason);
    }

    /** Calls the delegate once; caches and returns its answer, or throws without touching the cache. */
    private ProtocolParams fetch(Instant now) {
        lastFetchAttempt = now;
        ProtocolParams fresh = delegate.getProtocolParams();
        if (fresh == null) {
            throw new IllegalStateException("the protocol parameters supplier returned null");
        }
        Instant next = nextRefreshInstant(now);
        cached = fresh;
        refreshAt = next;
        log.info("Protocol parameters loaded; next refresh at {}", next);
        return fresh;
    }

    /** The failure and, when the supplier wraps one, its cause, which is usually the informative part. */
    private static String describe(RuntimeException e) {
        return e.getCause() == null ? e.toString() : e + " (cause: " + e.getCause() + ")";
    }

    /**
     * The next settling point after {@code now}: the current epoch's start plus the settling delay when
     * {@code now} is still inside that window, otherwise the next epoch's start plus the delay.
     */
    private Instant nextRefreshInstant(Instant now) {
        long slot = converters.time().toSlot(LocalDateTime.ofInstant(now, ZoneOffset.UTC));
        int currentEpoch = converters.slot().slotToEpoch(slot).intValue();
        Instant settle = settlingPoint(currentEpoch);
        return now.isBefore(settle) ? settle : settlingPoint(currentEpoch + 1);
    }

    /** The start of {@code epoch} plus the settling delay. */
    private Instant settlingPoint(int epoch) {
        return converters.epoch().beginningOfEpochToUTCTime(epoch)
                .toInstant(ZoneOffset.UTC)
                .plus(REFRESH_DELAY_AFTER_EPOCH_START);
    }
}
