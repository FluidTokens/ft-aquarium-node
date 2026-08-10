package com.fluidtokens.aquarium.offchain.controller;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationMode;
import com.fluidtokens.aquarium.offchain.model.loans.Loan;
import com.fluidtokens.aquarium.offchain.model.loans.RepaymentMode;
import com.fluidtokens.aquarium.offchain.model.loans.LoanHealth;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.service.loans.FluidOracleClient;
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
                           LiquidationView liquidation,
                           RepaymentView repayment,
                           HealthView health) {
    }

    /**
     * @param botLiquidatableOnly keep only loans the LenderManager actions could ever accept —
     *                            {@code Liquidation} mode with equity in collateral currency
     *                            (docs/lending-v4-findings.md §7 D1/D2). Not a health check:
     *                            these are eligible by <em>type</em>, not necessarily unhealthy.
     */
    @GetMapping
    public List<LoanView> loans(
            @RequestParam(name = "bot_liquidatable_only", defaultValue = "false") boolean botLiquidatableOnly) {
        long now = System.currentTimeMillis();
        return loanService.findAll().stream()
                .filter(loan -> !botLiquidatableOnly || loan.botLiquidatable())
                .map(loan -> toView(loan, loanHealthService.health(loan, now)))
                .toList();
    }

    private static HealthView toView(LoanHealth health) {
        return new HealthView(health.remainingDebt(), health.repaymentLate(), health.currentLtvPercent(),
                health.equity(), health.liquidatable(), health.unavailableReason());
    }

    private static LoanView toView(Loan loan, LoanHealth health) {
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
                loan.botLiquidatable(),
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
