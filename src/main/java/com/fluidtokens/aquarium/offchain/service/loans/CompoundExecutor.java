package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.config.ProtocolParamsRejections;
import com.fluidtokens.aquarium.offchain.model.loans.CompoundAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.CompoundCandidate;
import com.fluidtokens.aquarium.offchain.model.loans.CompoundExclusion;
import com.fluidtokens.aquarium.offchain.service.AppUtxoService;
import com.fluidtokens.aquarium.offchain.service.BlockEventListener;
import com.fluidtokens.aquarium.offchain.util.WalletInputSelection;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.conversions.CardanoConverters;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
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
 */
@Service
@Slf4j
public class CompoundExecutor {

    /** 30 s of backdate, matching the liquidation path. Anchored to the tip, never to the clock. */
    private static final long VALID_FROM_BACKDATE_MILLIS = 30_000L;
    private static final long VALIDITY_WINDOW_MILLIS = 120_000L;
    private static final int MAX_PROVIDER_RESPONSE_LENGTH = 131_072;
    private static final int MAX_PROVIDER_JSON_DEPTH = 16;
    private static final int MAX_PROVIDER_JSON_STRING_LENGTH = 65_536;
    private static final int MAX_PROVIDER_JSON_NUMBER_LENGTH = 20;
    private static final int MIN_PROVIDER_ROOT_MEMBERS = 3;
    private static final int MAX_PROVIDER_ROOT_MEMBERS = 32;
    private static final ObjectMapper BLOCKFROST_ERROR_MAPPER = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(MAX_PROVIDER_JSON_DEPTH)
                    .maxStringLength(MAX_PROVIDER_JSON_STRING_LENGTH)
                    .maxNameLength(MAX_PROVIDER_JSON_STRING_LENGTH)
                    .maxNumberLength(MAX_PROVIDER_JSON_NUMBER_LENGTH)
                    .build())
            .build());

    /** Everything this class can do to the network: hand over bytes. */
    @FunctionalInterface
    public interface TransactionSubmitter {
        Result<String> submit(byte[] signedTransactionBytes) throws Exception;
    }

    enum CandidateOutcome {
        DEFINITE_FAILURE,
        SUBMITTED,
        WOULD_SUBMIT,
        AMBIGUOUS;

        boolean terminal() {
            return this != DEFINITE_FAILURE;
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
    private final TransactionSubmitter submitter;

    /**
     * ⛔ Closed by {@code LoansConfigVerifier} when the live Lending v4 config does not match what this
     * node derives (FAB-115). Setter-injected and REQUIRED in the container: a missing gate bean must fail
     * the boot loudly, never leave lending ungated. Direct constructions in tests run ungated.
     */
    private com.fluidtokens.aquarium.offchain.service.LendingConfigGate lendingConfigGate;
    private volatile boolean lendingGateRefusalLogged;

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
                builder, utxoResolver, utxoSupplier, converters,
                bytes -> {
                    // FAB-134 B5a: a rejection naming the parameters refreshes them; the result itself
                    // is returned untouched (nothing retried, no outcome changed).
                    Result<String> result = backendService.getTransactionService().submitTransaction(bytes);
                    ProtocolParamsRejections.refreshIfParamsRejected(protocolParamsSupplier, result);
                    return result;
                });
    }

    /** The same loop with the submitter stated, so a test can watch exactly what reaches the wire. */
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
        this.submitter = submitter;
    }

    @Scheduled(timeUnit = TimeUnit.SECONDS, fixedDelayString = "${loans.compound.delay-seconds:60}")
    public void runCycle() {
        // One bad cycle must not take the scheduler thread with it.
        try {
            cycle();
        } catch (Exception e) {
            log.error("compound cycle failed: {}", e.toString(), e);
        }
    }

    void cycle() {
        if (refusedByLendingConfigGate("compound")) {
            return;
        }
        if (blockEventListener.getIsSyncing().get()) {
            log.debug("compound: still syncing, skipping the cycle");
            return;
        }

        CompoundCandidateScanner.Scan scan = scanner.scan();
        if (scan.candidates().isEmpty()) {
            log.debug("compound: no asset-manager escrows in the index");
            return;
        }

        // FAB-144 — THE MOST PROFITABLE ESCROW FIRST. Only the ORDER changes: every candidate is still
        // walked exactly once, and one whose fee cannot be estimated — refused, non-ada, no amount —
        // goes last rather than missing. Guarded: ranking must never cost a compound, so a fault in it
        // is reported and the scan's own order is walked instead.
        List<CompoundCandidate> ordered = scan.candidates();
        if (ordered.size() >= 2) {
            try {
                ordered = mostProfitableFirstThisCycle(scan.candidates());
            } catch (RuntimeException e) {
                log.warn("compound: ranking this cycle's candidates failed ({}); they are walked in "
                        + "scan order", e.toString());
            }
        }

        // Every escrow is reported, refused or not: "nothing to do" and "eight things all blocked"
        // must not look alike to an operator.
        for (CompoundCandidate candidate : ordered) {
            if (!candidate.structurallyReady()) {
                log.info("compound REFUSED {} escrow {}: {} — {}", candidate.loanId(),
                        candidate.escrowRef(), candidate.exclusion(), candidate.detail());
                continue;
            }
            if (consider(candidate).terminal()) {
                return;
            }
        }
    }

    // ---- the order a cycle walks its candidates in (FAB-144) ------------------------------------

    /** {@code candidates} reordered by expected fee, each computed once, and the order logged at INFO. */
    private List<CompoundCandidate> mostProfitableFirstThisCycle(List<CompoundCandidate> candidates) {
        Map<CompoundCandidate, Optional<BigInteger>> rewards = new IdentityHashMap<>();
        for (CompoundCandidate candidate : candidates) {
            rewards.put(candidate, grossRecoverableLovelace(candidate));
        }
        List<CompoundCandidate> ordered = mostProfitableFirst(candidates, rewards::get);
        log.info("compound order (most profitable first): {}", ordered.stream()
                .map(candidate -> candidate.escrowRef() + rewards.get(candidate)
                        .map(fee -> " gross≈" + fee + " lovelace").orElse(" gross unpriced"))
                .collect(Collectors.joining(", ")));
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

    private CandidateOutcome consider(CompoundCandidate candidate) {
        if (!configuration.isEnabled()) {
            // Not a fault, and said at INFO because a ready candidate going unprocessed is exactly
            // what an operator will ask about.
            log.info("compound READY but NOT ARMED {} escrow {}: Q={} raw units into pool {} at "
                            + "compoudingFeePerMille={} — set loans.compound.enabled=true to act on it",
                    candidate.loanId(), candidate.escrowRef(), candidate.addedLiquidity(),
                    candidate.poolId(), candidate.feePerMille());
            return CandidateOutcome.DEFINITE_FAILURE;
        }

        Optional<Utxo> bondUtxo = utxoResolver.resolveBondUtxo(candidate.bond());
        Optional<Utxo> configUtxo = utxoResolver.resolveConfigUtxo();
        Optional<Utxo> lmConfigUtxo = utxoResolver.resolveLmConfigUtxo();
        if (bondUtxo.isEmpty() || configUtxo.isEmpty() || lmConfigUtxo.isEmpty()) {
            log.warn("compound SKIPPED {}: the index is missing {}{}{}", candidate.loanId(),
                    bondUtxo.isEmpty() ? "the lender bond " : "",
                    configUtxo.isEmpty() ? "the config " : "",
                    lmConfigUtxo.isEmpty() ? "the lm config " : "");
            return CandidateOutcome.DEFINITE_FAILURE;
        }

        List<Utxo> walletUtxos = appUtxoService.listWalletUtxo();
        final WalletInputSelection.CompoundFunding funding;
        try {
            funding = WalletInputSelection.selectForCompound(walletUtxos, BigInteger.ZERO);
        } catch (WalletInputSelection.CompoundFundingException e) {
            // ⛔ "Nothing qualified" cannot tell an EMPTY wallet from a FULL one whose every UTxO is
            // ineligible, and those need opposite responses. Measured 2026-09-02: the wallet held
            // 9,898 ada and could not build, because its only UTxO also carried a native token —
            // CCL trap 17, where a successful transaction's change output disables the next one.
            long total = walletUtxos.stream()
                    .flatMap(u -> u.getAmount().stream())
                    .filter(a -> "lovelace".equals(a.getUnit()))
                    .mapToLong(a -> a.getQuantity().longValue()).sum();
            long multiAsset = walletUtxos.stream().filter(u -> u.getAmount().size() > 1).count();
            log.warn("compound SKIPPED {}: {}. Wallet has {} utxo(s), {} lovelace and {} "
                            + "token-bearing utxo(s); ordinary compound inputs may carry safe token "
                            + "change, while collateral must be a distinct pure-ADA UTxO",
                    candidate.loanId(), e.reason(), walletUtxos.size(), total, multiAsset);
            return CandidateOutcome.DEFINITE_FAILURE;
        }

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
            log.info("compound NOT APPROVED {} escrow {}: {} — F={} raw units, quote={}",
                    candidate.loanId(), candidate.escrowRef(), exclusion, fee, quote.refusal());
            return CandidateOutcome.DEFINITE_FAILURE;
        }

        CompoundTransactionBuilder.Built built;
        try {
            built = builder.buildWithFacts(new CompoundTransactionBuilder.Request(
                    candidate, referenceScripts(), bondUtxo.get(), configUtxo.get(), lmConfigUtxo.get(),
                    funding.ordinaryInput(),
                    account.baseAddress(), fee, slots[0], slots[1]));
        } catch (CompoundTransactionBuilder.RefusedException e) {
            log.warn("compound REFUSED BY BUILDER {} escrow {}: {}", candidate.loanId(),
                    candidate.escrowRef(), e.getMessage());
            return CandidateOutcome.DEFINITE_FAILURE;
        } catch (Exception e) {
            log.error("compound BUILD FAILED {} escrow {}: {}", candidate.loanId(),
                    candidate.escrowRef(), e.toString(), e);
            return CandidateOutcome.DEFINITE_FAILURE;
        }

        // ⛔ The gate runs on the BUILT transaction's own fee — measured, not estimated.
        // The principal asset is passed honestly (never a boolean smuggling a pricing decision).
        // The exact quote retained above and the kernel's final-body facts are the admission inputs.
        CompoundAssessment assessment = economics.assessFinal(true, true,
                candidate.bond().datum().principalAsset(), candidate.addedLiquidity(),
                candidate.feePerMille(), built.facts(), quote);

        if (!assessment.approved()) {
            log.info("compound NOT APPROVED {} escrow {}: {} — Q={} raw units at compoudingFeePerMille={} "
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
            return CandidateOutcome.DEFINITE_FAILURE;
        }

        return submit(candidate, built, assessment, quote);
    }

    private CandidateOutcome submit(CompoundCandidate candidate, CompoundTransactionBuilder.Built built,
                                    CompoundAssessment assessment, PricingService.Priced quote) {
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
            return CandidateOutcome.DEFINITE_FAILURE;
        }
        CompoundExclusion observationRefusal = economics.revalidateObservation(assessment,
                candidate.bond().datum().principalAsset(), System.currentTimeMillis());
        if (observationRefusal != null) {
            log.info("compound NOT APPROVED {} escrow {}: {} immediately before signing",
                    candidate.loanId(), candidate.escrowRef(), observationRefusal);
            return CandidateOutcome.DEFINITE_FAILURE;
        }
        CompoundAssessment finalAssessment = economics.assessFinal(true, true,
                candidate.bond().datum().principalAsset(), candidate.addedLiquidity(),
                candidate.feePerMille(), built.facts(), quote);
        if (!finalAssessment.approved()) {
            log.info("compound NOT APPROVED {} escrow {}: {} on the immediate pre-sign recheck",
                    candidate.loanId(), candidate.escrowRef(), finalAssessment.exclusion());
            return CandidateOutcome.DEFINITE_FAILURE;
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
                return CandidateOutcome.DEFINITE_FAILURE;
            }
        } catch (Exception e) {
            log.error("compound SIGN FAILED {}: {} — nothing was transmitted",
                    candidate.loanId(), e.toString(), e);
            return CandidateOutcome.DEFINITE_FAILURE;
        }

        try {
            Result<String> result = submitter.submit(signed);
            if (result != null && result.isSuccessful()) {
                boolean wouldSubmit = "WOULD_SUBMIT".equals(result.getValue())
                        || "WOULD_SUBMIT".equals(result.getResponse());
                log.info("{} {} escrow {}: Q={} raw units into pool {}, F={} raw units, priced "
                                + "reward={} lovelace, recovered E={} lovelace, final fee L={} lovelace, "
                                + "pool top-up T={} lovelace, net={} lovelace — tx {}",
                        wouldSubmit ? "WOULD_SUBMIT" : "COMPOUNDED",
                        candidate.loanId(), candidate.escrowRef(), candidate.addedLiquidity(),
                        candidate.poolId(), assessment.expectedFee(), assessment.expectedFeeLovelace(),
                        assessment.recoveredEscrowLovelace(), assessment.txFee(),
                        assessment.poolTopUpLovelace(), assessment.net(), result.getValue());
                return wouldSubmit ? CandidateOutcome.WOULD_SUBMIT : CandidateOutcome.SUBMITTED;
            }
            if (isDecodedBlockfrostProviderSubmitRefusal(result)) {
                log.warn("compound SUBMIT REJECTED {} by decoded Blockfrost provider submit refusal "
                                + "(HTTP {}): {}",
                        candidate.loanId(), result.code(), result.getResponse());
                return CandidateOutcome.DEFINITE_FAILURE;
            }
            log.error("compound SUBMIT AMBIGUOUS {}: {}", candidate.loanId(),
                    result == null ? "no response" : result);
            return CandidateOutcome.AMBIGUOUS;
        } catch (Exception e) {
            log.error("compound SUBMIT AMBIGUOUS {}: transport threw {}; candidate pass stops",
                    candidate.loanId(), e.toString(), e);
            return CandidateOutcome.AMBIGUOUS;
        }
    }

    /**
     * CCL 0.7.2 exposes a Blockfrost failure only as an HTTP code plus its raw response body.
     * Continue the candidate pass only for the complete, bounded HTTP-400 envelope documented by
     * Blockfrost for {@code /tx/submit}. The diagnostic {@code message} is deliberately opaque: the
     * provider publishes no Cardano/Haskell grammar for it, so its content cannot decide whether a
     * different candidate may be submitted.
     */
    private static boolean isDecodedBlockfrostProviderSubmitRefusal(Result<?> result) {
        if (result == null || result.isSuccessful() || result.code() != 400
                || result.getResponse() == null
                || result.getResponse().length() < 1
                || result.getResponse().length() > MAX_PROVIDER_RESPONSE_LENGTH) {
            return false;
        }
        String response = result.getResponse();
        int start = 0;
        int end = response.length();
        while (start < end && isJsonWhitespace(response.charAt(start))) {
            start++;
        }
        while (end > start && isJsonWhitespace(response.charAt(end - 1))) {
            end--;
        }
        if (start == end || response.charAt(start) == '\uFEFF') {
            return false;
        }

        try {
            JsonParser parser = BLOCKFROST_ERROR_MAPPER.getFactory()
                    .createParser(response.substring(start, end));
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            try (parser) {
                if (parser.nextToken() != JsonToken.START_OBJECT) {
                    return false;
                }
                int depth = 1;
                int rootMembers = 0;
                String rootField = null;
                boolean statusSeen = false;
                boolean statusValid = false;
                boolean errorSeen = false;
                boolean errorValid = false;
                boolean messageSeen = false;
                boolean messageValid = false;

                JsonToken token;
                while ((token = parser.nextToken()) != null) {
                    if (depth == 0) {
                        return false;
                    }
                    if (token == JsonToken.FIELD_NAME) {
                        if (parser.currentName().length() > MAX_PROVIDER_JSON_STRING_LENGTH) {
                            return false;
                        }
                        if (depth == 1) {
                            rootMembers++;
                            if (rootMembers > MAX_PROVIDER_ROOT_MEMBERS) {
                                return false;
                            }
                            rootField = parser.currentName();
                        }
                        continue;
                    }
                    if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
                        if (depth == 1 && rootField != null) {
                            if ("status_code".equals(rootField)) {
                                statusSeen = true;
                            } else if ("error".equals(rootField)) {
                                errorSeen = true;
                            } else if ("message".equals(rootField)) {
                                messageSeen = true;
                            }
                            rootField = null;
                        }
                        depth++;
                        if (depth > MAX_PROVIDER_JSON_DEPTH) {
                            return false;
                        }
                        continue;
                    }
                    if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) {
                        depth--;
                        if (depth < 0) {
                            return false;
                        }
                        continue;
                    }
                    if (token == JsonToken.VALUE_STRING
                            && parser.getText().length() > MAX_PROVIDER_JSON_STRING_LENGTH) {
                        return false;
                    }
                    if ((token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT)
                            && parser.getText().length() > MAX_PROVIDER_JSON_NUMBER_LENGTH) {
                        return false;
                    }
                    if (depth == 1 && rootField != null) {
                        if ("status_code".equals(rootField)) {
                            statusSeen = true;
                            statusValid = token == JsonToken.VALUE_NUMBER_INT
                                    && BigInteger.valueOf(400).equals(parser.getBigIntegerValue())
                                    && result.code() == 400;
                        } else if ("error".equals(rootField)) {
                            errorSeen = true;
                            errorValid = token == JsonToken.VALUE_STRING
                                    && "Bad Request".equals(parser.getText());
                        } else if ("message".equals(rootField)) {
                            messageSeen = true;
                            messageValid = token == JsonToken.VALUE_STRING;
                        }
                        rootField = null;
                    }
                }
                return depth == 0
                        && rootMembers >= MIN_PROVIDER_ROOT_MEMBERS
                        && statusSeen && statusValid
                        && errorSeen && errorValid
                        && messageSeen && messageValid;
            }
        } catch (java.io.IOException | RuntimeException ignored) {
            return false;
        }
    }

    private static boolean isJsonWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n';
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
