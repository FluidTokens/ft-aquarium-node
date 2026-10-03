package com.fluidtokens.aquarium.offchain.service.wallet;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.yaci.store.events.internal.CommitEvent;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.UtxoId;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.conversions.CardanoConverters;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * ⛔ <b>The startup wallet rebalance: ONE comparison of the wallet Blockfrost lists against the local
 * index, near tip, and at most ONE self-send to close the gap.</b> Giovanni, first-hand, FAB-134,
 * ruled 2026-10-03: <i>"The first rebalance triggers once we are within 5 minutes of tip. That first
 * trigger calls Blockfrost ONE time, compares with the local UTxOs and rebalances if required. No other
 * commit event fetches Blockfrost. Even if the rebalance fails we don't hold anything back: log the
 * error and continue. Processing of events must wait until we are within 5 minutes of tip."</i>
 *
 * <h2>Why</h2>
 * The wallet credential is indexed only from {@code store.sync-start-*} onwards, so UTxOs created
 * before it are invisible to the index — and a partial wallet view understates silently (officina
 * yaci-store-index-scoping §5). One self-send of the WHOLE wallet turns every one of them into an
 * output the index watched being created. Until the one-shot has finished {@link WalletReadiness} stays
 * closed and the three processors skip their cycles.
 *
 * <h2>The state machine, driven by Yaci commit events, once per process</h2>
 * <ol>
 *   <li><b>Waiting for tip.</b> The event's slot is converted to its block time with
 *       {@link CardanoConverters}. A block more than {@link #NEAR_TIP} older than the clock: nothing —
 *       no Blockfrost, no index read. (This is the sweep's OWN five-minute check; the syncing flag of
 *       {@code BlockEventListener} uses ten.)</li>
 *   <li><b>Near tip, nothing spends</b> — the tank processor, liquidation (SHADOW counts as well as
 *       LIVE) and compound are all off: no listing; the state reads {@link WalletReadiness#IDLE} and the
 *       gate opens, so the readiness page still shows the wallet. The predicate's inputs are startup
 *       configuration, so deciding once is exact.</li>
 *   <li><b>Near tip, something spends: the ONE Blockfrost read.</b> The base address AND the enterprise
 *       address of the bot's payment key, every page (a short page ends an address; a 404 is
 *       Blockfrost's answer for an address the chain has never seen, i.e. empty). The settle slot is
 *       {@code toSlot(t0)}, t0 being when the listing COMPLETED. A failed listing is logged, shown as
 *       {@code done: listing failed …}, and the gate opens; nothing is ever listed again.</li>
 *   <li><b>Settling.</b> Nothing is compared until a commit event at or after the settle slot, so the
 *       index has applied everything Blockfrost had seen when it answered.</li>
 *   <li><b>Compare and act, once.</b> A listed UTxO is <i>missing</i> when {@code UtxoRepository.findById}
 *       has no row for it (spent rows count as known — the index saw it). Nothing missing: done. Something
 *       missing: ONE self-send spending the whole listing — {@code buildShaped} first,
 *       {@code buildConsolidation} if that is refused — signed, its SIGNED size checked against
 *       {@code maxTxSize} (a vkey witness adds about 100 bytes; no limit fails closed), and submitted.
 *       Whatever happens the outcome is logged (ERROR for every failure), shown as {@code done: …}, and
 *       the gate opens. No retry, no second build, no second submit.</li>
 *   <li><b>Done.</b> Every later event is a no-op; the gate never re-closes.</li>
 * </ol>
 *
 * <p>No exception ever leaves {@link #onCommit(long)}: it would otherwise propagate into Yaci's event
 * dispatch. Once near tip, an exception ends the one-shot as {@code done: rebalance failed: …} with the
 * gate open; before near tip it is logged and the sweep keeps waiting, so the gate never opens early.
 *
 * <h2>Enterprise-address UTxOs</h2>
 * A UTxO at the enterprise address is listed, judged and spent like any other, and the sweep moves it
 * into the base address, where every output of the engine goes.
 */
@Component
@Slf4j
public class WalletSweepService {

    /**
     * One page of Blockfrost's {@code /addresses/{address}/utxos}: the listing seam. Production is
     * {@code bf.getUtxoService().getUtxos(address, 100, page)}; pages are 1-based.
     */
    @FunctionalInterface
    public interface WalletLister {

        Result<List<Utxo>> page(String address, int page) throws Exception;
    }

    /** Blockfrost's maximum page size; a page shorter than this is the last one. */
    static final int PAGE_SIZE = 100;

    /** A block at most this old (against the clock) is near tip. */
    static final Duration NEAR_TIP = Duration.ofMinutes(5);

    /** Blockfrost's answer for an address the chain has never seen: an empty listing, not an outage. */
    private static final int NOT_FOUND = 404;

    private enum Phase { WAITING_FOR_TIP, SETTLING, DONE }

    /** The one listing, or why it failed. */
    private record Listing(List<Utxo> utxos, String failure) {
    }

    private final UtxoRepository utxoRepository;
    private final WalletReadiness readiness;
    private final WalletShapeTransactions engine;
    private final ProtocolParamsSupplier protocolParamsSupplier;
    private final Supplier<List<AppConfig.LiquidationConfiguration.Market>> markets;
    private final CardanoConverters converters;
    private final WalletLister lister;
    private final Clock clock;
    private final List<String> addresses;
    private final BooleanSupplier somethingSpends;

    private Phase phase = Phase.WAITING_FOR_TIP;
    private List<Utxo> listing;
    private long settleSlot;

    /** The wiring Spring uses: Blockfrost is narrowed to a page lister and a byte submitter here. */
    @Autowired
    public WalletSweepService(UtxoRepository utxoRepository,
                              WalletReadiness readiness,
                              Account account,
                              AppConfig.Network network,
                              AppConfig.LiquidationConfiguration liquidationConfiguration,
                              AppConfig.CompoundConfiguration compoundConfiguration,
                              @Value("${scheduling.transaction-processor.enabled:false}") boolean tankProcessorEnabled,
                              ProtocolParamsSupplier protocolParamsSupplier,
                              CardanoConverters converters,
                              BFBackendService backendService) {
        this(utxoRepository, readiness, account, network.getCardanoNetwork(),
                liquidationConfiguration::getMarkets, protocolParamsSupplier, converters,
                (address, page) -> backendService.getUtxoService().getUtxos(address, PAGE_SIZE, page),
                bytes -> backendService.getTransactionService().submitTransaction(bytes),
                Clock.systemUTC(),
                () -> somethingSpends(tankProcessorEnabled, liquidationConfiguration, compoundConfiguration));
    }

    /** Every seam stated, so a test can drive the listing, the wire and the clock. */
    public WalletSweepService(UtxoRepository utxoRepository,
                              WalletReadiness readiness,
                              Account account,
                              Network network,
                              Supplier<List<AppConfig.LiquidationConfiguration.Market>> markets,
                              ProtocolParamsSupplier protocolParamsSupplier,
                              CardanoConverters converters,
                              WalletLister lister,
                              WalletShapeTransactions.TransactionSubmitter submitter,
                              Clock clock,
                              BooleanSupplier somethingSpends) {
        this.utxoRepository = Objects.requireNonNull(utxoRepository, "utxoRepository");
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.engine = new WalletShapeTransactions(account, protocolParamsSupplier, submitter);
        this.protocolParamsSupplier = protocolParamsSupplier;
        this.markets = Objects.requireNonNull(markets, "markets");
        this.converters = Objects.requireNonNull(converters, "converters");
        this.lister = Objects.requireNonNull(lister, "lister");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.somethingSpends = Objects.requireNonNull(somethingSpends, "somethingSpends");
        String enterprise = AddressProvider.getEntAddress(
                account.getBaseAddress().getPaymentCredential().orElseThrow(), network).toBech32();
        this.addresses = List.of(account.baseAddress(), enterprise);
    }

    /**
     * Whether anything on this node spends from the wallet: the tank processor (its bean exists only
     * when {@code scheduling.transaction-processor.enabled}), liquidation in any mode but
     * {@code DISABLED} — SHADOW counts, a shadow node prepares its wallet too — or compound armed.
     * The two configuration objects are the ones the executors read.
     */
    static boolean somethingSpends(boolean tankProcessorEnabled,
                                   AppConfig.LiquidationConfiguration liquidation,
                                   AppConfig.CompoundConfiguration compound) {
        AppConfig.LiquidationConfiguration.Mode mode = liquidation == null ? null : liquidation.getMode();
        boolean liquidating = mode != null && mode != AppConfig.LiquidationConfiguration.Mode.DISABLED;
        boolean compounding = compound != null && compound.isEnabled();
        return tankProcessorEnabled || liquidating || compounding;
    }

    /** What this sweep's predicate answers now. */
    boolean somethingSpends() {
        return somethingSpends.getAsBoolean();
    }

    /** The base address and the enterprise address of the bot's payment key, in listing order. */
    List<String> listedAddresses() {
        return addresses;
    }

    /** Yaci's commit event: the slot of the block just applied drives the state machine. */
    @EventListener
    public void onCommitEvent(CommitEvent<?> event) {
        try {
            onCommit(event.getMetadata().getSlot());
        } catch (Exception e) {
            log.error("wallet sweep: could not read the commit event's slot: {}", e.toString(), e);
        }
    }

    /**
     * One applied block. Never throws an {@link Exception}. An {@link Error} near tip still ends the one-shot
     * (DONE, gate open) BEFORE it propagates, so a block Yaci rolls back cannot replay a compare or a submit.
     */
    public synchronized void onCommit(long slot) {
        if (phase == Phase.DONE) {
            return;
        }
        boolean nearTip = phase != Phase.WAITING_FOR_TIP;
        try {
            if (phase == Phase.WAITING_FOR_TIP) {
                if (!nearTip(slot)) {
                    return;
                }
                nearTip = true;
                atTip();
            } else if (slot >= settleSlot) {
                compareAndAct();
            }
        } catch (Throwable e) {
            if (!nearTip) {
                log.error("wallet sweep: could not tell whether slot {} is near tip; still waiting: {}",
                        slot, e.toString(), e);
            } else {
                log.error("wallet sweep FAILED: {}; the processors are released anyway, and the rebalance is not "
                        + "tried again in this process", e.toString(), e);
                done("rebalance failed: " + e);
            }
            if (e instanceof Error error) {
                throw error;
            }
        }
    }

    /** The block's own time, from the slot, against the clock: at most {@link #NEAR_TIP} old. */
    private boolean nearTip(long slot) {
        Instant blockTime = converters.slot().slotToTime(slot).toInstant(ZoneOffset.UTC);
        return !blockTime.isBefore(clock.instant().minus(NEAR_TIP));
    }

    private void atTip() throws Exception {
        if (!somethingSpends.getAsBoolean()) {
            log.info("wallet sweep: near tip and no spending processor is enabled; nothing to prepare");
            phase = Phase.DONE;
            readiness.markDone(WalletReadiness.IDLE.substring("done: ".length()));
            return;
        }
        Listing listed = listWallet();
        if (listed.failure() != null) {
            log.error("wallet sweep FAILED: listing the wallet failed ({}); the processors are released anyway, "
                    + "and the wallet is not listed again in this process", listed.failure());
            done("listing failed: " + listed.failure());
            return;
        }
        // t0 is when Blockfrost has ANSWERED: the index must reach that slot, not the one the listing began at.
        listing = listed.utxos();
        settleSlot = toSlot(clock.instant());
        phase = Phase.SETTLING;
        readiness.markSettling();
        log.info("wallet sweep: listed {} wallet UTxOs; comparing once the index applies slot {}",
                listing.size(), settleSlot);
    }

    private void compareAndAct() throws Exception {
        List<String> missing = new ArrayList<>();
        for (Utxo utxo : listing) {
            if (utxoRepository.findById(new UtxoId(utxo.getTxHash(), utxo.getOutputIndex())).isEmpty()) {
                missing.add(utxo.getTxHash() + "#" + utxo.getOutputIndex());
            }
        }
        if (missing.isEmpty()) {
            log.info("wallet sweep: all {} wallet UTxOs Blockfrost listed are in the local index; nothing to "
                    + "rebalance", listing.size());
            done(WalletReadiness.NOTHING_TO_REBALANCE.substring("done: ".length()));
            return;
        }
        rebalance(missing);
    }

    private void rebalance(List<String> missing) throws Exception {
        log.warn("wallet sweep: {} of {} listed wallet UTxOs are not in the local index {}; sweeping the WHOLE "
                + "wallet in one self-send", missing.size(), listing.size(), missing);

        WalletShapeTransactions.Outcome outcome =
                engine.buildShaped(listing, WalletShape.relevantUnits(markets.get()));
        if (!outcome.isBuilt()) {
            log.warn("wallet sweep: the shaped self-send was refused ({}); falling back to a consolidation",
                    outcome.detail());
            outcome = engine.buildConsolidation(listing);
            if (!outcome.isBuilt()) {
                failed("the consolidation was refused too (" + outcome.detail() + ")");
                return;
            }
        }

        Transaction signed = engine.sign(outcome.transaction());
        String sizeProblem = signedSizeProblem(signed);
        if (sizeProblem != null) {
            failed(sizeProblem);
            return;
        }

        String txHash = TransactionUtil.getTxHash(signed);
        Result<String> result;
        try {
            result = engine.submit(signed);
        } catch (Exception e) {
            log.error("wallet sweep: submit of {} threw; its outcome is unknown", txHash, e);
            failed("submit outcome unknown for " + txHash + ": " + e);
            return;
        }
        if (result == null || !result.isSuccessful()) {
            failed("submit rejected for " + txHash + ": " + (result == null ? "no result" : result.getResponse()));
            return;
        }
        log.info("wallet sweep: submitted {} ({}), spending all {} listed wallet UTxOs; the processors are released",
                txHash, outcome.detail(), listing.size());
        done("rebalanced " + txHash);
    }

    private void failed(String reason) {
        log.error("wallet sweep FAILED: {}. Nothing more is submitted in this process; the processors are "
                + "released anyway", reason);
        done("rebalance failed: " + reason);
    }

    private void done(String detail) {
        phase = Phase.DONE;
        listing = null;
        readiness.markDone(detail);
    }

    /** Why the SIGNED transaction is too large for the ledger, or null. Fails closed without a limit. */
    private String signedSizeProblem(Transaction signed) throws Exception {
        ProtocolParams params = protocolParamsSupplier.getProtocolParams();
        if (params == null || params.getMaxTxSize() == null) {
            return "protocol parameters carry no maxTxSize: refusing to guess the ledger's limit";
        }
        int size = signed.serialize().length;
        if (size > params.getMaxTxSize()) {
            return "signed transaction too large: " + size + " bytes > maxTxSize " + params.getMaxTxSize();
        }
        return null;
    }

    /** The whole wallet, every page of both addresses — or why a page failed. */
    private Listing listWallet() {
        List<Utxo> all = new ArrayList<>();
        for (String address : addresses) {
            for (int page = 1; ; page++) {
                Result<List<Utxo>> result;
                try {
                    result = lister.page(address, page);
                } catch (Exception e) {
                    return new Listing(null, address + " page " + page + ": " + e);
                }
                if (result == null) {
                    return new Listing(null, address + " page " + page + " returned nothing");
                }
                if (!result.isSuccessful()) {
                    if (result.code() == NOT_FOUND) {
                        break;
                    }
                    return new Listing(null, address + " page " + page + " HTTP " + result.code() + ": "
                            + result.getResponse());
                }
                List<Utxo> items = result.getValue() == null ? List.of() : result.getValue();
                all.addAll(items);
                if (items.size() < PAGE_SIZE) {
                    break;
                }
            }
        }
        return new Listing(all, null);
    }

    private long toSlot(Instant instant) {
        return converters.time().toSlot(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
    }
}
