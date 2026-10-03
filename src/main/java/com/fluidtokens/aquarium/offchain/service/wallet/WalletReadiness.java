package com.fluidtokens.aquarium.offchain.service.wallet;

import org.springframework.stereotype.Component;

/**
 * ⛔ <b>Whether the local index holds every wallet UTxO — and until it does, nothing spends from the
 * wallet.</b>
 *
 * <p>The wallet credential is indexed only from {@code store.sync-start-*} onwards, so a node whose
 * wallet was funded before that point starts with a PARTIAL view of it, and a partial view understates
 * silently (officina yaci-store-index-scoping §5): coin selection, collateral and "can we afford this"
 * are then decided on UTxOs the index has never seen. {@link WalletSweepService} closes that gap with
 * one self-send, after which every wallet UTxO is an output the index watched being created.
 *
 * <p>The flag starts {@code false} and only {@link WalletSweepService} sets it, once. The three
 * processors ({@code ScheduledTransactionService}, {@code LiquidationExecutor},
 * {@code CompoundExecutor}) skip every cycle until it is {@code true}, exactly as they skip while
 * syncing.
 *
 * <p>The sweep state is reported on {@code /healthcheck} as {@code wallet_sweep}: {@code pending},
 * {@code swept <txHash>}, {@code refused <reason>}, {@code ready}, or {@link #IDLE} on a node where
 * no spending processor is enabled (the sweep does not run there, and nothing waits on the gate).
 */
@Component
public class WalletReadiness {

    public static final String PENDING = "pending";
    public static final String READY = "ready";
    /** The tank processor, liquidation (shadow or live) and compound are all off: no sweep is needed. */
    public static final String IDLE = "idle: no spending processor enabled";

    private volatile boolean walletReady;

    private volatile String sweepState = PENDING;

    /** True once every wallet UTxO Blockfrost lists is known to the local index. */
    public boolean isWalletReady() {
        return walletReady;
    }

    /** {@code pending}, {@code swept <txHash>}, {@code refused <reason>}, {@code ready} or {@link #IDLE}. */
    public String sweepState() {
        return sweepState;
    }

    void markReady() {
        sweepState = READY;
        walletReady = true;
    }

    void markIdle() {
        sweepState = IDLE;
    }

    /** Back to {@code pending} from {@link #IDLE}, when a spending processor has been enabled since. */
    void markPendingIfIdle() {
        if (IDLE.equals(sweepState)) {
            sweepState = PENDING;
        }
    }

    void markSwept(String txHash) {
        sweepState = "swept " + txHash;
    }

    void markRefused(String reason) {
        sweepState = "refused " + reason;
    }
}
