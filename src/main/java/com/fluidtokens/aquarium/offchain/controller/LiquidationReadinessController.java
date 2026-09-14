package com.fluidtokens.aquarium.offchain.controller;

import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.model.AssetDisplay;
import com.fluidtokens.aquarium.offchain.model.LoanAge;
import com.fluidtokens.aquarium.offchain.model.TokenMetadata;
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
import com.fluidtokens.aquarium.offchain.service.loans.TokenMetadataService;
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
import java.util.HashMap;
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
                      // F5 (round 2) — RENAMED from advanceLovelace: the figure is denominated in the
                      // LOAN'S OWN PRINCIPAL asset (principalUnit, above), never lovelace — a USDM loan
                      // reported here in a field named "…Lovelace" was 4.63x wrong in the wrong unit,
                      // read literally as lovelace by an operator acting on it.
                      BigInteger advancePrincipalAmount,
                      // ---- display enrichment ----------------------------------------------------
                      // The figures above stay exactly as they were: raw, unscaled, in the loan's own
                      // units, because everything downstream reasons about them. These three are what
                      // the page renders, and nothing computes with them.
                      LoanAge age,
                      AssetDisplay principalDisplay,
                      AssetDisplay collateralDisplay) {

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
    private final ObjectProvider<TokenMetadataService> tokenMetadata;
    private final ObjectProvider<LoansContractRegistry> registry;
    private final AppConfig.LiquidationConfiguration liquidationConfiguration;
    private final AppConfig.Network network;

    public LiquidationReadinessController(ObjectProvider<LiquidationCandidateScanner> scanner,
                                          ObjectProvider<LoanService> loanService,
                                          ObjectProvider<LoanHealthService> loanHealthService,
                                          ObjectProvider<FluidOracleClient> oracleClient,
                                          ObjectProvider<MinswapPoolResolver> poolResolver,
                                          ObjectProvider<TokenMetadataService> tokenMetadata,
                                          ObjectProvider<LoansContractRegistry> registry,
                                          AppConfig.LiquidationConfiguration liquidationConfiguration,
                                          AppConfig.Network network) {
        this.scanner = scanner;
        this.loanService = loanService;
        this.loanHealthService = loanHealthService;
        this.oracleClient = oracleClient;
        this.poolResolver = poolResolver;
        this.tokenMetadata = tokenMetadata;
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
        // ⛔ ONE render, ONE lookup per distinct pair. See resolvePool: this map lives exactly as long
        // as this request and is never shared between renders, so nothing it holds can go stale.
        Map<String, PoolLookup> poolMemo = new HashMap<>();
        // Same reasoning as the pool memo one level down: an asset's ticker and scale are a property
        // of the ASSET, not of the row, and a table is mostly two or three distinct assets.
        Map<String, TokenMetadata> metadataMemo = new HashMap<>();
        List<Row> rows = new ArrayList<>();
        for (Loan loan : result.loanCensus().loans()) {
            LiquidationAssessment assessment = byLoanId.get(loan.loanId());
            rows.add(row(loan, assessment, healthService.health(loan, now), gate, poolMemo, metadataMemo, now));
        }
        rows.sort(Comparator.comparingDouble(Row::sortKey));
        return rows;
    }

    private Row row(Loan loan, LiquidationAssessment assessment, LoanHealth health,
                    MarketGate gate, Map<String, PoolLookup> poolMemo,
                    Map<String, TokenMetadata> metadataMemo, long now) {
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
            var pool = resolvePool(collateralAsset, datum.principalAsset(), poolMemo);
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
                route, routeDetail, advance,
                LoanAge.since(datum.lendDate(), now),
                display(datum.principalAsset().toUnit(), datum.principalAmount(), metadataMemo),
                display(collateralAsset.toUnit(), loan.collateralAmount(), metadataMemo));
    }

    /**
     * The rendered form of one amount: scaled when a registry published a scale, raw and marked when
     * nothing did. Never a guessed scale — see {@link AssetDisplay#of}.
     *
     * <p>When the token metadata service is absent the amount still renders, as raw base units with
     * the unknown marker. That is the honest degradation: the page keeps working and says that it does
     * not know, rather than inventing a scale to look complete.
     */
    private AssetDisplay display(String unit, BigInteger amount, Map<String, TokenMetadata> memo) {
        TokenMetadata metadata = memo.computeIfAbsent(unit, u -> {
            TokenMetadataService service = tokenMetadata.getIfAvailable();
            if (service != null) {
                return service.lookup(u);
            }
            return "lovelace".equals(u) ? TokenMetadata.ada() : TokenMetadata.unknown(u);
        });
        return AssetDisplay.of(amount, metadata);
    }

    record PoolLookup(boolean available, String reason) {
    }

    /**
     * ⛔ <b>The pool question is per PAIR; this page was asking it per ROW.</b>
     *
     * <p>{@link MinswapPoolResolver#resolveEitherOrder} is a Blockfrost round trip — one call, or
     * <b>two</b> when the first asset ordering 404s, which is always the case for a pair that has no
     * pool at all ({@code compute_lp_asset_name} is order-sensitive). It holds no cache. So a table of
     * N loans cost <b>N to 2N Blockfrost calls per render</b>, and every loan sharing a pair re-asked
     * an identical question and received an identical answer.
     *
     * <p>This memo is <b>request-scoped</b>: created in {@link #rows}, discarded when the response is.
     * Two loans on the same pair now produce one lookup instead of two, and the data rendered is
     * byte-for-byte what the page would have shown anyway — <b>same request, same instant</b>.
     *
     * <h2>⚠ Why this is a dedupe and deliberately NOT a TTL cache</h2>
     * {@code resolveEitherOrder} returns the pool UTxO, and <b>a pool UTxO carries reserves</b>.
     * Reserves held across renders are stale reserves, and the same resolver is injected into
     * {@link com.fluidtokens.aquarium.offchain.service.loans.ConvertLiquidationRouter} on the convert
     * <b>build</b> path — where stale reserves would price a real transaction. A per-request map
     * cannot reach that path and cannot outlive the answer it belongs to; a TTL cache would be a
     * different and money-shaped change. <b>Do not promote this to a field.</b>
     */
    PoolLookup resolvePool(AssetType collateral, AssetType principal,
                           Map<String, PoolLookup> poolMemo) {
        return poolMemo.computeIfAbsent(pairKey(collateral, principal),
                key -> lookupPool(collateral, principal));
    }

    /**
     * Order-independent key. {@code resolveEitherOrder} tries both orderings and the pool exists under
     * exactly one of them, so {@code (ada, fldt)} and {@code (fldt, ada)} are the same question and
     * must share one entry — keying on the arguments as given would miss half the duplicates.
     */
    static String pairKey(AssetType collateral, AssetType principal) {
        String a = collateral.toUnit();
        String b = principal.toUnit();
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }

    // Package-private so the dedupe can be proven against a counting resolver without a Spring context.
    PoolLookup lookupPool(AssetType collateral, AssetType principal) {
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
     * builder, never {@code remainingDebt}. Null when the collateral (or, for a non-ada principal,
     * the principal) has no oracle entry, because the amount genuinely cannot be known then.
     * <p>
     * F5 (round 2) — routed through the REAL {@code numbers()} overload with the loan's own principal
     * oracle, never the deprecated 4-arg one that silently priced every principal as ada. That
     * silent substitution was a 4.63x wrong number in a field an operator acts on directly.
     */
    // Package-private (not private) so LiquidationReadinessControllerTest can prove F5's fix directly
    // — that a real principalOracle actually reaches numbers(), not just that the deprecated 4-arg
    // overload got deleted — without standing up the full readiness()/rows() Spring plumbing.
    BigInteger advanceAmount(Loan loan, LenderBond bond, long now) {
        FluidOracleClient client = oracleClient.getIfAvailable();
        LoansContractRegistry reg = registry.getIfAvailable();
        if (client == null || reg == null) {
            return null;
        }
        Optional<OracleEntry> oracle = client.findEntry(loan.datum().collateral().assetType());
        if (oracle.isEmpty()) {
            return null;
        }
        AssetType principalAsset = loan.datum().principalAsset();
        OracleEntry principalOracle = null;
        if (!principalAsset.isAda()) {
            // Same lookup style already used for the collateral leg above — findEntry is keyed by the
            // PRICED asset (byToken), never the oracle NFT (that is findEntryByOracleToken's job, for
            // building a real transaction where the exact reference-input coordinate matters). A
            // controller-only simplification unchanged by this fix; see the class javadoc on where a
            // re-derivation is and is not tolerated here.
            Optional<OracleEntry> principalEntry = client.findEntry(principalAsset);
            if (principalEntry.isEmpty()) {
                return null;
            }
            principalOracle = principalEntry.get();
        }
        try {
            return new LiquidatePayInAdvanceTransactionBuilder(reg, network.getCardanoNetwork(),
                    (com.bloxbean.cardano.client.api.UtxoSupplier) null,
                    (com.bloxbean.cardano.client.api.ProtocolParamsSupplier) null)
                    .numbers(loan, bond, oracle.get(), principalOracle, now)
                    .convertedLoanCollateralToPrincipalAmount();
        } catch (RuntimeException e) {
            log.debug("could not compute the advance amount for {}: {}", loan.utxoRef(), e.toString());
            return null;
        }
    }
}
