package com.fluidtokens.aquarium.offchain.service;

import com.bloxbean.cardano.yaci.store.events.internal.CommitEvent;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.conversions.CardanoConverters;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Service
@RequiredArgsConstructor
@Slf4j
public class BlockEventListener {

    private final CardanoConverters cardanoConverters;

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

    @EventListener
    public void processBlock(CommitEvent<?> commitEvent) {

        var currentRealSlot = cardanoConverters.time().toSlot(LocalDateTime.now(ZoneOffset.UTC));

        lastAppliedSlot.set(commitEvent.getMetadata().getSlot());

        if (commitEvent.getMetadata().getSlot() < currentRealSlot - 60 * 10) {
            isSyncing.set(true);
        } else {
            isSyncing.set(false);
        }

    }


}
