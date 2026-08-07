package com.fluidtokens.aquarium.offchain.controller;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationMode;
import com.fluidtokens.aquarium.offchain.model.loans.Loan;
import com.fluidtokens.aquarium.offchain.model.loans.RepaymentMode;
import com.fluidtokens.aquarium.offchain.model.loans.LoanHealth;
import com.fluidtokens.aquarium.offchain.service.loans.LoanHealthService;
import com.fluidtokens.aquarium.offchain.service.loans.LoanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;

/**
 * Read-only view over the indexed Lending v4 loans.
 * <p>
 * v1 exposes only what is on chain today. Health factor, remaining debt and equity are
 * deliberately absent: they need the {@code finance.ak} port and live oracle prices, and a
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
