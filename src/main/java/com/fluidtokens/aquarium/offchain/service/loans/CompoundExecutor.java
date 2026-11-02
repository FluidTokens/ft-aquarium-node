package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.config.ProtocolParamsRejections;
import com.fluidtokens.aquarium.offchain.model.loans.CompoundAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.CompoundCandidate;
import com.fluidtokens.aquarium.offchain.model.loans.CompoundExclusion;
import com.fluidtokens.aquarium.offchain.service.AppUtxoService;
import com.fluidtokens.aquarium.offchain.service.BlockEventListener;
import com.fluidtokens.aquarium.offchain.service.SubmitFence;
import com.fluidtokens.aquarium.offchain.service.WorkResult;
import com.fluidtokens.aquarium.offchain.service.wallet.WalletRebalancer;
import com.fluidtokens.aquarium.offchain.service.wallet.WalletRoles;
import com.fluidtokens.aquarium.offchain.service.wallet.WalletShape;
import com.fluidtokens.aquarium.offchain.util.LedgerCeilings;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.conversions.CardanoConverters;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The compound loop: scan for repayment escrows, price them, build, and submit.
 *
 * <h2>Why building comes BEFORE the profitability gate</h2>
 * The gate compares {@code expectedFee} against the transaction's own fee, and that fee is not known
 * until the transaction is built and balanced. So the order is scan → build → assess → submit, not
 * scan → assess → build. <b>Building is free</b> — no signature, nothing transmitted, nothing on
 * chain — so pricing by building costs an operator nothing and is the only way to price honestly.
 * The alternative, estimating a fee to decide whether to compute a fee, would be a second model of
 * the same number and would drift from it.
 *
 * <h2>Arming</h2>
 * Two independent conditions, composed with AND and unable to contradict each other:
 * {@code loans.compound.enabled} decides whether the loop acts at all, and
 * {@code loans.compound.profit-margin-lovelace} decides which candidates clear. Neither is a
 * substitute for the other — "do not run this" must not be expressible only as an absurd margin.
 *
 * <h2>Every refusal is logged with its reason</h2>
 * An operator asking "why did it not compound?" must find the answer in the log rather than in the
 * source. Structural refusals come from {@link CompoundCandidateScanner}, economic ones from
 * {@link CompoundEconomics}, and both are printed with the arithmetic that produced them.
 *
 * <h2>⛔ Funded from U, with collateral C (FAB-185)</h2>
 * The wallet is read ONCE per walk, after the scan and only when an armed walk has a ready candidate, and
 * its roles are resolved from that read ({@link WalletRoles}): C, the ada-only output of exactly
 * {@code WalletShape.collateralLovelace(params)}, and U, the largest other ada-only output. Every candidate
 * of the walk is funded from U and pins C as its only collateral; the builder sees the wallet holding U
 * alone, so no compound can spend C, a token output or a published reference script. A missing C or U is
 * said ONCE per role per walk at WARN, naming the role and the wallet rebalancer's last outcome — the
 * rebalancer is what keeps C and U in place — and nothing is built.
 */
@Service
@Slf4j
public class CompoundExecutor {

    /** 30 s of backdate, matching the liquidation path. Anchored to the tip, never to the clock. */
    private static final long VALID_FROM_BACKDATE_MILLIS = 30_000L;
    private static final long VALIDITY_WINDOW_MILLIS = 120_000L;
    /** FAB-180: an unchanged cycle summary is still said at INFO this often, so a quiet log is not a dead one. */
    private static final Duration SUMMARY_HEARTBEAT = Duration.ofHours(1);

    /** Everything this class can do to the network: hand over bytes. */
    @FunctionalInterface
    public interface TransactionSubmitter {
        Result<String> submit(byte[] signedTransactionBytes) throws Exception;
    }

    /**
     * FAB-180: what one cycle's walk saw, for its single summary line. Counting only — nothing reads it
     * to decide anything. {@code escrows == Σrefused + ready}, and every walk outcome counted here
     * (not approved, not armed, not reached) is a ready candidate.
     */
    static final class CycleTally {
        int escrows;
        int priced;
        int ready;
        final Map<String, Integer> refused = new TreeMap<>();
        final Map<String, Integer> notApproved = new TreeMap<>();
        int notArmed;
        int notReached;

        void notApproved(CompoundExclusion exclusion) {
            notApproved.merge(String.valueOf(exclusion), 1, Integer::sum);
        }

        String summary() {
            StringBuilder line = new StringBuilder("compound scan: ")
                    .append(escrows).append(" escrows, ")
                    .append(priced).append(" priced, ")
                    .append(ready).append(" ready, refused ").append(refused)
                    .append(", not approved ").append(notApproved);
            if (notArmed > 0) {
                line.append(", not armed ").append(notArmed);
            }
            if (notReached > 0) {
                line.append(", not reached ").append(notReached);
            }
            return line.toString();
        }
    }

    private final AppConfig.CompoundConfiguration configuration;
    private final AppConfig.Network network;
    private final BlockEventListener blockEventListener;
    private final AppUtxoService appUtxoService;
    private final Account account;
    private final CompoundCandidateScanner scanner;
    private final CompoundEconomics economics;
    private final CompoundTransactionBuilder builder;
    private final LiquidationUtxoResolver utxoResolver;
    private final UtxoSupplier utxoSupplier;
    private final CardanoConverters converters;
    /** FAB-185: sizes C ({@code WalletShape.collateralLovelace}) and U's fee ceiling, once per walk. */
    private final ProtocolParamsSupplier protocolParamsSupplier;
    private final TransactionSubmitter submitter;

    /**
     * FAB-185: the wallet rebalancer, read for one thing only — its last outcome, which every wallet-role
     * refusal names, because the rebalancer is what keeps C and U in place. Setter-injected and REQUIRED in
     * the container, like the liquidation executor's; null only in a direct test construction, where a
     * refusal says the rebalancer is not wired.
     */
    private WalletRebalancer walletRebalancer;

    @Autowired
    public void setWalletRebalancer(WalletRebalancer walletRebalancer) {
        this.walletRebalancer = walletRebalancer;
    }

    /**
     * ⛔ Closed by {@code LoansConfigVerifier} when the live Lending v4 config does not match what this
     * node derives (FAB-115). Setter-injected and REQUIRED in the container: a missing gate bean must fail
     * the boot loudly, never leave lending ungated. Direct constructions in tests run ungated.
     */
    private com.fluidtokens.aquarium.offchain.service.LendingConfigGate lendingConfigGate;
    private volatile boolean lendingGateRefusalLogged;

    /** FAB-180: read ONLY for the summary's heartbeat. Nothing that builds, prices or submits uses it. */
    Clock clock = Clock.systemUTC();
    private String lastInfoSummary;
    private Instant lastInfoSummaryAt;

    void setClock(Clock clock) {
        this.clock = clock;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setLendingConfigGate(com.fluidtokens.aquarium.offchain.service.LendingConfigGate gate) {
        this.lendingConfigGate = gate;
    }

    /** True, and logged once, when the Lending v4 config gate is closed. */
    private boolean refusedByLendingConfigGate(String what) {
        if (lendingConfigGate == null || !lendingConfigGate.isBlocked()) {
            return false;
        }
        if (!lendingGateRefusalLogged) {
            lendingGateRefusalLogged = true;
            log.error("{} REFUSED for the life of this process: {} — {}", what,
                    com.fluidtokens.aquarium.offchain.service.LendingConfigGate.REFUSAL,
                    lendingConfigGate.blockedReason().orElse(""));
        }
        return true;
    }

    /**
     * ⛔ {@code @Autowired} IS LOAD-BEARING. This class has two constructors, and with neither marked
     * Spring does not pick one — it looks for a no-arg constructor, finds none, and the context fails
     * to start with {@code NoSuchMethodException: CompoundExecutor.<init>()}. Nothing about that
     * message names the real cause.
     *
     * <p>Measured 2026-09-02: an image shipped without this annotation crash-looped in 14 seconds and
     * took the bot down, on a Recreate singleton where <b>a bad image is an outage every time</b>.
     * {@code LiquidationExecutor} has carried the annotation since it was written; this class copied
     * its two-constructor SHAPE and not its ANNOTATION. <b>A sibling's shape is not its wiring.</b>
     * {@code ExecutorContextResolutionTest} now fails on this, so the next omission is a red suite
     * rather than a rollback.
     */
    @Autowired
    public CompoundExecutor(AppConfig.CompoundConfiguration configuration,
                            AppConfig.Network network,
                            BlockEventListener blockEventListener,
                            AppUtxoService appUtxoService,
                            Account account,
                            CompoundCandidateScanner scanner,
                            CompoundEconomics economics,
                            CompoundTransactionBuilder builder,
                            LiquidationUtxoResolver utxoResolver,
                            UtxoSupplier utxoSupplier,
                            CardanoConverters converters,
                            ProtocolParamsSupplier protocolParamsSupplier,
                            BFBackendService backendService) {
        this(configuration, network, blockEventListener, appUtxoService, account, scanner, economics,
                builder, utxoResolver, utxoSupplier, converters, protocolParamsSupplier,
                bytes -> {
                    // FAB-134 B5a: a rejection naming the parameters refreshes them; the result itself
                    // is returned untouched (nothing retried, no outcome changed).
                    Result<String> result = backendService.getTransactionService().submitTransaction(bytes);
                    ProtocolParamsRejections.refreshIfParamsRejected(protocolParamsSupplier, result);
                    return result;
                });
    }

    /**
     * The same loop with the submitter stated, so a test can watch exactly what reaches the wire. The
     * protocol parameters are stated too (FAB-185): they size the wallet's roles C and U.
     */
    public CompoundExecutor(AppConfig.CompoundConfiguration configuration,
                            AppConfig.Network network,
                            BlockEventListener blockEventListener,
                            AppUtxoService appUtxoService,
                            Account account,
                            CompoundCandidateScanner scanner,
                            CompoundEconomics economics,
                            CompoundTransactionBuilder builder,
                            LiquidationUtxoResolver utxoResolver,
                            UtxoSupplier utxoSupplier,
                            CardanoConverters converters,
                            ProtocolParamsSupplier protocolParamsSupplier,
                            TransactionSubmitter submitter) {
        this.configuration = configuration;
        this.network = network;
        this.blockEventListener = blockEventListener;
        this.appUtxoService = appUtxoService;
        this.account = account;
        this.scanner = scanner;
        this.economics = economics;
        this.builder = builder;
        this.utxoResolver = utxoResolver;
        this.utxoSupplier = utxoSupplier;
        this.converters = converters;
        this.protocolParamsSupplier = protocolParamsSupplier;
        this.submitter = submitter;
    }

    /** One walk behind a fence that never closes. */
    WorkResult cycle() {
        return cycle(SubmitFence.OPEN);
    }

    /**
     * One walk. Returns the terminal outcome that ended it, or {@link WorkResult#DEFINITE_FAILURE} when
     * nothing terminal happened — including every return before the walk. {@code fence} is read before
     * each ready candidate and immediately before transmitting; once it reads {@code false} nothing more
     * is transmitted, the walk ends, and the summary counts the rest as {@code not reached}. It is not
     * retained after the call.
     */
    public WorkResult cycle(SubmitFence fence) {
        if (refusedByLendingConfigGate("compound")) {
            return WorkResult.DEFINITE_FAILURE;
        }
        if (blockEventListener.getIsSyncing().get()) {
            log.debug("compound: still syncing, skipping the cycle");
            return WorkResult.DEFINITE_FAILURE;
        }

        CompoundCandidateScanner.Scan scan = scanner.scan();
        if (scan.candidates().isEmpty()) {
            log.debug("compound: no asset-manager escrows in the index");
            return WorkResult.DEFINITE_FAILURE;
        }

        CycleTally tally = new CycleTally();
        for (CompoundCandidate candidate : scan.candidates()) {
            tally.escrows++;
            if (candidate.structurallyReady()) {
                tally.ready++;
            } else {
                tally.refused.merge(String.valueOf(candidate.exclusion()), 1, Integer::sum);
            }
        }

        // FAB-144 — THE MOST PROFITABLE ESCROW FIRST. Only the ORDER changes: every candidate is still
        // walked exactly once, and one whose fee cannot be estimated — refused, non-ada, no amount —
        // goes last rather than missing. Guarded: ranking must never cost a compound, so a fault in it
        // is reported and the scan's own order is walked instead.
        List<CompoundCandidate> ordered = scan.candidates();
        if (ordered.size() >= 2) {
            try {
                ordered = mostProfitableFirstThisCycle(scan.candidates(), tally);
            } catch (RuntimeException e) {
                log.warn("compound: ranking this cycle's candidates failed ({}); they are walked in "
                        + "scan order", e.toString());
            }
        } else {
            // FAB-180: no ranking ran, so the one candidate's reward is computed here, once, and only
            // counted. A fault leaves it unpriced; it must never cost the cycle.
            try {
                if (grossRecoverableLovelace(ordered.getFirst()).isPresent()) {
                    tally.priced = 1;
                }
            } catch (RuntimeException e) {
                tally.priced = 0;
            }
        }

        // ⛔ FAB-185 — THE WALLET'S ROLES, RESOLVED ONCE FOR THIS WALK FROM ONE READ.
        //
        // Below the scan and the ranking, and only when an ARMED walk has a ready candidate: a disarmed walk
        // builds nothing and never needed the wallet, so it still does not read it. Every candidate of the
        // walk is funded from U with C as its collateral; a missing role is said once per role per walk.
        WalletCheck walletCheck = configuration.isEnabled() && tally.ready > 0 ? walletCheck() : null;

        // Every escrow is reported, refused or not: "nothing to do" and "eight things all blocked"
        // must not look alike to an operator. Per escrow at DEBUG; the cycle's summary carries the counts.
        int readyWalked = 0;
        for (CompoundCandidate candidate : ordered) {
            if (!candidate.structurallyReady()) {
                log.debug("compound REFUSED {} escrow {}: {} — {}", candidate.loanId(),
                        candidate.escrowRef(), candidate.exclusion(), candidate.detail());
                continue;
            }
            if (!fence.mayProceed()) {
                // FAB-183: the chain rolled back or the pass deadline passed. Nothing more goes out.
                tally.notReached = tally.ready - readyWalked;
                logSummary(tally);
                return WorkResult.DEFINITE_FAILURE;
            }
            readyWalked++;
            WorkResult outcome = consider(candidate, tally, fence, walletCheck);
            if (outcome.terminal()) {
                tally.notReached = tally.ready - readyWalked;
                logSummary(tally);
                return outcome;
            }
        }
        logSummary(tally);
        return WorkResult.DEFINITE_FAILURE;
    }

    /**
     * FAB-180: one line per walk. INFO when it says something the last INFO summary did not, or when that
     * line is {@link #SUMMARY_HEARTBEAT} old; DEBUG otherwise, so an unchanging world is quiet.
     */
    private void logSummary(CycleTally tally) {
        String summary = tally.summary();
        Instant now = clock.instant();
        if (!summary.equals(lastInfoSummary)
                || Duration.between(lastInfoSummaryAt, now).compareTo(SUMMARY_HEARTBEAT) >= 0) {
            lastInfoSummary = summary;
            lastInfoSummaryAt = now;
            log.info("{}", summary);
        } else {
            log.debug("{}", summary);
        }
    }

    // ---- the order a cycle walks its candidates in (FAB-144) ------------------------------------

    /**
     * {@code candidates} reordered by expected fee, each computed once, and the order logged — at INFO
     * when at least one is priced, at DEBUG when none is. The priced count goes to {@code tally} last, so
     * a ranking that throws leaves it at zero.
     */
    private List<CompoundCandidate> mostProfitableFirstThisCycle(List<CompoundCandidate> candidates,
                                                                 CycleTally tally) {
        Map<CompoundCandidate, Optional<BigInteger>> rewards = new IdentityHashMap<>();
        for (CompoundCandidate candidate : candidates) {
            rewards.put(candidate, grossRecoverableLovelace(candidate));
        }
        List<CompoundCandidate> ordered = mostProfitableFirst(candidates, rewards::get);
        String order = ordered.stream()
                .map(candidate -> candidate.escrowRef() + rewards.get(candidate)
                        .map(fee -> " gross≈" + fee + " lovelace").orElse(" gross unpriced"))
                .collect(Collectors.joining(", "));
        int priced = (int) rewards.values().stream().filter(Optional::isPresent).count();
        if (priced > 0) {
            log.info("compound order (most profitable first): {}", order);
        } else {
            log.debug("compound order (most profitable first): {}", order);
        }
        tally.priced = priced;
        return ordered;
    }

    /** Legacy ADA-only ranking helper retained for source compatibility with readiness checks. */
    static Optional<BigInteger> expectedFeeLovelace(CompoundCandidate candidate) {
        try {
            if (candidate == null || !candidate.structurallyReady() || candidate.addedLiquidity() == null
                    || !candidate.bond().datum().principalAsset().isAda()) {
                return Optional.empty();
            }
            return Optional.of(CompoundEconomics.compoundingFee(candidate.addedLiquidity(),
                    candidate.feePerMille(), candidate.bond().datum().principalAsset()));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private Optional<BigInteger> grossRecoverableLovelace(CompoundCandidate candidate) {
        if (candidate == null || !candidate.structurallyReady() || candidate.addedLiquidity() == null
                || candidate.bond() == null || candidate.bond().datum() == null) {
            return Optional.empty();
        }
        BigInteger escrowCoin = candidate.escrow().getAmount().stream()
                .filter(a -> "lovelace".equals(a.getUnit()))
                .map(a -> a.getQuantity()).findFirst().orElse(BigInteger.ZERO);
        return economics.grossRecoverableLovelace(candidate.bond().datum().principalAsset(),
                candidate.addedLiquidity(), candidate.feePerMille(), escrowCoin,
                System.currentTimeMillis());
    }

    /**
     * A NEW list holding every element of {@code candidates}, each exactly once, in descending order of
     * {@code reward}. A candidate whose reward is empty, null or throws sorts after every priced one;
     * ties, and order among the unpriced, break on {@code escrowRef()} ascending. {@code reward} is
     * applied once per element.
     */
    static List<CompoundCandidate> mostProfitableFirst(List<CompoundCandidate> candidates,
                                                       Function<CompoundCandidate, Optional<BigInteger>> reward) {
        Map<CompoundCandidate, BigInteger> keys = new IdentityHashMap<>();
        for (CompoundCandidate candidate : candidates) {
            BigInteger key;
            try {
                Optional<BigInteger> estimate = reward.apply(candidate);
                key = estimate == null ? null : estimate.orElse(null);
            } catch (RuntimeException e) {
                key = null;
            }
            keys.put(candidate, key);
        }
        List<CompoundCandidate> ordered = new ArrayList<>(candidates);
        ordered.sort(Comparator.comparing((CompoundCandidate candidate) -> keys.get(candidate),
                        Comparator.nullsLast(Comparator.<BigInteger>reverseOrder()))
                .thenComparing(candidate -> candidate == null ? null : candidate.escrowRef(),
                        Comparator.nullsLast(Comparator.<String>naturalOrder())));
        return ordered;
    }

    private WorkResult consider(CompoundCandidate candidate, CycleTally tally, SubmitFence fence,
                                WalletCheck walletCheck) {
        if (!configuration.isEnabled()) {
            // Not a fault. Said at DEBUG per escrow (FAB-180); the cycle summary's `not armed` count is
            // what answers an operator asking why a ready candidate went unprocessed.
            tally.notArmed++;
            log.debug("compound READY but NOT ARMED {} escrow {}: Q={} raw units into pool {} at "
                            + "compoudingFeePerMille={} — set loans.compound.enabled=true to act on it",
                    candidate.loanId(), candidate.escrowRef(), candidate.addedLiquidity(),
                    candidate.poolId(), candidate.feePerMille());
            return WorkResult.DEFINITE_FAILURE;
        }

        Optional<Utxo> bondUtxo = utxoResolver.resolveBondUtxo(candidate.bond());
        Optional<Utxo> configUtxo = utxoResolver.resolveConfigUtxo();
        Optional<Utxo> lmConfigUtxo = utxoResolver.resolveLmConfigUtxo();
        if (bondUtxo.isEmpty() || configUtxo.isEmpty() || lmConfigUtxo.isEmpty()) {
            log.warn("compound SKIPPED {}: the index is missing {}{}{}", candidate.loanId(),
                    bondUtxo.isEmpty() ? "the lender bond " : "",
                    configUtxo.isEmpty() ? "the config " : "",
                    lmConfigUtxo.isEmpty() ? "the lm config " : "");
            return WorkResult.DEFINITE_FAILURE;
        }

        // ⛔ FAB-185: funded from U, the walk's ONE ordinary wallet input, with C as the only collateral —
        // both resolved once for the walk, never re-read here. A missing role or a U below the fee ceiling
        // refuses this candidate; nothing is built from another wallet output instead.
        Utxo fundingUtxo = designatedU(candidate, walletCheck);
        if (fundingUtxo == null) {
            return WorkResult.DEFINITE_FAILURE;
        }
        Utxo collateralUtxo = walletCheck.roles().c().orElseThrow();

        // THE validator's fee (ADA: at least 1 ADA since 2026-10-26; token: Q*c/1000). It is what the
        // builder hands the pool owner's share to, what the pool output is reduced by, and what is priced.
        BigInteger fee = CompoundEconomics.compoundingFee(candidate.addedLiquidity(), candidate.feePerMille(),
                candidate.bond().datum().principalAsset());

        long now = System.currentTimeMillis();
        long anchor = Math.min(now, tipMillis());
        long[] slots = {
                converters.time().toSlot(java.time.Instant.ofEpochMilli(anchor - VALID_FROM_BACKDATE_MILLIS)
                        .atZone(java.time.ZoneOffset.UTC).toLocalDateTime()),
                converters.time().toSlot(java.time.Instant.ofEpochMilli(now + VALIDITY_WINDOW_MILLIS)
                        .atZone(java.time.ZoneOffset.UTC).toLocalDateTime())
        };

        PricingService.Priced quote = economics.quote(
                candidate.bond().datum().principalAsset(), fee, now);
        if (!quote.isPriced()) {
            CompoundExclusion exclusion = CompoundEconomics.priceExclusion(quote.refusal());
            tally.notApproved(exclusion);
            log.debug("compound NOT APPROVED {} escrow {}: {} — F={} raw units, quote={}",
                    candidate.loanId(), candidate.escrowRef(), exclusion, fee, quote.refusal());
            return WorkResult.DEFINITE_FAILURE;
        }

        CompoundTransactionBuilder.Built built;
        try {
            built = builder.buildWithFacts(new CompoundTransactionBuilder.Request(
                    candidate, referenceScripts(), bondUtxo.get(), configUtxo.get(), lmConfigUtxo.get(),
                    fundingUtxo,
                    account.baseAddress(), fee, slots[0], slots[1], collateralUtxo));
        } catch (CompoundTransactionBuilder.RefusedException e) {
            log.warn("compound REFUSED BY BUILDER {} escrow {}: {}", candidate.loanId(),
                    candidate.escrowRef(), e.getMessage());
            return WorkResult.DEFINITE_FAILURE;
        } catch (Exception e) {
            log.error("compound BUILD FAILED {} escrow {}: {}", candidate.loanId(),
                    candidate.escrowRef(), e.toString(), e);
            return WorkResult.DEFINITE_FAILURE;
        }

        // ⛔ The gate runs on the BUILT transaction's own fee — measured, not estimated.
        // The principal asset is passed honestly (never a boolean smuggling a pricing decision).
        // The exact quote retained above and the kernel's final-body facts are the admission inputs.
        CompoundAssessment assessment = economics.assessFinal(true, true,
                candidate.bond().datum().principalAsset(), candidate.addedLiquidity(),
                candidate.feePerMille(), built.facts(), quote);

        if (!assessment.approved()) {
            tally.notApproved(assessment.exclusion());
            log.debug("compound NOT APPROVED {} escrow {}: {} — Q={} raw units at compoudingFeePerMille={} "
                            + "gives F={} raw units, priced reward={} lovelace, recovered E={} lovelace, "
                            + "final fee L={} lovelace, pool top-up T={} lovelace, net={} lovelace "
                            + "against floor={} lovelace{}",
                    candidate.loanId(), candidate.escrowRef(), assessment.exclusion(),
                    assessment.escrow(), assessment.feePerMille(), assessment.expectedFee(),
                    assessment.expectedFeeLovelace(), assessment.recoveredEscrowLovelace(),
                    assessment.txFee(), assessment.poolTopUpLovelace(), assessment.net(), assessment.floor(),
                    assessment.zeroFeePool()
                            ? " (this pool publishes compoudingFeePerMille = 0: an ADA pool still pays the "
                              + "1 ADA floor and a token pool pays nothing; this measured net requires a "
                              + "lower operator-stated floor)"
                            : "");
            return WorkResult.DEFINITE_FAILURE;
        }

        return submit(candidate, built, assessment, quote, tally, fence);
    }

    private WorkResult submit(CompoundCandidate candidate, CompoundTransactionBuilder.Built built,
                              CompoundAssessment assessment, PricingService.Priced quote,
                              CycleTally tally, SubmitFence fence) {
        // ⛔ THE NETWORK GATE IS GONE — `loans.submittable-network` was removed on 2026-09-04
        // (Giovanni: "a barrier that silently blocks submission even when everything else is armed
        // is a bug, not a safeguard"). `config.network` alone decides where this node acts, and it
        // is not a permission to check here: by the time control reaches this method the operator
        // has already said which network to run on.
        //
        // ⚑ ONE BOOLEAN ON THIS PATH, AND THAT IS RULED, NOT AN OVERSIGHT. Between a candidate and a
        // real submission the liquidation path has `mode == live` plus the market's own effective
        // mode, plus two profit floors; compound has `loans.compound.enabled` plus one floor.
        //
        // Giovanni ruled the asymmetry deliberate on 2026-09-04, when the question was put to him
        // directly: compound stays ON/OFF plus a minimum margin — "shadow doesn't make sense, it's a
        // low-risk operation". It advances no capital and moves already-repaid principal into the
        // pool that is owed it. ⛔ So do NOT add a compound mode or a second switch here; that was
        // considered and declined. The same day removed liquidation's own redundant second boolean,
        // for the same reason: gates that restate each other are cost without safety.

        Transaction transaction = built.transaction();
        if (!Arrays.equals(built.facts().bodyIdentity(),
                CompoundTransactionBuilder.bodyIdentity(transaction))) {
            log.warn("compound REFUSED {}: validated transaction body changed before signing",
                    candidate.loanId());
            return WorkResult.DEFINITE_FAILURE;
        }
        CompoundExclusion observationRefusal = economics.revalidateObservation(assessment,
                candidate.bond().datum().principalAsset(), System.currentTimeMillis());
        if (observationRefusal != null) {
            tally.notApproved(observationRefusal);
            log.info("compound NOT APPROVED {} escrow {}: {} immediately before signing",
                    candidate.loanId(), candidate.escrowRef(), observationRefusal);
            return WorkResult.DEFINITE_FAILURE;
        }
        CompoundAssessment finalAssessment = economics.assessFinal(true, true,
                candidate.bond().datum().principalAsset(), candidate.addedLiquidity(),
                candidate.feePerMille(), built.facts(), quote);
        if (!finalAssessment.approved()) {
            tally.notApproved(finalAssessment.exclusion());
            log.info("compound NOT APPROVED {} escrow {}: {} on the immediate pre-sign recheck",
                    candidate.loanId(), candidate.escrowRef(), finalAssessment.exclusion());
            return WorkResult.DEFINITE_FAILURE;
        }
        assessment = finalAssessment;

        byte[] signed;
        try {
            signed = account.sign(transaction).serialize();
            Transaction signedTransaction = Transaction.deserialize(signed);
            if (!Arrays.equals(built.facts().bodyIdentity(),
                    CompoundTransactionBuilder.bodyIdentity(signedTransaction))) {
                log.error("compound SIGN FAILED {}: signer changed the validated transaction body; "
                        + "nothing was transmitted", candidate.loanId());
                return WorkResult.DEFINITE_FAILURE;
            }
        } catch (Exception e) {
            log.error("compound SIGN FAILED {}: {} — nothing was transmitted",
                    candidate.loanId(), e.toString(), e);
            return WorkResult.DEFINITE_FAILURE;
        }

        // ⛔ THE LAST LOOK BEFORE THE WIRE. A definite failure, not a terminal outcome: nothing went out,
        // and the walk's own fence check stops it before the next candidate.
        if (!fence.mayProceed()) {
            log.info("compound FENCED {}: nothing transmitted", candidate.loanId());
            return WorkResult.DEFINITE_FAILURE;
        }
        try {
            Result<String> result = submitter.submit(signed);
            WorkResult outcome = WorkResult.classify(result);
            switch (outcome) {
                case SUBMITTED, WOULD_SUBMIT -> {
                    log.info("{} {} escrow {}: Q={} raw units into pool {}, F={} raw units, priced "
                                    + "reward={} lovelace, recovered E={} lovelace, final fee L={} lovelace, "
                                    + "pool top-up T={} lovelace, net={} lovelace — tx {}",
                            outcome == WorkResult.WOULD_SUBMIT ? "WOULD_SUBMIT" : "COMPOUNDED",
                            candidate.loanId(), candidate.escrowRef(), candidate.addedLiquidity(),
                            candidate.poolId(), assessment.expectedFee(), assessment.expectedFeeLovelace(),
                            assessment.recoveredEscrowLovelace(), assessment.txFee(),
                            assessment.poolTopUpLovelace(), assessment.net(), result.getValue());
                }
                case DEFINITE_FAILURE -> {
                    log.warn("compound SUBMIT REJECTED {} by decoded Blockfrost provider submit refusal "
                                    + "(HTTP {}): {}",
                            candidate.loanId(), result.code(), result.getResponse());
                }
                case AMBIGUOUS -> {
                    log.error("compound SUBMIT AMBIGUOUS {}: {}", candidate.loanId(),
                            result == null ? "no response" : result);
                }
            }
            return outcome;
        } catch (Exception e) {
            log.error("compound SUBMIT AMBIGUOUS {}: transport threw {}; candidate pass stops",
                    candidate.loanId(), e.toString(), e);
            return WorkResult.AMBIGUOUS;
        }
    }

    // ---- the walk's wallet roles (FAB-185) -------------------------------------------------------

    /** The role names a refusal starts with: {@code role C}, {@code role U}. */
    static final String ROLE_C = "C";
    static final String ROLE_U = "U";

    /**
     * One walk's wallet: the roles resolved from its one read and the fee ceiling U is sized against — or,
     * when the protocol parameters could not size C, neither, with why — and the missing roles already said
     * at WARN. Created per {@link #cycle(SubmitFence)} call and dropped with it; nothing in it outlives it.
     */
    private record WalletCheck(WalletRoles roles, BigInteger feeCeiling, String unsizable,
                               Set<String> warnedMissingRoles) {
    }

    /**
     * Reads the wallet once and resolves its roles. ⚠ The parameters are REQUIRED: without them C cannot be
     * sized, and without C no role can be told apart. They are proven usable INSIDE the catch — the fee
     * ceiling and C are both derived there — so a partial object is "C cannot be sized", never a later
     * NullPointerException (CCL trap 19).
     */
    private WalletCheck walletCheck() {
        List<Utxo> wallet = appUtxoService.listWalletUtxo();
        BigInteger feeCeiling;
        BigInteger collateralLovelace;
        try {
            ProtocolParams params = protocolParamsSupplier.getProtocolParams();
            feeCeiling = LedgerCeilings.maxPossibleFee(params);
            collateralLovelace = WalletShape.collateralLovelace(params);
        } catch (Exception e) {
            return new WalletCheck(null, null, "the protocol parameters are unusable (" + e + "), so C cannot "
                    + "be sized and no wallet output can be told apart", new HashSet<>());
        }
        WalletRoles roles = WalletRoles.resolve(wallet, collateralLovelace);
        log.debug("compound wallet roles for this walk: C {}, U {}",
                roles.c().map(c -> WalletRoles.ref(c) + " (" + collateralLovelace + " lovelace)").orElse("none"),
                roles.u().map(u -> WalletRoles.ref(u) + " (" + LedgerCeilings.lovelaceOf(u) + " lovelace)")
                        .orElse("none"));
        return new WalletCheck(roles, feeCeiling, null, new HashSet<>());
    }

    /**
     * U, when this walk has both roles and U holds at least the fee ceiling; otherwise null, said: a missing
     * role at WARN once per role per walk (DEBUG after), a U that is too small at WARN for this candidate.
     */
    private Utxo designatedU(CompoundCandidate candidate, WalletCheck walletCheck) {
        if (walletCheck.roles() == null) {
            missingRole(candidate, walletCheck, ROLE_C, walletCheck.unsizable());
            return null;
        }
        WalletRoles roles = walletCheck.roles();
        if (roles.c().isEmpty()) {
            missingRole(candidate, walletCheck, ROLE_C, "the wallet holds no ada-only output (no datum, no "
                    + "reference script) of exactly " + roles.collateralLovelace() + " lovelace, the collateral");
            return null;
        }
        if (roles.u().isEmpty()) {
            missingRole(candidate, walletCheck, ROLE_U, "the wallet holds no ada-only output (no datum, no "
                    + "reference script) besides C, the " + roles.collateralLovelace() + "-lovelace collateral");
            return null;
        }
        Utxo u = roles.u().get();
        BigInteger holds = LedgerCeilings.lovelaceOf(u);
        if (holds.compareTo(walletCheck.feeCeiling()) < 0) {
            log.warn("compound SKIPPED {}: role U, wallet output {}, holds {} lovelace — too small for the fee "
                            + "ceiling of {} lovelace (a pre-build ceiling: the build is the judge); {}",
                    candidate.loanId(), WalletRoles.ref(u), holds, walletCheck.feeCeiling(), rebalancerOutcome());
            return null;
        }
        return u;
    }

    /** Says a missing {@code role} for {@code candidate}: at WARN the first time in this walk, at DEBUG after. */
    private void missingRole(CompoundCandidate candidate, WalletCheck walletCheck, String role, String why) {
        String detail = "role %s is missing: %s, so this compound has nothing to be funded from; %s"
                .formatted(role, why, rebalancerOutcome());
        if (walletCheck.warnedMissingRoles().add(role)) {
            log.warn("compound SKIPPED {}: {}", candidate.loanId(), detail);
        } else {
            log.debug("compound SKIPPED {}: {}", candidate.loanId(), detail);
        }
    }

    /** What a role refusal says about the rebalancer, which is what keeps the wallet in shape. */
    private String rebalancerOutcome() {
        return walletRebalancer == null ? "the wallet rebalancer is not wired"
                : "the wallet rebalancer's last outcome: " + walletRebalancer.lastOutcome();
    }

    /**
     * Resolve the <b>boot-narrowed</b> coordinates into scriptHash → UTxO, <b>reading the hash off the
     * chain</b> rather than trusting the operator's ordering. {@code getReferenceScripts()} is the list
     * {@code LoansReferenceScriptVerifier} left at boot (FAB-108): a malformed, repeated, absent, spent,
     * script-less or foreign coordinate, or a second one publishing an already-supplied validator, was
     * dropped there, and each compound validator no usable coordinate supplies travels inline. A
     * coordinate that publishes no reference script, or that cannot be resolved, is still logged and
     * skipped here: the validator then travels inline, which is correct but larger — never silently
     * referenced-but-absent, which is {@code RequiredRedeemersMismatch} (CCL trap 13).
     *
     * <p>⚠ Residue: a coordinate the verifier could not check (UNVERIFIED — the provider could not be
     * asked at boot) is kept, resolved on each call and referenced <b>whatever it publishes</b>. If that
     * is a script no redeemer needs, it costs fee only and the ledger accepts it; an offline Aiken rig
     * would reject it.
     */
    Map<String, TransactionInput> referenceScripts() {
        String configured = configuration.getReferenceScripts();
        if (configured == null || configured.isBlank()) {
            return Map.of();
        }
        Map<String, TransactionInput> resolved = new LinkedHashMap<>();
        for (String raw : configured.split(",")) {
            String coordinate = raw.trim();
            if (coordinate.isEmpty()) {
                continue;
            }
            String[] parts = coordinate.split("#");
            if (parts.length != 2) {
                log.warn("compound: reference-script coordinate '{}' is not txHash#index; ignoring",
                        coordinate);
                continue;
            }
            Optional<Utxo> utxo = utxoSupplier.getTxOutput(parts[0], Integer.parseInt(parts[1]));
            String hash = utxo.map(Utxo::getReferenceScriptHash).orElse(null);
            if (hash == null || hash.isBlank()) {
                log.warn("compound: reference-script coordinate {} publishes no script (or is "
                        + "unresolvable); that validator will travel inline", coordinate);
                continue;
            }
            resolved.put(hash, new TransactionInput(parts[0], Integer.parseInt(parts[1])));
            log.info("compound: {} travels by reference from {}", hash, coordinate);
        }
        return Map.copyOf(resolved);
    }

    private long tipMillis() {
        long slot = blockEventListener.getLastAppliedSlot().get();
        if (slot <= 0) {
            return Long.MAX_VALUE;   // unknown tip: min() then leaves `now` untouched
        }
        return converters.slot().slotToTime(slot).toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
    }
}
