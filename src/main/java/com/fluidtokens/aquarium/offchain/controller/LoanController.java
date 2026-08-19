package com.fluidtokens.aquarium.offchain.controller;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationExclusion;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationMode;
import com.fluidtokens.aquarium.offchain.model.loans.Loan;
import com.fluidtokens.aquarium.offchain.model.loans.RepaymentMode;
import com.fluidtokens.aquarium.offchain.model.loans.LoanHealth;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.service.loans.FluidOracleClient;
import com.fluidtokens.aquarium.offchain.service.loans.LiquidationCandidateScanner;
import com.fluidtokens.aquarium.offchain.service.loans.LoanHealthService;
import com.fluidtokens.aquarium.offchain.service.loans.LoanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read-only view over the indexed Lending v4 loans.
 * <p>
 * Every field outside {@code health} comes straight off the indexed UTxO. The {@code health}
 * block is derived by {@link LoanHealthService}, and is split by what it depends on: debt and
 * lateness need only the datum and the clock, everything else needs a live oracle price for both
 * legs. When a price is missing those fields are null with an {@code unavailable_reason} — a
 * wrong health factor is worse than no health factor.
 */
@RestController
@RequestMapping("${apiPrefix}/loans")
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "loans", name = "enabled", havingValue = "true")
public class LoanController {

    private final LoanService loanService;

    private final LoanHealthService loanHealthService;

    private final ObjectProvider<FluidOracleClient> oracleClient;

    private final LiquidationCandidateScanner scanner;

    /**
     * One oracle's freshness. {@code usable_now} is the question that matters: a feed can be
     * present and recently fetched and still be unusable, because the validity window it carries
     * has passed.
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record OracleFeedView(String token,
                                 String oracleToken,
                                 String variant,
                                 BigInteger priceInLovelaces,
                                 BigInteger priceDenominator,
                                 String validFrom,
                                 String validTo,
                                 boolean usableNow,
                                 int signatures,
                                 int threshold,
                                 boolean usableForLiquidation) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record OracleStatusView(String lastRefresh,
                                   long secondsSinceRefresh,
                                   int trackedAssets,
                                   int usableNow,
                                   List<OracleFeedView> feeds) {
    }

    /**
     * Why health is or is not available, without having to infer it from null LTVs.
     * <p>
     * A stale oracle looks exactly like a healthy one from {@code /loans} — every priced field
     * simply turns null — so the difference between "no feed for this asset" and "the scheduler
     * has not refreshed in four minutes" needs somewhere to show.
     */
    @GetMapping("/oracle")
    public OracleStatusView oracle() {
        var client = oracleClient.getIfAvailable();
        if (client == null) {
            return new OracleStatusView(null, -1, 0, 0, List.of());
        }
        long now = System.currentTimeMillis();
        var feeds = client.entries().stream()
                .map(entry -> toView(entry, now))
                .sorted(Comparator.comparing(OracleFeedView::token))
                .toList();
        return new OracleStatusView(
                client.lastRefresh().toString(),
                Duration.between(client.lastRefresh(), Instant.now()).toSeconds(),
                client.trackedAssets(),
                (int) feeds.stream().filter(OracleFeedView::usableNow).count(),
                feeds);
    }

    private static OracleFeedView toView(OracleEntry entry, long now) {
        var feed = entry.feed();
        return new OracleFeedView(
                entry.token().toUnit(),
                entry.oracleToken().toUnit(),
                feed.variant().name(),
                feed.priceInLovelaces(),
                feed.priceDenominator(),
                Instant.ofEpochMilli(feed.validFrom()).toString(),
                Instant.ofEpochMilli(feed.validTo()).toString(),
                feed.usableAt(now),
                entry.signatures().size(),
                entry.threshold(),
                entry.usableForLiquidation());
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AssetAmount(String unit, BigInteger amount) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record LiquidationView(String mode,
                                  BigInteger ltv,
                                  BigInteger ltvDivider,
                                  Double ltvPercent,
                                  BigInteger partialLiquidationPenaltyPerMille,
                                  Boolean equityInPrincipalCurrency,
                                  boolean modeAllowsBotLiquidation) {
    }

    /**
     * {@code remaining_debt} and {@code repayment_late} need no oracle and are always present.
     * The rest require a price for both legs; when one is missing every field is null and
     * {@code unavailable_reason} explains it, rather than reporting a fabricated zero.
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record HealthView(BigInteger remainingDebt,
                             boolean repaymentLate,
                             Double currentLtvPercent,
                             BigInteger equity,
                             Boolean liquidatable,
                             String unavailableReason) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RepaymentView(String mode,
                                BigInteger apyIncreaseLinearCoefficient,
                                BigInteger maxPossibleRecasts) {
    }

    /**
     * {@code bot_liquidatable} is the executor's verdict: true exactly when
     * {@link LiquidationCandidateScanner#scan(long)} produced a {@link LiquidationAssessment#buildable()}
     * for this loan — i.e. the executor would <em>build</em> a liquidation now. It is not
     * {@link Loan#botLiquidatable()} (a loan-side type filter alone) and not a would-<em>submit</em>
     * verdict (fee/profitability is a separate economic veto at {@code /loans/liquidations}).
     * <p>
     * {@code bot_liquidatable_reason} is null exactly when {@code bot_liquidatable} is true; otherwise
     * it names why — the excluded assessment's {@link LiquidationExclusion} name, or
     * {@link LiquidationExclusion#BOND_NOT_DELEGATED} when no bond assessment exists for the loan.
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record LoanView(String loanId,
                           String utxo,
                           String address,
                           String originId,
                           AssetAmount principal,
                           AssetAmount collateral,
                           String principalOracleUnit,
                           String collateralOracleUnit,
                           BigInteger lovelace,
                           BigInteger interestRate,
                           BigInteger lendDate,
                           String lendDateIso,
                           BigInteger repaidInstallments,
                           BigInteger totalInstallments,
                           BigInteger installmentPeriodHours,
                           BigInteger initialGracePeriodHours,
                           BigInteger repaymentTimeWindowHours,
                           BigInteger penaltyFeeForLateRepayment,
                           BigInteger doneRecasts,
                           boolean repaymentReceipts,
                           boolean botLiquidatable,
                           String botLiquidatableReason,
                           LiquidationView liquidation,
                           RepaymentView repayment,
                           HealthView health) {
    }

    /** The executor's per-loan liquidation verdict, sourced from a {@link LiquidationAssessment}. */
    private record Verdict(boolean botLiquidatable, String reason) {
    }

    /**
     * present + buildable ⇒ {@code (true, null)}; present + excluded ⇒ {@code (false, exclusion)};
     * absent ⇒ {@code (false, BOND_NOT_DELEGATED)}. A missing assessment is never liquidatable.
     */
    private static Verdict verdict(LiquidationAssessment assessment) {
        if (assessment == null) {
            return new Verdict(false, LiquidationExclusion.BOND_NOT_DELEGATED.name());
        }
        if (assessment.buildable()) {
            return new Verdict(true, null);
        }
        return new Verdict(false, assessment.exclusion().name());
    }

    /**
     * @param botLiquidatableOnly keep only loans whose {@code bot_liquidatable} is true — the ones the
     *                            executor would build a liquidation for right now. Sourced from the
     *                            same {@link LiquidationCandidateScanner} assessment the
     *                            {@code /loans/liquidations} path uses, not the loan-side type filter.
     */
    @GetMapping
    public List<LoanView> loans(
            @RequestParam(name = "bot_liquidatable_only", defaultValue = "false") boolean botLiquidatableOnly) {
        long now = System.currentTimeMillis();
        // One scan per request, joined loan-side by loanId. A duplicate bond loanId must not blank the
        // endpoint — same "one bad input must not blank the endpoint" posture as the scanner/LoanService:
        // keep the first, log and drop the rest.
        Map<String, LiquidationAssessment> assessmentsByLoanId = scanner.scan(now).stream()
                .collect(Collectors.toMap(a -> a.bond().loanId(), Function.identity(), (first, duplicate) -> {
                    log.warn("duplicate bond loanId {} in liquidation scan: keeping {}, dropping {}",
                            first.bond().loanId(), first.bond().utxoRef(), duplicate.bond().utxoRef());
                    return first;
                }));
        return loanService.findAll().stream()
                .map(loan -> Map.entry(loan, verdict(assessmentsByLoanId.get(loan.loanId()))))
                .filter(entry -> !botLiquidatableOnly || entry.getValue().botLiquidatable())
                .map(entry -> toView(entry.getKey(), entry.getValue(),
                        loanHealthService.health(entry.getKey(), now)))
                .toList();
    }

    private static HealthView toView(LoanHealth health) {
        return new HealthView(health.remainingDebt(), health.repaymentLate(), health.currentLtvPercent(),
                health.equity(), health.liquidatable(), health.unavailableReason());
    }

    private static LoanView toView(Loan loan, Verdict verdict, LoanHealth health) {
        var d = loan.datum();
        return new LoanView(
                loan.loanId(),
                loan.utxoRef(),
                loan.address(),
                d.originId(),
                new AssetAmount(d.principalAsset().toUnit(), d.principalAmount()),
                new AssetAmount(d.collateral().unit().orElse(null), loan.collateralAmount()),
                d.principalOracleAsset().toUnit(),
                d.collateral().oracleTokenAsset().toUnit(),
                loan.lovelace(),
                // deliberately raw: the datum comment says rate/10000, but for a PerpetualLoan
                // this field is `c` in APY = mx + c, so a single "percent" rendering would be wrong
                // for the only repayment mode currently live on preview.
                d.interestRate(),
                d.lendDate(),
                toIso(d.lendDate()),
                d.repaidInstallments(),
                d.totalInstallments(),
                d.installmentPeriod(),
                d.initialGracePeriod(),
                d.repaymentTimeWindow(),
                d.penaltyFeeForLateRepayment(),
                d.doneRecasts(),
                d.repaymentReceipts(),
                verdict.botLiquidatable(),
                verdict.reason(),
                toView(d.liquidationMode()),
                toView(d.repaymentMode()),
                toView(health));
    }

    private static LiquidationView toView(LiquidationMode mode) {
        if (mode instanceof LiquidationMode.Liquidation l) {
            Double pct = BigInteger.ZERO.equals(l.ltvDivider())
                    ? null
                    : l.ltv().doubleValue() / l.ltvDivider().doubleValue() * 100.0;
            return new LiquidationView("Liquidation", l.ltv(), l.ltvDivider(), pct,
                    l.partialLiquidationPenaltyPerMille(), l.equityInPrincipalCurrency(),
                    mode.allowsBotLiquidation());
        }
        var name = mode instanceof LiquidationMode.NoLiquidationDutchAuctionClaim
                ? "NoLiquidationDutchAuctionClaim"
                : "NoLiquidationFullCollateralClaim";
        return new LiquidationView(name, null, null, null, null, null, false);
    }

    private static RepaymentView toView(RepaymentMode mode) {
        return switch (mode) {
            case RepaymentMode.InterestOnRemainingPrincipal m ->
                    new RepaymentView("InterestOnRemainingPrincipal", null, m.maxPossibleRecasts());
            case RepaymentMode.PrincipalAndInterestOnInstallments ignored ->
                    new RepaymentView("PrincipalAndInterestOnInstallments", null, null);
            case RepaymentMode.PerpetualLoan m ->
                    new RepaymentView("PerpetualLoan", m.apyIncreaseLinearCoefficient(), m.maxPossibleRecasts());
        };
    }

    /** Chain data is untrusted — a nonsense lendDate must not 500 the endpoint. */
    private static String toIso(BigInteger epochMillis) {
        try {
            return Instant.ofEpochMilli(epochMillis.longValueExact()).toString();
        } catch (ArithmeticException | java.time.DateTimeException e) {
            return null;
        }
    }
}
