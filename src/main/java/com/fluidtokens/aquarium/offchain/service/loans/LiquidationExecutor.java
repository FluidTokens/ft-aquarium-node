package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationDecision;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationExclusion;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.model.loans.Rational;
import com.fluidtokens.aquarium.offchain.service.AppUtxoService;
import com.fluidtokens.aquarium.offchain.service.BlockEventListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
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
 * The shadow liquidation loop: every cycle it scans every indexed lender bond, builds one real
 * {@code Liquidate} transaction per buildable candidate, prices it, and writes down what it would
 * have done.
 *
 * <h2>It cannot submit</h2>
 * Not "does not" — <em>cannot</em>. This class holds no {@code BackendService}, nothing that
 * processes or transmits a transaction, and no {@code QuickTxBuilder}; the only thing it can do
 * with a {@link Transaction} is read it. {@link LiquidateTransactionBuilder} likewise takes a
 * {@link com.bloxbean.cardano.client.api.UtxoSupplier} and a
 * {@link com.bloxbean.cardano.client.api.ProtocolParamsSupplier} and nothing that can reach the
 * network for writing. Arming the bot is a later slice and will have to <em>add</em> a submission
 * path, which is exactly the property worth having: no reviewer has to check that such a call is
 * guarded correctly, because there is no such call anywhere on this path.
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
@RequiredArgsConstructor
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

    private final AppConfig.LiquidationConfiguration configuration;

    private final BlockEventListener blockEventListener;

    private final AppUtxoService appUtxoService;

    private final Account account;

    private final LiquidationCandidateScanner scanner;

    private final LiquidationUtxoResolver utxoResolver;

    private final LiquidateTransactionBuilder builder;

    private final LiquidationDecisionLog decisionLog;

    private final ObjectProvider<FluidOracleClient> oracleClient;

    /** Loan UTxO ref to the epoch-millis at which its quarantine lapses. */
    private final Map<String, Long> quarantine = new ConcurrentHashMap<>();

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

        Optional<Utxo> walletUtxoOpt = adaOnlyWalletUtxo();
        if (walletUtxoOpt.isEmpty()) {
            return;
        }
        Utxo walletUtxo = walletUtxoOpt.get();

        List<LiquidationAssessment> assessments = scanner.scan(now);

        // The oracle snapshot is taken once, in the same cycle as the scan, and reused for every
        // candidate. Re-reading the registry between scanning and building would let a candidate be
        // assessed against one price and built against another — and the builder's V4 guard would
        // then refuse it, turning a price refresh into a wave of unexplained refusals.
        Map<String, OracleEntry> oraclesByUnit = oracleSnapshot();

        List<LiquidationAssessment> buildable = assessments.stream()
                .filter(LiquidationAssessment::buildable)
                .toList();
        Map<LiquidationExclusion, Integer> exclusions = histogram(assessments);
        // Excluded bonds are counted, never logged as decisions: the histogram is the whole record
        // of them, so every scanned bond is accounted for without the ring buffer being flooded by
        // the healthy majority.
        decisionLog.recordRun(now, assessments.size(), buildable.size(), exclusions);
        log.info("liquidation scan: {} bonds, {} buildable, exclusions {}",
                assessments.size(), buildable.size(), exclusions);

        // Before the early return, not after it: a cycle that finds nothing buildable is exactly the
        // cycle in which every quarantined loan was already skipped by the scanner, and letting the
        // expiries lapse only when there happens to be work would keep entries alive far past their
        // configured lifetime.
        expireQuarantine(now);

        if (buildable.isEmpty()) {
            return;
        }

        Optional<Utxo> configUtxo = utxoResolver.resolveConfigUtxo();
        Optional<Utxo> lmConfigUtxo = utxoResolver.resolveLmConfigUtxo();
        if (configUtxo.isEmpty() || lmConfigUtxo.isEmpty()) {
            log.warn("cannot build liquidations: config utxo present={}, lm config utxo present={}",
                    configUtxo.isPresent(), lmConfigUtxo.isPresent());
            return;
        }

        for (LiquidationAssessment assessment : buildable) {
            try {
                consider(assessment, now, walletUtxo, configUtxo.get(), lmConfigUtxo.get(), oraclesByUnit);
            } catch (Exception e) {
                // consider() already turns every expected failure into a decision; this is the last
                // net, so that one unexpected candidate does not cost the rest of the cycle.
                //
                // Identified by the BOND, not the loan: this catch exists for the cases consider()
                // did not anticipate, and a null loan on a supposedly buildable assessment is one of
                // them — dereferencing assessment.loan() here would throw out of the handler that is
                // meant to contain it. The bond is the one thing every assessment always carries.
                log.warn("could not consider bond {}: {}", assessment.bond().utxoRef(), e.toString());
                log.debug("liquidation candidate failed", e);
            }
        }
    }

    // ---- one candidate ------------------------------------------------------------------------

    private void consider(LiquidationAssessment assessment, long now, Utxo walletUtxo, Utxo configUtxo,
                          Utxo lmConfigUtxo, Map<String, OracleEntry> oraclesByUnit) {
        String loanUtxoRef = assessment.loan().utxoRef();
        if (isQuarantined(loanUtxoRef, now)) {
            log.debug("loan {} is quarantined until {}", loanUtxoRef, quarantine.get(loanUtxoRef));
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

        LiquidateTransactionBuilder.Request request = new LiquidateTransactionBuilder.Request(
                List.of(new LiquidateTransactionBuilder.LoanLiquidation(assessment, loanUtxo.get(),
                        bondUtxo.get())),
                configUtxo,
                lmConfigUtxo,
                oraclesByUnit,
                walletUtxo,
                account.baseAddress(),
                now - VALID_FROM_BACKDATE_MILLIS,
                now + configuration.getValidityWindowSeconds() * 1000L,
                configuration.getOracleWindowMarginSeconds() * 1000L,
                // Reference scripts are a later slice. With none published every validator travels
                // in the witness set, which is what makes tx_size_bytes worth reporting.
                LiquidateTransactionBuilder.ReferenceScripts.none());

        Transaction transaction;
        try {
            transaction = builder.build(request);
        } catch (LiquidateTransactionBuilder.RefusedException e) {
            // A refusal is the builder working: it is a statement about this candidate, reproducible
            // next cycle, and costs nothing. Not quarantined for that reason.
            decisionLog.record(decision(assessment, now, LiquidationDecision.Outcome.REFUSED,
                    e.getReason().name(), e.getMessage()));
            return;
        } catch (Exception e) {
            // Anything else is a failure of the machinery rather than a verdict on the candidate —
            // a Blockfrost timeout fetching protocol params, say. Quarantined so a systematically
            // broken candidate does not burn a build attempt every cycle, but only for a while.
            quarantineUntil(loanUtxoRef, now + configuration.getQuarantineMinutes() * 60_000L);
            decisionLog.record(decision(assessment, now, LiquidationDecision.Outcome.REFUSED,
                    e.getClass().getSimpleName(), e.getMessage()));
            log.warn("building the liquidation of {} threw: {}", loanUtxoRef, e.toString());
            log.debug("liquidation build failed", e);
            return;
        }

        record(assessment, now, transaction, oraclesByUnit);
    }

    /**
     * Prices the built transaction and files the verdict.
     * <p>
     * The bot's whole take is the liquidation fee slice, denominated in the <em>collateral</em>
     * asset, so it has to be priced through the collateral leg's feed before it can be compared with
     * a lovelace transaction fee. For ada collateral that feed is {@link OraclePriceFeed#unit()},
     * the 1:1 identity {@code retrieve_oracle_data} synthesises for the empty policy id — the same
     * feed the scanner and the builder used, not a special case invented here.
     */
    private void record(LiquidationAssessment assessment, long now, Transaction transaction,
                        Map<String, OracleEntry> oraclesByUnit) {
        OraclePriceFeed collateralFeed = collateralFeed(assessment, oraclesByUnit);
        BigInteger expectedFee = LoanFinance
                .toLovelace(Rational.fromInt(assessment.liquidationFee()), collateralFeed)
                .floor();
        BigInteger txFee = transaction.getBody().getFee();
        BigInteger margin = configuration.getProfitMarginLovelace();
        BigInteger expectedProfit = expectedFee.subtract(txFee).subtract(margin);

        boolean profitable = expectedProfit.signum() > 0;
        LiquidationDecision.Outcome outcome = profitable
                ? LiquidationDecision.Outcome.WOULD_SUBMIT
                : LiquidationDecision.Outcome.UNPROFITABLE;

        String detail = "fee slice %s lovelace - tx fee %s - margin %s = %s"
                .formatted(expectedFee, txFee, margin, expectedProfit);

        int size;
        String cborHex;
        try {
            byte[] bytes = transaction.serialize();
            size = bytes.length;
            cborHex = transaction.serializeToHex();
        } catch (Exception e) {
            // The transaction exists but cannot be measured; the verdict still stands, so it is
            // recorded without the fields that could not be produced.
            log.warn("could not serialise the built liquidation of {}: {}",
                    assessment.loan().utxoRef(), e.toString());
            decisionLog.record(decision(assessment, now, outcome, outcome.name(), detail));
            return;
        }

        decisionLog.record(new LiquidationDecision(
                now,
                assessment.loan().loanId(),
                assessment.loan().utxoRef(),
                assessment.bond().utxoRef(),
                LiquidationDecision.VARIANT,
                outcome,
                outcome.name(),
                detail,
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
                        : transaction.getWitnessSet().getRedeemers().size()));

        log.info("liquidation of {} would {}: {} ({} bytes)", assessment.loan().utxoRef(),
                outcome, detail, size);
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

    private LiquidationDecision decision(LiquidationAssessment assessment, long now,
                                         LiquidationDecision.Outcome outcome, String reason,
                                         String detail) {
        return new LiquidationDecision(
                now,
                assessment.loan().loanId(),
                assessment.loan().utxoRef(),
                assessment.bond().utxoRef(),
                LiquidationDecision.VARIANT,
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
                null, null, null, null);
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
    private Optional<Utxo> adaOnlyWalletUtxo() {
        List<Utxo> walletUtxos = appUtxoService.listWalletUtxo();
        if (walletUtxos.isEmpty()) {
            log.warn("No wallet UTXOs found for account: {}", account.baseAddress());
            return Optional.empty();
        }
        Optional<Utxo> walletUtxo = walletUtxos.stream()
                .filter(utxo -> utxo.getAmount().size() == 1
                        && utxo.getReferenceScriptHash() == null
                        && utxo.getInlineDatum() == null
                        && utxo.getDataHash() == null)
                .findFirst();
        if (walletUtxo.isEmpty()) {
            log.warn("no valid utxos found. please ensure wallet has at least one utxo which contains "
                    + "ONLY ADA and carries no datum");
        }
        return walletUtxo;
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
