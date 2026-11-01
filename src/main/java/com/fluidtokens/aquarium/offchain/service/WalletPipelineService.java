package com.fluidtokens.aquarium.offchain.service;

import com.bloxbean.cardano.yaci.store.events.RollbackEvent;
import com.bloxbean.cardano.yaci.store.events.internal.CommitEvent;
import com.fluidtokens.aquarium.offchain.config.BoundedBFBackendService;
import com.fluidtokens.aquarium.offchain.service.loans.CompoundExecutor;
import com.fluidtokens.aquarium.offchain.service.loans.FluidOracleClient;
import com.fluidtokens.aquarium.offchain.service.loans.LiquidationExecutor;
import com.fluidtokens.aquarium.offchain.service.wallet.WalletRebalancer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.conversions.CardanoConverters;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * The bot wallet's ONE spender (FAB-183): a {@link WalletPipeline} whose stages run in this order, one pass at a
 * time, on the {@code wallet-pipeline} thread —
 * <ol>
 *   <li>liquidation, {@link LiquidationExecutor#cycle(long, SubmitFence)};</li>
 *   <li>compound, {@link CompoundExecutor#cycle(SubmitFence)};</li>
 *   <li>housekeeping, {@link WalletRebalancer#housekeep(SubmitFence)}.</li>
 * </ol>
 * A stage that sent something ends the pass, so the next stage waits for the next trigger.
 *
 * <p><b>Three triggers, and no timer.</b> A committed block offers its chain point ({@link #onCommit}); a Yaci
 * {@link RollbackEvent} starts a new generation, so queued work is dropped and an older pass's fence closes
 * ({@link #onRollback}); a change in {@link FluidOracleClient}'s merged prices re-runs the last handled point. Every
 * trigger only records and hands off: no stage ever runs on the thread that delivered it. The Tank processor
 * ({@code ScheduledTransactionService}) keeps its own timer; it is not a stage here.
 *
 * <h2>After the block's transaction commits, never inside it (FAB-131 r2)</h2>
 * At the chain tip Yaci Store 0.1.7 publishes {@code CommitEvent} from INSIDE the block's database transaction:
 * {@code ShelleyBlockEventPublisher.publishBlockEvents} (and its Byron twin) is {@code @Transactional}, publishes
 * the event synchronously and only then sets the cursor (v0.1.7 {@code ShelleyBlockEventPublisher.java:61-69}).
 * Offering a block from a plain {@code @EventListener} would let a pass read the index and submit for a block that
 * may still roll back, and for a block the index has not finished storing. So the entry point is a
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)}: it runs once the block is committed, and not at all if
 * the block's transaction rolls back.
 *
 * <p>{@code fallbackExecution = true} because the other publish path has no transaction: during initial sync Yaci
 * batches blocks and publishes {@code CommitEvent} from an executor thread with none open
 * ({@code processBlocksInParallel}, v0.1.7 {@code ShelleyBlockEventPublisher.java:97-127}). Without the fallback the
 * event would be dropped there (and in any caller with no transaction).
 *
 * <h2>Initial sync is never offered</h2>
 * The event's OWN drift — {@code now − blockTime(slot)} — is compared with
 * {@code aquarium.syncing-threshold-minutes}, the threshold {@link BlockEventListener} reads. A block older than
 * that is logged at DEBUG and not offered, so a node working through its initial sync never starts a pass. The
 * stages keep their own syncing gates as well.
 *
 * <h2>Ordering</h2>
 * {@link #onCommit} carries the default order, so it runs after {@link BlockEventListener#processCommittedBlock}
 * ({@link Ordered#HIGHEST_PRECEDENCE}) for the same event, on both publish paths: a pass handed off here always
 * finds {@code lastCommittedSlot} at least at the offered slot. Listeners of equal order would run in bean
 * registration order instead.
 *
 * <h2>Never throws into Yaci</h2>
 * On the transactional path an exception from {@link #onCommit} never reaches Yaci Store: it runs as an
 * after-completion synchronization, and Spring logs and swallows whatever such a callback throws. On the
 * {@code fallbackExecution} path there is no synchronization in between, and it would propagate into Yaci Store's
 * publisher. That is why every listener here sits behind a catch-all — the idiom {@link BlockEventListener} uses.
 */
@Service
@Slf4j
public class WalletPipelineService {

    private final WalletPipeline pipeline;

    private final CardanoConverters cardanoConverters;

    private final ObjectProvider<FluidOracleClient> oracleClient;

    private final long syncingThresholdMinutes;

    private final Duration syncingThreshold;

    private final long passTimeoutSeconds;

    private final Clock clock;

    @Autowired
    public WalletPipelineService(BoundedBFBackendService backend,
                                 LiquidationExecutor liquidationExecutor,
                                 CompoundExecutor compoundExecutor,
                                 WalletRebalancer walletRebalancer,
                                 CardanoConverters cardanoConverters,
                                 ObjectProvider<FluidOracleClient> oracleClient,
                                 @Value("${aquarium.syncing-threshold-minutes}") long syncingThresholdMinutes,
                                 @Value("${wallet-coordinator.pass-timeout-seconds:120}") long passTimeoutSeconds) {
        this(backend, liquidationExecutor, compoundExecutor, walletRebalancer, cardanoConverters, oracleClient,
                syncingThresholdMinutes, passTimeoutSeconds, Clock.systemUTC());
    }

    WalletPipelineService(BoundedBFBackendService backend,
                          LiquidationExecutor liquidationExecutor,
                          CompoundExecutor compoundExecutor,
                          WalletRebalancer walletRebalancer,
                          CardanoConverters cardanoConverters,
                          ObjectProvider<FluidOracleClient> oracleClient,
                          long syncingThresholdMinutes,
                          long passTimeoutSeconds,
                          Clock clock) {
        if (syncingThresholdMinutes <= 0) {
            throw new IllegalArgumentException(
                    "aquarium.syncing-threshold-minutes must be a positive number of minutes, was "
                            + syncingThresholdMinutes);
        }
        if (passTimeoutSeconds <= 0) {
            throw new IllegalArgumentException(
                    "wallet-coordinator.pass-timeout-seconds must be a positive number of seconds, was "
                            + passTimeoutSeconds);
        }
        Objects.requireNonNull(liquidationExecutor, "liquidationExecutor");
        Objects.requireNonNull(compoundExecutor, "compoundExecutor");
        Objects.requireNonNull(walletRebalancer, "walletRebalancer");
        this.cardanoConverters = Objects.requireNonNull(cardanoConverters, "cardanoConverters");
        this.oracleClient = Objects.requireNonNull(oracleClient, "oracleClient");
        this.syncingThresholdMinutes = syncingThresholdMinutes;
        this.syncingThreshold = Duration.ofMinutes(syncingThresholdMinutes);
        this.passTimeoutSeconds = passTimeoutSeconds;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.pipeline = new WalletPipeline(backend,
                fence -> liquidationExecutor.cycle(this.clock.millis(), fence),
                compoundExecutor::cycle,
                walletRebalancer::housekeep,
                Duration.ofSeconds(passTimeoutSeconds));
    }

    @PostConstruct
    void start() {
        FluidOracleClient client = oracleClient.getIfAvailable();
        if (client != null) {
            client.addChangeListener((source, changedOracleNfts, streamConnected) -> pipeline.offerPriceChange());
        }
        log.info("WALLET PIPELINE liquidation → compound → housekeeping, on committed blocks (drift ≤ {} min) and "
                + "oracle price changes; no timer; pass deadline {} s", syncingThresholdMinutes, passTimeoutSeconds);
    }

    /**
     * Offers a committed block whose own drift is within the syncing threshold. Never runs a stage, never throws.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCommit(CommitEvent<?> commitEvent) {
        try {
            long slot = commitEvent.getMetadata().getSlot();
            String blockHash = commitEvent.getMetadata().getBlockHash();
            Instant blockTime = cardanoConverters.slot().slotToTime(slot).toInstant(ZoneOffset.UTC);
            Duration drift = Duration.between(blockTime, clock.instant());
            if (drift.compareTo(syncingThreshold) > 0) {
                log.debug("wallet pipeline: block at slot {} is {} s old, beyond the syncing threshold; not offered",
                        slot, drift.toSeconds());
                return;
            }
            pipeline.offerBlock(slot, blockHash);
        } catch (RuntimeException e) {
            log.warn("wallet pipeline: could not offer a committed CommitEvent; nothing queued for it", e);
        }
    }

    /** Starts a new generation: queued work is dropped and every older pass is fenced. Never throws. */
    @EventListener
    public void onRollback(RollbackEvent rollbackEvent) {
        try {
            pipeline.rollback();
            log.info("wallet pipeline: rollback to slot {} — queued work dropped, any old pass is fenced",
                    rollbackEvent.getRollbackTo() == null ? null : rollbackEvent.getRollbackTo().getSlot());
        } catch (RuntimeException e) {
            log.warn("wallet pipeline: could not handle a RollbackEvent", e);
        }
    }

    @PreDestroy
    void stop() {
        pipeline.close();
    }
}
