package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.fluidtokens.aquarium.offchain.util.LedgerCeilings;
import com.fluidtokens.aquarium.offchain.util.WalletInputSelection;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationDecision;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationExclusion;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.model.loans.Rational;
import com.fluidtokens.aquarium.offchain.service.AppUtxoService;
import com.fluidtokens.aquarium.offchain.service.BlockEventListener;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.conversions.CardanoConverters;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * The liquidation loop: every cycle it scans every indexed lender bond, builds one real
 * {@code Liquidate} transaction per buildable candidate, prices it, and either writes down what it
 * would have done or — when every one of eight vetoes passes — signs it and submits it.
 *
 * <h2>The veto chain is the whole safety story</h2>
 * Submitting is the irreversible act: it burns the loan NFT and moves someone's collateral. Failing
 * to submit costs a cycle. So the eight checks in {@link #verdict} are enumerated explicitly,
 * evaluated in order, and every one of them resolves to <em>not submitting</em> when it cannot be
 * established — a Blockfrost timeout fetching protocol parameters is not evidence that the
 * transaction fits, and an oracle client that is not there is not evidence that its feed is fresh.
 * <p>
 * They are independent by construction: none is inferred from another's arithmetic. In particular
 * {@code TX_TOO_LARGE} measures the serialised transaction against the live {@code maxTxSize} and
 * has nothing to do with {@code NOT_PROFITABLE}'s fee arithmetic, even though an oversized
 * transaction usually also carries a large fee.
 *
 * <h2>What is submitted is what was vetted</h2>
 * The transaction handed to {@link #submit} is the same {@link Transaction} object
 * {@link LiquidateTransactionBuilder} returned and every veto ran against. The one transformation
 * applied to it is {@code account.sign(transaction)}, which adds exactly one vkey witness and leaves
 * the body bytes untouched (cardano-client-lib's {@code TransactionSigner} splices the witness into
 * the original serialisation), so the submitted transaction has the hash the decision records.
 * <p>
 * This class <em>does</em> hold a {@link LiquidateTransactionBuilder} — it is what produced the
 * transaction in the first place — so the guarantee is not "nothing here could rebuild". It is
 * narrower and checkable: {@link #verdict} and {@link #submit} never receive the
 * {@link LiquidateTransactionBuilder.Request}, so neither has the inputs a rebuild would need, and
 * the only thing this class can reach the network with is a {@link TransactionSubmitter}, whose
 * single method takes bytes. The property is enforced where properties of this kind have to be:
 * by a test. {@code LiquidationSubmitVetoTest} signs the recorded CBOR independently and compares
 * the result byte for byte with what reached the submitter.
 *
 * <h2>Shaped like {@code ScheduledTransactionService}, not shared with it</h2>
 * The syncing guard, the ada-only wallet UTxO selection and the per-item try/catch mirror
 * {@code ScheduledTransactionService} deliberately. They are not factored out of it: that class is
 * the mainnet scheduled-transaction path that every operator runs, and refactoring it to serve a
 * preview-only shadow bot would put production code at risk for a duplication that is four lines
 * long.
 * <p>
 * One divergence is deliberate and material. {@code ScheduledTransactionService} quarantines a
 * failing UTxO in an unbounded {@code Vector} that is never cleaned out, so one failure disables
 * that item until the process restarts. Here the quarantine is keyed on the loan UTxO ref, bounded,
 * and expires after {@code loans.liquidation.quarantine-minutes}: a transient Blockfrost hiccup
 * while fetching protocol params must not permanently exclude one borrower's loan from every future
 * cycle, and a loan UTxO ref is consumed the moment anyone liquidates it, so entries for real
 * failures die out on their own anyway.
 *
 * <h2>One transaction per candidate</h2>
 * {@link LiquidateTransactionBuilder} accepts a batch, and this loop always hands it exactly one
 * loan. Batching is not a size question but a failure-isolation one: a batch is atomic, so one loan
 * whose UTxO was spent a block ago kills the liquidation of every other loan in it, and the
 * per-UTxO quarantine below cannot express "this batch failed because of that member". Until a
 * failure model exists that can, N stays 1.
 */
@Service
@Slf4j
@ConditionalOnProperty(prefix = "loans", name = "enabled", havingValue = "true")
public class LiquidationExecutor {

    /**
     * How many quarantine entries are kept at once. A bound rather than a policy: entries expire on
     * their own, and this only stops a pathological cycle — thousands of loans all failing to build
     * — from growing the map without limit. When it is reached the entry closest to expiry is
     * dropped, because it is the one whose exclusion is about to end anyway.
     */
    static final int MAX_QUARANTINED = 1_024;

    /**
     * How far before "now" the transaction's validity interval starts. Mirrors
     * {@code ScheduledTransactionService}'s {@code validFrom(slot - 30)}: the node's clock and the
     * chain's slot are never exactly aligned, and a window that starts in the future is rejected
     * outright.
     */
    private static final long VALID_FROM_BACKDATE_MILLIS = 30_000L;

    /** The one network this epic is allowed to submit on. */
    static final String SUBMITTABLE_NETWORK = "preview";

    /**
     * The eight submit vetoes, in the order they are evaluated. Each is a separate, named reason
     * this candidate was not submitted; a decision carries exactly the first one that fired.
     */
    public enum SubmitVeto {
        /** S1 — {@code loans.liquidation.mode} is not {@code live}. */
        MODE_NOT_LIVE,
        /** S2 — {@code loans.liquidation.enabled} is false. */
        NOT_ARMED,
        /** S3 — the node is not on preview. */
        NETWORK_NOT_PREVIEW,
        /** S4 — expected profit is not strictly positive. */
        NOT_PROFITABLE,
        /** S5 — the serialised transaction is over the live {@code maxTxSize}, or it could not be read. */
        TX_TOO_LARGE,
        /** S6 — a feed this candidate prices against has less than the margin of window left, now. */
        ORACLE_WINDOW_TOO_SHORT_TO_SUBMIT,
        /** S7 — the loan or bond UTxO is no longer unspent, or that could not be established. */
        STALE_UTXO,
        /**
         * S8 — the built transaction's own validity interval has already ended, or its end could not
         * be read.
         * <p>
         * Not a duplicate of S6, and the gap it closes is a real one. S6 can only speak about loans
         * that have an oracle feed; <b>an ada/ada loan has no feed at all</b>, so before S8 there was
         * no submit-time staleness check whatsoever on exactly the shape that actually builds today.
         * The direction was already safe — an expired transaction is refused in phase 1 and costs
         * nothing — but "we submitted a transaction we knew had expired" is not a thing this loop
         * should do, and the contract's intent was staleness protection at submit time.
         * <p>
         * Evaluated last on purpose. Where a feed exists, S6's firing region is contained in this
         * one (the builder demands {@code feed.validTo >= tx.validTo + margin}, so a feed can only
         * run short after the transaction has expired), and the more specific reason is the more
         * useful one to report.
         */
        TRANSACTION_WINDOW_ELAPSED
    }

    /**
     * Everything this class can do to the network: hand over bytes. Deliberately not a
     * {@code BackendService} — a submitter cannot build, balance, evaluate or re-fetch anything, so
     * "the bytes submitted are the bytes vetted" is a property of the wiring rather than of care.
     */
    @FunctionalInterface
    public interface TransactionSubmitter {

        /** @return the backend's verdict; the value on success is the transaction hash */
        Result<String> submit(byte[] signedTransactionBytes) throws Exception;
    }

    private final AppConfig.LiquidationConfiguration configuration;

    private final BlockEventListener blockEventListener;

    private final AppUtxoService appUtxoService;

    private final Account account;

    private final LiquidationCandidateScanner scanner;

    private final LiquidationUtxoResolver utxoResolver;

    private final LiquidateTransactionBuilder builder;

    /**
     * The convert-liquidation seam. A candidate whose lender bond carries
     * {@code shouldLiquidationConvertToPrincipal == True} is routed through this to the promoted,
     * submit-incapable {@link LiquidatePayInAdvanceTransactionBuilder} rather than through
     * {@link #builder}; a convert shape the seam cannot yet model is a clean {@code REFUSED} row, not a
     * crash. Non-convert candidates never touch it.
     */
    private final PayInAdvanceLiquidationRouter payInAdvanceRouter;

    /**
     * The runtime-derived v4 hashes. Read here for one thing only: the asset-manager spend script
     * hash, which is the payment credential the builder sends every funded asset-manager output to,
     * so {@link #minAdaFunded} can pick those outputs out of the finished body by credential rather
     * than by a predicted position.
     */
    private final LoansContractRegistry registry;

    private final LiquidationDecisionLog decisionLog;

    private final ObjectProvider<FluidOracleClient> oracleClient;

    private final AppConfig.Network network;

    private final ProtocolParamsSupplier protocolParamsSupplier;

    private final TransactionSubmitter submitter;

    /**
     * Slot-to-wall-clock conversion, so S8 can read the validity end off the transaction body rather
     * than trusting the millisecond window that was <em>requested</em>. The builder clamps that
     * window inwards to whole slots, so the requested end is always at or after the real one — using
     * it would let a transaction that has genuinely expired through by up to one slot.
     */
    private final CardanoConverters converters;

    /** Loan UTxO ref to the epoch-millis at which its quarantine lapses. */
    private final Map<String, Long> quarantine = new ConcurrentHashMap<>();

    /**
     * The clock the submit-time checks read, as opposed to the cycle's own {@code now}.
     * <p>
     * The two are genuinely different instants and the difference is the whole point of S6. A cycle
     * scans, resolves UTxOs, fetches protocol parameters and evaluates scripts before it gets
     * anywhere near submitting, and every one of those is a Blockfrost round trip; re-checking the
     * oracle windows against the instant the cycle <em>started</em> would be re-checking nothing.
     * Package-private and settable so a test can advance it without waiting.
     */
    private java.util.function.LongSupplier submitClock = System::currentTimeMillis;

    void setSubmitClock(java.util.function.LongSupplier submitClock) {
        this.submitClock = submitClock;
    }

    /**
     * The wiring Spring uses. The {@link BFBackendService} is narrowed to a
     * {@link TransactionSubmitter} here and nowhere else, so the only field of this class that
     * can reach the network is that one-method submitter. (The class does hold a
     * {@link LiquidateTransactionBuilder}; see the class javadoc for what is and is not
     * guaranteed by that.)
     */
    @Autowired
    public LiquidationExecutor(AppConfig.LiquidationConfiguration configuration,
                               BlockEventListener blockEventListener,
                               AppUtxoService appUtxoService,
                               Account account,
                               LiquidationCandidateScanner scanner,
                               LiquidationUtxoResolver utxoResolver,
                               LiquidateTransactionBuilder builder,
                               PayInAdvanceLiquidationRouter payInAdvanceRouter,
                               LoansContractRegistry registry,
                               LiquidationDecisionLog decisionLog,
                               ObjectProvider<FluidOracleClient> oracleClient,
                               AppConfig.Network network,
                               ProtocolParamsSupplier protocolParamsSupplier,
                               CardanoConverters converters,
                               BFBackendService backendService) {
        this(configuration, blockEventListener, appUtxoService, account, scanner, utxoResolver, builder,
                payInAdvanceRouter, registry, decisionLog, oracleClient, network, protocolParamsSupplier,
                converters, bytes -> backendService.getTransactionService().submitTransaction(bytes));
    }

    /** The same loop with the submitter stated, so a test can watch exactly what reaches the wire. */
    public LiquidationExecutor(AppConfig.LiquidationConfiguration configuration,
                               BlockEventListener blockEventListener,
                               AppUtxoService appUtxoService,
                               Account account,
                               LiquidationCandidateScanner scanner,
                               LiquidationUtxoResolver utxoResolver,
                               LiquidateTransactionBuilder builder,
                               PayInAdvanceLiquidationRouter payInAdvanceRouter,
                               LoansContractRegistry registry,
                               LiquidationDecisionLog decisionLog,
                               ObjectProvider<FluidOracleClient> oracleClient,
                               AppConfig.Network network,
                               ProtocolParamsSupplier protocolParamsSupplier,
                               CardanoConverters converters,
                               TransactionSubmitter submitter) {
        this.configuration = configuration;
        this.blockEventListener = blockEventListener;
        this.appUtxoService = appUtxoService;
        this.account = account;
        this.scanner = scanner;
        this.utxoResolver = utxoResolver;
        this.builder = builder;
        this.payInAdvanceRouter = payInAdvanceRouter;
        this.registry = registry;
        this.decisionLog = decisionLog;
        this.oracleClient = oracleClient;
        this.network = network;
        this.protocolParamsSupplier = protocolParamsSupplier;
        this.converters = converters;
        this.submitter = submitter;
        guardMainnetNegativeMargin();
        guardMainnetIgnoreProfitCheck();
        announceEffectiveSettings();
    }

    /**
     * ⛔ The settings actually in force, printed once at boot.
     *
     * <h2>Why this is not redundant with {@code ShippedDefaultsTest}</h2>
     * That test pins what the <b>file</b> ships. <b>It is completely blind to an environment
     * override</b>, which is how every one of these values is meant to be changed — so the two are
     * complementary rather than overlapping: the pins protect the promise, this reports the reality.
     *
     * <h2>What made it necessary</h2>
     * Asked by {@code steward-d0} on 2026-08-27, after it checked whether it could observe a change
     * to the validity window from outside and found it could not: <b>absent from the environment,
     * absent from the manifest, absent from every boot line</b> — verified by reading the whole INIT
     * block rather than by one keyword grep. <b>The only observable was the CBOR width of a built
     * transaction, and with zero loans nothing builds.</b> ⇒ A narrowing could land and stay
     * invisible until the liquidation that paid for it, which is the wrong moment to discover the
     * risk was taken.
     * <p>
     * ⚑ Same defect class as the loan census (T-060), the feed age (T-067) and the tank traffic line:
     * <b>a number that governs behaviour, already known, never printed.</b>
     */
    private void announceEffectiveSettings() {
        log.info("LIQUIDATION SETTINGS mode={} enabled={} network={}",
                configuration.getMode(), configuration.isEnabled(),
                network == null ? "unknown" : network.getNetwork());
        // The window is stated as its three parts AND its total: the total is what the ledger sees,
        // and the backdate is a code constant an operator cannot change, so a report of the total
        // alone would look configurable when half of it is not.
        log.info("LIQUIDATION WINDOW validity-window={}s + backdate={}s = {}s total; oracle-margin={}s",
                configuration.getValidityWindowSeconds(),
                VALID_FROM_BACKDATE_MILLIS / 1000L,
                configuration.getValidityWindowSeconds() + VALID_FROM_BACKDATE_MILLIS / 1000L,
                configuration.getOracleWindowMarginSeconds());
        log.info("LIQUIDATION ECONOMICS profit-margin={} lovelace; min-profit-absolute={} lovelace; "
                        + "check-profitability={}; ignore-profit-check={}",
                configuration.getProfitMarginLovelace(),
                configuration.getMinProfitAbsoluteLovelace(),
                configuration.isCheckProfitability(),
                configuration.isIgnoreProfitCheck());
    }

    /**
     * ⛔ {@code ignore-profit-check} disables BOTH profitability gates. On mainnet that is a hard
     * startup failure, never a warning.
     * <p>
     * Same reasoning as {@link #guardMainnetNegativeMargin()} and deliberately the same shape: this
     * bean only exists when {@code loans.enabled=true}, so refusing construction refuses to arm the
     * liquidation path at all on a mainnet node configured to liquidate at a loss. A WARN on that path
     * is a comment, not a guard, and "copy the working preview config to mainnet" is the foreseeable
     * operator action.
     * <p>
     * On preview it proceeds, and announces itself unconditionally at boot: a bot that will move
     * someone's collateral at a loss has to say so where the operator already looks.
     */
    private void guardMainnetIgnoreProfitCheck() {
        if (!configuration.isIgnoreProfitCheck()) {
            return;
        }
        String networkName = network == null ? null : network.getNetwork();
        // Fail-closed: anything that is not preview or preprod resolves to mainnet.
        boolean mainnet = networkName == null
                || (!"preview".equalsIgnoreCase(networkName) && !"preprod".equalsIgnoreCase(networkName));
        if (mainnet) {
            throw new IllegalStateException(("loans.liquidation.ignore-profit-check is TRUE on network "
                    + "%s; it bypasses the absolute profit floor AND the margin, so the bot would "
                    + "liquidate at an unbounded loss. It is a preview-only test switch and must never "
                    + "be set on mainnet").formatted(networkName));
        }
        log.warn("⛔ loans.liquidation.ignore-profit-check is TRUE on network {} — BOTH profitability "
                + "gates are bypassed and liquidations will be submitted AT A LOSS. This is a "
                + "preview-only test switch.", networkName);
    }

    /**
     * FIX 3 (F1.ii). A negative {@code profit-margin-lovelace} is a preview-only override — it
     * re-authorises loss-making liquidations, which is a thing to do only on throwaway preview
     * capital. On mainnet it is a hard startup failure rather than a WARN, because "copy a working
     * preview config to mainnet" is the foreseeable operator action and a WARN on the mainnet path
     * is a comment, not a guard. This bean only exists when {@code loans.enabled=true} (the arming
     * condition), so failing construction here refuses to arm the liquidation path on a mainnet node
     * whose margin would silently sanction losses. On preview it stays a WARN and proceeds.
     */
    private void guardMainnetNegativeMargin() {
        BigInteger margin = configuration.getProfitMarginLovelace();
        if (margin == null || margin.signum() >= 0) {
            return;
        }
        String networkName = network == null ? null : network.getNetwork();
        // Fail-closed: anything that is not preview or preprod resolves to mainnet, exactly as
        // AppConfig.Network.getCardanoNetwork() treats an unrecognised value.
        boolean mainnet = networkName == null
                || (!"preview".equalsIgnoreCase(networkName) && !"preprod".equalsIgnoreCase(networkName));
        if (mainnet) {
            throw new IllegalStateException(("loans.liquidation.profit-margin-lovelace is %s (negative) "
                    + "on network %s; a negative margin is a preview-only override that re-authorises "
                    + "loss-making liquidations and must never be set on mainnet")
                    .formatted(margin, networkName));
        }
        log.warn("loans.liquidation.profit-margin-lovelace is {} (negative) on network {}; this is a "
                + "preview-only override that re-authorises loss-making liquidations", margin, networkName);
    }

    @Scheduled(timeUnit = TimeUnit.SECONDS, fixedDelayString = "${loans.liquidation.delay-seconds}")
    public void runCycle() {
        // One bad cycle must not take the scheduler thread with it: a thrown exception out of a
        // @Scheduled method cancels nothing, but it does lose the run, and any state left half
        // written here is better reported than propagated.
        try {
            cycle(System.currentTimeMillis());
        } catch (Exception e) {
            log.warn("liquidation cycle failed", e);
        }
    }

    /**
     * One cycle, at an explicit instant so it is testable without a clock. Package-private: the
     * scheduler comes in through {@link #runCycle()}.
     */
    void cycle(long now) {
        if (configuration.getMode() == AppConfig.LiquidationConfiguration.Mode.DISABLED) {
            return;
        }

        if (blockEventListener.getIsSyncing().get()) {
            log.info("node is syncing, skipping...");
            return;
        }

        LiquidationCandidateScanner.Scan scan = scanner.scan(now);
        List<LiquidationAssessment> assessments = scan.assessments();

        // The oracle snapshot is taken once, in the same cycle as the scan, and reused for every
        // candidate. Re-reading the registry between scanning and building would let a candidate be
        // assessed against one price and built against another — and the builder's V4 guard would
        // then refuse it, turning a price refresh into a wave of unexplained refusals.
        Map<String, OracleEntry> oraclesByUnit = oracleSnapshot();

        List<LiquidationAssessment> buildable = assessments.stream()
                .filter(LiquidationAssessment::buildable)
                .toList();
        Map<LiquidationExclusion, Integer> exclusions = histogram(assessments);

        // T-060 PART 2 — A BOND WHOSE LOAN NO LONGER EXISTS IS NOT AN EXCLUSION.
        //
        // Giovanni, from the logs: "okay oracle unusable as metric, but what's this 7 bonds and 5 not
        // found... if it's related to a utxo being spent, the loan does not exist anymore. so we
        // should stop counting both as a bond in the first place and as not found in the brackets."
        // He is right, and the reason is structural: CLOSING A LOAN BURNS THE LOAN NFT WHILE THE
        // LENDER BOND SURVIVES as the lender's separate claim ticket. A bond outliving its loan is
        // the ordinary post-settlement state, so counting it as a live candidate inflates the
        // denominator AND manufactures an alarming-looking exclusion out of one stale row.
        //
        // ⚠ BUT IT IS RECLASSIFIED, NOT DELETED — and that is a deliberate departure from "stop
        // counting". If the number vanished entirely, then the day a loan is absent for a reason
        // that is NOT "settled", THE LINE THAT WOULD HAVE TOLD US IS THE LINE WE REMOVED. A metric
        // that goes quiet because the population left and one that goes quiet because the reader
        // broke look identical. So `settled` is reported beside the live count, out of the
        // exclusions bracket where it read as a fault.
        int settled = exclusions.getOrDefault(LiquidationExclusion.LOAN_NOT_FOUND, 0);
        Map<LiquidationExclusion, Integer> liveExclusions = exclusions.entrySet().stream()
                .filter(e -> e.getKey() != LiquidationExclusion.LOAN_NOT_FOUND)
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        int liveBonds = assessments.size() - settled;

        // Excluded bonds are counted, never logged as decisions: the histogram is the whole record
        // of them, so every scanned bond is accounted for without the ring buffer being flooded by
        // the healthy majority.
        decisionLog.recordRun(now, liveBonds, settled, buildable.size(), liveExclusions);

        // ⛔ AND THE CENSUS CLAUSE STAYS, BECAUSE SUPPRESSING LOAN_NOT_FOUND IS ONLY SAFE WHILE IT
        // READS ZERO. "settled" now asserts that those bonds' loans are GONE; `unreadable` is the
        // only thing that can contradict it, by showing a loan that is present and simply not
        // legible to us. The moment it is non-zero, the settled count is not trustworthy either.
        int unreadable = scan.loanCensus().unreadable();
        log.info("liquidation scan: {} live bonds{}, {} buildable, exclusions {}{}",
                liveBonds,
                settled == 0 ? "" : " (%d settled — the bond outlives its loan)".formatted(settled),
                buildable.size(), liveExclusions,
                unreadable == 0
                        // Stated positively and only when it is true: the ABSENCE of a warning is not
                        // evidence, but an explicit zero is. It is what licenses reading the settled
                        // count as settled rather than as loans we merely cannot see.
                        ? " (all %d loan utxos at the credential were readable)"
                                .formatted(scan.loanCensus().utxosAtCredential()
                                        - scan.loanCensus().notALoan())
                        : " ⚠ %d LOAN UTXO(S) UNREADABLE — the settled count above is CONTAMINATED "
                                .formatted(unreadable)
                                + "and must not be read as loans that are simply gone");

        // Before the early return, not after it: a cycle that finds nothing buildable is exactly the
        // cycle in which every quarantined loan was already skipped by the scanner, and letting the
        // expiries lapse only when there happens to be work would keep entries alive far past their
        // configured lifetime.
        expireQuarantine(now);

        if (buildable.isEmpty()) {
            return;
        }

        // The wallet gate sits HERE, below the scan, and that position is the point.
        //
        // It used to be the first thing the cycle did, which made an unfunded wallet indistinguishable
        // from a quiet market: the cycle returned before scanning, so nothing was scanned, nothing was
        // priced, and the decision log recorded no run at all. That is the blind-bot shape again, and
        // it bites hardest in SHADOW mode -- the mode whose entire purpose is to prove the bot can see
        // and price real loans BEFORE anyone funds a wallet or arms it. Requiring a spendable UTxO
        // before observing the world made the observation-only mode useless exactly when it is wanted.
        //
        // Nothing is loosened for LIVE: a liquidation still cannot be built without a wallet input,
        // and the refusal is now explicit and counted rather than a silent early return.
        List<Utxo> walletUtxos = nominableWalletUtxos();
        if (walletUtxos.isEmpty()) {
            log.warn("{} buildable candidate(s) found but no ada-only wallet utxo is available, so "
                    + "none can be built; the scan above is still a complete record of what was seen "
                    + "and priced", buildable.size());
            return;
        }
        // T-052 — the fee ceiling every liquidation must cover regardless of its shape. The
        // principal-repaying path adds its lender payout on top; the fee-only path needs nothing more,
        // which is the whole point: it must not be made to demand an input sized for the other case.
        //
        // ⚠ OPTIONAL, AND DELIBERATELY SO. This is a remote read, and a remote read that ALL selection
        // depends on turns a parameter outage into a liquidation outage. Worse, it would fire before
        // anything else in the cycle and pre-empt the TX_TOO_LARGE veto — the one path whose whole job
        // is to report an unfetchable maxTxSize (LiquidationSubmitVetoTest S5). When it cannot be had,
        // selection degrades to the pre-T-052 behaviour, largest-nominable: never wrong, only wasteful.
        // T-061 — the PARAMS are held, not just the fee ceiling, because a refusal has to size EVERY
        // gate it can see and the collateral ceiling comes from the same object.
        Optional<ProtocolParams> fetched;
        try {
            ProtocolParams fresh = protocolParamsSupplier.getProtocolParams();
            // ⛔ PROVE IT IS USABLE HERE, INSIDE THE CATCH THAT HANDLES IT.
            //
            // The fetch used to be `maxPossibleFee(getProtocolParams())` — one expression, one
            // try/catch — so a params object with null fields degraded to "unavailable" like any
            // other failure. Holding the raw object and deriving later moved that derivation OUTSIDE
            // this catch, and LiquidationSubmitVetoTest S5 (whose supplier returns a deliberately
            // partial object) went from a clean veto to a NullPointerException.
            //
            // ⚠ THAT IS THE THIRD TIME TODAY A COMPUTATION CROSSING AN ERROR BOUNDARY CHANGED A
            // FAILURE MODE — T-050 moved two remote reads out of complete()'s try/catch, T-052 moved
            // one to cycle start, and this moved a derivation past its own handler. The code was
            // correct each time; only the REPORTING broke, which is why review does not catch it and
            // a test asserting a failure mode does.
            //
            // Both ceilings and every field the diagnosis reads are exercised here, so anything
            // unusable is caught as unavailable rather than surfacing later as an NPE.
            LedgerCeilings.maxPossibleCollateral(fresh);
            Math.max(1, fresh.getMaxCollateralInputs());
            fetched = Optional.of(fresh);
        } catch (Exception e) {
            fetched = Optional.empty();
            log.warn("could not fetch protocol parameters to size the wallet input ({}); falling back "
                    + "to the largest nominable utxo for this cycle", causeChain(e));
        }
        // ⛔ THE VALIDITY WINDOW IS ANCHORED TO THE CHAIN, NOT TO THE WALL CLOCK.
        //
        // The first live submission (2026-08-26, tx 8062f42d…) was rejected
        // OutsideValidityIntervalUTxO with invalidBefore 127 slots past the node's current slot. The
        // builder was CORRECT — invalidBefore was decided_at − 30s exactly, the intended backdate.
        // THE NODE'S "CURRENT SLOT" IS THE LAST BLOCK IT APPLIED, NOT WALL CLOCK, and preview was
        // sitting in a 182-slot block gap. Measured on chain from two independent sources: preview's
        // mean gap is ~37s with a max of 182 in ~25 blocks, SO A 30-SECOND BACKDATE CANNOT COVER AN
        // ORDINARY GAP.
        //
        // Anchoring to the last applied slot makes invalidBefore <= the node's position BY
        // CONSTRUCTION at any gap length, instead of by a guess at how long a gap might be. Ask the
        // chain where it is; do not infer it from a clock the chain cannot see.
        //
        // ⚠ min(), not the tip alone: if our indexer runs AHEAD of the submitting node we must still
        // not outrun it, and if it runs behind we backdate more — which is the safe direction. And
        // only the START moves: validTo stays wall-clock-based so the transaction keeps its full
        // landing time, and because the oracle-window check reads validTo and is a SEPARATE
        // constraint that this must not disturb.
        long anchor = windowAnchorMillis(now, blockEventListener.getLastAppliedSlot().get(), converters);
        if (anchor != now) {
            log.info("validity window anchored to the last applied block ({} ms behind wall clock) — "
                    + "invalidBefore would otherwise sit ahead of the node's current slot", now - anchor);
        }
        final long validFromMillis = anchor - VALID_FROM_BACKDATE_MILLIS;
        final long validToMillis = now + configuration.getValidityWindowSeconds() * 1000L;

        // Effectively final, so the per-candidate selector lambda can close over them.
        final Optional<ProtocolParams> params = fetched;
        final Optional<BigInteger> feeCeiling = params.map(LedgerCeilings::maxPossibleFee);

        Optional<Utxo> configUtxo = utxoResolver.resolveConfigUtxo();
        Optional<Utxo> lmConfigUtxo = utxoResolver.resolveLmConfigUtxo();
        if (configUtxo.isEmpty() || lmConfigUtxo.isEmpty()) {
            log.warn("cannot build liquidations: config utxo present={}, lm config utxo present={}",
                    configUtxo.isPresent(), lmConfigUtxo.isPresent());
            return;
        }

        for (LiquidationAssessment assessment : buildable) {
            try {
                consider(assessment, now, validFromMillis, validToMillis, walletUtxos, params, configUtxo.get(), lmConfigUtxo.get(),
                        oraclesByUnit);
            } catch (Exception e) {
                // consider() already turns every expected failure into a decision; this is the last
                // net, so that one unexpected candidate does not cost the rest of the cycle.
                //
                // Identified by the BOND, not the loan: this catch exists for the cases consider()
                // did not anticipate, and a null loan on a supposedly buildable assessment is one of
                // them — dereferencing assessment.loan() here would throw out of the handler that is
                // meant to contain it. The bond is the one thing every assessment always carries.
                log.error("could not consider bond {}: {}", assessment.bond().utxoRef(), causeChain(e), e);
            }
        }
    }

    // ---- one candidate ------------------------------------------------------------------------

    private void consider(LiquidationAssessment assessment, long now,
                          long validFromMillis, long validToMillis,
                          List<Utxo> walletUtxos,
                          Optional<ProtocolParams> params, Utxo configUtxo, Utxo lmConfigUtxo,
                          Map<String, OracleEntry> oraclesByUnit) {
        // Derived here rather than passed alongside, so the two can never disagree about which
        // parameters they came from.
        Optional<BigInteger> feeCeiling = params.map(LedgerCeilings::maxPossibleFee);
        String loanUtxoRef = assessment.loan().utxoRef();
        Long heldUntil = quarantine.get(loanUtxoRef);
        if (isQuarantined(loanUtxoRef, now)) {
            // Recorded, not merely logged. This was the ONLY path out of consider() that returned
            // without a decision, so a held loan was indistinguishable from one that was never
            // considered — and the hold is the bot's own doing, which is exactly the fact an operator
            // needs to see. The debug line stays: it is per-cycle and the record is what persists.
            log.debug("loan {} is quarantined until {}", loanUtxoRef, heldUntil);
            decisionLog.record(decision(assessment, now, LiquidationDecision.Outcome.QUARANTINED,
                    LiquidationDecision.Outcome.QUARANTINED.name(),
                    heldUntil == null
                            ? "held by an earlier failure; the hold lapses shortly"
                            : "held by an earlier failure for another %d s (until epoch-millis %d)"
                                    .formatted(Math.max(0L, (heldUntil - now) / 1000L), heldUntil)));
            return;
        }

        Optional<Utxo> loanUtxo = utxoResolver.resolveLoanUtxo(assessment.loan());
        Optional<Utxo> bondUtxo = utxoResolver.resolveBondUtxo(assessment.bond());
        if (loanUtxo.isEmpty() || bondUtxo.isEmpty()) {
            // Spent between the scan and now. Not an error and not quarantined: the ref is gone for
            // good, so it will simply not be scanned again.
            decisionLog.record(decision(assessment, now, LiquidationDecision.Outcome.NO_UTXO,
                    LiquidationDecision.Outcome.NO_UTXO.name(),
                    "loan utxo present=%s, bond utxo present=%s — spent since the scan"
                            .formatted(loanUtxo.isPresent(), bondUtxo.isPresent())));
            return;
        }

        Transaction transaction;
        if (assessment.bond().datum().shouldLiquidationConvertToPrincipal()) {
            // ⛔ ANNOUNCE IT. On this path the bot PAYS the lender ada out of its own wallet and
            // acquires the collateral token — the opposite sign to the plain path, and a trade
            // nobody has specified the intended end state for. It emitted NO log line of any kind
            // until now, so the only way to notice one had happened was a FALLING wallet balance
            // read off chain. An armed, unspecified and unobservable behaviour is three problems,
            // and the cheapest of them to fix is the third.
            log.info("{} is a CONVERT liquidation (LiquidateAndPayInAdvance): the bot PAYS the "
                            + "lender in ada and acquires the collateral, rather than earning a fee",
                    loanUtxoRef);
            // Convert loan: routed to the promoted, submit-incapable pay-in-advance builder. The same
            // window the plain path uses is handed to the router, which derives its own slots from it.
            try {
                transaction = payInAdvanceRouter.buildConvertLiquidation(assessment, loanUtxo.get(),
                        bondUtxo.get(), configUtxo, lmConfigUtxo, oraclesByUnit,
                        // T-052 — the router knows the lender payout, this knows the wallet and the
                        // fee ceiling, and neither has to learn the other's job. It computes the
                        // exact ada it must repay and asks for the SMALLEST input that covers that
                        // plus the fee.
                        lenderPayout -> nominate(walletUtxos, feeCeiling, lenderPayout, loanUtxoRef),
                        validFromMillis,
                        validToMillis);
            } catch (PayInAdvanceLiquidationRouter.WalletInputTooSmallException e) {
                // T-061 — the router knows the lender payout and nothing about the wallet; THIS knows
                // the wallet and the parameters. Neither can state every gate alone, so the message is
                // completed here rather than left naming one of several.
                // T-052. Same treatment as "not modelled": a clean statement about this candidate
                // against this wallet. NOT quarantined — the wallet can be topped up between cycles,
                // and quarantining would keep refusing a candidate that had become buildable.
                String convertDetail = e.getMessage() + " " + collateralGate(walletUtxos, params);
                decisionLog.record(decision(assessment, now, LiquidationDecision.Outcome.REFUSED,
                        "WALLET_INPUT_TOO_SMALL", convertDetail));
                log.warn("the convert liquidation of {} was refused: {}", loanUtxoRef, convertDetail);
                return;
            } catch (PayInAdvanceLiquidationRouter.PayInAdvanceNotModelledException e) {
                // A convert shape the seam cannot yet model (non-ada principal / non-positive equity):
                // a clean statement about this candidate, reproducible next cycle. Not quarantined, and
                // no transaction was built — exactly the plain path's RefusedException treatment.
                decisionLog.record(decision(assessment, now, LiquidationDecision.Outcome.REFUSED,
                        e.getMessage(), e.getMessage()));
                return;
            } catch (Exception e) {
                // A genuine builder failure. Quarantined exactly as the plain path's machinery-failure
                // branch does, so a systematically broken candidate does not burn a build attempt every
                // cycle, but only for a while. The refusal MUST say why: the builder wraps the real
                // fault as IllegalStateException("cannot build the pay-in-advance transaction", cause),
                // so recording only e.getMessage() drops the cause and leaves the operator debugging
                // blind. The detail carries the whole cause chain and the exception is logged in full at
                // ERROR (never DEBUG, which an INFO-level node never prints) — a build failure whose
                // cause never reaches the log hides the next one too.
                quarantineUntil(loanUtxoRef, now + configuration.getQuarantineMinutes() * 60_000L);
                decisionLog.record(decision(assessment, now, LiquidationDecision.Outcome.REFUSED,
                        rootReason(e), causeChain(e)));
                log.error("building the pay-in-advance liquidation of {} failed: {}",
                        loanUtxoRef, causeChain(e), e);
                return;
            }
        } else {
            // T-052 — A FEE-ONLY LIQUIDATION MUST NOT BE MADE TO DEMAND A LARGE INPUT.
            //
            // On the plain path the bot pays no principal: the collateral it collects funds every
            // output the transaction creates, so the only ada it must bring is the fee. Nominating
            // the largest wallet utxo (what this did until now) is not wrong, it is wasteful in a way
            // that compounds — the biggest input gets consumed by whichever candidate runs first, so
            // a fee-only liquidation can spend the one input a principal-repaying candidate needed.
            Optional<Utxo> plainWalletUtxo =
                    nominate(walletUtxos, feeCeiling, BigInteger.ZERO, loanUtxoRef);
            if (plainWalletUtxo.isEmpty()) {
                // A refusal, not a failure: true of this candidate against this wallet, reproducible
                // next cycle, and cured by a top-up. Never quarantined.
                // feeCeiling is necessarily present here: with it absent, nominate() falls back to
                // largest-nominable, and the cycle gate above already established that at least one
                // nominable utxo exists — so the empty branch is unreachable without a ceiling.
                String detail = walletDiagnosis(walletUtxos, params, feeCeiling, BigInteger.ZERO);
                decisionLog.record(decision(assessment, now, LiquidationDecision.Outcome.REFUSED,
                        "WALLET_INPUT_TOO_SMALL", detail));
                log.warn("the liquidation of {} was refused: {}", loanUtxoRef, detail);
                return;
            }
            Utxo walletUtxo = plainWalletUtxo.get();

            LiquidateTransactionBuilder.Request request = new LiquidateTransactionBuilder.Request(
                    List.of(new LiquidateTransactionBuilder.LoanLiquidation(assessment, loanUtxo.get(),
                            bondUtxo.get())),
                    configUtxo,
                    lmConfigUtxo,
                    oraclesByUnit,
                    walletUtxo,
                    account.baseAddress(),
                    validFromMillis,
                    validToMillis,
                    configuration.getOracleWindowMarginSeconds() * 1000L,
                    // Whatever loans.liquidation.reference-scripts.* names. Every unset one means that
                    // validator travels in the witness set instead, which is legal and much larger —
                    // with none set at all the transaction cannot fit under maxTxSize, and the
                    // TX_TOO_LARGE veto below is what says so.
                    configuration.getReferenceScripts());

            try {
                transaction = builder.build(request);
            } catch (LiquidateTransactionBuilder.RefusedException e) {
                // A refusal is the builder working: it is a statement about this candidate, reproducible
                // next cycle, and costs nothing. Not quarantined for that reason.
                //
                // T-040, fifth site. TWO of the builder's fifty refusals wrap a real fault —
                // SCRIPT_COST_EVALUATION_FAILED and TRANSACTION_NOT_BUILDABLE, both raised from the
                // catch around context.build(). Recording only e.getMessage() dropped that cause, so
                // "the machinery broke" and "this candidate is not liquidatable" reached the operator
                // looking identical. The other forty-eight carry no cause and are untouched: a clean
                // verdict needs no stack trace, and logging every refusal at ERROR would bury the two
                // that matter under the forty-eight that do not.
                decisionLog.record(decision(assessment, now, LiquidationDecision.Outcome.REFUSED,
                        e.getReason().name(), refusalDetail(e)));
                if (e.getCause() != null) {
                    log.error("the liquidation of {} was refused as {} by a failure underneath: {}",
                            loanUtxoRef, e.getReason(), causeChain(e.getCause()), e);
                }
                return;
            } catch (Exception e) {
                // Anything else is a failure of the machinery rather than a verdict on the candidate —
                // a Blockfrost timeout fetching protocol params, say. Quarantined so a systematically
                // broken candidate does not burn a build attempt every cycle, but only for a while. Same
                // rule as the convert branch: surface the whole cause chain in the detail and log the
                // exception in full at ERROR, never at DEBUG where an INFO-level operator never sees it.
                quarantineUntil(loanUtxoRef, now + configuration.getQuarantineMinutes() * 60_000L);
                decisionLog.record(decision(assessment, now, LiquidationDecision.Outcome.REFUSED,
                        rootReason(e), causeChain(e)));
                log.error("building the liquidation of {} failed: {}", loanUtxoRef, causeChain(e), e);
                return;
            }
        }

        record(assessment, now, transaction, oraclesByUnit);
    }

    // ---- failure surfacing ------------------------------------------------------------------------

    /**
     * The class name of the exception's <em>root</em> cause, which is more actionable than the
     * wrapper's: a build failure surfaces as {@code IllegalStateException("cannot build …", realCause)},
     * and "IllegalStateException" tells an operator nothing while the root cause names what actually
     * broke. Falls back to the exception's own class when there is no cause.
     */
    static String rootReason(Throwable t) {
        if (t == null) {
            return "";
        }
        return rootCause(t).getClass().getSimpleName();
    }

    /**
     * The whole cause chain rendered wrapper-first — {@code Class: message ⇐ Class: message ⇐ …} — so a
     * refusal's {@code detail} always says <em>why</em>. This is the string that reaches the decision
     * log; the swallowed-wrapper-message bug (a refusal reading only "cannot build the pay-in-advance
     * transaction", with the real cause discarded) is exactly what it fixes. Never null: a message-less
     * exception still contributes its class name.
     */
    static String causeChain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable c = t;
        // Bounded against a self-referential or cyclic cause chain (getCause() returning this or an
        // ancestor): at most a handful of links, never an unbounded walk.
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
     * A refusal's operator-facing detail: the builder's own message when the refusal is a verdict on
     * the candidate, and that message followed by the whole underlying cause chain when the refusal is
     * wrapping a failure. Only the {@code context.build()} catch passes a cause, so forty-eight of the
     * fifty refusals are unchanged by this and keep reading exactly as the builder wrote them.
     */
    static String refusalDetail(LiquidateTransactionBuilder.RefusedException e) {
        return e.getCause() == null
                ? e.getMessage()
                : e.getMessage() + " \u21d0 " + causeChain(e.getCause());
    }

    private static Throwable rootCause(Throwable t) {
        Throwable root = t;
        int depth = 0;
        while (root.getCause() != null && root.getCause() != root && depth < 12) {
            root = root.getCause();
            depth++;
        }
        return root;
    }

    /**
     * Prices the built transaction, runs the submit vetoes, and either submits it or files the
     * verdict saying why it did not.
     * <p>
     * The bot's whole take is the liquidation fee slice, denominated in the <em>collateral</em>
     * asset, so it has to be priced through the collateral leg's feed before it can be compared with
     * a lovelace transaction fee. For ada collateral that feed is {@link OraclePriceFeed#unit()},
     * the 1:1 identity {@code retrieve_oracle_data} synthesises for the empty policy id — the same
     * feed the scanner and the builder used, not a special case invented here.
     *
     * <h3>The min-ada the bot funds is counted (E5-A / F4)</h3>
     * {@link #minAdaFunded} is the lovelace the builder puts into the emitted asset-manager outputs
     * (compensation + claim) <em>beyond</em> what the loan UTxO's own collateral already carried — the
     * per-loan {@code Σ(assetManagerOutput.ada) − adaFromTheCollateralInput} that
     * cardano-client-lib tops to the min-ada floor out of the bot's own inputs. It is real lovelace
     * leaving the wallet that {@code txFee} does not include, and it is per-loan rather than a
     * constant: on token collateral the outputs carry no ada of their own, so the whole rider is
     * funded; on ada collateral only the shortfall between a sub-floor payout and the floor is.
     *
     * <h3>Two numbers, deliberately kept apart (E5-B Finding 1 / F1.i)</h3>
     * <ul>
     *   <li>{@code floorProfit = expectedFee − txFee − minAdaFunded} — the margin-EXCLUDED profit the
     *       profitability floors ({@link #verdict}'s S4) test. The margin is <b>not</b> inside it, so a
     *       negative {@code profit-margin-lovelace} can no longer be subtracted-as-a-negative to
     *       inflate the number past a floor (T-027: at margin −3,000,000 a real −0.19 ADA result scored
     *       +2.81 ADA and cleared).</li>
     *   <li>{@code expectedProfit = floorProfit − margin} — the same margin-adjusted "is it worth
     *       doing" number the decision log has always recorded, now corrected for min-ada. It is what
     *       the margin lever in {@link #verdict} tests, exactly where it did before.</li>
     * </ul>
     *
     * <h3>⚠ O-2 seam (unresolved) — see {@link #verdict} where {@code floorProfit} is used</h3>
     * {@code expectedFee} is a <b>mark-to-oracle token value</b> priced through the collateral feed,
     * not realisable ADA. This ticket keeps today's fee computation and only adds the min-ada term; O-2
     * may later restrict the floor to realisable ADA, which is not decided here.
     */
    private void record(LiquidationAssessment assessment, long now, Transaction transaction,
                        Map<String, OracleEntry> oraclesByUnit) {
        OraclePriceFeed collateralFeed = collateralFeed(assessment, oraclesByUnit);
        BigInteger expectedFee = LoanFinance
                .toLovelace(Rational.fromInt(assessment.liquidationFee()), collateralFeed)
                .floor();
        BigInteger txFee = transaction.getBody().getFee();
        BigInteger minAdaFunded = minAdaFunded(assessment, transaction);
        // FIX 1/FIX 2: the floors test this margin-EXCLUDED number; the margin is applied separately.
        BigInteger floorProfit = expectedFee.subtract(txFee).subtract(minAdaFunded);
        BigInteger margin = configuration.getProfitMarginLovelace();
        BigInteger expectedProfit = floorProfit.subtract(margin);

        String detail = "fee slice %s lovelace - tx fee %s - min-ada %s = floor %s; - margin %s = %s"
                .formatted(expectedFee, txFee, minAdaFunded, floorProfit, margin, expectedProfit);

        int size;
        String cborHex;
        try {
            byte[] bytes = transaction.serialize();
            size = bytes.length;
            cborHex = transaction.serializeToHex();
        } catch (Exception e) {
            // The transaction exists but cannot be measured — which is also the S5 evidence, so
            // there is nothing here that could ever be submitted. The pricing verdict still stands
            // and is recorded without the fields that could not be produced.
            // T-040: was WARN with e.toString() and no throwable — the nearest sibling of the three
            // silent sites and the most surprising omission, because the exception reaches NOTHING
            // else. The recorded decision carries the pricing detail, not the fault, so this line is
            // the only place the cause can ever appear.
            log.error("could not serialise the built liquidation of {}: {}",
                    assessment.loan().utxoRef(), causeChain(e), e);
            LiquidationDecision.Outcome unmeasured = wouldSubmit(floorProfit, expectedProfit)
                    ? LiquidationDecision.Outcome.WOULD_SUBMIT
                    : LiquidationDecision.Outcome.UNPROFITABLE;
            decisionLog.record(decision(assessment, now, unmeasured, unmeasured.name(), detail,
                    SubmitVeto.TX_TOO_LARGE));
            return;
        }

        Verdict verdict = verdict(assessment, now, transaction, oraclesByUnit, floorProfit,
                expectedProfit, size, detail);

        decisionLog.record(new LiquidationDecision(
                now,
                assessment.loan().loanId(),
                assessment.loan().utxoRef(),
                assessment.bond().utxoRef(),
                variantOf(assessment),
                verdict.outcome(),
                verdict.outcome().name(),
                verdict.detail(),
                assessment.late(),
                assessment.remainingDebt(),
                assessment.equity(),
                assessment.liquidationFee(),
                assessment.loan().datum().collateral().assetType().toUnit(),
                expectedFee,
                txFee,
                margin,
                expectedProfit,
                TransactionUtil.getTxHash(transaction),
                size,
                cborHex,
                transaction.getBody().getInputs().size(),
                transaction.getBody().getOutputs().size(),
                transaction.getBody().getReferenceInputs().size(),
                transaction.getWitnessSet() == null || transaction.getWitnessSet().getRedeemers() == null
                        ? 0
                        : transaction.getWitnessSet().getRedeemers().size(),
                verdict.veto() == null ? null : verdict.veto().name()));

        log.info("liquidation of {}: {} ({}), {} ({} bytes)", assessment.loan().utxoRef(),
                verdict.outcome(), verdict.veto(), verdict.detail(), size);
    }

    // ---- the submit vetoes --------------------------------------------------------------------

    /** What this candidate's row says, and which veto — if any — produced it. */
    private record Verdict(LiquidationDecision.Outcome outcome, SubmitVeto veto, String detail) {
    }

    /**
     * Runs the veto chain and, only if all eight pass, signs and submits.
     * <p>
     * The mapping from veto to outcome is deliberate rather than uniform. S1–S3 are standing
     * configuration — the bot is simply not armed for this node — so the row keeps saying what the
     * <em>candidate</em> deserved ({@code WOULD_SUBMIT} / {@code UNPROFITABLE}) and names the veto
     * alongside; that is exactly what shadow mode is for, and what makes "WOULD_SUBMIT next to
     * armed:false" readable. S5–S8 are statements about this candidate at this instant on an
     * otherwise armed node, and they get {@link LiquidationDecision.Outcome#SUBMIT_VETOED}.
     */
    private Verdict verdict(LiquidationAssessment assessment, long now, Transaction transaction,
                            Map<String, OracleEntry> oraclesByUnit, BigInteger floorProfit,
                            BigInteger expectedProfit, int size, String detail) {
        // What the row says on an unarmed node: WOULD_SUBMIT only if it would actually clear S4 on an
        // armed one — i.e. it passes both the profitability floor and the margin lever.
        LiquidationDecision.Outcome shadowOutcome = wouldSubmit(floorProfit, expectedProfit)
                ? LiquidationDecision.Outcome.WOULD_SUBMIT
                : LiquidationDecision.Outcome.UNPROFITABLE;

        // S1 — the mode.
        if (configuration.getMode() != AppConfig.LiquidationConfiguration.Mode.LIVE) {
            return new Verdict(shadowOutcome, SubmitVeto.MODE_NOT_LIVE, detail);
        }
        // S2 — the arming flag. Independent of S1 on purpose: two switches, so one flipped by
        // accident does not arm the bot.
        if (!configuration.isEnabled()) {
            return new Verdict(shadowOutcome, SubmitVeto.NOT_ARMED, detail);
        }
        // S3 — the network. A second line behind loans.enabled=false on mainnet, not a restatement
        // of it: this one is enforced here, in the code that would do the submitting.
        String networkName = network == null ? null : network.getNetwork();
        if (!SUBMITTABLE_NETWORK.equalsIgnoreCase(networkName)) {
            return new Verdict(shadowOutcome, SubmitVeto.NETWORK_NOT_PREVIEW,
                    "%s; network is %s, this epic submits only on %s"
                            .formatted(detail, networkName, SUBMITTABLE_NETWORK));
        }
        // S4 — profitability. Two independent gates, and the margin is deliberately NOT inside the
        // number the floors test (F1.i): a negative margin can no longer inflate a loss past a floor.
        //
        // (a) The absolute floor, applied only when check-profitability is on, tests floorProfit —
        //     the margin-EXCLUDED fee − txFee − minAdaFunded. Default floor 0: a floored loss is
        //     refused regardless of the margin. (O-2 seam: floorProfit's fee term is a mark-to-oracle
        //     token value, unchanged here — see record()'s javadoc.)
        //     ⛔ ignore-profit-check bypasses this gate AND (c) below. It is checked in both places
        //     rather than once around them because they are independent by construction, and a switch
        //     that reached only one would be indistinguishable from a broken one: a candidate whose
        //     floorProfit is negative is refused HERE whatever the margin does.
        if (!configuration.isIgnoreProfitCheck()
                && configuration.isCheckProfitability()
                && floorProfit.compareTo(configuration.getMinProfitAbsoluteLovelace()) < 0) {
            return new Verdict(LiquidationDecision.Outcome.UNPROFITABLE, SubmitVeto.NOT_PROFITABLE,
                    detail);
        }
        // (b) A percentage floor rides here too (SPEC §5). It is INERT for the current non-fronting
        //     Liquidate mode (SPEC S8): "percent of the principal advanced" has no advanced principal
        //     to apply to until a fronting mode (E1) exists. Left as a marked hook, wired then.
        //
        // (c) The margin lever, exactly where it was: strictly positive over the operator's margin.
        //     Breaking even is not a reason to move someone's collateral. Kept separate from the floor
        //     so it stays an operator preference, not a defeater of the floor.
        if (!configuration.isIgnoreProfitCheck() && expectedProfit.signum() <= 0) {
            return new Verdict(LiquidationDecision.Outcome.UNPROFITABLE, SubmitVeto.NOT_PROFITABLE,
                    detail);
        }
        // Record WHAT WAS IGNORED, not merely that something was. A decision log saying only
        // "proceeded" cannot answer "what would it have refused", which is the whole question an
        // operator has about a bot running with its loss protection off.
        if (configuration.isIgnoreProfitCheck()
                && (floorProfit.compareTo(configuration.getMinProfitAbsoluteLovelace()) < 0
                    || expectedProfit.signum() <= 0)) {
            log.warn("⛔ ignore-profit-check BYPASSED the profit gates for {}: {} — this liquidation "
                    + "would otherwise have been refused as unprofitable",
                    assessment.loan().utxoRef(), detail);
        }
        // S5 — the size, against the live parameter. Never a hard-coded 16384, and never inferred
        // from S4's arithmetic: a transaction can be handsomely profitable and still not fit.
        Integer maxTxSize;
        try {
            maxTxSize = protocolParamsSupplier.getProtocolParams().getMaxTxSize();
        } catch (Exception e) {
            // Not knowing the limit is not evidence of being under it. The veto is unchanged; what
            // changes (T-040) is that the operator can now find out WHY. This catch used to log
            // NOTHING AT ALL and interpolate e.toString() into the veto detail — which stops at the
            // outermost exception, so a transport timeout wrapped by its client showed as the wrapper
            // and the real fault was gone. A veto an operator cannot explain is a veto they cannot act on.
            log.error("could not fetch maxTxSize while vetting the liquidation of {}: {}",
                    assessment.loan().utxoRef(), causeChain(e), e);
            return new Verdict(LiquidationDecision.Outcome.SUBMIT_VETOED, SubmitVeto.TX_TOO_LARGE,
                    "%s; maxTxSize could not be fetched (%s), so the %d-byte transaction cannot be "
                            .formatted(detail, causeChain(e), size) + "cleared for submission");
        }
        if (maxTxSize == null) {
            return new Verdict(LiquidationDecision.Outcome.SUBMIT_VETOED, SubmitVeto.TX_TOO_LARGE,
                    "%s; the protocol parameters carry no maxTxSize, so the %d-byte transaction "
                            .formatted(detail, size) + "cannot be cleared for submission");
        }
        if (size > maxTxSize) {
            return new Verdict(LiquidationDecision.Outcome.SUBMIT_VETOED, SubmitVeto.TX_TOO_LARGE,
                    "%s; %d bytes over the live maxTxSize of %d — publish the reference scripts"
                            .formatted(detail, size, maxTxSize));
        }
        // S6 — the oracle windows, re-read against the clock NOW rather than against the window the
        // transaction was built for. A build that started a minute ago proves nothing about the feed
        // that is going to be evaluated when this lands in a block.
        String oracleVeto = oracleWindowShortfall(assessment, submitClock.getAsLong(), oraclesByUnit);
        if (oracleVeto != null) {
            return new Verdict(LiquidationDecision.Outcome.SUBMIT_VETOED,
                    SubmitVeto.ORACLE_WINDOW_TOO_SHORT_TO_SUBMIT, detail + "; " + oracleVeto);
        }
        // S7 — the two UTxOs, re-read immediately before the wire. They were unspent when the build
        // started; a block may have arrived since.
        String staleVeto = staleUtxo(assessment);
        if (staleVeto != null) {
            return new Verdict(LiquidationDecision.Outcome.SUBMIT_VETOED, SubmitVeto.STALE_UTXO,
                    detail + "; " + staleVeto);
        }

        // S8 — the transaction's own validity interval. Last, so that where a feed exists S6 reports
        // the more specific reason; but reached on every candidate, including the ada/ada ones S6
        // has nothing to say about.
        String elapsed = transactionWindowElapsed(transaction, submitClock.getAsLong(),
                assessment.loan().utxoRef());
        if (elapsed != null) {
            return new Verdict(LiquidationDecision.Outcome.SUBMIT_VETOED,
                    SubmitVeto.TRANSACTION_WINDOW_ELAPSED, detail + "; " + elapsed);
        }

        return submit(assessment, now, transaction, detail);
    }

    /**
     * S8. The end of the built body's validity interval, converted from its slot and compared with
     * the clock now.
     * <p>
     * A body with no ttl is treated as a veto rather than as "never expires": this builder always
     * sets one, so its absence means the transaction is not the one the vetoes were reasoning about.
     * A conversion that throws is a veto for the standing reason — being unable to establish the
     * check is failing it.
     *
     * @return null when the window is still open, otherwise why it is not
     */
    private String transactionWindowElapsed(Transaction transaction, long submitTime,
                                            String loanUtxoRef) {
        Long ttlSlot = transaction.getBody().getTtl();
        if (ttlSlot == null || ttlSlot <= 0) {
            return "the built transaction carries no validity end, so it cannot be shown unexpired";
        }
        long validToMillis;
        try {
            LocalDateTime endsAt = converters.slot().slotToTime(ttlSlot);
            validToMillis = endsAt.toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (Exception e) {
            // T-040: logged nothing, and buried e.toString() in the veto detail. Same rule as the
            // maxTxSize fetch above — the whole cause chain, at ERROR, with the throwable attached.
            log.error("could not convert slot {} to a time while vetting the liquidation of {}: {}",
                    ttlSlot, loanUtxoRef, causeChain(e), e);
            return "slot %d could not be converted to a time (%s), so the validity end is unknown"
                    .formatted(ttlSlot, causeChain(e));
        }
        if (submitTime > validToMillis) {
            return "the transaction's validity interval ended at %d (slot %d), %dms before submit time"
                    .formatted(validToMillis, ttlSlot, submitTime - validToMillis);
        }
        return null;
    }

    /**
     * S6. Every leg this loan prices against must still have at least the configured margin of feed
     * window ahead of it at {@code now}. Ada legs are exempt for the reason
     * {@link OraclePriceFeed#isSynthesisedUnitFeed()} gives: {@code retrieve_oracle_data} returns
     * their 1:1 feed before it reaches any window check.
     *
     * @param submitTime the clock at the moment of submitting, not the cycle's {@code now}
     * @return null when the windows are fine, otherwise why they are not
     */
    private String oracleWindowShortfall(LiquidationAssessment assessment, long submitTime,
                                         Map<String, OracleEntry> oraclesByUnit) {
        // An oracle registry we cannot consult is not a fresh one.
        if (oracleClient.getIfAvailable() == null) {
            return "the oracle registry client is unavailable, so no feed window can be re-checked";
        }
        long marginMillis = configuration.getOracleWindowMarginSeconds() * 1000L;
        LoanDatum datum = assessment.loan().datum();
        String principal = shortfall(datum.principalAsset().isAda(), datum.principalOracleAsset(),
                "principal", submitTime, marginMillis, oraclesByUnit);
        if (principal != null) {
            return principal;
        }
        return shortfall(datum.collateral().isAda(), datum.collateral().oracleTokenAsset(),
                "collateral", submitTime, marginMillis, oraclesByUnit);
    }

    private static String shortfall(boolean isAda, AssetType oracleToken, String which, long submitTime,
                                    long marginMillis, Map<String, OracleEntry> oraclesByUnit) {
        if (isAda) {
            return null;
        }
        OracleEntry entry = oraclesByUnit == null ? null : oraclesByUnit.get(oracleToken.toUnit());
        if (entry == null) {
            // Unreachable through a successful build — the builder refuses ORACLE_ENTRY_MISSING for
            // a non-ada leg with no entry in this very map, so by here it is present. Kept because
            // the alternative on the submit path is a NullPointerException, and the rule for this
            // chain is that not being able to check is failing the check.
            return "the %s leg has no oracle entry to re-check".formatted(which);
        }
        long remaining = entry.feed().validTo() - submitTime;
        if (remaining < marginMillis) {
            return "the %s feed has %dms of window left at submit time, %dms required"
                    .formatted(which, remaining, marginMillis);
        }
        return null;
    }

    /**
     * S7. Both UTxOs, re-resolved against the local index immediately before signing. A resolver
     * that throws is treated exactly like a spent UTxO: it did not say the output is still there.
     *
     * @return null when both are still unspent, otherwise why they are not
     */
    private String staleUtxo(LiquidationAssessment assessment) {
        try {
            if (utxoResolver.resolveLoanUtxo(assessment.loan()).isEmpty()) {
                return "the loan utxo is no longer unspent";
            }
            if (utxoResolver.resolveBondUtxo(assessment.bond()).isEmpty()) {
                return "the bond utxo is no longer unspent";
            }
            return null;
        } catch (Exception e) {
            // T-040: logged nothing, and buried e.toString() in the veto detail. A resolver that
            // throws is still treated exactly like a spent UTxO — the veto is unchanged — but the
            // operator now gets the whole cause chain at ERROR with the throwable attached.
            log.error("the utxo re-check threw while vetting the liquidation of {}: {}",
                    assessment.loan().utxoRef(), causeChain(e), e);
            return "the utxo re-check threw (" + causeChain(e)
                    + "), so neither utxo could be confirmed unspent";
        }
    }

    /**
     * Signs the vetted transaction and hands it over. The only place in this codebase that transmits
     * a liquidation.
     * <p>
     * {@code transaction} is the object every veto ran against, and {@code account.sign} splices one
     * vkey witness into its own serialisation without touching the body — the fee the builder
     * computed already accounts for exactly that one witness, and the payment key is the only one a
     * {@code Liquidate} needs. Nothing is rebuilt, re-balanced or re-priced here; there is no
     * builder on this class to do it with.
     * <p>
     * The loan UTxO is quarantined either way. That is what stops the next cycle re-deriving the
     * same candidate from an index that has not yet seen the spend and submitting a second time: the
     * quarantine is keyed on the loan UTxO ref, so it holds for that <em>output</em> until either
     * the quarantine lapses or the output is genuinely gone from the index — at which point the
     * scanner stops producing it anyway.
     */
    private Verdict submit(LiquidationAssessment assessment, long now, Transaction transaction,
                           String detail) {
        String loanUtxoRef = assessment.loan().utxoRef();
        quarantineUntil(loanUtxoRef, now + configuration.getQuarantineMinutes() * 60_000L);

        byte[] signed;
        try {
            signed = account.sign(transaction).serialize();
        } catch (Exception e) {
            // Not one of the seven — those are all about whether submitting is *allowed*, and this
            // is the machinery failing after they all said yes. It carries no veto name for exactly
            // that reason, and it still transmits nothing.
            log.error("could not sign the liquidation of {}: {}", loanUtxoRef, causeChain(e), e);
            return new Verdict(LiquidationDecision.Outcome.SUBMIT_VETOED, null,
                    detail + "; signing threw (" + causeChain(e) + "), nothing was transmitted");
        }

        try {
            Result<String> result = submitter.submit(signed);
            if (result != null && result.isSuccessful()) {
                log.info("SUBMITTED liquidation of {}: tx {}", loanUtxoRef, result.getValue());
                return new Verdict(LiquidationDecision.Outcome.SUBMITTED, null,
                        detail + "; submitted as " + result.getValue());
            }
            String response = result == null ? "no response" : result.getResponse();
            log.warn("submitting the liquidation of {} was rejected: {}", loanUtxoRef, response);
            return new Verdict(LiquidationDecision.Outcome.SUBMIT_FAILED, null,
                    detail + "; backend rejected it: " + response);
        } catch (Exception e) {
            // Transmitted or not — we do not know, which is exactly why the quarantine above was
            // taken before the attempt rather than after it.
            log.error("submitting the liquidation of {} threw: {}", loanUtxoRef, causeChain(e), e);
            return new Verdict(LiquidationDecision.Outcome.SUBMIT_FAILED, null,
                    detail + "; submission threw: " + causeChain(e));
        }
    }

    /**
     * Whether this candidate would be submitted on an armed node — the two S4 gates as one predicate,
     * so the shadow outcome and the unmeasured-body outcome say the same thing S4 does: it clears the
     * absolute floor (when check-profitability is on) AND clears the operator's margin.
     */
    private boolean wouldSubmit(BigInteger floorProfit, BigInteger expectedProfit) {
        if (configuration.isCheckProfitability()
                && floorProfit.compareTo(configuration.getMinProfitAbsoluteLovelace()) < 0) {
            return false;
        }
        return expectedProfit.signum() > 0;
    }

    /**
     * FIX 1 (E5-A / F4). The lovelace the builder funds on the emitted asset-manager outputs beyond
     * what the loan's own collateral carried — per-loan, never a constant.
     * <p>
     * The builder sends every funded asset-manager output (the borrower compensation output and the
     * lender claim output) to the asset-manager spend credential, and cardano-client-lib tops each to
     * its min-ada floor out of the bot's own inputs. So the ada the bot puts in is
     * {@code Σ(assetManagerOutput.ada) − adaFromTheLoanInput}: the spent loan UTxO's own ada is
     * available to cover those outputs before the bot adds any of its own, so it is the offset. The
     * ledger delta the bot's wallet sees is {@code L − A − F} (loan-UTxO ada {@code L}, asset-manager
     * output ada {@code A}, fee {@code F}), so the cost beyond the fee is {@code A − L} for either
     * collateral type — which is exactly this offset applied to {@code Σ(assetManagerOutput.ada)}:
     * <ul>
     *   <li><b>ada collateral</b> — the loan UTxO's ada <em>is</em> the collateral, and the
     *       {@code liquidationFee} slice returns to the bot rather than to the outputs, so the ada that
     *       actually flows into the outputs is {@code L − liquidationFee = collateralAmount −
     *       liquidationFee} regardless of how the builder split it between {@code equity} and
     *       {@code collateralPayout}. Only the shortfall between a sub-floor payout and the floor is
     *       actually funded.</li>
     *   <li><b>token collateral</b> — the outputs carry tokens and no ada of their own, so the whole of
     *       the loan UTxO's ada {@code L} is available to cover their min-ada floor; the offset is that
     *       ada ({@code loan().lovelace()}), and only the min-ada beyond it is funded.</li>
     * </ul>
     * Read off the finished body by credential (never a predicted position), exactly as the builder's
     * own {@code assetOutputIndexes} locates the same outputs. Clamped at zero as defence in depth: the
     * outputs can only ever carry their intrinsic ada plus a non-negative top-up.
     */
    private BigInteger minAdaFunded(LiquidationAssessment assessment, Transaction transaction) {
        String assetManagerCredential = registry.getAssetManagerSpendScriptHash();
        BigInteger outputAda = BigInteger.ZERO;
        for (TransactionOutput output : transaction.getBody().getOutputs()) {
            if (assetManagerCredential.equals(paymentCredentialOf(output.getAddress()))) {
                outputAda = outputAda.add(output.getValue().getCoin());
            }
        }
        BigInteger collateralAdaOffset = assessment.loan().datum().collateral().isAda()
                ? assessment.loan().collateralAmount().subtract(assessment.liquidationFee())
                : assessment.loan().lovelace();
        BigInteger funded = outputAda.subtract(collateralAdaOffset);
        return funded.signum() > 0 ? funded : BigInteger.ZERO;
    }

    /** The payment credential hash of an address, or null — the same read the builder does. */
    private static String paymentCredentialOf(String address) {
        return new Address(address).getPaymentCredentialHash().map(HexUtil::encodeHexString).orElse(null);
    }

    private static OraclePriceFeed collateralFeed(LiquidationAssessment assessment,
                                                  Map<String, OracleEntry> oraclesByUnit) {
        var collateral = assessment.loan().datum().collateral();
        if (collateral.isAda()) {
            return OraclePriceFeed.unit();
        }
        OracleEntry entry = oraclesByUnit.get(collateral.oracleTokenAsset().toUnit());
        // Unreachable through a successful build: the builder refuses ORACLE_ENTRY_MISSING for a
        // non-ada leg with no entry in this very map, so by here it is present.
        if (entry == null) {
            throw new IllegalStateException("no oracle entry for the collateral leg of loan "
                    + assessment.loan().loanId() + " after the transaction built");
        }
        return entry.feed();
    }

    /**
     * Which action this candidate is routed to — read from the SAME predicate {@code consider()}
     * routes on, so the record cannot disagree with what was built.
     *
     * <p>⚠ This was {@code LiquidationDecision.VARIANT}, hardcoded, for the entire time the convert
     * path was live: <b>every convert liquidation would have been recorded as a plain
     * {@code Liquidate}</b>. The field's own javadoc said it existed "so a second action can be
     * added later without every stored decision becoming ambiguous" — <b>the second action was
     * added and the field was not.</b> The convert path also emits no log line of its own, so
     * between the two it was armed, executable, and invisible except by inferring a FALLING wallet
     * balance from chain.
     */
    private static String variantOf(LiquidationAssessment assessment) {
        return assessment.bond().datum().shouldLiquidationConvertToPrincipal()
                ? LiquidationDecision.VARIANT_CONVERT
                : LiquidationDecision.VARIANT;
    }

    private LiquidationDecision decision(LiquidationAssessment assessment, long now,
                                         LiquidationDecision.Outcome outcome, String reason,
                                         String detail) {
        return decision(assessment, now, outcome, reason, detail, null);
    }

    private LiquidationDecision decision(LiquidationAssessment assessment, long now,
                                         LiquidationDecision.Outcome outcome, String reason,
                                         String detail, SubmitVeto veto) {
        return new LiquidationDecision(
                now,
                assessment.loan().loanId(),
                assessment.loan().utxoRef(),
                assessment.bond().utxoRef(),
                // ⚠ BOTH construction sites, not one. This helper and the verdict path at ~899 each
                // build a LiquidationDecision, and fixing only the first left every REFUSAL on the
                // convert path still labelled "Liquidate" while successes were labelled correctly —
                // which is worse than the original defect, because the record would then be
                // inconsistent with itself rather than uniformly wrong.
                variantOf(assessment),
                outcome,
                reason,
                detail,
                assessment.late(),
                assessment.remainingDebt(),
                assessment.equity(),
                assessment.liquidationFee(),
                assessment.loan().datum().collateral().assetType().toUnit(),
                null, null, null, null,
                null, null, null,
                null, null, null, null,
                veto == null ? null : veto.name());
    }

    // ---- cycle plumbing -----------------------------------------------------------------------

    /**
     * The bot's fee and collateral UTxO: ada only, no reference script, <em>and no datum</em>.
     * <p>
     * The first two clauses are {@code ScheduledTransactionService}'s. The two datum clauses are
     * this loop's own, and they are not belt-and-braces — they are the difference between the loop
     * working and the loop producing nothing at all. {@link LiquidateTransactionBuilder}'s
     * {@code WALLET_UTXO_NOT_ADA_ONLY} rejects a datum-carrying wallet UTxO, and a single-asset ada
     * UTxO with an inline datum is entirely routine in a bot wallet (a DEX order refund, an airdrop
     * claim). Selecting one here would refuse <em>every</em> candidate of <em>every</em> cycle for as
     * long as it sat in the wallet — and a refusal is not quarantined, so nothing would ever break
     * the loop out of it. Filtering it out here costs one clause; not filtering it costs the slice
     * its entire output, with no symptom louder than a repeated refusal reason.
     */
    private List<Utxo> nominableWalletUtxos() {
        List<Utxo> walletUtxos = appUtxoService.listWalletUtxo();
        if (walletUtxos.isEmpty()) {
            log.warn("No wallet UTXOs found for account: {}", account.baseAddress());
            return List.of();
        }
        List<Utxo> nominable = walletUtxos.stream()
                .filter(WalletInputSelection::nominable)
                .toList();
        if (nominable.isEmpty()) {
            // Say WHAT was rejected and WHY, not just that nothing qualified. The 2026-08-24 shadow
            // run cost a full diagnosis round to establish something this line would have stated
            // outright: the only UTxO at the bot's address was the published loan_claim_action
            // reference script. "No valid utxos" alone cannot tell an empty wallet apart from a
            // wallet whose entire contents are ineligible, and those need opposite responses.
            log.warn("no spendable wallet utxo at {} — {} candidate(s) all rejected: [{}]. The bot "
                            + "needs at least one utxo holding ONLY ada, with no datum and no "
                            + "reference script.",
                    account.baseAddress(), walletUtxos.size(),
                    walletUtxos.stream().map(LiquidationExecutor::whyNotSpendable)
                            .collect(java.util.stream.Collectors.joining("; ")));
        } else {
            log.info("{} spendable wallet utxo(s) this cycle, {} lovelace in the largest — each "
                            + "candidate nominates the SMALLEST that covers its own requirement",
                    nominable.size(),
                    WalletInputSelection.largestNominable(nominable).map(Object::toString).orElse("0"));
        }
        return nominable;
    }

    /**
     * T-052 — nominate the wallet input for ONE candidate: the smallest that covers the fee ceiling
     * plus whatever extra ada this liquidation itself must pay out.
     *
     * @param extraOutlay ada this transaction pays from the bot's own wallet beyond the fee — the
     *                    lender payout on the principal-repaying path, and {@code ZERO} on the
     *                    fee-only path, which is the distinction the whole ticket is about
     */
    private Optional<Utxo> nominate(List<Utxo> walletUtxos, Optional<BigInteger> feeCeiling,
                                    BigInteger extraOutlay, String loanUtxoRef) {
        Optional<Utxo> chosen = feeCeiling
                .map(ceiling -> WalletInputSelection.smallestSufficient(
                        walletUtxos, ceiling.add(extraOutlay)))
                .orElseGet(() -> WalletInputSelection.largest(walletUtxos));
        chosen.ifPresent(utxo -> log.info("{} nominates wallet utxo {}#{} ({} lovelace) for a "
                        + "requirement of {} + {} outlay{}",
                loanUtxoRef, utxo.getTxHash(), utxo.getOutputIndex(),
                LedgerCeilings.lovelaceOf(utxo),
                feeCeiling.map(Object::toString).orElse("an unknown fee"), extraOutlay,
                feeCeiling.isPresent() ? "" : " (largest, protocol params unavailable)"));
        return chosen;
    }

    /**
     * T-061 — <b>every wallet requirement this cycle can evaluate, stated together.</b>
     *
     * <h2>Why this exists</h2>
     * The refusal this replaced named ONE gate — the spend input's fee coverage — and told the
     * operator to <i>"fund the wallet with one ada-only utxo of at least that amount"</i>. <b>Doing
     * exactly that clears the spend gate and then fails the collateral one</b>, because T-050 made
     * collateral inputs a separate selection with a separate ceiling. The operator spends a funding
     * transaction and returns to the same refusal with the same confidence.
     * <p>
     * <b>A remedy is a claim.</b> If a message tells you what to do, doing it must be sufficient — or
     * the message must say it is not.
     *
     * <h2>⚠ And it may only name gates it has actually evaluated</h2>
     * Listing requirements this code has not checked would be a different lie, and a worse one:
     * an operator cannot tell a computed figure from a plausible one. So each line below is
     * <em>measured</em> here, and when the protocol parameters are unavailable the message says the
     * gates could not be sized rather than guessing at them.
     */
    static String walletDiagnosis(List<Utxo> walletUtxos, Optional<ProtocolParams> params,
                                   Optional<BigInteger> spendRequired, BigInteger extraOutlay) {
        String largest = WalletInputSelection.largestNominable(walletUtxos)
                .map(Object::toString).orElse("none at all");
        if (params.isEmpty() || spendRequired.isEmpty()) {
            return ("no ada-only wallet utxo could be nominated; largest nominable is %s. The "
                    + "protocol parameters could not be fetched this cycle, so neither the fee nor the "
                    + "collateral requirement could be sized — this names no figure rather than "
                    + "guessing one.").formatted(largest);
        }
        BigInteger spend = spendRequired.get().add(extraOutlay);
        return ("the wallet does not satisfy every requirement a liquidation has, and BOTH are listed "
                + "because funding for one does not satisfy the other. (1) SPEND INPUT: one ada-only, "
                + "datum-free, reference-script-free utxo holding at least %s lovelace (the most this "
                + "ledger could charge in fees%s); largest nominable is %s. %s Fund the wallet so both "
                + "hold, not just the first.")
                .formatted(spend,
                        extraOutlay.signum() > 0 ? " plus " + extraOutlay + " paid out" : "",
                        largest, collateralGate(walletUtxos, params));
    }

    /**
     * The second gate, which the spend gate masks. Collateral inputs are chosen separately from the
     * nominated spend input (T-050) and are summed across at most {@code maxCollateralInputs} utxos,
     * so this is a TOTAL rather than a single-utxo requirement — and an operator funding "one utxo of
     * at least X" can satisfy the spend gate and still miss it.
     */
    static String collateralGate(List<Utxo> walletUtxos, Optional<ProtocolParams> params) {
        if (params.isEmpty()) {
            return "(2) COLLATERAL: could not be sized — protocol parameters unavailable this cycle.";
        }
        ProtocolParams p = params.get();
        BigInteger required = LedgerCeilings.maxPossibleCollateral(p);
        int limit = Math.max(1, p.getMaxCollateralInputs());
        BigInteger available = walletUtxos.stream()
                .filter(WalletInputSelection::nominable)
                .map(LedgerCeilings::lovelaceOf)
                .sorted(java.util.Comparator.reverseOrder())
                .limit(limit)
                .reduce(BigInteger.ZERO, BigInteger::add);
        return ("(2) COLLATERAL: at least %s lovelace in TOTAL across at most %d nominable utxos, "
                + "chosen separately from the spend input; available %s.")
                .formatted(required, limit, available);
    }

    /**
     * <b>Where the validity window starts from: the earlier of wall clock and the chain's own
     * position.</b>
     *
     * @param lastAppliedSlot the last block this node applied, or {@code 0} if none has arrived yet —
     *                        <b>zero means UNKNOWN, not "the epoch"</b>, and falls back to wall clock
     *                        rather than anchoring a transaction at 1970
     */
    static long windowAnchorMillis(long nowMillis, long lastAppliedSlot, CardanoConverters converters) {
        if (lastAppliedSlot <= 0) {
            return nowMillis;
        }
        long tipMillis = converters.slot().slotToTime(lastAppliedSlot)
                .toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
        return Math.min(nowMillis, tipMillis);
    }

    /** Why one wallet candidate cannot be spent, for the operator-facing rejection list. */
    private static String whyNotSpendable(Utxo utxo) {
        List<String> reasons = new ArrayList<>();
        if (utxo.getAmount().size() != 1) {
            reasons.add("carries " + utxo.getAmount().size() + " assets, not ada alone");
        }
        if (utxo.getReferenceScriptHash() != null) {
            reasons.add("carries reference script " + utxo.getReferenceScriptHash());
        }
        if (utxo.getInlineDatum() != null) {
            reasons.add("carries an inline datum");
        }
        if (utxo.getDataHash() != null) {
            reasons.add("carries a datum hash");
        }
        return utxo.getTxHash() + "#" + utxo.getOutputIndex()
                + (reasons.isEmpty() ? " eligible" : " " + String.join(", ", reasons));
    }

    /**
     * The oracle registry as it stands right now, keyed the way the builder wants it: by the oracle
     * NFT's unit, which is what a loan datum points at and what {@code retrieve_oracle_data} matches
     * a reference input against — not by the priced asset.
     */
    private Map<String, OracleEntry> oracleSnapshot() {
        FluidOracleClient client = oracleClient.getIfAvailable();
        if (client == null) {
            return Map.of();
        }
        Map<String, OracleEntry> byUnit = new LinkedHashMap<>();
        for (OracleEntry entry : client.entries()) {
            byUnit.putIfAbsent(entry.oracleToken().toUnit(), entry);
        }
        return Map.copyOf(byUnit);
    }

    private static Map<LiquidationExclusion, Integer> histogram(List<LiquidationAssessment> assessments) {
        Map<LiquidationExclusion, Integer> histogram = new EnumMap<>(LiquidationExclusion.class);
        for (LiquidationAssessment assessment : assessments) {
            if (assessment.exclusion() != null) {
                histogram.merge(assessment.exclusion(), 1, Integer::sum);
            }
        }
        return histogram;
    }

    // ---- quarantine ---------------------------------------------------------------------------

    private boolean isQuarantined(String loanUtxoRef, long now) {
        Long until = quarantine.get(loanUtxoRef);
        if (until == null) {
            return false;
        }
        if (until <= now) {
            quarantine.remove(loanUtxoRef, until);
            return false;
        }
        return true;
    }

    private void expireQuarantine(long now) {
        quarantine.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    /**
     * Quarantines one <b>loan UTxO ref</b> — {@code txHash#index}, never a loan id. The distinction
     * is load-bearing: a loan id outlives the UTxO that carries it (it is minted once and burned at
     * the end), so keying on it would exclude a borrower's loan across every re-creation of its
     * UTxO, while keying on the ref means a quarantine dies naturally the moment the output is
     * spent by anyone.
     * <p>
     * Package-private so the eviction and expiry rules can be driven directly from a test; nothing
     * outside this package quarantines anything.
     */
    void quarantineUntil(String loanUtxoRef, long until) {
        if (quarantine.size() >= MAX_QUARANTINED && !quarantine.containsKey(loanUtxoRef)) {
            List<Map.Entry<String, Long>> soonest = new ArrayList<>(quarantine.entrySet());
            soonest.sort(Comparator.comparing(Map.Entry::getValue));
            quarantine.remove(soonest.getFirst().getKey());
        }
        quarantine.put(loanUtxoRef, until);
    }

    /** How many loan UTxOs are currently quarantined. */
    int quarantinedCount() {
        return quarantine.size();
    }

    /** Which loan UTxO refs are currently quarantined — the eviction rule's observable half. */
    Set<String> quarantinedRefs() {
        return Set.copyOf(quarantine.keySet());
    }
}
