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
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.UtxoId;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.service.BlockEventListener;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.conversions.CardanoConverters;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * ⛔ <b>The startup wallet sweep: get every wallet UTxO into the local index before anything spends
 * from the wallet.</b> Runs only on a node where something spends (Giovanni, first-hand, FAB-134,
 * ruled 2026-10-03): the tank processor, liquidation (SHADOW counts as well as LIVE) or compound.
 *
 * <h2>Why</h2>
 * The wallet credential is indexed only from {@code store.sync-start-*} onwards, so UTxOs created
 * before it are invisible to the index — and a partial wallet view understates silently (officina
 * yaci-store-index-scoping §5). One self-send of the WHOLE wallet turns every one of them into an
 * output the index watched being created. Until that has happened {@link WalletReadiness} stays closed
 * and the three processors skip their cycles.
 *
 * <h2>The flow, one step per tick</h2>
 * <ol>
 *   <li>While nothing spends: nothing — no listing, no Blockfrost call, no build, no submit — and the
 *       state reads {@link WalletReadiness#IDLE}. The gate stays closed; nothing reads it. The predicate
 *       is asked afresh on every tick, off the same configuration objects the executors read, so a
 *       processor enabled later is seen on the next tick, which starts again from an unsettled
 *       observation.</li>
 *   <li>While syncing: nothing — no listing call.</li>
 *   <li>List the wallet from Blockfrost afresh: the base address AND the enterprise address of the
 *       bot's payment key, every page (a short page ends an address; a 404 is Blockfrost's answer for an
 *       address the chain has never seen, i.e. empty).</li>
 *   <li><b>Settle before comparing.</b> The listing's time t0 is recorded and nothing is compared until
 *       the index has applied a block at or after {@code toSlot(t0)}. Only the UTxOs listed at t0 AND
 *       still listed now are judged — so an output that is merely fresh is never mistaken for a missing
 *       one. The refs of that one observation are the only thing carried from tick to tick; nothing is
 *       ever built from them, and every build spends the listing of its own tick.</li>
 *   <li>A judged UTxO is <i>missing</i> when {@code UtxoRepository.findById} has no row for it (spent
 *       rows count as known — the index saw it).</li>
 *   <li>Nothing missing (and the last sweep's outputs indexed): {@code walletReady = true}; the poller
 *       does nothing from then on. A listed output of the last sweep is judged DIRECTLY, whatever the
 *       previous listing held: while any of them has no index row the gate stays closed, and a sweep that
 *       lands between two ticks is waited for, not resubmitted. Only a sweep none of whose outputs is
 *       listed (it never landed) may be given up on once its window has closed.</li>
 *   <li>Something missing: ONE self-send spending the whole listing — {@code buildShaped} first,
 *       {@code buildConsolidation} if that is refused — signed, its SIGNED size checked against
 *       {@code maxTxSize} (the engine measured the unsigned body; a vkey witness adds about 100 bytes),
 *       and submitted.</li>
 *   <li>No second submit for {@link #CONVERGENCE_WINDOW} after a submit. If the wallet has not converged
 *       by then, the next comparison rebuilds and resubmits from a fresh listing (a harmless resubmit is
 *       acceptable, by ruling).</li>
 * </ol>
 *
 * <h2>Every ambiguous case means no submit</h2>
 * Syncing, not yet settled, a listing failure, both builds refused, an oversize signed transaction, and
 * any exception: no submit, the gate stays closed, the next tick tries again. A listing failure is shown
 * on the state as {@code refused listing failed: …}. No exception ever leaves {@link #tick()}: one
 * escaping a {@code @Scheduled} method is only logged by the scheduler, so it is caught and logged here,
 * with its cause, instead.
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

    /** How long a submitted sweep is given to converge before a rebuild and resubmit. */
    static final Duration CONVERGENCE_WINDOW = Duration.ofMinutes(10);

    /** Blockfrost's answer for an address the chain has never seen: an empty listing, not an outage. */
    private static final int NOT_FOUND = 404;

    /** The refs listed at one instant, and the slot the index must reach before they are judged. */
    private record Observation(long settleSlot, Set<String> refs) {
    }

    /** The last submitted sweep: its hash, how many outputs it has, and when it was submitted. */
    private record Sweep(String txHash, int outputCount, Instant submittedAt) {
    }

    private final BlockEventListener blockEventListener;
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

    private Observation observation;
    private Sweep lastSweep;

    /** The wiring Spring uses: Blockfrost is narrowed to a page lister and a byte submitter here. */
    @Autowired
    public WalletSweepService(BlockEventListener blockEventListener,
                              UtxoRepository utxoRepository,
                              WalletReadiness readiness,
                              Account account,
                              AppConfig.Network network,
                              AppConfig.LiquidationConfiguration liquidationConfiguration,
                              AppConfig.CompoundConfiguration compoundConfiguration,
                              @Value("${scheduling.transaction-processor.enabled:false}") boolean tankProcessorEnabled,
                              ProtocolParamsSupplier protocolParamsSupplier,
                              CardanoConverters converters,
                              BFBackendService backendService) {
        this(blockEventListener, utxoRepository, readiness, account, network.getCardanoNetwork(),
                liquidationConfiguration::getMarkets, protocolParamsSupplier, converters,
                (address, page) -> backendService.getUtxoService().getUtxos(address, PAGE_SIZE, page),
                bytes -> backendService.getTransactionService().submitTransaction(bytes),
                Clock.systemUTC(),
                () -> somethingSpends(tankProcessorEnabled, liquidationConfiguration, compoundConfiguration));
    }

    /** Every seam stated, so a test can drive the listing, the wire and the clock. */
    public WalletSweepService(BlockEventListener blockEventListener,
                              UtxoRepository utxoRepository,
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
        this.blockEventListener = Objects.requireNonNull(blockEventListener, "blockEventListener");
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
     * The two configuration objects are the ones the executors read, so this is asked on every tick.
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

    @Scheduled(timeUnit = TimeUnit.SECONDS, fixedDelayString = "${loans.wallet.sweep.delay-seconds:30}")
    public void tick() {
        try {
            step();
        } catch (Exception e) {
            log.warn("wallet sweep: tick failed, nothing more is done until the next one: {}", e.toString(), e);
        }
    }

    void step() throws Exception {
        if (readiness.isWalletReady()) {
            return;
        }
        if (!somethingSpends.getAsBoolean()) {
            log.debug("wallet sweep: no spending processor is enabled, nothing to prepare");
            observation = null;
            readiness.markIdle();
            return;
        }
        readiness.markPendingIfIdle();
        if (blockEventListener.getIsSyncing().get()) {
            log.debug("wallet sweep: node is syncing, skipping");
            observation = null;
            return;
        }

        Optional<List<Utxo>> listed = listWallet();
        if (listed.isEmpty()) {
            return;
        }
        // t0 is when Blockfrost has ANSWERED: the index must reach that slot, not the one the listing began at.
        Instant now = clock.instant();
        List<Utxo> listing = listed.get();
        Set<String> refs = refsOf(listing);
        long lastAppliedSlot = blockEventListener.getLastAppliedSlot().get();

        if (observation == null || lastAppliedSlot < observation.settleSlot()) {
            if (observation == null) {
                observation = new Observation(toSlot(now), refs);
            }
            log.debug("wallet sweep: waiting for the index to apply slot {} (at {}) before comparing",
                    observation.settleSlot(), lastAppliedSlot);
            return;
        }

        // Settled: judge only what was listed at t0 and is still listed now.
        Set<String> judged = new LinkedHashSet<>(observation.refs());
        judged.retainAll(refs);
        observation = new Observation(toSlot(now), refs);
        List<String> missing = judged.stream().filter(ref -> !indexed(ref)).toList();

        boolean windowOpen = lastSweep != null && now.isBefore(lastSweep.submittedAt().plus(CONVERGENCE_WINDOW));
        boolean sweepIndexed = lastSweep == null || sweepOutputsIndexed(lastSweep);
        // The last sweep's outputs in THIS listing are judged directly: one that just landed was never in
        // the previous listing, so `judged` cannot see it.
        List<String> sweepListed = lastSweep == null ? List.of()
                : refs.stream().filter(ref -> ref.startsWith(lastSweep.txHash() + "#")).toList();
        List<String> sweepListedUnindexed = sweepListed.stream().filter(ref -> !indexed(ref)).toList();
        if (missing.isEmpty() && sweepListedUnindexed.isEmpty()
                && (sweepIndexed || (!windowOpen && sweepListed.isEmpty()))) {
            readiness.markReady();
            log.info("wallet sweep: all {} wallet UTxOs Blockfrost lists are in the local index — wallet READY",
                    listing.size());
            return;
        }
        if (windowOpen) {
            log.info("wallet sweep: awaiting {} ({} listed UTxOs still missing, its outputs {}indexed); no "
                            + "resubmit before {}", lastSweep.txHash(), missing.size(), sweepIndexed ? "" : "not yet ",
                    lastSweep.submittedAt().plus(CONVERGENCE_WINDOW));
            return;
        }
        if (missing.isEmpty() && !sweepListedUnindexed.isEmpty()) {
            // Freshly landed (a settled one would be in `missing`): wait for the index, do not resubmit.
            log.info("wallet sweep: {} landed, {} of its listed outputs not indexed yet; waiting for the index",
                    lastSweep.txHash(), sweepListedUnindexed.size());
            return;
        }
        sweep(listing, missing, now);
    }

    private void sweep(List<Utxo> listing, List<String> missing, Instant now) throws Exception {
        log.warn("wallet sweep: {} of {} listed wallet UTxOs are not in the local index {}; sweeping the WHOLE "
                + "wallet in one self-send", missing.size(), listing.size(), missing);

        WalletShapeTransactions.Outcome outcome =
                engine.buildShaped(listing, WalletShape.relevantUnits(markets.get()));
        if (!outcome.isBuilt()) {
            log.warn("wallet sweep: the shaped self-send was refused ({}); falling back to a consolidation",
                    outcome.detail());
            outcome = engine.buildConsolidation(listing);
            if (!outcome.isBuilt()) {
                log.error("wallet sweep REFUSED: the consolidation was refused too ({}). Nothing submitted; the "
                        + "processors stay gated; retrying on the next tick", outcome.detail());
                readiness.markRefused(outcome.detail());
                return;
            }
        }

        Transaction signed = engine.sign(outcome.transaction());
        String sizeProblem = signedSizeProblem(signed);
        if (sizeProblem != null) {
            log.error("wallet sweep REFUSED: {}. Nothing submitted; the processors stay gated", sizeProblem);
            readiness.markRefused(sizeProblem);
            return;
        }

        String txHash = TransactionUtil.getTxHash(signed);
        int outputCount = signed.getBody().getOutputs().size();
        Result<String> result;
        try {
            result = engine.submit(signed);
        } catch (Exception e) {
            // The bytes may or may not have reached a node: treat the sweep as in flight, so the window
            // holds back a resubmit, and let the next comparisons tell.
            lastSweep = new Sweep(txHash, outputCount, now);
            readiness.markRefused("submit outcome unknown for " + txHash + ": " + e);
            throw e;
        }
        if (result == null || !result.isSuccessful()) {
            String why = "submit rejected for " + txHash + ": " + (result == null ? "no result" : result.getResponse());
            log.warn("wallet sweep: {}; retrying on the next tick", why);
            readiness.markRefused(why);
            return;
        }
        lastSweep = new Sweep(txHash, outputCount, now);
        readiness.markSwept(txHash);
        log.info("wallet sweep: submitted {} ({}), spending all {} listed wallet UTxOs; waiting for its outputs "
                + "to reach the index", txHash, outcome.detail(), listing.size());
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

    /** The whole wallet, every page of both addresses; empty when any page fails (logged). */
    private Optional<List<Utxo>> listWallet() throws Exception {
        List<Utxo> all = new ArrayList<>();
        for (String address : addresses) {
            for (int page = 1; ; page++) {
                Result<List<Utxo>> result;
                try {
                    result = lister.page(address, page);
                } catch (Exception e) {
                    readiness.markRefused("listing failed: " + address + " page " + page + ": " + e);
                    throw e;
                }
                if (result == null) {
                    log.warn("wallet sweep: listing {} page {} returned nothing; retrying on the next tick",
                            address, page);
                    readiness.markRefused("listing failed: " + address + " page " + page + " returned nothing");
                    return Optional.empty();
                }
                if (!result.isSuccessful()) {
                    if (result.code() == NOT_FOUND) {
                        break;
                    }
                    log.warn("wallet sweep: listing {} page {} failed (HTTP {}: {}); retrying on the next tick",
                            address, page, result.code(), result.getResponse());
                    readiness.markRefused("listing failed: " + address + " page " + page + " HTTP " + result.code()
                            + ": " + result.getResponse());
                    return Optional.empty();
                }
                List<Utxo> items = result.getValue() == null ? List.of() : result.getValue();
                all.addAll(items);
                if (items.size() < PAGE_SIZE) {
                    break;
                }
            }
        }
        return Optional.of(all);
    }

    private boolean indexed(String ref) {
        int hash = ref.lastIndexOf('#');
        return utxoRepository.findById(
                new UtxoId(ref.substring(0, hash), Integer.parseInt(ref.substring(hash + 1)))).isPresent();
    }

    private boolean sweepOutputsIndexed(Sweep sweep) {
        return IntStream.range(0, sweep.outputCount())
                .allMatch(i -> utxoRepository.findById(new UtxoId(sweep.txHash(), i)).isPresent());
    }

    private long toSlot(Instant instant) {
        return converters.time().toSlot(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
    }

    private static Set<String> refsOf(List<Utxo> listing) {
        Set<String> refs = new LinkedHashSet<>();
        for (Utxo utxo : listing) {
            refs.add(utxo.getTxHash() + "#" + utxo.getOutputIndex());
        }
        return refs;
    }
}
