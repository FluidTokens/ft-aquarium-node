package com.fluidtokens.aquarium.offchain.controller;

import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationMode;
import com.fluidtokens.aquarium.offchain.model.loans.Loan;
import com.fluidtokens.aquarium.offchain.model.loans.LoanHealth;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.model.loans.Rational;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import com.fluidtokens.aquarium.offchain.service.loans.ConvertEconomics;
import com.fluidtokens.aquarium.offchain.service.loans.FluidOracleClient;
import com.fluidtokens.aquarium.offchain.service.loans.LiquidatePayInAdvanceTransactionBuilder;
import com.fluidtokens.aquarium.offchain.service.loans.LiquidationCandidateScanner;
import com.fluidtokens.aquarium.offchain.service.loans.LoanFinance;
import com.fluidtokens.aquarium.offchain.service.loans.LoanHealthService;
import com.fluidtokens.aquarium.offchain.service.loans.LoanService;
import com.fluidtokens.aquarium.offchain.service.loans.MarketGate;
import com.fluidtokens.aquarium.offchain.service.loans.MinswapPoolResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * ⛔ <b>The operator readiness list — "which loan will I have to act on next, and will I need capital
 * ready before it goes sour?"</b>
 *
 * <h2>What this is FOR, stated so it does not drift</h2>
 * <b>Decision support, not profit tracking.</b> The use case it exists to serve, in Giovanni's words:
 * a loan is heading for liquidation, there is <b>no Minswap pool for its pair</b>, so the bot cannot
 * convert — and the operator must <b>find the principal in advance</b>. That decision has to be made
 * <i>before</i> the loan becomes liquidatable, which is why this list deliberately includes loans that
 * are only <b>approaching</b> the threshold. A list of what is already liquidatable would arrive
 * exactly too late to be useful.
 *
 * <h2>⛔ WHAT-YOU-SEE == WHAT-THE-BOT-WOULD-DO, and it is not a slogan</h2>
 * Every figure here comes from the <b>same production object the bot acts through</b>:
 * {@link LiquidationCandidateScanner} for the verdict, {@link LoanHealthService} for health,
 * {@link ConvertEconomics#liquidationFee} for the fee slice, {@link MinswapPoolResolver} for whether a
 * pool exists, {@link MarketGate} for the routing, and
 * {@link LiquidatePayInAdvanceTransactionBuilder#numbers} for the amount an advance must front.
 * <b>Nothing is re-derived in this class.</b> A view that recomputed any of them would be a second
 * implementation of the same formula, and the first time the two disagreed the operator would have two
 * numbers and no way to choose.
 *
 * <p>⚠ In particular the advance amount is {@code convertedLoanCollateralToPrincipalAmount} and
 * <b>never {@code remainingDebt}</b> — they differ by 886,721 lovelace on the live mainnet candidate
 * (findings §54.4), and the debt is the intuitive figure and the wrong one.
 *
 * <h2>⚠ UNKNOWN is a value, and zero is not</h2>
 * Where a feed is missing or a pool cannot be resolved the cell reads <b>UNKNOWN with the reason</b>.
 * {@link LoanHealth} already refuses to guess — no feed yields null and a reason, never zero — and this
 * inherits that. <b>A fabricated number here would be acted on.</b>
 *
 * <h2>Off by default, and no authentication</h2>
 * Behind {@code loans.ui.enabled} (default {@code false}), so with it unset this bean is never
 * constructed and no route is mapped. <b>There is no authentication and none is planned</b>: it is a
 * run-it-locally tool, it shows loan positions and the operator's own routing, and anything reachable
 * on a public port is reachable by anyone. Bind to loopback or put a reverse proxy in front.
 */
@Controller
@RequestMapping("${apiPrefix}/loans/readiness")
@ConditionalOnProperty(prefix = "loans.ui", name = "enabled", havingValue = "true")
@Slf4j
public class LiquidationReadinessController {

    /** One row. Every field is either a production figure or an explicit {@code null} + reason. */
    public record Row(String loanId,
                      String utxoRef,
                      String principalUnit,
                      BigInteger principalAmount,
                      String collateralUnit,
                      BigInteger collateralAmount,
                      Double healthFactor,
                      Double currentLtvPercent,
                      Boolean liquidatable,
                      String healthUnknownReason,
                      BigInteger feeInCollateral,
                      BigInteger feeValueLovelace,
                      String feeUnknownReason,
                      String route,
                      String routeDetail,
                      BigInteger advanceLovelace) {

        /** Sorting key: lower is closer to liquidation. Unknown health sorts last, never first. */
        public double sortKey() {
            return healthFactor == null ? Double.MAX_VALUE : healthFactor;
        }
    }

    private final ObjectProvider<LiquidationCandidateScanner> scanner;
    private final ObjectProvider<LoanService> loanService;
    private final ObjectProvider<LoanHealthService> loanHealthService;
    private final ObjectProvider<FluidOracleClient> oracleClient;
    private final ObjectProvider<MinswapPoolResolver> poolResolver;
    private final ObjectProvider<LoansContractRegistry> registry;
    private final AppConfig.LiquidationConfiguration liquidationConfiguration;
    private final AppConfig.Network network;

    public LiquidationReadinessController(ObjectProvider<LiquidationCandidateScanner> scanner,
                                          ObjectProvider<LoanService> loanService,
                                          ObjectProvider<LoanHealthService> loanHealthService,
                                          ObjectProvider<FluidOracleClient> oracleClient,
                                          ObjectProvider<MinswapPoolResolver> poolResolver,
                                          ObjectProvider<LoansContractRegistry> registry,
                                          AppConfig.LiquidationConfiguration liquidationConfiguration,
                                          AppConfig.Network network) {
        this.scanner = scanner;
        this.loanService = loanService;
        this.loanHealthService = loanHealthService;
        this.oracleClient = oracleClient;
        this.poolResolver = poolResolver;
        this.registry = registry;
        this.liquidationConfiguration = liquidationConfiguration;
        this.network = network;
    }

    @GetMapping
    public String readiness(Model model) {
        model.addAttribute("network", network == null ? "unknown" : network.getNetwork());
        model.addAttribute("generatedAt", java.time.Instant.now().toString());

        LoanService loans = loanService.getIfAvailable();
        LiquidationCandidateScanner scan = scanner.getIfAvailable();
        LoanHealthService health = loanHealthService.getIfAvailable();
        if (loans == null || scan == null || health == null) {
            // ⚠ A legible line beats a context failure. This branch predates the removal of
            // `loans.enabled` (2026-09-04), when it was the ordinary misconfiguration of turning the
            // UI on with lending off. The lending beans are unconditional now, so reaching this means
            // one of them genuinely failed to build — a different and worse thing, and the line must
            // not keep blaming a flag that no longer exists.
            model.addAttribute("disabledReason",
                    "the lending scanner or health service is not available on this node, so there is "
                            + "nothing to read. These beans are built unconditionally, so this points "
                            + "at a startup failure in one of them rather than at a setting — check "
                            + "the boot log. The readiness list has no scanner of its own; it reads "
                            + "exactly what the bot reads.");
            model.addAttribute("rows", List.of());
            return "readiness";
        }

        long now = System.currentTimeMillis();
        model.addAttribute("disabledReason", null);
        model.addAttribute("rows", rows(loans, scan, health, now));
        return "readiness";
    }

    private List<Row> rows(LoanService loans, LiquidationCandidateScanner scan,
                           LoanHealthService healthService, long now) {
        LiquidationCandidateScanner.Scan result = scan.scan(now);

        Map<String, LiquidationAssessment> byLoanId = result.assessments().stream()
                .collect(Collectors.toMap(a -> a.bond().loanId(), Function.identity(),
                        (first, duplicate) -> first));

        MarketGate gate = new MarketGate(liquidationConfiguration);
        List<Row> rows = new ArrayList<>();
        for (Loan loan : result.loanCensus().loans()) {
            LiquidationAssessment assessment = byLoanId.get(loan.loanId());
            rows.add(row(loan, assessment, healthService.health(loan, now), gate, now));
        }
        rows.sort(Comparator.comparingDouble(Row::sortKey));
        return rows;
    }

    private Row row(Loan loan, LiquidationAssessment assessment, LoanHealth health,
                    MarketGate gate, long now) {
        var datum = loan.datum();
        AssetType collateralAsset = datum.collateral().assetType();

        // ---- health factor: liquidationLtv / currentLtv, so < 1 is liquidatable -------------------
        Double healthFactor = null;
        String healthUnknown = health.unavailableReason();
        if (health.currentLtv() != null
                && datum.liquidationMode() instanceof LiquidationMode.Liquidation liquidation) {
            Rational threshold = LoanFinance.liquidationLtv(liquidation);
            Optional<Rational> inverse = health.currentLtv().reciprocal();
            if (inverse.isPresent()) {
                Rational hf = threshold.mul(inverse.get());
                healthFactor = hf.numerator().doubleValue() / hf.denominator().doubleValue();
            } else {
                healthUnknown = "current LTV is zero — no debt priced against this collateral yet";
            }
        } else if (healthUnknown == null) {
            healthUnknown = "this loan's mode carries no LTV threshold";
        }

        // ---- the fee slice: the gate's own function, never a re-derivation ------------------------
        BigInteger feeTokens = null;
        BigInteger feeValue = null;
        String feeUnknown;
        LenderBond bond = assessment == null ? null : assessment.bond();
        if (bond == null) {
            feeUnknown = "no lender bond is indexed for this loan, so its fee rate is unknown";
        } else {
            feeTokens = ConvertEconomics.liquidationFee(loan.collateralAmount(),
                    bond.datum().liquidationFeePerMille().longValueExact());
            feeUnknown = null;
            var client = oracleClient.getIfAvailable();
            var feed = client == null ? Optional.<com.fluidtokens.aquarium.offchain.model.loans
                    .OraclePriceFeed>empty() : client.findFeed(collateralAsset, now);
            if (feed.isEmpty()) {
                feeUnknown = "no usable oracle feed for the collateral, so the slice cannot be valued";
            } else {
                try {
                    feeValue = LoanFinance.toLovelace(Rational.fromInt(feeTokens), feed.get()).floor();
                } catch (RuntimeException e) {
                    feeUnknown = "the collateral feed cannot be priced (" + e.getClass().getSimpleName() + ")";
                }
            }
        }

        // ---- the route, and the capital an advance would need -------------------------------------
        String route;
        String routeDetail;
        BigInteger advance = null;
        if (bond == null) {
            route = "UNKNOWN";
            routeDetail = "no lender bond indexed — the bond decides whether conversion is permitted";
        } else if (!bond.datum().shouldLiquidationConvertToPrincipal()) {
            route = "PLAIN LIQUIDATE";
            routeDetail = "the lender bond forbids conversion, so the bot takes its fee in collateral "
                    + "and fronts nothing";
        } else {
            var action = gate.actionFor(datum.principalAsset());
            var pool = resolvePool(collateralAsset, datum.principalAsset());
            if (action == AppConfig.LiquidationConfiguration.Action.CONVERT && pool.available()) {
                route = "CONVERT";
                routeDetail = "a Minswap pool exists for this pair, so the bot creates a swap order and "
                        + "fronts no capital";
            } else {
                route = "CAPITAL IN ADVANCE";
                routeDetail = action == AppConfig.LiquidationConfiguration.Action.ANTICIPATE
                        ? "this market is configured action: ANTICIPATE, so the bot fronts the principal"
                        : "no Minswap pool is available for this pair (" + pool.reason()
                                + "), so conversion cannot be used and the principal must be fronted";
                advance = advanceAmount(loan, bond, now);
            }
        }

        return new Row(loan.loanId(), loan.utxoRef(),
                datum.principalAsset().toUnit(), datum.principalAmount(),
                collateralAsset.toUnit(), loan.collateralAmount(),
                healthFactor, health.currentLtvPercent(), health.liquidatable(),
                healthFactor == null ? healthUnknown : null,
                feeTokens, feeValue, feeUnknown,
                route, routeDetail, advance);
    }

    private record PoolLookup(boolean available, String reason) {
    }

    private PoolLookup resolvePool(AssetType collateral, AssetType principal) {
        MinswapPoolResolver resolver = poolResolver.getIfAvailable();
        if (resolver == null) {
            return new PoolLookup(false, "this node cannot convert — loans.minswap.* is unset or "
                    + "belongs to another network");
        }
        try {
            return resolver.resolveEitherOrder(collateral, principal)
                    .map(p -> new PoolLookup(true, null))
                    .orElseGet(() -> new PoolLookup(false, "no pool found for the pair"));
        } catch (RuntimeException e) {
            // The resolver reaches the chain, and one unreachable pool must not blank the page.
            log.debug("pool lookup failed for {}/{}: {}", collateral.toUnit(), principal.toUnit(),
                    e.toString());
            return new PoolLookup(false, "the pool lookup failed (" + e.getClass().getSimpleName() + ")");
        }
    }

    /**
     * ⛔ The PRODUCTION figure — {@code convertedLoanCollateralToPrincipalAmount}, taken from the
     * builder, never {@code remainingDebt}. Null when the collateral has no oracle entry, because
     * the amount genuinely cannot be known then.
     */
    private BigInteger advanceAmount(Loan loan, LenderBond bond, long now) {
        FluidOracleClient client = oracleClient.getIfAvailable();
        LoansContractRegistry reg = registry.getIfAvailable();
        if (client == null || reg == null) {
            return null;
        }
        Optional<OracleEntry> oracle = client.findEntry(loan.datum().collateral().assetType());
        if (oracle.isEmpty()) {
            return null;
        }
        try {
            return new LiquidatePayInAdvanceTransactionBuilder(reg, network.getCardanoNetwork(),
                    (com.bloxbean.cardano.client.api.UtxoSupplier) null,
                    (com.bloxbean.cardano.client.api.ProtocolParamsSupplier) null)
                    .numbers(loan, bond, oracle.get(), now)
                    .convertedLoanCollateralToPrincipalAmount();
        } catch (RuntimeException e) {
            log.debug("could not compute the advance amount for {}: {}", loan.utxoRef(), e.toString());
            return null;
        }
    }
}
