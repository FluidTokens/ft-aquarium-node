package com.fluidtokens.aquarium.offchain.service;

import com.bloxbean.cardano.yaci.store.events.internal.CommitEvent;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.conversions.CardanoConverters;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks the chain's position as this node sees it, from Yaci Store's {@link CommitEvent}s.
 *
 * <p><b>The syncing flag is the AGE of the last block this node applied</b> (FAB-136). For each
 * {@code CommitEvent}, the event's slot is converted to its block time and the drift is
 * {@code now − blockTime}; the node is syncing exactly when that drift exceeds
 * {@code aquarium.syncing-threshold-minutes} (env {@code AQUARIUM_SYNCING_THRESHOLD_MINUTES}, shipped
 * default {@value #DEFAULT_SYNCING_THRESHOLD_MINUTES}). The flag starts {@code true} and only
 * {@link #processBlock} writes it. It gates the three spending processors, the {@code /healthcheck}
 * verdict and the readiness wallet: while it is set, nothing is read from the wallet and nothing is
 * spent.
 *
 * <p>⚠ <b>Accepted residue: a stalled relay leaves the last value.</b> The flag moves only when a block
 * arrives, so a node that was caught up and then stops receiving blocks keeps reporting "not syncing"
 * however old its last block becomes. No liveness watchdog exists for this (ruled 2026-10-04).
 *
 * <p>The listener never throws: an exception from an {@code @EventListener} on {@code CommitEvent}
 * would propagate into Yaci Store's event publisher mid-sync. A failure is logged at WARN and leaves
 * the flag unchanged; an event whose slot cannot be read leaves {@link #lastAppliedSlot} unchanged too.
 */
@Service
@Slf4j
public class BlockEventListener {

    /** The shipped default of {@code aquarium.syncing-threshold-minutes} in {@code application.yaml}. */
    public static final long DEFAULT_SYNCING_THRESHOLD_MINUTES = 10;

    private final CardanoConverters cardanoConverters;

    private final Duration syncingThreshold;

    private final Clock clock;

    @Getter
    private final AtomicBoolean isSyncing = new AtomicBoolean(true);

    /**
     * <b>The slot of the last block this node applied — the chain's position as WE see it.</b>
     *
     * <p>It was already being computed here and thrown away, and that discard cost the first live
     * liquidation. A transaction's {@code invalidBefore} was built from {@code System
     * .currentTimeMillis()}, backdated 30 seconds, and rejected {@code OutsideValidityIntervalUTxO}
     * because the validating node's current slot is <b>the last block it applied</b>, not wall clock
     * — and preview was sitting in a <b>182-slot block gap</b>, the largest of the surrounding 24.
     * Measured on chain 2026-08-26: preview's mean gap is 36 s, so <b>a 30-second backdate cannot
     * cover an ordinary gap, let alone a long one.</b>
     *
     * <p>⚠ Zero until the first block arrives. A caller must treat zero as "unknown" and fall back to
     * wall clock rather than anchoring a transaction at the epoch.
     */
    @Getter
    private final AtomicLong lastAppliedSlot = new AtomicLong(0L);

    @Autowired
    public BlockEventListener(CardanoConverters cardanoConverters,
                              @Value("${aquarium.syncing-threshold-minutes}") long thresholdMinutes) {
        this(cardanoConverters, thresholdMinutes, Clock.systemUTC());
    }

    /** The shipped default threshold — for tests that only need a listener to exist. */
    public BlockEventListener(CardanoConverters cardanoConverters) {
        this(cardanoConverters, DEFAULT_SYNCING_THRESHOLD_MINUTES);
    }

    BlockEventListener(CardanoConverters cardanoConverters, long thresholdMinutes, Clock clock) {
        if (thresholdMinutes <= 0) {
            throw new IllegalArgumentException(
                    "aquarium.syncing-threshold-minutes must be a positive number of minutes, was " + thresholdMinutes);
        }
        this.cardanoConverters = cardanoConverters;
        this.syncingThreshold = Duration.ofMinutes(thresholdMinutes);
        this.clock = clock;
    }

    @EventListener
    public void processBlock(CommitEvent<?> commitEvent) {
        try {
            long slot = commitEvent.getMetadata().getSlot();
            lastAppliedSlot.set(slot);

            Instant blockTime = cardanoConverters.slot().slotToTime(slot).toInstant(ZoneOffset.UTC);
            Duration drift = Duration.between(blockTime, clock.instant());
            isSyncing.set(drift.compareTo(syncingThreshold) > 0);
        } catch (RuntimeException e) {
            log.warn("[SYNC] could not process a CommitEvent; syncing={} left unchanged, lastAppliedSlot={}",
                    isSyncing.get(), lastAppliedSlot.get(), e);
        }
    }


}
