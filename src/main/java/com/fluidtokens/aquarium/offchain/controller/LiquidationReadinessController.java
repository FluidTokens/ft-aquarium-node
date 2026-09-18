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
import com.fluidtokens.aquarium.offchain.model.loans.MinswapPoolDatum;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.model.loans.Rational;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import com.fluidtokens.aquarium.offchain.service.loans.FluidOracleClient;
import com.fluidtokens.aquarium.offchain.service.loans.ConvertEconomics;
import com.fluidtokens.aquarium.offchain.service.loans.LiquidatePayInAdvanceTransactionBuilder;
import com.fluidtokens.aquarium.offchain.service.loans.LiquidationCandidateScanner;
import com.fluidtokens.aquarium.offchain.service.loans.LoanFinance;
import com.fluidtokens.aquarium.offchain.service.loans.LoanHealthService;
import com.fluidtokens.aquarium.offchain.service.loans.LoanService;
import com.fluidtokens.aquarium.offchain.service.loans.MarketGate;
import com.fluidtokens.aquarium.offchain.service.loans.MinswapPoolResolver;
import com.fluidtokens.aquarium.offchain.service.loans.PoolUsability;
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
                      AssetDisplay collateralDisplay,
                      // ⛔ Whether a pool could fill THIS loan — not whether one exists. See PoolUsability.
                      PoolUsability poolUsability,
                      // ---- the fee slice and the capital, SCALED AND TICKERED ---------------------
                      // ⚠ feeInCollateral above is in the COLLATERAL asset's own base units and
                      // feeValueLovelace is that amount priced in lovelace. Two different assets, and
                      // the page used to render both as bare integers side by side, which reads as one
                      // number restated. These carry the scale and the ticker so they cannot.
                      AssetDisplay feeDisplay,
                      // The same fee priced in ada, so the two figures are never two bare integers.
                      AssetDisplay feeValueDisplay,
                      AssetDisplay advanceDisplay,
                      // cexplorer links. Null when the network or the loan policy is unknown — a dead
                      // link is worse than none, so the template renders plain text instead.
                      String loanExplorerUrl,
                      String utxoExplorerUrl) {

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
        Map<String, PoolFetch> poolMemo = new HashMap<>();
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
                    MarketGate gate, Map<String, PoolFetch> poolMemo,
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
        // Defaults for the branches that never reach a pool: the bond settles it before the chain does.
        PoolUsability usability = new PoolUsability(PoolUsability.Verdict.UNKNOWN,
                "the lender bond decides this loan's route before a pool is consulted");
        if (bond == null) {
            route = "UNKNOWN";
            routeDetail = "no lender bond indexed — the bond decides whether conversion is permitted";
        } else if (!bond.datum().shouldLiquidationConvertToPrincipal()) {
            route = "PLAIN LIQUIDATE";
            routeDetail = "the lender bond forbids conversion, so the bot takes its fee in collateral "
                    + "and fronts nothing";
        } else {
            var action = gate.actionFor(datum.principalAsset());
            // The FETCH is per pair and memoised; the VERDICT is per loan. Both come from the same
            // already-fetched pool datum, so asking the sharper question costs no extra call.
            PoolFetch fetched = resolvePool(collateralAsset, datum.principalAsset(), poolMemo);
            usability = usabilityFor(fetched, loan, bond, collateralAsset, datum.principalAsset(), now);

            if (action == AppConfig.LiquidationConfiguration.Action.CONVERT && usability.usable()) {
                route = "CONVERT";
                routeDetail = "a Minswap pool is deep enough to clear this loan's debt, so the bot "
                        + "creates a swap order and fronts no capital";
            } else {
                route = "CAPITAL IN ADVANCE";
                // ⛔ The market's configuration and the pool's state are DIFFERENT reasons, and an
                // operator acts on them differently: a setting will not change by itself, a thin pool
                // may. So both are reported, never one standing in for the other.
                routeDetail = action == AppConfig.LiquidationConfiguration.Action.ANTICIPATE
                        ? "this market is configured action: ANTICIPATE, so the bot fronts the principal "
                                + "whatever the pool says"
                        : "conversion is unavailable for this loan, so the principal must be fronted";
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
                display(collateralAsset.toUnit(), loan.collateralAmount(), metadataMemo),
                usability,
                feeTokens == null ? null : display(collateralAsset.toUnit(), feeTokens, metadataMemo),
                feeValue == null ? null : display("lovelace", feeValue, metadataMemo),
                advance == null ? null : display(datum.principalAsset().toUnit(), advance, metadataMemo),
                loanAssetUrl(loan.loanId()), txUrl(loan.utxoRef()));
    }

    /**
     * ⛔ Whether the fetched pool could fill THIS loan.
     *
     * <p>Everything here is arithmetic over values already in hand — the pool datum from the memoised
     * fetch, and this loan's own figures from the oracle cache. <b>No external call.</b> If a lookup is
     * unavailable the reason survives unchanged; the page must never turn "could not ask" into
     * "no pool", because one says try again shortly and the other says hold capital from now on.
     */
    private PoolUsability usabilityFor(PoolFetch fetched, Loan loan, LenderBond bond,
                                       AssetType collateral, AssetType principal, long now) {
        if (fetched.unavailable() != null) {
            return fetched.unavailable();
        }
        var numbers = numbersFor(loan, bond, now);
        if (numbers == null) {
            return new PoolUsability(PoolUsability.Verdict.UNKNOWN,
                    "this loan's debt and equity cannot be priced, so pool usability is not known");
        }
        // collateralLenderShouldReceive IS collateral − equity − liquidationFee: what reaches the pool.
        return PoolUsability.assess(collateral, principal,
                numbers.collateralLenderShouldReceive(), numbers.remainingDebt(), fetched.datum());
    }

    /**
     * The rendered form of one amount: scaled when a registry published a scale, raw and marked when
     * nothing did. Never a guessed scale — see {@link AssetDisplay#of}.
     *
     * <p>When the token metadata service is absent the amount still renders, as raw base units with
     * the unknown marker. That is the honest degradation: the page keeps working and says that it does
     * not know, rather than inventing a scale to look complete.
     */
    /**
     * cexplorer base for the ACTIVE network, derived from the profile rather than configured.
     *
     * <p>A second configuration key would be a second source of truth for something the profile
     * already settles, and its failure mode is silent: a mainnet node linking to preview pages.
     * Returns null on an unknown network so callers render plain text rather than a dead link.
     */
    private String explorerBase() {
        String n = network == null ? null : network.getNetwork();
        if (n == null) {
            return null;
        }
        return switch (n) {
            case "mainnet" -> "https://cexplorer.io";
            case "preview" -> "https://preview.cexplorer.io";
            case "preprod" -> "https://preprod.cexplorer.io";
            default -> null;
        };
    }

    /**
     * The loan NFT's asset page. {@code loanId} is the asset NAME; the unit cexplorer wants is the
     * loan POLICY concatenated with it, so this is null when the registry has no coordinates.
     */
    private String loanAssetUrl(String loanId) {
        String base = explorerBase();
        LoansContractRegistry reg = registry.getIfAvailable();
        if (base == null || loanId == null || reg == null || !reg.isConfigured()) {
            return null;
        }
        return base + "/asset/" + reg.getLoanPolicyId() + loanId;
    }

    /** The transaction that created the loan UTxO. {@code utxoRef} is {@code txHash#index}. */
    private String txUrl(String utxoRef) {
        String base = explorerBase();
        if (base == null || utxoRef == null || !utxoRef.contains("#")) {
            return null;
        }
        return base + "/tx/" + utxoRef.substring(0, utxoRef.indexOf('#'));
    }

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

    /**
     * One pair's pool as fetched. Exactly one field is non-null: the datum when a pool was found, or
     * the reason it could not be. The FETCH is per pair and memoised; the VERDICT is per loan and is
     * computed from this by {@link PoolUsability#assess}.
     */
    record PoolFetch(MinswapPoolDatum datum, PoolUsability unavailable) {
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
    PoolFetch resolvePool(AssetType collateral, AssetType principal,
                          Map<String, PoolFetch> poolMemo) {
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
    PoolFetch lookupPool(AssetType collateral, AssetType principal) {
        MinswapPoolResolver resolver = poolResolver.getIfAvailable();
        if (resolver == null) {
            return new PoolFetch(null, PoolUsability.notConfigured());
        }
        try {
            return resolver.resolveEitherOrder(collateral, principal)
                    .map(p -> new PoolFetch(p.datum(), null))
                    .orElseGet(() -> new PoolFetch(null, PoolUsability.noPool()));
        } catch (RuntimeException e) {
            // ⛔ A failed lookup is NOT "no pool exists". One says hold capital for this loan from now
            // on; the other says try again shortly. Collapsing them was the defect this now avoids.
            // ⛔ WARN, NOT DEBUG. This was log.debug, so on a node running at INFO the page told the
            // operator the lookup "did not complete ... worth re-checking" and the logs held NOTHING
            // to re-check. A UI that reports a fault must not be the only place the fault exists.
            log.warn("pool lookup failed for {}/{}: {} — the readiness page shows CHECK FAILED for "
                            + "every loan on this pair until it succeeds",
                    collateral.toUnit(), principal.toUnit(), e.toString(), e);
            return new PoolFetch(null, PoolUsability.checkFailed(e.getClass().getSimpleName()));
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
        var n = numbersFor(loan, bond, now);
        return n == null ? null : n.convertedLoanCollateralToPrincipalAmount();
    }

    /**
     * The loan's five figures, as the builder and the validators compute them. Oracle prices come from
     * the in-memory feed cache, so this costs no network call — which is what lets the pool verdict be
     * per-loan without changing the page's cost profile.
     */
    LiquidatePayInAdvanceTransactionBuilder.Numbers numbersFor(Loan loan, LenderBond bond, long now) {
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
                    .numbers(loan, bond, oracle.get(), principalOracle, now);
        } catch (RuntimeException e) {
            log.debug("could not compute this loan's figures for {}: {}", loan.utxoRef(), e.toString());
            return null;
        }
    }
}
