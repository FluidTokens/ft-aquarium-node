package com.fluidtokens.aquarium.offchain.service.wallet;

import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.yaci.store.events.internal.CommitEvent;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration.Action;
import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration.Market;
import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration.Mode;
import com.fluidtokens.aquarium.offchain.service.AppUtxoService;
import com.fluidtokens.aquarium.offchain.service.BlockEventListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Keeps the bot wallet in {@link WalletShape}'s target shape (FAB-130) — the first production caller of
 * {@link WalletShapeTransactions}. On every Yaci {@link CommitEvent} it asks the pure engine whether the
 * wallet is out of shape and, if so, builds the shaped self-send, signs it and submits it.
 *
 * <h2>The gates, cheapest first (FAB-131, as Giovanni ruled them)</h2>
 * <ol>
 *   <li><b>G1</b> {@code loans.wallet.rebalance.enabled} (env {@code AQUARIUM_WALLET_REBALANCE_ENABLED}, default
 *       {@code true});</li>
 *   <li><b>G2</b> not {@link #suspend() suspended} — in memory only; effective = G1 AND NOT suspended, so
 *       {@link #resume()} can never override G1;</li>
 *   <li><b>G3</b> {@code loans.liquidation.mode} is {@code SHADOW} or {@code LIVE}. ⚠ <b>SHADOW SUBMITS the
 *       rebalance</b>: it is wallet housekeeping, not a liquidation;</li>
 *   <li><b>G4</b> at least one configured market is {@code action: ANTICIPATE} — an ADA-principal
 *       ({@code lovelace}) one included, although it adds nothing to the relevant set;</li>
 *   <li><b>G5</b> the node is not syncing ({@link BlockEventListener#getIsSyncing()}).</li>
 * </ol>
 * A closed gate reads nothing and logs at DEBUG only: a node in the shipped {@code DISABLED} mode, or with no
 * {@code ANTICIPATE} market, behaves exactly as it did before this class existed.
 *
 * <h2>Once the gates are open</h2>
 * <ul>
 *   <li>The wallet is read from the local index ({@link AppUtxoService#listWalletUtxo()}). A read failure is an
 *       ERROR for this event and nothing is built — never an empty wallet.</li>
 *   <li>The relevant set is {@link WalletShape#relevantUnits}: the ANTICIPATE principals, lower-cased, never
 *       {@code lovelace}.</li>
 *   <li>No reason → nothing, at DEBUG. Otherwise {@link WalletShapeTransactions#buildShaped} spends the WHOLE
 *       wallet (no reference-script or datum filtering: the operator keeps the wallet free of them).</li>
 *   <li>Not built → WARN with the engine's detail and the reasons, <b>on every event, unthrottled</b> (ruled:
 *       a wallet that cannot be shaped is the operator's problem). Never signed, never submitted, so a refusal
 *       costs no fee.</li>
 *   <li>Built → signed and submitted; INFO with the hash, the reasons and the fee, or ERROR with the
 *       rejection.</li>
 * </ul>
 * <b>There is no pending-transaction guard</b> (ruled): if the next block still shows the old wallet, the
 * same self-send is rebuilt and resubmitted, and the duplicate fails harmlessly at the ledger.
 *
 * <h2>Never throws into Yaci</h2>
 * An exception from an {@code @EventListener} on {@code CommitEvent} would propagate into Yaci Store's
 * publisher mid-sync, so the whole body sits behind a catch-all that logs at ERROR — the idiom
 * {@link BlockEventListener} uses.
 *
 * <h2>Ordering</h2>
 * The syncing flag this reads must be the one {@link BlockEventListener#processBlock} wrote for the SAME
 * event. That listener runs at {@link Ordered#HIGHEST_PRECEDENCE} and this one at
 * {@link Ordered#LOWEST_PRECEDENCE}; without the explicit order, listeners of equal order run in bean
 * registration order and this would act on a one-event-stale flag.
 */
@Service
@Slf4j
public class WalletRebalancer {

    /**
     * @param envEnabled {@code loans.wallet.rebalance.enabled}
     * @param suspended  the in-memory suspension
     * @param effective  {@code envEnabled && !suspended}
     */
    public record State(boolean envEnabled, boolean suspended, boolean effective) {
    }

    private final WalletShapeTransactions engine;

    private final Supplier<List<Utxo>> walletRead;

    private final BooleanSupplier syncing;

    private final Supplier<Mode> mode;

    private final Supplier<List<Market>> markets;

    private final boolean envEnabled;

    private final AtomicBoolean suspended = new AtomicBoolean(false);

    @Autowired
    public WalletRebalancer(WalletShapeTransactions engine,
                            AppUtxoService appUtxoService,
                            BlockEventListener blockEventListener,
                            AppConfig.LiquidationConfiguration liquidationConfiguration,
                            @Value("${loans.wallet.rebalance.enabled:true}") boolean envEnabled) {
        this(engine, appUtxoService::listWalletUtxo, () -> blockEventListener.getIsSyncing().get(),
                liquidationConfiguration::getMode, liquidationConfiguration::getMarkets, envEnabled);
    }

    /** The seams, so the gates and the outcomes can be driven without Spring. */
    WalletRebalancer(WalletShapeTransactions engine,
                     Supplier<List<Utxo>> walletRead,
                     BooleanSupplier syncing,
                     Supplier<Mode> mode,
                     Supplier<List<Market>> markets,
                     boolean envEnabled) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.walletRead = Objects.requireNonNull(walletRead, "walletRead");
        this.syncing = Objects.requireNonNull(syncing, "syncing");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.markets = Objects.requireNonNull(markets, "markets");
        this.envEnabled = envEnabled;
    }

    /** Stop rebalancing until {@link #resume()}. In memory only: a restart forgets it. */
    public void suspend() {
        suspended.set(true);
        log.info("[REBALANCE] suspended; effective={}", state().effective());
    }

    /** Lift a {@link #suspend()}. Cannot turn on a rebalancer the environment turned off. */
    public void resume() {
        suspended.set(false);
        log.info("[REBALANCE] resumed; effective={} (loans.wallet.rebalance.enabled={})",
                state().effective(), envEnabled);
    }

    public State state() {
        boolean isSuspended = suspended.get();
        return new State(envEnabled, isSuspended, envEnabled && !isSuspended);
    }

    @EventListener
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void processCommit(CommitEvent<?> commitEvent) {
        onCommit();
    }

    /** One CommitEvent's worth of work. Never throws. */
    void onCommit() {
        try {
            rebalanceIfNeeded();
        } catch (Exception e) {
            log.error("[REBALANCE] failed on this block; nothing further is attempted until the next one: {}",
                    e.toString(), e);
        }
    }

    private void rebalanceIfNeeded() throws Exception {
        if (!envEnabled) {
            log.debug("[REBALANCE] skipped: loans.wallet.rebalance.enabled is false");
            return;
        }
        if (suspended.get()) {
            log.debug("[REBALANCE] skipped: suspended");
            return;
        }
        Mode current = mode.get();
        if (current != Mode.SHADOW && current != Mode.LIVE) {
            log.debug("[REBALANCE] skipped: loans.liquidation.mode is {}", current);
            return;
        }
        List<Market> configured = markets.get();
        if (configured == null || configured.stream().noneMatch(m -> m != null && m.getAction() == Action.ANTICIPATE)) {
            log.debug("[REBALANCE] skipped: no action: ANTICIPATE market");
            return;
        }
        if (syncing.getAsBoolean()) {
            log.debug("[REBALANCE] skipped: the node is syncing");
            return;
        }

        Set<String> relevant = WalletShape.relevantUnits(configured);
        List<Utxo> wallet;
        try {
            wallet = walletRead.get();
        } catch (RuntimeException e) {
            log.error("[REBALANCE] could not read the wallet from the index; nothing built for this block: {}",
                    e.toString(), e);
            return;
        }
        if (wallet == null) {
            log.error("[REBALANCE] the wallet read returned no list; nothing built for this block");
            return;
        }

        EnumSet<WalletShape.Reason> reasons = WalletShape.reasons(wallet, relevant, engine.collateralLovelace());
        if (reasons.isEmpty()) {
            log.debug("[REBALANCE] the wallet is in shape ({} UTxOs, relevant {})", wallet.size(), relevant);
            return;
        }

        WalletShapeTransactions.Outcome outcome = engine.buildShaped(wallet, relevant);
        if (!outcome.isBuilt()) {
            log.warn("[REBALANCE] {}: {} — the wallet is out of shape {}, relevant {}; nothing signed or submitted",
                    outcome.status(), outcome.detail(), reasons, relevant);
            return;
        }

        Transaction signed = engine.sign(outcome.transaction());
        Result<String> result = engine.submit(signed);
        if (result != null && result.isSuccessful()) {
            log.info("[REBALANCE] submitted {} — reasons {}, relevant {}, fee {} lovelace",
                    result.getValue(), reasons, relevant, outcome.transaction().getBody().getFee());
        } else {
            log.error("[REBALANCE] submission rejected — reasons {}, relevant {}, fee {} lovelace: {}",
                    reasons, relevant, outcome.transaction().getBody().getFee(),
                    result == null ? "no result" : result.getResponse());
        }
    }
}
