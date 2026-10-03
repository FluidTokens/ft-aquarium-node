package com.fluidtokens.aquarium.offchain.service.wallet;

import org.springframework.stereotype.Component;

/**
 * ⛔ <b>Whether the startup wallet rebalance has had its one chance — and until it has, nothing spends
 * from the wallet.</b>
 *
 * <p>The wallet credential is indexed only from {@code store.sync-start-*} onwards, so a node whose
 * wallet was funded before that point starts with a PARTIAL view of it, and a partial view understates
 * silently (officina yaci-store-index-scoping §5): coin selection, collateral and "can we afford this"
 * are then decided on UTxOs the index has never seen. {@link WalletSweepService} compares the wallet
 * Blockfrost lists with the local index ONCE, near tip, and closes any gap with one self-send.
 *
 * <p>The flag starts {@code false} and only {@link WalletSweepService} sets it, once, when that one-shot
 * has finished — in ANY outcome, failures included (Giovanni, 2026-10-03: a failed rebalance is logged
 * and processing continues). It never re-closes. The three processors ({@code ScheduledTransactionService},
 * {@code LiquidationExecutor}, {@code CompoundExecutor}) skip every cycle until it is {@code true}.
 *
 * <p>The state is reported on {@code /healthcheck} as {@code wallet_sweep}: {@link #WAITING_FOR_TIP},
 * {@link #SETTLING}, then one of the {@code done: …} variants — {@link #NOTHING_TO_REBALANCE},
 * {@code done: rebalanced <txHash>}, {@code done: rebalance failed: <reason>},
 * {@code done: listing failed: <reason>} or {@link #IDLE} (no spending processor enabled: nothing is
 * listed, and the gate opens so the readiness page can show the wallet).
 */
@Component
public class WalletReadiness {

    /** Blocks are still more than five minutes old: nothing has been listed or compared yet. */
    public static final String WAITING_FOR_TIP = "waiting for tip";
    /** Listed once; waiting for the index to apply a block at or after the moment the listing completed. */
    public static final String SETTLING = "settling";
    /** Every listed UTxO already had an index row. */
    public static final String NOTHING_TO_REBALANCE = "done: nothing to rebalance";
    /** The tank processor, liquidation (shadow or live) and compound are all off: nothing is listed. */
    public static final String IDLE = "done: idle, no spending processor enabled";

    private static final String DONE = "done: ";

    private volatile boolean walletReady;

    private volatile String sweepState = WAITING_FOR_TIP;

    /** True once the one-shot rebalance has finished, whatever its outcome. */
    public boolean isWalletReady() {
        return walletReady;
    }

    /** {@link #WAITING_FOR_TIP}, {@link #SETTLING} or a {@code done: …} variant. */
    public String sweepState() {
        return sweepState;
    }

    void markSettling() {
        sweepState = SETTLING;
    }

    /** The one-shot is over: {@code done: <detail>}, and the gate opens for good. */
    void markDone(String detail) {
        sweepState = DONE + detail;
        walletReady = true;
    }
}
