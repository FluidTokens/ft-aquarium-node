package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.model.loans.CompoundAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.CompoundCandidate;
import com.fluidtokens.aquarium.offchain.service.AppUtxoService;
import com.fluidtokens.aquarium.offchain.service.BlockEventListener;
import com.fluidtokens.aquarium.offchain.util.WalletInputSelection;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.conversions.CardanoConverters;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

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
@ConditionalOnProperty(prefix = "loans", name = "enabled", havingValue = "true")
public class CompoundExecutor {

    /** 30 s of backdate, matching the liquidation path. Anchored to the tip, never to the clock. */
    private static final long VALID_FROM_BACKDATE_MILLIS = 30_000L;
    private static final long VALIDITY_WINDOW_MILLIS = 120_000L;

    /** Everything this class can do to the network: hand over bytes. */
    @FunctionalInterface
    public interface TransactionSubmitter {
        Result<String> submit(byte[] signedTransactionBytes) throws Exception;
    }

    private final AppConfig.CompoundConfiguration configuration;
    private final BlockEventListener blockEventListener;
    private final AppUtxoService appUtxoService;
    private final Account account;
    private final CompoundCandidateScanner scanner;
    private final CompoundEconomics economics;
    private final CompoundTransactionBuilder builder;
    private final LiquidationUtxoResolver utxoResolver;
    private final CardanoConverters converters;
    private final TransactionSubmitter submitter;

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
                            BlockEventListener blockEventListener,
                            AppUtxoService appUtxoService,
                            Account account,
                            CompoundCandidateScanner scanner,
                            CompoundEconomics economics,
                            CompoundTransactionBuilder builder,
                            LiquidationUtxoResolver utxoResolver,
                            CardanoConverters converters,
                            BFBackendService backendService) {
        this(configuration, blockEventListener, appUtxoService, account, scanner, economics, builder,
                utxoResolver, converters,
                bytes -> backendService.getTransactionService().submitTransaction(bytes));
    }

    /** The same loop with the submitter stated, so a test can watch exactly what reaches the wire. */
    public CompoundExecutor(AppConfig.CompoundConfiguration configuration,
                            BlockEventListener blockEventListener,
                            AppUtxoService appUtxoService,
                            Account account,
                            CompoundCandidateScanner scanner,
                            CompoundEconomics economics,
                            CompoundTransactionBuilder builder,
                            LiquidationUtxoResolver utxoResolver,
                            CardanoConverters converters,
                            TransactionSubmitter submitter) {
        this.configuration = configuration;
        this.blockEventListener = blockEventListener;
        this.appUtxoService = appUtxoService;
        this.account = account;
        this.scanner = scanner;
        this.economics = economics;
        this.builder = builder;
        this.utxoResolver = utxoResolver;
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
        if (blockEventListener.getIsSyncing().get()) {
            log.debug("compound: still syncing, skipping the cycle");
            return;
        }

        CompoundCandidateScanner.Scan scan = scanner.scan();
        if (scan.candidates().isEmpty()) {
            log.debug("compound: no asset-manager escrows in the index");
            return;
        }

        // Every escrow is reported, refused or not: "nothing to do" and "eight things all blocked"
        // must not look alike to an operator.
        for (CompoundCandidate candidate : scan.candidates()) {
            if (!candidate.structurallyReady()) {
                log.info("compound REFUSED {} escrow {}: {} — {}", candidate.loanId(),
                        candidate.escrowRef(), candidate.exclusion(), candidate.detail());
                continue;
            }
            consider(candidate);
        }
    }

    private void consider(CompoundCandidate candidate) {
        if (!configuration.isEnabled()) {
            // Not a fault, and said at INFO because a ready candidate going unprocessed is exactly
            // what an operator will ask about.
            log.info("compound READY but NOT ARMED {} escrow {}: {} lovelace into pool {} at {}/1000 "
                            + "— set loans.compound.enabled=true to act on it",
                    candidate.loanId(), candidate.escrowRef(), candidate.addedLiquidity(),
                    candidate.poolId(), candidate.feePerMille());
            return;
        }

        Optional<Utxo> bondUtxo = utxoResolver.resolveBondUtxo(candidate.bond());
        Optional<Utxo> configUtxo = utxoResolver.resolveConfigUtxo();
        Optional<Utxo> lmConfigUtxo = utxoResolver.resolveLmConfigUtxo();
        if (bondUtxo.isEmpty() || configUtxo.isEmpty() || lmConfigUtxo.isEmpty()) {
            log.warn("compound SKIPPED {}: the index is missing {}{}{}", candidate.loanId(),
                    bondUtxo.isEmpty() ? "the lender bond " : "",
                    configUtxo.isEmpty() ? "the config " : "",
                    lmConfigUtxo.isEmpty() ? "the lm config " : "");
            return;
        }

        Optional<Utxo> wallet = nominableWalletUtxo();
        if (wallet.isEmpty()) {
            log.warn("compound SKIPPED {}: no ada-only wallet utxo is nominable as input and collateral",
                    candidate.loanId());
            return;
        }

        BigInteger fee = CompoundEconomics.expectedFee(candidate.addedLiquidity(), candidate.feePerMille());

        long now = System.currentTimeMillis();
        long anchor = Math.min(now, tipMillis());
        long[] slots = {
                converters.time().toSlot(java.time.Instant.ofEpochMilli(anchor - VALID_FROM_BACKDATE_MILLIS)
                        .atZone(java.time.ZoneOffset.UTC).toLocalDateTime()),
                converters.time().toSlot(java.time.Instant.ofEpochMilli(now + VALIDITY_WINDOW_MILLIS)
                        .atZone(java.time.ZoneOffset.UTC).toLocalDateTime())
        };

        Transaction transaction;
        try {
            transaction = builder.build(new CompoundTransactionBuilder.Request(
                    candidate, bondUtxo.get(), configUtxo.get(), lmConfigUtxo.get(), wallet.get(),
                    account.baseAddress(), fee, slots[0], slots[1]));
        } catch (CompoundTransactionBuilder.RefusedException e) {
            log.warn("compound REFUSED BY BUILDER {} escrow {}: {}", candidate.loanId(),
                    candidate.escrowRef(), e.getMessage());
            return;
        } catch (Exception e) {
            log.error("compound BUILD FAILED {} escrow {}: {}", candidate.loanId(),
                    candidate.escrowRef(), e.toString(), e);
            return;
        }

        // ⛔ The gate runs on the BUILT transaction's own fee — measured, not estimated.
        CompoundAssessment assessment = economics.assess(true, true, candidate.principalIsAda(),
                candidate.addedLiquidity(), candidate.feePerMille(), transaction.getBody().getFee());

        if (!assessment.approved()) {
            log.info("compound NOT APPROVED {} escrow {}: {} — escrow {} at {}/1000 earns {}, tx fee {}, "
                            + "net {} against floor {}{}",
                    candidate.loanId(), candidate.escrowRef(), assessment.exclusion(),
                    assessment.escrow(), assessment.feePerMille(), assessment.expectedFee(),
                    assessment.txFee(), assessment.net(), assessment.floor(),
                    assessment.zeroFeePool()
                            ? " (this pool publishes NO compounding fee; a negative floor is the only "
                              + "way to process it, and that is the operator's call to state)"
                            : "");
            return;
        }

        submit(candidate, transaction, assessment);
    }

    private void submit(CompoundCandidate candidate, Transaction transaction,
                        CompoundAssessment assessment) {
        byte[] signed;
        try {
            signed = account.sign(transaction).serialize();
        } catch (Exception e) {
            log.error("compound SIGN FAILED {}: {} — nothing was transmitted",
                    candidate.loanId(), e.toString(), e);
            return;
        }

        try {
            Result<String> result = submitter.submit(signed);
            if (result != null && result.isSuccessful()) {
                log.info("COMPOUNDED {} escrow {}: {} lovelace into pool {}, fee earned {}, tx fee {}, "
                                + "net {} — tx {}",
                        candidate.loanId(), candidate.escrowRef(), candidate.addedLiquidity(),
                        candidate.poolId(), assessment.expectedFee(), assessment.txFee(),
                        assessment.net(), result.getValue());
                return;
            }
            log.warn("compound SUBMIT REJECTED {}: {}", candidate.loanId(),
                    result == null ? "no response" : result.getResponse());
        } catch (Exception e) {
            log.error("compound SUBMIT THREW {}: {}", candidate.loanId(), e.toString(), e);
        }
    }

    /**
     * An ada-only wallet UTxO, which the transaction uses as both spend input and Plutus collateral.
     * {@code WalletInputSelection.nominable} is the same predicate the liquidation path uses, and it
     * exists because collateral must be pure ada — CCL trap 16, where a multi-asset or too-small
     * nomination produces a negative collateral return and an unparseable transaction.
     */
    private Optional<Utxo> nominableWalletUtxo() {
        List<Utxo> utxos = appUtxoService.listWalletUtxo();
        return utxos.stream().filter(WalletInputSelection::nominable).findFirst();
    }

    private long tipMillis() {
        long slot = blockEventListener.getLastAppliedSlot().get();
        if (slot <= 0) {
            return Long.MAX_VALUE;   // unknown tip: min() then leaves `now` untouched
        }
        return converters.slot().slotToTime(slot).toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
    }
}
