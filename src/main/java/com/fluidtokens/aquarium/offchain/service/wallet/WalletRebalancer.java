package com.fluidtokens.aquarium.offchain.service.wallet;

import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration.Action;
import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration.Market;
import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration.Mode;
import com.fluidtokens.aquarium.offchain.service.AppUtxoService;
import com.fluidtokens.aquarium.offchain.service.BlockEventListener;
import com.fluidtokens.aquarium.offchain.service.StageTally;
import com.fluidtokens.aquarium.offchain.service.SubmitFence;
import com.fluidtokens.aquarium.offchain.service.WalletPipelineService;
import com.fluidtokens.aquarium.offchain.service.WorkResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Keeps the bot wallet in {@link WalletShape}'s target shape (FAB-130) — the first production caller of
 * {@link WalletShapeTransactions}. It is the wallet pipeline's <b>housekeeping stage</b> (FAB-183): each
 * {@link #housekeep(SubmitFence)} asks the pure engine whether the wallet is out of shape and, if so, builds the
 * shaped self-send, signs it and submits it.
 *
 * <p>It is not a listener and has no timer. {@link WalletPipelineService} calls it, on the {@code wallet-pipeline}
 * thread, once per pass and only when neither the liquidation stage nor the compound stage sent anything in that
 * pass; a pass is triggered by a committed block or an oracle price change. Why that trigger runs after the block's
 * transaction commits, and how initial sync is kept out, is documented on {@link WalletPipelineService}.
 *
 * <h2>The gates, cheapest first (FAB-131, as Giovanni ruled them; FAB-188 removed the market gate)</h2>
 * <ol>
 *   <li>{@code loans.wallet.rebalance.enabled} (env {@code AQUARIUM_WALLET_REBALANCE_ENABLED}, default
 *       {@code true});</li>
 *   <li>not {@link #suspend() suspended} — in memory only; effective = enabled AND NOT suspended, so
 *       {@link #resume()} can never override the environment;</li>
 *   <li>{@code loans.liquidation.mode} is {@code SHADOW} or {@code LIVE}. ⚠ <b>SHADOW SUBMITS the
 *       rebalance</b>: it is wallet housekeeping, not a liquidation;</li>
 *   <li>the node is not syncing ({@link BlockEventListener#getIsSyncing()}).</li>
 * </ol>
 * <b>The markets do not arm it</b> (FAB-188): every shadow or live node keeps its wallet shaped, because every
 * liquidation route needs a collateral UTxO and an ada-only input, a convert-only node included. A closed gate
 * reads nothing and logs at DEBUG only — except that an environment-disabled or suspended rebalancer on a
 * {@code SHADOW} or {@code LIVE} node logs ONE WARN per pass, because liquidation depends on a shaped wallet.
 * A node in the shipped {@code DISABLED} mode behaves exactly as it did before this class existed.
 *
 * <p>The readiness page reads {@link #activity()} (FAB-132), and that is the SAME private evaluation of these
 * four gates that each {@link #housekeep(SubmitFence)} acts on: {@link Activity#armed()} is true exactly when the
 * stage would go on to read the wallet, and otherwise {@link Activity#idle()} names the first closed gate. The page
 * therefore cannot say "armed" over an idle rebalancer, nor give a reason the bot does not share.
 *
 * <h2>Once the gates are open</h2>
 * <ul>
 *   <li>The wallet is read from the local index ({@link AppUtxoService#listWalletUtxo()}). A read failure is an
 *       ERROR for this pass and nothing is built — never an empty wallet.</li>
 *   <li>The relevant set is {@link WalletShape#relevantUnits} over the {@code action: ANTICIPATE} markets that can
 *       liquidate — {@link AppConfig.LiquidationConfiguration#effectiveMode effectiveMode} (the market's own
 *       {@code mode}, capped by the node's) {@code SHADOW} or {@code LIVE} — computed once per pass, lower-cased,
 *       never {@code lovelace}. It may be empty (no such market): the wallet is still shaped, with no
 *       token-isolating output. A market with {@code mode: DISABLED} never liquidates, so its token is not
 *       relevant and goes in the other-tokens bundle (it would otherwise tie up 10 ADA in an isolated UTxO for
 *       nothing).</li>
 *   <li>No reason → nothing, at DEBUG. Otherwise {@link WalletShapeTransactions#buildShaped} spends the WHOLE
 *       wallet (no reference-script or datum filtering: the operator keeps the wallet free of them).</li>
 *   <li>Not built → WARN with the engine's detail and the reasons, <b>on every pass, unthrottled</b> (ruled:
 *       a wallet that cannot be shaped is the operator's problem). Never signed, never submitted, so a refusal
 *       costs no fee.</li>
 *   <li>Built → signed; then the pass's {@link SubmitFence} is read immediately before the submit. A closed
 *       fence (the chain rolled back, the pass deadline passed, or the pipeline closed) submits nothing and says
 *       so at INFO. An open one submits: INFO with the hash, the reasons and the fee, or ERROR with the
 *       rejection.</li>
 * </ul>
 * <b>There is no pending-transaction guard</b> (ruled): if the next block still shows the old wallet, the
 * same self-send is rebuilt and resubmitted, and the duplicate fails harmlessly at the ledger.
 *
 * <h2>What a pass is told</h2>
 * {@link #housekeep(SubmitFence)} returns {@link WorkResult#SUBMITTED} when the submit succeeded,
 * {@link WorkResult#classify} of the {@code Result} when it was refused, and {@link WorkResult#AMBIGUOUS} when
 * the submit threw (the bytes may have reached Blockfrost). Every other ending — a closed gate, a wallet in
 * shape, a refusal to build, a failed read, a closed fence, or an exception before the submit — sent nothing
 * and is {@link WorkResult#DEFINITE_FAILURE}.
 *
 * <h2>What the pass line is told (FAB-195)</h2>
 * {@link #housekeep(SubmitFence, StageTally)} also counts at most one ending per pass on the stage's tally, for the
 * pipeline's summary line only: a submit that went out (an accepted one, a rejection {@link WorkResult#classify}
 * calls AMBIGUOUS, or a submit that threw) is {@code submitted}; a decoded rejection is {@code attempted}; a failed
 * or null wallet read, or a failure the catch-all caught, is {@code error}; a build that was not built, or a closed
 * fence, is {@code no-op}. A closed gate and a wallet in shape count nothing. An {@code error} the stage itself
 * caught is said in one ERROR line with its cause chain, and its stack trace goes to DEBUG.
 *
 * <h2>What the last pass did</h2>
 * Every ending of {@link #housekeep(SubmitFence)} is also recorded in {@link #lastOutcome()} (FAB-188), a one-line
 * text whose prefix names the ending, so another component can quote what the rebalancer last did.
 *
 * <h2>Never throws</h2>
 * The whole body sits behind a catch-all that logs at ERROR — one line with the cause chain, the stack trace at
 * DEBUG — so a failure ends this stage for this pass and nothing more: the pipeline's next trigger tries again.
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

    /** Why the rebalancer is idle: its first closed gate, in gate order. */
    public enum Idle { ENV_DISABLED, SUSPENDED, MODE_NOT_SHADOW_OR_LIVE, SYNCING }

    /**
     * What the rebalancer would do on the next {@link #housekeep(SubmitFence)}, as the gates read now.
     *
     * @param state exactly what {@link #state()} returns
     * @param mode  {@code loans.liquidation.mode} as the mode supplier gives it; may be null
     * @param idle  the first closed gate, or null when every gate is open
     */
    public record Activity(State state, Mode mode, Idle idle) {

        /** Every gate is open: the next pass reads the wallet and rebalances it if it is out of shape. */
        public boolean armed() {
            return idle == null;
        }
    }

    /**
     * One evaluation of the gates: the first closed one, or — every gate open — the ANTICIPATE markets that can
     * liquidate (possibly none), the source of the relevant set.
     */
    private record Gates(State state, Mode mode, Idle idle, List<Market> anticipating) {
    }

    /** {@link #lastOutcome()} before the first {@link #housekeep(SubmitFence)}. */
    static final String NOT_RUN_YET = "not run yet";

    private final WalletShapeTransactions engine;

    private final Supplier<List<Utxo>> walletRead;

    private final BooleanSupplier syncing;

    private final Supplier<Mode> mode;

    private final Supplier<List<Market>> markets;

    private final Function<Market, Mode> effectiveMode;

    private final boolean envEnabled;

    private final AtomicBoolean suspended = new AtomicBoolean(false);

    private volatile String lastOutcome = NOT_RUN_YET;

    @Autowired
    public WalletRebalancer(WalletShapeTransactions engine,
                            AppUtxoService appUtxoService,
                            BlockEventListener blockEventListener,
                            AppConfig.LiquidationConfiguration liquidationConfiguration,
                            @Value("${loans.wallet.rebalance.enabled:true}") boolean envEnabled) {
        this(engine, appUtxoService::listWalletUtxo, () -> blockEventListener.getIsSyncing().get(),
                liquidationConfiguration::getMode, liquidationConfiguration::getMarkets,
                liquidationConfiguration::effectiveMode, envEnabled);
    }

    /** The seams, so the gates and the outcomes can be driven without Spring. */
    WalletRebalancer(WalletShapeTransactions engine,
                     Supplier<List<Utxo>> walletRead,
                     BooleanSupplier syncing,
                     Supplier<Mode> mode,
                     Supplier<List<Market>> markets,
                     Function<Market, Mode> effectiveMode,
                     boolean envEnabled) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.walletRead = Objects.requireNonNull(walletRead, "walletRead");
        this.syncing = Objects.requireNonNull(syncing, "syncing");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.markets = Objects.requireNonNull(markets, "markets");
        this.effectiveMode = Objects.requireNonNull(effectiveMode, "effectiveMode");
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

    /** The gates as they read now, for the readiness page. Reads no wallet; never throws on a null mode or market list. */
    public Activity activity() {
        Gates gates = gates();
        return new Activity(gates.state(), gates.mode(), gates.idle());
    }

    /**
     * What the last {@link #housekeep(SubmitFence)} ended with, one line, never null. Its prefix names the ending:
     * {@code not run yet}, {@code idle: <IDLE>}, {@code could not read the wallet: <cause>},
     * {@code in shape (<n> UTxOs)}, {@code <STATUS>: <detail>} for a build that was not built,
     * {@code fenced: nothing submitted}, {@code submitted <txHash>}, {@code submission rejected: <response>},
     * {@code submission threw: <cause>}, or {@code failed: <cause>}.
     */
    public String lastOutcome() {
        return lastOutcome;
    }

    /**
     * The housekeeping stage: one pass's worth of work, behind {@code fence}. Never throws.
     *
     * @return {@link WorkResult#SUBMITTED}, the classified refusal, {@link WorkResult#AMBIGUOUS} when the submit
     *         threw, or {@link WorkResult#DEFINITE_FAILURE} for every ending that sent nothing
     */
    public WorkResult housekeep(SubmitFence fence) {
        return housekeep(fence, new StageTally());
    }

    /**
     * {@link #housekeep(SubmitFence)}, counting this pass's ending on {@code tally} (FAB-195) — the wallet
     * pipeline's own call. The answer is exactly the one-argument form's.
     */
    public WorkResult housekeep(SubmitFence fence, StageTally tally) {
        try {
            return rebalanceIfNeeded(fence, tally);
        } catch (Exception e) {
            tally.recordError();
            log.error("[REBALANCE] failed on this block; nothing further is attempted until the next one: {}",
                    causeChain(e));
            log.debug("[REBALANCE] failed on this block", e);
            lastOutcome = "failed: " + e;
            return WorkResult.DEFINITE_FAILURE;
        }
    }

    private static void logFailure(Exception e) {
        log.error("[REBALANCE] failed on this block; nothing further is attempted until the next one: {}",
                e.toString(), e);
    }

    /**
     * The whole cause chain, wrapper first — {@code Class: message ⇐ Class: message ⇐ …} — the form the liquidation
     * and compound stages log theirs in. Bounded against a cyclic chain; a message-less exception still contributes
     * its class name.
     */
    static String causeChain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable c = t;
        for (int depth = 0; c != null && depth < 12; depth++) {
            if (sb.length() > 0) {
                sb.append(" ⇐ ");
            }
            sb.append(c.getClass().getSimpleName());
            if (c.getMessage() != null) {
                sb.append(": ").append(c.getMessage());
            }
            Throwable next = c.getCause();
            if (next == c) {
                break;
            }
            c = next;
        }
        return sb.toString();
    }

    /**
     * The four gates, cheapest first, stopping at the first closed one — the ONE evaluation both
     * {@link #activity()} and {@link #rebalanceIfNeeded(SubmitFence)} take their decision from.
     */
    private Gates gates() {
        State state = state();
        Mode current = mode.get();
        if (!state.envEnabled()) {
            return new Gates(state, current, Idle.ENV_DISABLED, List.of());
        }
        if (state.suspended()) {
            return new Gates(state, current, Idle.SUSPENDED, List.of());
        }
        if (current != Mode.SHADOW && current != Mode.LIVE) {
            return new Gates(state, current, Idle.MODE_NOT_SHADOW_OR_LIVE, List.of());
        }
        if (syncing.getAsBoolean()) {
            return new Gates(state, current, Idle.SYNCING, List.of());
        }
        return new Gates(state, current, null, anticipatingMarkets(markets.get()));
    }

    private WorkResult rebalanceIfNeeded(SubmitFence fence, StageTally tally) throws Exception {
        Gates gates = gates();
        if (gates.idle() != null) {
            lastOutcome = "idle: " + gates.idle().name();
            boolean liquidating = gates.mode() == Mode.SHADOW || gates.mode() == Mode.LIVE;
            switch (gates.idle()) {
                case ENV_DISABLED, SUSPENDED -> {
                    if (liquidating) {
                        // FAB-188 (B1): the node can liquidate, and liquidation depends on a shaped wallet.
                        log.warn("[REBALANCE] {}: wallet rebalancing is off on a {} node, and liquidation depends "
                                + "on a shaped wallet; nothing rebalanced", gates.idle(), gates.mode());
                    } else if (gates.idle() == Idle.ENV_DISABLED) {
                        log.debug("[REBALANCE] skipped: loans.wallet.rebalance.enabled is false");
                    } else {
                        log.debug("[REBALANCE] skipped: suspended");
                    }
                }
                case MODE_NOT_SHADOW_OR_LIVE -> log.debug("[REBALANCE] skipped: loans.liquidation.mode is {}",
                        gates.mode());
                case SYNCING -> log.debug("[REBALANCE] skipped: the node is syncing");
            }
            return WorkResult.DEFINITE_FAILURE;
        }
        List<Market> anticipating = gates.anticipating();

        Set<String> relevant = WalletShape.relevantUnits(anticipating);
        List<Utxo> wallet;
        try {
            wallet = walletRead.get();
        } catch (RuntimeException e) {
            tally.recordError();
            log.error("[REBALANCE] could not read the wallet from the index; nothing built for this block: {}",
                    causeChain(e));
            log.debug("[REBALANCE] could not read the wallet from the index", e);
            lastOutcome = "could not read the wallet: " + e;
            return WorkResult.DEFINITE_FAILURE;
        }
        if (wallet == null) {
            tally.recordError();
            log.error("[REBALANCE] the wallet read returned no list; nothing built for this block");
            lastOutcome = "could not read the wallet: the read returned no list";
            return WorkResult.DEFINITE_FAILURE;
        }

        EnumSet<WalletShape.Reason> reasons = WalletShape.reasons(wallet, relevant, engine.collateralLovelace());
        if (reasons.isEmpty()) {
            log.debug("[REBALANCE] the wallet is in shape ({} UTxOs, relevant {})", wallet.size(), relevant);
            lastOutcome = "in shape (" + wallet.size() + " UTxOs)";
            return WorkResult.DEFINITE_FAILURE;
        }

        WalletShapeTransactions.Outcome outcome = engine.buildShaped(wallet, relevant);
        if (!outcome.isBuilt()) {
            log.warn("[REBALANCE] {}: {} — the wallet is out of shape {}, relevant {}; nothing signed or submitted",
                    outcome.status(), outcome.detail(), reasons, relevant);
            lastOutcome = outcome.status() + ": " + outcome.detail();
            tally.recordNoOp();
            return WorkResult.DEFINITE_FAILURE;
        }

        Transaction signed = engine.sign(outcome.transaction());
        // Read before the submit, so nothing after a submit can throw and report it as never sent.
        BigInteger fee = outcome.transaction().getBody().getFee();
        if (!fence.mayProceed()) {
            log.info("[REBALANCE] fenced: nothing submitted");
            lastOutcome = "fenced: nothing submitted";
            tally.recordNoOp();
            return WorkResult.DEFINITE_FAILURE;
        }
        Result<String> result;
        try {
            result = engine.submit(signed);
        } catch (Exception e) {
            // The bytes may have reached Blockfrost before it threw.
            logFailure(e);
            lastOutcome = "submission threw: " + e;
            tally.recordSubmitted();
            return WorkResult.AMBIGUOUS;
        }
        if (result != null && result.isSuccessful()) {
            log.info("[REBALANCE] submitted {} — reasons {}, relevant {}, fee {} lovelace",
                    result.getValue(), reasons, relevant, fee);
            lastOutcome = "submitted " + result.getValue();
            tally.recordSubmitted();
            return WorkResult.SUBMITTED;
        }
        String rejection = result == null ? "no result" : result.getResponse();
        log.error("[REBALANCE] submission rejected — reasons {}, relevant {}, fee {} lovelace: {}",
                reasons, relevant, fee, rejection);
        lastOutcome = "submission rejected: " + rejection;
        WorkResult rejected = WorkResult.classify(result);
        if (rejected == WorkResult.DEFINITE_FAILURE) {
            tally.recordAttempted();
        } else {
            // AMBIGUOUS: no clear answer, so it may have gone out — said as submitted (a display merge only).
            tally.recordSubmitted();
        }
        return rejected;
    }

    /**
     * The {@code action: ANTICIPATE} markets that can liquidate: effective mode {@code SHADOW} or {@code LIVE}. The
     * source of the relevant set, never a gate: empty (or a null market list) shapes the wallet with no token
     * isolated.
     */
    private List<Market> anticipatingMarkets(List<Market> configured) {
        if (configured == null) {
            return List.of();
        }
        return configured.stream()
                .filter(Objects::nonNull)
                .filter(m -> m.getAction() == Action.ANTICIPATE)
                .filter(m -> {
                    Mode effective = effectiveMode.apply(m);
                    return effective == Mode.SHADOW || effective == Mode.LIVE;
                })
                .toList();
    }
}
