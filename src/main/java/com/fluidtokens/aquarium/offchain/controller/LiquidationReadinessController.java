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
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.model.loans.Rational;
import com.fluidtokens.aquarium.offchain.service.AppUtxoService;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import com.fluidtokens.aquarium.offchain.service.loans.FluidOracleClient;
import com.fluidtokens.aquarium.offchain.service.loans.AnticipateAndSell;
import com.fluidtokens.aquarium.offchain.service.loans.ConvertEconomics;
import com.fluidtokens.aquarium.offchain.service.loans.ExpectedLiquidationReward;
import com.fluidtokens.aquarium.offchain.service.loans.LiquidatePayInAdvanceTransactionBuilder;
import com.fluidtokens.aquarium.offchain.service.loans.LiquidationCandidateScanner;
import com.fluidtokens.aquarium.offchain.service.loans.LoanFinance;
import com.fluidtokens.aquarium.offchain.service.loans.LoanHealthService;
import com.fluidtokens.aquarium.offchain.service.loans.LoanService;
import com.fluidtokens.aquarium.offchain.service.loans.MarketGate;
import com.fluidtokens.aquarium.offchain.service.loans.MinswapPoolResolver;
import com.fluidtokens.aquarium.offchain.service.loans.PoolUsability;
import com.fluidtokens.aquarium.offchain.service.loans.TokenMetadataService;
import com.fluidtokens.aquarium.offchain.util.LedgerCeilings;
import com.fluidtokens.aquarium.offchain.util.WalletInputSelection;
import lombok.extern.slf4j.Slf4j;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
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
 *
 * <p>⚠ <b>The page has exactly two state-changing routes</b> (FAB-132), {@code POST …/wallet-rebalance/suspend}
 * and {@code POST …/wallet-rebalance/resume}, also unauthenticated and with no CSRF token (ruled). They can
 * only suspend or resume wallet rebalancing — in memory, until the next restart — and nothing else; resume can
 * never override the environment, so a node with {@code AQUARIUM_WALLET_REBALANCE_ENABLED=false} stays off
 * whatever is posted. Each answers {@code 303 See Other} to this page, so the page's reload re-GETs it and
 * never re-POSTs a switch.
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
                      // ⛔ THE DEBT, NOT THE PRINCIPAL, is what every decision turns on -- interest
                      // accrues and the original borrowed amount stops being the number anyone acts
                      // on. Taken from LoanHealth, which computes it with or without prices, so this
                      // column renders even when the oracle cannot value the collateral.
                      BigInteger remainingDebt,
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
                      AssetDisplay debtDisplay,
                      // ⛔ What the operator would net by fronting the principal and selling the
                      // collateral themselves -- a DIFFERENT question from poolUsability, not a
                      // softer one. See AnticipateAndSell.
                      AnticipateAndSell anticipateAndSell,
                      // ⚠ SCALED AND TICKERED like every other amount on this page. A net shown in raw
                      // base units is the same defect as the fee slice and the capital figure were.
                      AssetDisplay anticipateDisplay,
                      // ⛔ THREE SEPARATE FACTS, not one. The route is what a liquidation WOULD build,
                      // the pool status is the chain's answer, and actionNow is what this node would
                      // actually do — which on a disabled node is "nothing", however good the rest looks.
                      ActionNow actionNow,
                      // ⚠ Why the bot could not act on this loan IF IT HAD TO — forward-looking, and
                      // independent of whether it is liquidatable today. See ProcessingBlocker.
                      ProcessingBlocker blocker,
                      // cexplorer links. Null when the network or the loan policy is unknown — a dead
                      // link is worse than none, so the template renders plain text instead.
                      String loanExplorerUrl,
                      String utxoExplorerUrl,
                      // FAB-112: the oracleVersion of the collateral oracle THIS loan's datum names
                      // (1 = Lending v3's, 2 = Lending v4's), null when unknown or no oracle.
                      Integer collateralOracleVersion,
                      // FAB-145: the WHOLE collateral priced in lovelace at the same feed the fee slice
                      // is valued at. Null when it could not be priced — never zero, because the book
                      // above the table must exclude such a loan from its totals rather than count it
                      // as worthless.
                      BigInteger collateralValueLovelace) {

        /** Without a collateral value — every construction that predates FAB-145. */
        public Row(String loanId, String utxoRef, String principalUnit, BigInteger principalAmount,
                   BigInteger remainingDebt, String collateralUnit, BigInteger collateralAmount,
                   Double healthFactor, Double currentLtvPercent, Boolean liquidatable,
                   String healthUnknownReason, BigInteger feeInCollateral, BigInteger feeValueLovelace,
                   String feeUnknownReason, String route, String routeDetail,
                   BigInteger advancePrincipalAmount, LoanAge age, AssetDisplay principalDisplay,
                   AssetDisplay collateralDisplay, PoolUsability poolUsability, AssetDisplay feeDisplay,
                   AssetDisplay feeValueDisplay, AssetDisplay advanceDisplay, AssetDisplay debtDisplay,
                   AnticipateAndSell anticipateAndSell, AssetDisplay anticipateDisplay, ActionNow actionNow,
                   ProcessingBlocker blocker, String loanExplorerUrl, String utxoExplorerUrl,
                   Integer collateralOracleVersion) {
            this(loanId, utxoRef, principalUnit, principalAmount, remainingDebt, collateralUnit,
                    collateralAmount, healthFactor, currentLtvPercent, liquidatable, healthUnknownReason,
                    feeInCollateral, feeValueLovelace, feeUnknownReason, route, routeDetail,
                    advancePrincipalAmount, age, principalDisplay, collateralDisplay, poolUsability,
                    feeDisplay, feeValueDisplay, advanceDisplay, debtDisplay, anticipateAndSell,
                    anticipateDisplay, actionNow, blocker, loanExplorerUrl, utxoExplorerUrl,
                    collateralOracleVersion, null);
        }

        /** Without an oracle version — every construction that predates FAB-112. */
        public Row(String loanId, String utxoRef, String principalUnit, BigInteger principalAmount,
                   BigInteger remainingDebt, String collateralUnit, BigInteger collateralAmount,
                   Double healthFactor, Double currentLtvPercent, Boolean liquidatable,
                   String healthUnknownReason, BigInteger feeInCollateral, BigInteger feeValueLovelace,
                   String feeUnknownReason, String route, String routeDetail,
                   BigInteger advancePrincipalAmount, LoanAge age, AssetDisplay principalDisplay,
                   AssetDisplay collateralDisplay, PoolUsability poolUsability, AssetDisplay feeDisplay,
                   AssetDisplay feeValueDisplay, AssetDisplay advanceDisplay, AssetDisplay debtDisplay,
                   AnticipateAndSell anticipateAndSell, AssetDisplay anticipateDisplay, ActionNow actionNow,
                   ProcessingBlocker blocker, String loanExplorerUrl, String utxoExplorerUrl) {
            this(loanId, utxoRef, principalUnit, principalAmount, remainingDebt, collateralUnit,
                    collateralAmount, healthFactor, currentLtvPercent, liquidatable, healthUnknownReason,
                    feeInCollateral, feeValueLovelace, feeUnknownReason, route, routeDetail,
                    advancePrincipalAmount, age, principalDisplay, collateralDisplay, poolUsability,
                    feeDisplay, feeValueDisplay, advanceDisplay, debtDisplay, anticipateAndSell,
                    anticipateDisplay, actionNow, blocker, loanExplorerUrl, utxoExplorerUrl, null, null);
        }

        /** Sorting key: lower is closer to liquidation. Unknown health sorts last, never first. */
        public double sortKey() {
            return healthFactor == null ? Double.MAX_VALUE : healthFactor;
        }
    }

    /**
     * ⛔ FAB-145: the book above the table — the FILTERED list's counts and the collateral it locks (TVL),
     * an ESTIMATE at oracle prices. Giovanni's rulings (2026-10-18): TVL is collateral only, never debt;
     * USD comes from the reference stablecoin's oracle feed alone (USDCx by default since FAB-182, the page's
     * one ADA/USD rate); and the summary follows the active filters.
     *
     * @param totalLovelace         the PRICED loans' collateral in lovelace; null when there are loans and
     *                              none could be priced — unknown, never zero
     * @param unpricedLoans         loans excluded from every total because their collateral has no price
     * @param usdDisplay            {@code totalLovelace} in the reference stablecoin, scaled; null when USD is
     *                              unavailable. Its label is the token's own, which is what the page names
     * @param usdUnavailableReason  why {@code usdDisplay} is null; null when it is not
     */
    public record BookSummary(long loans, long liquidatable, long blocked, List<TokenTvl> tokens,
                              BigInteger totalLovelace, long unpricedLoans, AssetDisplay totalAdaDisplay,
                              AssetDisplay usdDisplay, AssetDisplay adaUsdRateDisplay,
                              String usdUnavailableReason) {
    }

    /**
     * One collateral token's line of the book. ⚠ {@code amount} is EVERY loan's collateral, priced or not;
     * {@code valueLovelace} only the priced ones', and null when none was — so an unpriced loan is never
     * dropped from what is locked and never valued at zero.
     */
    public record TokenTvl(String unit, long loans, AssetDisplay amount, BigInteger valueLovelace,
                           AssetDisplay valueDisplay, long unpricedLoans) {
    }

    /**
     * ⛔ FAB-182: the prices strip under the wallet — every token the oracle registry prices, priced with the
     * feed the bot holds for it, plus ADA's own USD rate first. Display only: nothing decides on it.
     *
     * @param entries              the ADA entry, then one per distinct registry token, by label
     * @param usdReference         the reference stablecoin's own label (USDCx, …), which the strip's caveat names
     *                             so every view — the phone's included — says in words that USD is an estimate;
     *                             null when no reference unit is configured
     * @param usdUnavailableReason why every USD cell is {@code —}; null when USD is available
     * @param serverNow            the render instant (epoch ms) every age is measured from, so the page's
     *                             script ticks from the server's clock and not the browser's
     */
    public record PriceStrip(List<TokenPrice> entries, String usdReference, String usdUnavailableReason,
                             long serverNow) {
    }

    /**
     * One entry of the strip.
     *
     * @param adaText         the price of one WHOLE token in ADA, 4 significant digits — per raw BASE UNIT when
     *                        {@code metadataUnknown}, because a scale nobody published is never guessed; null for
     *                        the ADA entry
     * @param usdText         {@code $…}, or {@code —} when USD is unavailable
     * @param metadataUnknown no decimals are known, so both figures are per base unit and the page marks them
     * @param stale           no feed for this token is usable now; the figures are the last one held
     * @param validFrom       the shown feed's {@code validFrom} (epoch ms); null for the ADA entry, which has no age
     * @param ageText         {@code now − validFrom} as {@code XmYs}, or {@code XhYm} from one hour
     */
    public record TokenPrice(String unit, String label, String policyId, String adaText, String usdText,
                             boolean metadataUnknown, boolean stale, Long validFrom, String ageText) {
    }

    /**
     * ⛔ FAB-132: the "Wallet rebalancing" line under the banner, one text per state, taken from the
     * rebalancer's own {@code activity()} — the evaluation the bot acts on — so it never reads ARMED while
     * the rebalancer is idle, and always says why it is idle.
     *
     * @param state      {@code OFF}, {@code SUSPENDED}, {@code IDLE} or {@code ARMED}
     * @param canSuspend the page offers Suspend
     * @param canResume  the page offers Resume; never with the environment off, which this page cannot override
     */
    public record RebalanceView(boolean envEnabled, boolean suspended, String state, String detail,
                                boolean canSuspend, boolean canResume) {

        static RebalanceView of(com.fluidtokens.aquarium.offchain.service.wallet.WalletRebalancer.Activity a) {
            boolean env = a.state().envEnabled();
            boolean suspended = a.state().suspended();
            if (a.armed()) {
                return new RebalanceView(env, suspended, "ARMED",
                        "on each pass — after a committed block or an oracle price change — in which no liquidation "
                                + "or compound went out, if the wallet has drifted out of shape, the node signs and "
                                + "submits one self-send of its whole wallet, in shadow as well as live", true, false);
            }
            return switch (a.idle()) {
                case ENV_DISABLED -> new RebalanceView(env, suspended, "OFF",
                        "AQUARIUM_WALLET_REBALANCE_ENABLED is false, so this node never rebalances its wallet; "
                                + "this page cannot turn it on", false, false);
                case SUSPENDED -> new RebalanceView(env, suspended, "SUSPENDED",
                        "from this page: nothing is rebalanced until you resume it here, or until the node "
                                + "restarts", false, true);
                case MODE_NOT_SHADOW_OR_LIVE -> new RebalanceView(env, suspended, "IDLE",
                        "the liquidation mode is " + (a.mode() == null ? "not set" : a.mode().name())
                                + "; rebalancing runs only in shadow or live", true, false);
                case SYNCING -> new RebalanceView(env, suspended, "IDLE",
                        "the node is syncing; rebalancing starts once it has caught up", true, false);
            };
        }
    }

    private final ObjectProvider<LiquidationCandidateScanner> scanner;
    private final ObjectProvider<LoanService> loanService;
    private final ObjectProvider<LoanHealthService> loanHealthService;
    private final ObjectProvider<FluidOracleClient> oracleClient;
    private final ObjectProvider<MinswapPoolResolver> poolResolver;
    private final ObjectProvider<TokenMetadataService> tokenMetadata;
    private final ObjectProvider<LoansContractRegistry> registry;
    private final ObjectProvider<AppUtxoService> walletUtxos;
    private final AppConfig.LiquidationConfiguration liquidationConfiguration;
    private final AppConfig.Network network;

    /**
     * ⚠ A RENDER-FREQUENCY THROTTLE ON A LOCAL READ, not a cache of chain data against a provider.
     * {@code AppUtxoService.listWalletUtxo()} reads the local Yaci index by the wallet's payment
     * credential (FAB-134 B2) — no Blockfrost call is involved — and this page re-renders every sixty
     * seconds in every open tab, so the TTL only bounds how often one render path queries Postgres.
     *
     * <p>⚠ A throttled number shown as live is still a stale number, so the reading's AGE is rendered
     * beside it. Volatile rather than synchronized: a duplicate read under a race costs one database
     * query, and a lock on a render path costs a stall.
     */
    private static final long WALLET_TTL_MILLIS = 60_000L;

    /** ⚠ Safe only because the default sort is health-ascending: page 1 is the urgent page. */
    static final int PAGE_SIZE = 25;
    private volatile WalletBalance cachedWallet = WalletBalance.unknown();

    // ⚠ @Value fields rather than constructor parameters: these three are read ONLY to describe the
    // node's posture in the banner, nothing computes with them, and threading them through a
    // constructor every test already calls would buy nothing. OperationalStatus is unit-tested
    // directly, which is where the logic that matters actually lives.
    @org.springframework.beans.factory.annotation.Value("${loans.liquidation.convert.enabled:true}")
    private boolean convertEnabled = true;
    @org.springframework.beans.factory.annotation.Value("${loans.compound.enabled:false}")
    private boolean compoundEnabled;
    @org.springframework.beans.factory.annotation.Value("${scheduling.transaction-processor.enabled:false}")
    private boolean processorEnabled;
    // FAB-145: the stablecoin the book's USD estimate is priced through — and, since FAB-182, the prices
    // strip's too: the page's ONE ADA/USD rate. Display only, like the three above; blank means USD is
    // shown as unavailable, never guessed.
    @org.springframework.beans.factory.annotation.Value("${loans.ui.usd-reference-unit:}")
    private String usdReferenceUnit = "";

    public LiquidationReadinessController(ObjectProvider<LiquidationCandidateScanner> scanner,
                                          ObjectProvider<LoanService> loanService,
                                          ObjectProvider<LoanHealthService> loanHealthService,
                                          ObjectProvider<FluidOracleClient> oracleClient,
                                          ObjectProvider<MinswapPoolResolver> poolResolver,
                                          ObjectProvider<TokenMetadataService> tokenMetadata,
                                          ObjectProvider<LoansContractRegistry> registry,
                                          ObjectProvider<AppUtxoService> walletUtxos,
                                          AppConfig.LiquidationConfiguration liquidationConfiguration,
                                          AppConfig.Network network) {
        this.scanner = scanner;
        this.loanService = loanService;
        this.loanHealthService = loanHealthService;
        this.oracleClient = oracleClient;
        this.poolResolver = poolResolver;
        this.tokenMetadata = tokenMetadata;
        this.registry = registry;
        this.walletUtxos = walletUtxos;
        this.liquidationConfiguration = liquidationConfiguration;
        this.network = network;
    }

    /** Closed when the live Lending v4 config does not match this node (FAB-115). Required. */
    private com.fluidtokens.aquarium.offchain.service.LendingConfigGate lendingConfigGate;

    @org.springframework.beans.factory.annotation.Autowired
    public void setLendingConfigGate(com.fluidtokens.aquarium.offchain.service.LendingConfigGate gate) {
        this.lendingConfigGate = gate;
    }

    /**
     * FAB-132: the wallet rebalancer the page describes and can suspend or resume. Setter-injected and REQUIRED
     * in the container; null only in a direct test construction, where the page shows no rebalancing line.
     */
    private com.fluidtokens.aquarium.offchain.service.wallet.WalletRebalancer walletRebalancer;

    @org.springframework.beans.factory.annotation.Autowired(required = true)
    public void setWalletRebalancer(com.fluidtokens.aquarium.offchain.service.wallet.WalletRebalancer walletRebalancer) {
        this.walletRebalancer = walletRebalancer;
    }

    /**
     * ⛔ FAB-134 B2 / FAB-136: the wallet panel is gated on {@code !isSyncing}, exactly as the processors
     * are, because the local index is the only place the wallet is read from. Setter-injected and
     * REQUIRED in the container; null only in a direct test construction, where it reads as open.
     */
    private com.fluidtokens.aquarium.offchain.service.BlockEventListener blockEventListener;

    /**
     * FAB-108: what the boot-time reference-script check DROPPED, left UNVERIFIED or found MISSING, for the
     * page's banner. None of it refuses lending, so it is a banner and never a row override. Setter-injected
     * and REQUIRED in the container; null only in a direct test construction, where it reads as nothing.
     */
    private com.fluidtokens.aquarium.offchain.service.LoansReferenceScriptVerifier loansReferenceScriptVerifier;

    @org.springframework.beans.factory.annotation.Autowired(required = true)
    public void setLoansReferenceScriptVerifier(
            com.fluidtokens.aquarium.offchain.service.LoansReferenceScriptVerifier loansReferenceScriptVerifier) {
        this.loansReferenceScriptVerifier = loansReferenceScriptVerifier;
    }

    /**
     * FAB-128: the protocol parameters the token-principal ada floor is sized from — the per-epoch cached
     * bean the bot already uses ({@code EpochProtocolParamsSupplier}), so a render normally makes no provider
     * call. Setter-injected and REQUIRED in the container; null only in a direct test construction, where the
     * floor is computed without a fee ceiling and the note says so.
     */
    private com.bloxbean.cardano.client.api.ProtocolParamsSupplier protocolParamsSupplier;

    @org.springframework.beans.factory.annotation.Autowired(required = true)
    public void setProtocolParamsSupplier(com.bloxbean.cardano.client.api.ProtocolParamsSupplier protocolParamsSupplier) {
        this.protocolParamsSupplier = protocolParamsSupplier;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = true)
    public void setBlockEventListener(com.fluidtokens.aquarium.offchain.service.BlockEventListener blockEventListener) {
        this.blockEventListener = blockEventListener;
    }

    /**
     * GH-55: the version check's last answer, for the header's meta line. The page only READS what the daily
     * run stored and never triggers a check. Setter-injected and REQUIRED in the container; null only in a
     * direct test construction, where the header shows no version.
     */
    private com.fluidtokens.aquarium.offchain.service.NodeVersionCheck nodeVersionCheck;

    @org.springframework.beans.factory.annotation.Autowired(required = true)
    public void setNodeVersionCheck(com.fluidtokens.aquarium.offchain.service.NodeVersionCheck nodeVersionCheck) {
        this.nodeVersionCheck = nodeVersionCheck;
    }

    /**
     * FAB-174: the oracle stream's status, for one line on the page. The page only READS it; by default
     * ({@code loans.oracle.source=stream}) the bot's prices merge the stream's pushes in {@code FluidOracleClient}.
     * Setter-injected and REQUIRED in the container; null only in a direct test construction, where no line renders.
     */
    private com.fluidtokens.aquarium.offchain.service.loans.OracleStreamLauncher oracleStreamLauncher;

    @org.springframework.beans.factory.annotation.Autowired(required = true)
    public void setOracleStreamLauncher(com.fluidtokens.aquarium.offchain.service.loans.OracleStreamLauncher l) {
        this.oracleStreamLauncher = l;
    }

    /**
     * ⚠ SORT AND FILTER ARE QUERY PARAMETERS, NOT JAVASCRIPT, and the auto-refresh is why. The page
     * re-GETs its own URL every minute, so client-side state would be silently undone on each tick —
     * and it would be invisible to the template tests, which render server-side. Params survive the
     * refresh, make the view shareable as a link, and stay testable.
     */
    @GetMapping
    public String readiness(Model model,
                            @RequestParam(name = "sort", required = false) String sort,
                            @RequestParam(name = "dir", required = false) String dir,
                            @RequestParam(name = "principal", required = false) String principal,
                            @RequestParam(name = "collateral", required = false) String collateral,
                            @RequestParam(name = "page", required = false) Integer page) {
        model.addAttribute("network", network == null ? "unknown" : network.getNetwork());
        model.addAttribute("generatedAt", java.time.Instant.now().toString());
        model.addAttribute("version", nodeVersionCheck == null ? null : nodeVersionCheck.status());
        model.addAttribute("oracleStream", oracleStreamLauncher == null ? null : oracleStreamLauncher.status());
        // ⛔ Shown ABOVE everything and independent of the rows: the loan view still works while the
        // gate is closed, and a page full of healthy-looking loans must not hide that the bot will
        // refuse every one of them.
        model.addAttribute("lendingConfigBlocked", lendingConfigGate == null ? null
                : lendingConfigGate.blockedReason().orElse(null));
        // ⚠ Beside the gate's banner and for the same reason, but it says the opposite: a dropped or
        // unverified reference script costs size, not lending, so the rows keep their own verdicts.
        model.addAttribute("referenceScriptDegradations", loansReferenceScriptVerifier == null ? List.of()
                : loansReferenceScriptVerifier.degradations());
        // ⛔ ONE activity() read for the rebalancing line AND the banner's ARMED note, before the early
        // return: the two can never disagree, and both show even when the lending beans are absent.
        var rebalanceActivity = walletRebalancer == null ? null : walletRebalancer.activity();
        model.addAttribute("rebalance", rebalanceActivity == null ? null : RebalanceView.of(rebalanceActivity));
        model.addAttribute("status", OperationalStatus.of(liquidationConfiguration,
                convertEnabled, compoundEnabled, processorEnabled,
                rebalanceActivity != null && rebalanceActivity.armed()));
        model.addAttribute("sort", sort == null ? "health" : sort);
        model.addAttribute("dir", dir == null ? "asc" : dir);
        model.addAttribute("principalFilter", principal);
        model.addAttribute("collateralFilter", collateral);
        // ⚠ Built here rather than in the template so a sort link CARRIES the active filters. A sort
        // that silently cleared the filter would look like the filter had failed.
        model.addAttribute("filterQuery",
                (principal == null || principal.isBlank() ? "" : "&principal=" + principal)
                        + (collateral == null || collateral.isBlank() ? "" : "&collateral=" + collateral));

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
        // ⚠ ONE memo for the rows AND the filter dropdowns (FAB-145): every unit an option names is a
        // unit some row already resolved, so the dropdowns' labels cost no lookup of their own — and
        // cannot disagree with the label the table shows for the same asset.
        Map<String, TokenMetadata> metadataMemo = new HashMap<>();
        List<Row> all = rows(loans, scan, health, now, metadataMemo);
        WalletBalance walletNow = wallet(now);
        model.addAttribute("wallet", walletNow);
        model.addAttribute("walletNotReady", walletNotReadyReason());
        model.addAttribute("walletAgeSeconds", walletNow.ageSeconds(now));
        // ⚠ Scaled and tickered through the SAME AssetDisplay the table uses, so the balance cannot
        // render in different units from the loan figures it is meant to be compared against.
        Map<String, TokenMetadata> walletMeta = new java.util.HashMap<>();
        Map<String, AssetDisplay> walletDisplay = new java.util.LinkedHashMap<>();
        walletNow.byUnit().forEach((unit, amount) ->
                walletDisplay.put(unit, display(unit, amount, walletMeta)));
        model.addAttribute("walletDisplay", walletDisplay);
        // ⛔ FAB-182: ONE USD reference feed per render, shared by the prices strip and the book below — the
        // page shows one ADA/USD rate, never two.
        String usdUnit = usdReferenceUnit();
        Optional<OraclePriceFeed> usd = usdFeed(usdUnit, now);
        model.addAttribute("prices", prices(usd, usdUnit, metadataMemo, now));
        // ⚠ The SELECT options come from the loans actually present, never from a fixed list: an
        // option that matches nothing is a filter an operator can set and then wonder about.
        model.addAttribute("principals", all.stream().map(Row::principalUnit).distinct().sorted().toList());
        model.addAttribute("collaterals", all.stream().map(Row::collateralUnit).distinct().sorted().toList());
        // FAB-145: what each option READS — the table's label for that asset, not its policy-id hex.
        // The option's VALUE stays the unit, so ?principal=<unit> bookmarks keep working.
        Function<String, String> labelOf = unit -> display(unit, BigInteger.ZERO, metadataMemo).label();
        model.addAttribute("principalOptions",
                optionLabels(all.stream().map(Row::principalUnit).toList(), labelOf));
        model.addAttribute("collateralOptions",
                optionLabels(all.stream().map(Row::collateralUnit).toList(), labelOf));
        List<Row> arranged = arrange(all, sort, dir, principal, collateral);

        // ⛔ THE COUNTS ARE TOTALS, NEVER THE PAGE'S. "2 liquidatable" meaning "on this page" is the
        // exact class of half-truth this page exists to avoid, and the phone view reads these.
        model.addAttribute("totalRows", arranged.size());
        model.addAttribute("liquidatableCount",
                arranged.stream().filter(r -> Boolean.TRUE.equals(r.liquidatable())).count());
        model.addAttribute("blockedCount",
                arranged.stream().filter(r -> r.blocker() != null && r.blocker().blocked()).count());
        // ⛔ FAB-145: the book, from the FILTERED list and never the page, like the counts above. The USD
        // feed is resolved once per render; labels go through the same memo as the table's.
        model.addAttribute("book", summarise(arranged, (unit, amount) -> display(unit, amount, metadataMemo),
                usd, usdUnit));

        int pages = Math.max(1, (arranged.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        // ⚠ CLAMPED, not trusted. A stale ?page= from a bookmark, or one left behind when a filter
        // narrowed the list, must land on a real page rather than an empty one that reads as
        // "the filter matched nothing".
        int current = Math.min(Math.max(page == null ? 1 : page, 1), pages);
        int from = (current - 1) * PAGE_SIZE;
        model.addAttribute("page", current);
        model.addAttribute("pages", pages);
        model.addAttribute("rows", arranged.subList(from, Math.min(from + PAGE_SIZE, arranged.size())));
        return "readiness";
    }

    /**
     * ⛔ FAB-132: suspend wallet rebalancing until resumed here or the node restarts. Reads no parameter and no
     * body, renders no view, and answers {@code 303 See Other} to the GET page — derived from THIS request, so
     * the context path and {@code apiPrefix} are whatever served the POST — so a reload never re-POSTs.
     */
    @PostMapping("/wallet-rebalance/suspend")
    public ResponseEntity<Void> suspendWalletRebalance(HttpServletRequest request) {
        if (walletRebalancer != null) {
            walletRebalancer.suspend();
        }
        String uri = request.getRequestURI();
        String suffix = "/wallet-rebalance/suspend";
        String page = uri.endsWith(suffix) ? uri.substring(0, uri.length() - suffix.length()) : uri;
        return ResponseEntity.status(HttpStatus.SEE_OTHER).header(HttpHeaders.LOCATION, page).build();
    }

    /**
     * ⛔ FAB-132: lift a suspension. It can never turn on a rebalancer the environment turned off — the rebalancer
     * itself refuses — and, like suspend, it answers {@code 303 See Other} to the GET page derived from the request.
     */
    @PostMapping("/wallet-rebalance/resume")
    public ResponseEntity<Void> resumeWalletRebalance(HttpServletRequest request) {
        if (walletRebalancer != null) {
            walletRebalancer.resume();
        }
        String uri = request.getRequestURI();
        String suffix = "/wallet-rebalance/resume";
        String page = uri.endsWith(suffix) ? uri.substring(0, uri.length() - suffix.length()) : uri;
        return ResponseEntity.status(HttpStatus.SEE_OTHER).header(HttpHeaders.LOCATION, page).build();
    }

    /**
     * ⛔ Sorting and filtering, separated from rendering so it can be tested without Spring.
     *
     * <p>⚠ <b>UNKNOWN sorts LAST in both directions.</b> A loan whose health could not be computed is
     * not "healthy" and must never outrank one measurably about to go — and flipping the direction
     * must not quietly promote it to the top, which a naive reverse would do. That ordering is the
     * whole product of this page.
     */
    static List<Row> arrange(List<Row> rows, String sort, String dir, String principal, String collateral) {
        List<Row> out = new java.util.ArrayList<>(rows.stream()
                .filter(r -> principal == null || principal.isBlank() || principal.equals(r.principalUnit()))
                .filter(r -> collateral == null || collateral.isBlank() || collateral.equals(r.collateralUnit()))
                .toList());
        boolean descending = "desc".equalsIgnoreCase(dir);
        // ⚠ Age is sorted on the loan's ISO lend-date, which orders lexicographically because every
        // value is the same fixed-width UTC format. A LATER lend date is a YOUNGER loan, so ascending
        // age is descending date — inverted here rather than at the call site, where it would be a
        // silent off-by-one-direction nobody would notice on a four-row page.
        Comparator<Row> known = "age".equalsIgnoreCase(sort)
                ? Comparator.comparing(
                        (Row r) -> r.age() == null || r.age().iso() == null ? "" : r.age().iso(),
                        Comparator.reverseOrder())
                : Comparator.comparingDouble(Row::sortKey);
        if (descending) {
            known = known.reversed();
        }
        // Unknown-health rows are pinned after everything measurable, whichever way the rest is sorted.
        Comparator<Row> unknownLast = Comparator.comparing(r -> r.healthFactor() == null);
        out.sort(unknownLast.thenComparing(known));
        return out;
    }

    /**
     * ⛔ FAB-145: the filter dropdowns' TEXT, unit → label, already in display order. Only what the
     * operator reads changes; the option's value stays the unit, which is the URL contract.
     *
     * <p>Ordered by label, case-insensitively, ties by unit. ⚠ <b>No two options read the same.</b> A
     * registry can give two tokens one ticker — or a token the ticker {@code ADA} — and two identical
     * options are a filter that can quietly select the wrong asset. So every unit whose label is shared
     * is suffixed with its shortened policy id, and with its full unit if even that is shared.
     *
     * <p>Package-private and static so it can be tested without a Spring context.
     */
    static Map<String, String> optionLabels(Collection<String> units, Function<String, String> labelOf) {
        Map<String, String> labels = new HashMap<>();
        for (String unit : units) {
            labels.computeIfAbsent(unit, u -> {
                String label = "lovelace".equals(u) ? "ADA" : labelOf.apply(u);
                return label == null || label.isBlank() ? u : label;
            });
        }
        Map<String, String> text = new HashMap<>(labels);
        disambiguate(text, u -> labels.get(u) + " ("
                + ("lovelace".equals(u) ? u : TokenMetadata.unknown(u).shortPolicyId()) + ")");
        disambiguate(text, u -> labels.get(u) + " (" + u + ")");

        Map<String, String> ordered = new LinkedHashMap<>();
        labels.keySet().stream()
                .sorted(Comparator.<String, String>comparing(labels::get, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(Function.identity()))
                .forEach(u -> ordered.put(u, text.get(u)));
        return ordered;
    }

    /**
     * ⛔ FAB-145: the book — counts, collateral locked per token and its value — over {@code rows}, which the
     * caller passes FILTERED and unpaged. Package-private and static so it can be tested without Spring.
     *
     * <p>Grouped by collateral unit. A loan whose collateral could not be priced is in its token's amount
     * and loan count, and OUT of every value and total; {@link BookSummary#unpricedLoans} says how many.
     * Token lines are ordered by value descending, valueless tokens last, ties by unit.
     *
     * <p>⚠ USD is {@code totalLovelace} through the reference stablecoin's feed, and it is UNAVAILABLE — with
     * the reason, in place of the figure — rather than wrong: no reference unit configured, no usable feed, a
     * conversion that throws, or a reference display with no published decimals (an unscaled figure is not
     * dollars). None of that touches the ADA figures. Every reason names the reference by its own label
     * (FAB-182), never by a hard-coded ticker.
     */
    static BookSummary summarise(List<Row> rows, BiFunction<String, BigInteger, AssetDisplay> display,
                                 Optional<OraclePriceFeed> usdFeed, String usdUnit) {
        Map<String, List<Row>> byUnit = rows.stream()
                .collect(Collectors.groupingBy(Row::collateralUnit, LinkedHashMap::new, Collectors.toList()));
        List<TokenTvl> tokens = new ArrayList<>();
        BigInteger total = BigInteger.ZERO;
        long unpriced = 0;
        for (Map.Entry<String, List<Row>> entry : byUnit.entrySet()) {
            BigInteger amount = BigInteger.ZERO;
            BigInteger value = null;
            long tokenUnpriced = 0;
            for (Row r : entry.getValue()) {
                amount = amount.add(r.collateralAmount() == null ? BigInteger.ZERO : r.collateralAmount());
                if (r.collateralValueLovelace() == null) {
                    tokenUnpriced++;
                } else {
                    value = (value == null ? BigInteger.ZERO : value).add(r.collateralValueLovelace());
                }
            }
            tokens.add(new TokenTvl(entry.getKey(), entry.getValue().size(), display.apply(entry.getKey(), amount),
                    value, value == null ? null : display.apply("lovelace", value), tokenUnpriced));
            total = value == null ? total : total.add(value);
            unpriced += tokenUnpriced;
        }
        tokens.sort(Comparator.comparing(TokenTvl::valueLovelace,
                        Comparator.nullsLast(Comparator.<BigInteger>reverseOrder()))
                .thenComparing(TokenTvl::unit));
        // Loans, and not one of them priced: the total is unknown, not zero.
        BigInteger totalLovelace = !rows.isEmpty() && unpriced == rows.size() ? null : total;

        AssetDisplay usd = null;
        AssetDisplay rate = null;
        String usdUnavailable;
        String reference = usdUnit == null || usdUnit.isBlank() ? null : referenceLabel(display, usdUnit);
        if (reference == null) {
            usdUnavailable = USD_TURNED_OFF;
        } else if (usdFeed.isEmpty()) {
            usdUnavailable = noUsdFeed(reference);
        } else if (totalLovelace == null) {
            usdUnavailable = "no loan's collateral could be priced, so there is nothing to convert";
        } else {
            usdUnavailable = null;
            try {
                usd = display.apply(usdUnit,
                        LoanFinance.fromLovelace(Rational.fromInt(totalLovelace), usdFeed.get()).floor());
                rate = display.apply(usdUnit,
                        LoanFinance.fromLovelace(Rational.fromInt(1_000_000L), usdFeed.get()).floor());
            } catch (RuntimeException e) {
                usdUnavailable = usdNotConverted(reference, e);
            }
            if (usdUnavailable == null && (usd.metadataUnknown() || rate.metadataUnknown())) {
                usdUnavailable = usdNoDecimals(reference);
            }
            if (usdUnavailable != null) {
                usd = null;
                rate = null;
            }
        }

        return new BookSummary(rows.size(),
                rows.stream().filter(r -> Boolean.TRUE.equals(r.liquidatable())).count(),
                rows.stream().filter(r -> r.blocker() != null && r.blocker().blocked()).count(),
                List.copyOf(tokens), totalLovelace, unpriced,
                totalLovelace == null ? null : display.apply("lovelace", totalLovelace),
                usd, rate, usdUnavailable);
    }

    /**
     * ⛔ The configured reference unit, trimmed ONCE, here, where it enters the controller — the book, the
     * strip and the feed lookup all take it from this, so a padded {@code LOANS_UI_USD_REFERENCE_UNIT} cannot
     * give the book one unit and the strip another (FAB-182 r2). Blank stays blank: USD turned off.
     */
    private String usdReferenceUnit() {
        return usdReferenceUnit == null ? "" : usdReferenceUnit.trim();
    }

    /**
     * The reference stablecoin's feed — the page's one ADA/USD rate, for the book AND the prices strip —
     * loan-free at the highest usable version; empty when no reference unit is configured, no oracle client
     * is present, or the unit cannot be read. Never throws: a USD failure must not take the page down.
     */
    private Optional<OraclePriceFeed> usdFeed(String usdUnit, long now) {
        FluidOracleClient client = oracleClient.getIfAvailable();
        if (client == null || usdUnit == null || usdUnit.isBlank()) {
            return Optional.empty();
        }
        try {
            return client.findFeed(AssetType.fromUnit(usdUnit), now);
        } catch (RuntimeException e) {
            log.debug("the USD reference unit {} could not be priced: {}", usdUnit, e.toString());
            return Optional.empty();
        }
    }

    // ---- FAB-182: the USD wording the book and the strip share, naming the reference by its own label ----

    static final String USD_TURNED_OFF =
            "turned off: no USD reference unit is set (LOANS_UI_USD_REFERENCE_UNIT is empty, or unset on preview)";

    private static String noUsdFeed(String reference) {
        return "no usable " + reference + " oracle price right now";
    }

    private static String usdNotConverted(String reference, RuntimeException e) {
        return "the " + reference + " price could not be converted (" + e.getClass().getSimpleName() + ")";
    }

    private static String usdNoDecimals(String reference) {
        return reference + " has no published decimals here, so a dollar figure cannot be scaled";
    }

    /**
     * What the page calls the reference stablecoin: its label from the SAME metadata lookup every amount
     * uses (USDCx, USDM, …), never a hard-coded ticker — so the words follow whatever unit is configured.
     * Falls back to the unit itself rather than failing the page.
     */
    private static String referenceLabel(BiFunction<String, BigInteger, AssetDisplay> display, String usdUnit) {
        try {
            String label = display.apply(usdUnit, BigInteger.ZERO).label();
            return label == null || label.isBlank() ? usdUnit : label;
        } catch (RuntimeException e) {
            return usdUnit;
        }
    }

    /**
     * The strip for this render, or null when there is no oracle client or it could not be built. {@code usd}
     * and {@code usdUnit} are the book's own — the page's one rate.
     */
    private PriceStrip prices(Optional<OraclePriceFeed> usd, String usdUnit, Map<String, TokenMetadata> metadataMemo,
                              long now) {
        FluidOracleClient client = oracleClient.getIfAvailable();
        if (client == null) {
            return null;
        }
        try {
            return priceStrip(client, unit -> metadata(unit, metadataMemo), usd, usdUnit, now);
        } catch (RuntimeException e) {
            // ⚠ DEBUG: the strip is a convenience, and its absence is visible on the page itself.
            log.debug("the readiness prices strip could not be built: {}", e.toString());
            return null;
        }
    }

    /**
     * ⛔ FAB-182: the prices strip, from the SAME oracle client the bot prices with and its loan-free
     * selection — no second source. Package-private and static so it can be tested without Spring.
     *
     * <ul>
     *   <li>One entry per distinct {@code entry.token()} in {@link FluidOracleClient#entries()}: the feed
     *       {@link FluidOracleClient#findFeed} picks (usable now, highest version), else the one
     *       {@link FluidOracleClient#findEntry} holds, flagged {@code stale}.</li>
     *   <li>ADA per WHOLE token = lovelace per base unit × 10^decimals ÷ 10^6. With no published decimals the
     *       figure stays per base unit and is marked — a scale nobody published is never guessed.</li>
     *   <li>USD = ADA × USD per ADA, where USD per ADA = 1 ÷ the ADA price of one whole reference token, through
     *       {@code usdFeed} — the book's feed, so the page has one rate. Unavailable ⇒ every USD cell is
     *       {@code —} and the reason is stated once, in the book's words.</li>
     *   <li>Exact rational arithmetic throughout; only the rendered text is rounded, to 4 significant digits.</li>
     * </ul>
     *
     * <p>A token that cannot be priced is left out and logged at DEBUG; the strip is then partial, never wrong.
     *
     * @return null when {@code client} is null (lending off: no strip)
     */
    static PriceStrip priceStrip(FluidOracleClient client, Function<String, TokenMetadata> metadataOf,
                                 Optional<OraclePriceFeed> usdFeed, String usdUnit, long now) {
        if (client == null) {
            return null;
        }
        // ---- USD per ADA: one rate, or a reason ---------------------------------------------------------
        Rational usdPerAda = null;
        String usdUnavailable;
        String reference = usdUnit == null || usdUnit.isBlank() ? null
                : referenceLabel((unit, amount) -> AssetDisplay.of(amount, metadataOf.apply(unit)), usdUnit);
        if (reference == null) {
            usdUnavailable = USD_TURNED_OFF;
        } else if (usdFeed.isEmpty()) {
            usdUnavailable = noUsdFeed(reference);
        } else {
            TokenMetadata referenceMeta = metadataOf.apply(usdUnit);
            if (!referenceMeta.hasDecimals()) {
                usdUnavailable = usdNoDecimals(reference);
            } else {
                try {
                    usdPerAda = adaPerWholeToken(usdFeed.get(), referenceMeta.decimals()).reciprocal()
                            .orElseThrow(() -> new ArithmeticException("oracle price is zero"));
                    usdUnavailable = null;
                } catch (RuntimeException e) {
                    usdUnavailable = usdNotConverted(reference, e);
                }
            }
        }

        // ---- one row per distinct token, labelled and ordered like the filter dropdowns -------------------
        Map<AssetType, OraclePriceFeed> feeds = new LinkedHashMap<>();
        Map<AssetType, Boolean> staleness = new HashMap<>();
        for (OracleEntry entry : client.entries()) {
            AssetType token = entry.token();
            if (token == null || token.isAda() || feeds.containsKey(token)) {
                continue;
            }
            Optional<OraclePriceFeed> usable = client.findFeed(token, now);
            Optional<OraclePriceFeed> held = usable.isPresent() ? usable
                    : client.findEntry(token).map(OracleEntry::feed);
            if (held.isPresent()) {
                feeds.put(token, held.get());
                staleness.put(token, usable.isEmpty());
            }
        }
        Map<String, AssetType> byUnit = new LinkedHashMap<>();
        feeds.keySet().forEach(token -> byUnit.put(token.toUnit(), token));
        List<String> units = new ArrayList<>(byUnit.keySet());
        units.add("lovelace");
        Map<String, String> labels = optionLabels(units, unit -> metadataOf.apply(unit).displayLabel());

        List<TokenPrice> entries = new ArrayList<>();
        entries.add(new TokenPrice("lovelace", labels.get("lovelace"), "", null, usd(usdPerAda),
                false, false, null, null));
        for (Map.Entry<String, String> labelled : labels.entrySet()) {
            AssetType token = byUnit.get(labelled.getKey());
            if (token == null) {
                continue;
            }
            OraclePriceFeed feed = feeds.get(token);
            try {
                TokenMetadata meta = metadataOf.apply(labelled.getKey());
                // ⛔ No published decimals: per BASE UNIT, marked — never scaled by a guess.
                Rational ada = adaPerWholeToken(feed, meta.hasDecimals() ? meta.decimals() : 0);
                entries.add(new TokenPrice(labelled.getKey(), labelled.getValue(), meta.policyId(),
                        significant(ada), usd(usdPerAda == null ? null : ada.mul(usdPerAda)),
                        !meta.hasDecimals(), staleness.get(token), feed.validFrom(),
                        age(now - feed.validFrom())));
            } catch (RuntimeException e) {
                log.debug("the prices strip left out {}: {}", labelled.getKey(), e.toString());
            }
        }
        return new PriceStrip(List.copyOf(entries), reference, usdUnavailable, now);
    }

    /** ADA per {@code 10^decimals} base units: lovelace per base unit × 10^decimals ÷ 1,000,000. */
    private static Rational adaPerWholeToken(OraclePriceFeed feed, int decimals) {
        return feed.price().mul(Rational.required(BigInteger.TEN.pow(decimals), BigInteger.valueOf(1_000_000L)));
    }

    /** {@code $…} at 4 significant digits, or {@code —} when there is no USD rate. */
    private static String usd(Rational value) {
        return value == null ? "—" : "$" + significant(value);
    }

    /** ⚠ 4 significant digits, so a tiny price (HOSKY) stays readable rather than rounding to zero. */
    static String significant(Rational value) {
        return new java.math.BigDecimal(value.numerator())
                .divide(new java.math.BigDecimal(value.denominator()),
                        new java.math.MathContext(4, java.math.RoundingMode.HALF_UP))
                .stripTrailingZeros().toPlainString();
    }

    /**
     * A feed's age as {@code XmYs}, or {@code XhYm} from one hour — the format the page's script also writes.
     * A {@code validFrom} after the render instant reads as zero rather than negative.
     */
    static String age(long millis) {
        long seconds = Math.max(0L, millis) / 1000L;
        return seconds < 3600L
                ? seconds / 60L + "m" + seconds % 60L + "s"
                : seconds / 3600L + "h" + (seconds % 3600L) / 60L + "m";
    }

    /** Re-labels with {@code longer} every unit whose current text another unit shares. */
    private static void disambiguate(Map<String, String> text, Function<String, String> longer) {
        Map<String, Long> uses = text.values().stream()
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        text.replaceAll((unit, current) -> uses.get(current) > 1 ? longer.apply(unit) : current);
    }

    /**
     * The wallet, at most once per {@link #WALLET_TTL_MILLIS}, and only once the node has caught up.
     *
     * <h2>⛔ Gated exactly like the processors</h2>
     * The read is the local index (FAB-134 B2), and the gate is {@code !isSyncing} only (FAB-136). While
     * the node is syncing a balance read from it may be PARTIAL, and a partial balance understates
     * silently ({@code officina:yaci-store-index-scoping} §5) — so while syncing the page shows NO
     * balance ({@link WalletBalance#unknown()}) and {@link #walletNotReadyReason()} says why. The
     * database is not even queried.
     *
     * <h2>An empty answer is authoritative</h2>
     * With the gate open, an empty result is an empty wallet and is shown as one — it is no longer
     * treated as "we learned nothing" and papered over with a previous balance. A read that THROWS
     * (the database is unreachable) still leaves the previous value standing: that is a failed read,
     * not an answer, and a transient database blip should not grey every affordability mark.
     */
    WalletBalance wallet(long now) {
        if (walletNotReadyReason() != null) {
            return WalletBalance.unknown();
        }
        WalletBalance cached = cachedWallet;
        if (cached.known() && now - cached.asOfMillis() < WALLET_TTL_MILLIS) {
            return cached;
        }
        AppUtxoService service = walletUtxos.getIfAvailable();
        if (service == null) {
            return cached;
        }
        try {
            WalletBalance fresh = WalletBalance.of(service.listWalletUtxo(), now);
            cachedWallet = fresh;
            return fresh;
        } catch (RuntimeException e) {
            log.warn("the wallet balance could not be read for the readiness page: {}", e.toString());
            return cached;
        }
    }

    /**
     * Why the wallet cannot be shown yet, or {@code null} when it can. Null collaborators mean a
     * direct test construction and read as open, like the processors' gates.
     */
    String walletNotReadyReason() {
        if (blockEventListener != null && blockEventListener.getIsSyncing().get()) {
            return "wallet not ready: the node is still syncing";
        }
        return null;
    }

    private List<Row> rows(LoanService loans, LiquidationCandidateScanner scan,
                           LoanHealthService healthService, long now, Map<String, TokenMetadata> metadataMemo) {
        LiquidationCandidateScanner.Scan result = scan.scan(now);

        Map<String, LiquidationAssessment> byLoanId = result.assessments().stream()
                .collect(Collectors.toMap(a -> a.bond().loanId(), Function.identity(),
                        (first, duplicate) -> first));

        MarketGate gate = new MarketGate(liquidationConfiguration);
        // ⛔ ONE render, ONE index snapshot, ONE verdict per distinct pair. See resolvePool: the memo and
        // the snapshot live exactly as long as this request and are never shared between renders, so
        // nothing they hold can go stale.
        Map<String, PoolFetch> poolMemo = new HashMap<>();
        Supplier<MinswapPoolResolver.Snapshot> poolSnapshot = poolSnapshotForOneRender();
        // Same reasoning as the pool memo one level down: an asset's ticker and scale are a property
        // of the ASSET, not of the row, and a table is mostly two or three distinct assets. The memo is
        // the caller's (FAB-145), so the filter dropdowns read the same lookups.
        List<Row> rows = new ArrayList<>();
        // ⚠ ONCE per render, not once per row: it is a database read behind a TTL, and twenty loans
        // asking the same question twenty times would defeat the cache on the first miss.
        WalletBalance wallet = wallet(now);
        // FAB-128: the fee ceiling a token-principal funding UTxO must carry beside the token, ONCE per
        // render like the wallet. Empty when it cannot be had — the floor then leaves it out, and the
        // note says so — never a reason for the page to fail.
        Optional<BigInteger> feeCeiling = feeCeiling();
        for (Loan loan : result.loanCensus().loans()) {
            LiquidationAssessment assessment = byLoanId.get(loan.loanId());
            rows.add(row(loan, assessment, healthService.health(loan, now), gate, poolMemo, poolSnapshot,
                    metadataMemo, wallet, feeCeiling, now));
        }
        rows.sort(Comparator.comparingDouble(Row::sortKey));
        return rows;
    }

    /** {@code maxPossibleFee} of the wired parameters, or empty when there are none or they are unusable. */
    private Optional<BigInteger> feeCeiling() {
        if (protocolParamsSupplier == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(LedgerCeilings.maxPossibleFee(protocolParamsSupplier.getProtocolParams()));
        } catch (Exception e) {
            log.debug("no fee ceiling for the readiness page's token floor: {}", e.toString());
            return Optional.empty();
        }
    }

    /**
     * What the blocker compares an advance against, and what it says when that falls short.
     *
     * @param balance the principal the bot would count
     * @param label   the badge instead of {@code funds}, or null for the generic text
     * @param detail  the tooltip that goes with {@code label}, or null
     */
    record FundsCheck(BigInteger balance, String label, String detail) { }

    /**
     * ⛔ FAB-128: <b>the page counts a token principal the way the bot does</b>, by the bot's own helpers —
     * never a re-derivation here. The bot shows its market gate only the token in UTxOs with no datum or
     * reference script that carry at least the fee ceiling plus the min-ada rider
     * ({@link WalletInputSelection#tokenHolding}); counting the wallet's TOTAL marked nothing while the bot
     * refused every cycle (measured: 15,000 NIGHT in a 1.18-ADA UTxO against an 11,033.9 NIGHT advance).
     *
     * <p>When the spendable count is short and token sits behind the floor, the note is the bot's own
     * ({@link WalletInputSelection.TokenHolding#adaFloorNote}); the label is {@code consolidate} when
     * consolidating alone would cover the advance, else {@code funds}.
     *
     * <p>An ADA principal and an unknown wallet are today's behaviour exactly: the wallet's total for the unit,
     * and no override.
     *
     * @param amount renders a quantity of the principal, unit included — the page's scaled amounts
     */
    static FundsCheck fundsCheck(WalletBalance wallet, AssetType principal, Optional<BigInteger> feeCeiling,
                                 BigInteger advance, Function<BigInteger, String> amount) {
        if (principal.isAda() || !wallet.known()) {
            return new FundsCheck(wallet.of(principal.toUnit()), null, null);
        }
        WalletInputSelection.TokenHolding holding = WalletInputSelection.tokenHolding(wallet.utxos(), principal,
                WalletInputSelection.tokenPrincipalRequiredAda(feeCeiling));
        if (advance == null) {
            return new FundsCheck(holding.spendable(), null, null);
        }
        String note = holding.adaFloorNote(amount, advance, feeCeiling.isPresent());
        if (note == null) {
            return new FundsCheck(holding.spendable(), null, null);
        }
        String label = holding.spendable().add(holding.belowAdaFloor()).compareTo(advance) >= 0
                ? "consolidate" : "funds";
        return new FundsCheck(holding.spendable(), label, note);
    }

    /**
     * ⛔ FAB-128: how the row renders the amounts in {@link #fundsCheck}'s note — <b>exact</b>, never the
     * page's rounded text. The note is a remedy, and a remedy is a claim that must be sufficient: rounded,
     * a 24,999.4 shortfall reads "add at least 24,999", and one base unit short reads "11,034 would be
     * short of the 11,034 required". {@link AssetDisplay#exactText} is the same scaled figure at full
     * precision.
     *
     * @param display the page's display of a quantity of the principal
     */
    static Function<BigInteger, String> noteAmounts(Function<BigInteger, AssetDisplay> display) {
        return q -> {
            AssetDisplay shown = display.apply(q);
            return shown.exactText() + " " + shown.label();
        };
    }

    private Row row(Loan loan, LiquidationAssessment assessment, LoanHealth health,
                    MarketGate gate, Map<String, PoolFetch> poolMemo,
                    Supplier<MinswapPoolResolver.Snapshot> poolSnapshot,
                    Map<String, TokenMetadata> metadataMemo, WalletBalance wallet,
                    Optional<BigInteger> feeCeiling, long now) {
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

        // ---- the collateral's price: ONE lookup, shared by the fee slice and the book (FAB-145) -----
        var client = oracleClient.getIfAvailable();
        // ⛔ FAB-111: the collateral oracle THIS loan names, not the token's preferred version. Ada
        // collateral gets the 1:1 unit feed from the client, so it is valued at its own amount.
        Optional<OraclePriceFeed> feed = client == null ? Optional.empty()
                : client.findFeedForLeg(collateralAsset, datum.collateral().oracleTokenAsset(), now);
        // Null when it cannot be priced: the book excludes the loan from its totals and says so.
        BigInteger collateralValue = null;
        if (feed.isPresent()) {
            try {
                collateralValue = LoanFinance.toLovelace(Rational.fromInt(loan.collateralAmount()), feed.get())
                        .floor();
            } catch (RuntimeException e) {
                log.debug("could not value the collateral of {}: {}", loan.utxoRef(), e.toString());
            }
        }

        // ---- the fee slice: the gate's own function, never a re-derivation ------------------------
        BigInteger feeTokens = null;
        BigInteger feeValue = null;
        String feeUnknown;
        LenderBond bond = assessment == null ? null : assessment.bond();
        BondRoute bondRoute = BondRoute.of(bond);
        if (bond == null) {
            feeUnknown = "no lender bond is indexed for this loan, so its fee rate is unknown";
        } else {
            // FAB-144: the same function ranks the bot's liquidations, so this column and the order
            // the bot tries loans in can never disagree.
            feeTokens = ExpectedLiquidationReward.feeTokens(loan, bond);
            feeUnknown = null;
            if (feed.isEmpty()) {
                feeUnknown = "no usable oracle feed for the collateral, so the slice cannot be valued";
            } else {
                try {
                    feeValue = ExpectedLiquidationReward.lovelace(feeTokens, feed.get());
                } catch (RuntimeException e) {
                    feeUnknown = "the collateral feed cannot be priced (" + e.getClass().getSimpleName() + ")";
                }
            }
        }

        // ---- the route, and the capital an advance would need -------------------------------------
        String route;
        String routeDetail;
        BigInteger advance = null;
        // Same defaulting as `usability` below and for the same reason: the branches that never reach
        // a pool must still say WHY, rather than leave a cell that reads as zero.
        AnticipateAndSell anticipate = AnticipateAndSell.unknown(
                "no lender bond indexed, so it is not known whether this loan may be converted at all");
        // Defaults for the branches that never reach a pool: the bond settles it before the chain does.
        PoolUsability usability = new PoolUsability(PoolUsability.Verdict.UNKNOWN,
                "the lender bond decides this loan's route before a pool is consulted");
        if (bondRoute == BondRoute.NO_BOND) {
            route = "UNKNOWN";
            routeDetail = "no lender bond indexed — the bond decides whether conversion is permitted";
            // ⚠ PAY-IN-ADVANCE REQUIRES THE BOND TO ALLOW CONVERSION (PayInAdvanceLiquidationRouter),
            // so on a bond that forbids it the hybrid is not a worse option -- it is not an option.
            // A profit figure against a route that cannot be taken is worse than no figure.
        } else if (bondRoute == BondRoute.PLAIN) {
            route = "PLAIN LIQUIDATE";
            routeDetail = "the lender bond forbids conversion, so the bot takes its fee in collateral "
                    + "and fronts nothing";
            anticipate = AnticipateAndSell.unknown("the lender bond forbids conversion, so "
                    + "pay-in-advance is not available for this loan");
        } else {
            var action = gate.actionFor(datum.principalAsset());
            // The FETCH is per pair and memoised; the VERDICT is per loan. Both come from the same
            // already-fetched pool datum, so asking the sharper question costs no extra call.
            PoolFetch fetched = resolvePool(collateralAsset, datum.principalAsset(), poolMemo, poolSnapshot);
            usability = usabilityFor(fetched, loan, bond, collateralAsset, datum.principalAsset(), now);

            if (datum.collateral().isAda()) {
                route = "NO ROUTE";
                routeDetail = "ada collateral on a convert bond — neither the convert nor the "
                        + "pay-in-advance route builds a liquidation for it, and the bond rules out "
                        + "the plain route";
            } else {
                // Exhaustive, no default: a new action does not compile until it names its route here.
                RouteChoice choice = switch (action) {
                    case CONVERT -> usability.usable()
                            ? new RouteChoice("CONVERT", "a Minswap pool is deep enough to clear this loan's "
                                    + "debt, so the bot creates a swap order and fronts no capital", false)
                            // ⛔ FAB-126: a CONVERT market whose pool verdict is not USABLE. The executor goes
                            // to the convert router and refuses on the pool, or as CONVERT_UNAVAILABLE when
                            // the node cannot convert; it NEVER falls back to pay-in-advance, which would
                            // front capital nobody authorised (LiquidationExecutor's action split). So the
                            // label is CONVERT, and fronting the principal is the operator's switch to flip,
                            // not ours. The advance is still computed: it is what a capital-fronting action
                            // would need, if they flip it.
                            : new RouteChoice("CONVERT", convertNotUsableDetail(usability), true);
                    // ⛔ The market's configuration and the pool's state are DIFFERENT reasons, and an
                    // operator acts on them differently: a setting will not change by itself, a thin pool
                    // may. So both are reported, never one standing in for the other.
                    case ANTICIPATE -> new RouteChoice("CAPITAL IN ADVANCE", "this market is configured "
                            + "action: ANTICIPATE, so the bot fronts the principal whatever the pool says", true);
                    case CONVERT_BEFORE_ANTICIPATE -> convertBeforeAnticipateRoute(convertEnabled, usability);
                };
                route = choice.route();
                routeDetail = choice.detail();
                if (choice.computesAdvance()) {
                    advance = advanceAmount(loan, bond, now);
                }
            }

            // ⛔ SHOWN ON EVERY CONVERTIBLE ROW, not only where CONVERT was refused. The operator's
            // question is "which route pays better here", and that cannot be answered by a number
            // that only appears once the other route has already failed.
            var numbers = numbersFor(loan, bond, now);
            anticipate = numbers == null
                    ? AnticipateAndSell.unknown("this loan's figures could not be priced, so the "
                            + "estimate is not known")
                    : AnticipateAndSell.estimate(collateralAsset, datum.principalAsset(),
                            numbers.collateralLenderShouldReceive(), numbers.liquidationFee(),
                            numbers.convertedLoanCollateralToPrincipalAmount(),
                            null, fetched.datums());
        }

        // ⛔ COMPUTED FROM THE EFFECTIVE STATE, never the configured one: the node mode is a ceiling,
        // the cap is per-loan, and convert can be off globally. See ActionNow.
        // ⚠ The FORWARD question, asked whatever this loan's health is today: if it crossed the
        // threshold in the next hour, could the bot handle it? A mark that only appeared once a loan
        // was already liquidatable would arrive too late to move capital.
        // FAB-128: a token principal is counted as the bot counts it, and a shortfall caused by the ada
        // floor says so — in the page's own scaled amounts.
        String principalUnit = datum.principalAsset().toUnit();
        FundsCheck funds = fundsCheck(wallet, datum.principalAsset(), feeCeiling, advance,
                noteAmounts(q -> display(principalUnit, q, metadataMemo)));
        ProcessingBlocker blocker = ProcessingBlocker.of(
                gate.effectiveMode(datum.principalAsset()), gate.actionFor(datum.principalAsset()),
                gate.marketFor(datum.principalAsset()), convertEnabled, bondRoute,
                new ProcessingBlocker.PoolUsabilityView(usability.verdict(), usability.detail()),
                advance, funds.balance(), wallet.known(), funds.label(), funds.detail());

        ActionNow actionNow = gatedAction(lendingConfigGate,
                excludedAction(honestAction(health.liquidatable(), datum.collateral().isAda(),
                                bondRoute == BondRoute.CONVERT,
                                ActionNow.forRoute(bondRoute, health.liquidatable(),
                                        gate.effectiveMode(datum.principalAsset()),
                                        gate.actionFor(datum.principalAsset()),
                                        gate.marketFor(datum.principalAsset()), convertEnabled,
                                        usability.verdict(), advance)),
                        health.liquidatable(), assessment));

        return new Row(loan.loanId(), loan.utxoRef(),
                datum.principalAsset().toUnit(), datum.principalAmount(),
                health.remainingDebt(),
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
                health.remainingDebt() == null ? null
                        : display(datum.principalAsset().toUnit(), health.remainingDebt(), metadataMemo),
                anticipate,
                anticipate.netInPrincipal() == null ? null
                        : display(datum.principalAsset().toUnit(), anticipate.netInPrincipal(), metadataMemo),
                actionNow, blocker,
                loanAssetUrl(loan.loanId()), txUrl(loan.utxoRef()),
                collateralOracleVersion(datum), collateralValue);
    }

    /**
     * FAB-117: a liquidatable ADA-collateral loan whose bond asks for CONVERSION gets
     * {@link ActionNow#adaCollateralNotLiquidated()} whatever the market's action -- {@code computed} would
     * say ADVANCE (an unknown advance skips the cap check) or CONVERT, and both of those routes refuse it.
     * A PLAIN bond keeps {@code computed}: the plain route does liquidate ada collateral.
     */
    static ActionNow honestAction(Boolean liquidatable, boolean adaCollateral, boolean convertBond,
                                  ActionNow computed) {
        return Boolean.TRUE.equals(liquidatable) && adaCollateral && convertBond
                ? ActionNow.adaCollateralNotLiquidated() : computed;
    }

    /**
     * The executor filters excluded assessments before {@code consider()}, so an excluded row can
     * never truthfully say the bot would act, whatever route its bond selects.
     */
    static ActionNow excludedAction(ActionNow computed, Boolean liquidatable,
                                    LiquidationAssessment assessment) {
        if (assessment != null && !assessment.buildable() && Boolean.TRUE.equals(liquidatable)) {
            return new ActionNow("NONE — excluded",
                    assessment.exclusion().name() + ": "
                            + (assessment.detail() == null ? "(no detail)" : assessment.detail())
                            + " — the bot never considers an excluded assessment",
                    false);
        }
        return computed;
    }

    /**
     * Why an ADA-collateral row has no pool verdict: neither the convert nor the pay-in-advance route builds
     * a liquidation for one. (The PLAIN route does -- this is about the pool, which only convert uses.)
     */
    static final String ADA_COLLATERAL_NOT_LIQUIDATED =
            "ada collateral: neither the convert nor the pay-in-advance route builds a liquidation for it (no "
                    + "collateral oracle leg), so no pool verdict is given";

    /**
     * ⛔ FAB-126 slice 2 r2: the route detail of a CONVERT market whose pool verdict is not USABLE, which
     * follows the VERDICT, not merely "not usable". The remedy each verdict earns is the one the executor
     * would actually honour (FAB-194-4 r2):
     * <ul>
     *   <li>{@code NO_POOL}, {@code TOO_THIN} and {@code NOT_CONFIGURED} earn the CONVERT_BEFORE_ANTICIPATE
     *       remedy: the executor falls back to pay-in-advance on exactly these (the router's
     *       {@code NoPoolException} — which a missing Minswap configuration also throws — and
     *       {@code POOL_TOO_THIN}), so that market would front the principal under its cap.</li>
     *   <li>{@code WRONG_PAIR} and {@code CANNOT_QUOTE} earn only the ANTICIPATE remedy: the convert route
     *       refuses them and a CONVERT_BEFORE_ANTICIPATE market would refuse them too, never falling back, so
     *       advising it would promise a capital route the bot does not take — and contradict this page's own
     *       CONVERT_BEFORE_ANTICIPATE rows.</li>
     *   <li>{@code CHECK_FAILED} and {@code UNKNOWN} earn no remedy: a failed lookup is not evidence that no
     *       pool exists — the executor may convert this loan on the next cycle — and an unpriced loan says
     *       nothing about any pool, so advising capital-fronting on either would turn "could not ask" into
     *       "no pool".</li>
     * </ul>
     *
     * <p>The switch is exhaustive so that a new verdict fails to compile here rather than falling into a
     * wrong text. {@code USABLE} cannot reach this branch (the caller routes it to the usable-pool
     * CONVERT detail first); it is given a <b>defensive text</b> rather than an exception, because a throw
     * would take down the whole readiness page for every row, and the text still claims nothing false.
     */
    static String convertNotUsableDetail(PoolUsability usability) {
        String noCapital = "; the bot fronts no capital on a CONVERT market";
        return switch (usability.verdict()) {
            case NO_POOL, TOO_THIN ->
                    "the pool cannot fill this loan's swap, so the convert route will refuse it: "
                            + usability.detail() + noCapital
                            + " — to front the principal instead, set this market to action: "
                            + "CONVERT_BEFORE_ANTICIPATE with a cap";
            case WRONG_PAIR, CANNOT_QUOTE ->
                    "the pool cannot fill this loan's swap, so the convert route will refuse it: "
                            + usability.detail() + noCapital
                            + " — to front the principal instead, set this market to action: ANTICIPATE with a cap";
            case CHECK_FAILED ->
                    "the pool check could not complete, which is NOT evidence that no pool exists; the bot "
                            + "checks again next cycle: " + usability.detail() + noCapital;
            case NOT_CONFIGURED ->
                    "this node cannot convert (no Minswap configuration for its network), so the convert "
                            + "route refuses every loan on this market: " + usability.detail() + noCapital
                            + " — configure loans.minswap.*, or set this market to action: "
                            + "CONVERT_BEFORE_ANTICIPATE with a cap";
            case UNKNOWN ->
                    "whether a pool can fill this swap is not known, because the loan's figures could not "
                            + "be priced: " + usability.detail() + noCapital;
            case USABLE ->
                    "the pool check reports a usable pool, but this row was not routed as one: "
                            + usability.detail() + noCapital;
        };
    }

    /** A convert-bond row's route, its detail, and whether the advance is computed for it. */
    record RouteChoice(String route, String detail, boolean computesAdvance) { }

    /**
     * ⛔ FAB-194-4: the route a {@code CONVERT_BEFORE_ANTICIPATE} market's token-collateral row would take,
     * read from the pool verdict this page computed from the node's own index — the executor's split, never
     * the ANTICIPATE market's fixed text. Convert switched off, or a verdict saying convert is IMPOSSIBLE
     * ({@code NO_POOL}, {@code TOO_THIN}, {@code NOT_CONFIGURED}), pays in advance; {@code USABLE} converts;
     * {@code WRONG_PAIR} and {@code CANNOT_QUOTE} are refused by the convert route and never fall back; a
     * verdict that could not be reached ({@code CHECK_FAILED}, {@code UNKNOWN}) leaves the route unknown. The
     * advance is computed wherever capital is, or might be, the question. The page tests every indexed pool
     * now while the bot resolves one pool when it builds, so each pool-driven detail ends with
     * {@link ActionNow#ESTIMATE_NOTE}.
     */
    static RouteChoice convertBeforeAnticipateRoute(boolean convertEnabled, PoolUsability usability) {
        String market = "this market is action: CONVERT_BEFORE_ANTICIPATE";
        PoolUsability.Verdict verdict = usability.verdict() == null
                ? PoolUsability.Verdict.UNKNOWN : usability.verdict();
        if (!convertEnabled) {
            return new RouteChoice("CAPITAL IN ADVANCE", market + " and convert is disabled on this node, so "
                    + "the bot pays in advance, fronting the principal under the market's cap, whatever the "
                    + "pool verdict (" + verdict + ") says", true);
        }
        return switch (verdict) {
            case USABLE -> new RouteChoice("CONVERT", market + " and a Minswap pool is deep enough to clear this "
                    + "loan's debt (" + verdict + "), so the bot converts first: it creates a swap order and "
                    + "fronts no capital" + ActionNow.ESTIMATE_NOTE, false);
            case NO_POOL, TOO_THIN, NOT_CONFIGURED -> new RouteChoice("CAPITAL IN ADVANCE", market
                    + " and convert is impossible for this loan (" + verdict + ": " + usability.detail()
                    + "), so the bot pays in advance instead, fronting the principal under the market's cap"
                    + ActionNow.ESTIMATE_NOTE, true);
            case WRONG_PAIR, CANNOT_QUOTE -> new RouteChoice("CONVERT", market + " and the convert route will "
                    + "refuse this loan (" + verdict + ": " + usability.detail() + "); that refusal is not a "
                    + "reason to pay in advance, so the bot fronts no capital — the figure shown is what an "
                    + "advance would need" + ActionNow.ESTIMATE_NOTE, true);
            case CHECK_FAILED, UNKNOWN -> new RouteChoice("UNKNOWN", market + " and whether convert is possible "
                    + "is not known (" + verdict + ": " + usability.detail() + "), so whether the bot would "
                    + "convert or pay in advance is not known either — this is not evidence that capital is "
                    + "needed" + ActionNow.ESTIMATE_NOTE, true);
        };
    }

    /**
     * ⛔ Whether the fetched pool could fill THIS loan.
     *
     * <p>Everything here is arithmetic over values already in hand — the pool datum from the memoised
     * fetch, and this loan's own figures from the oracle cache. <b>No external call.</b> If a lookup is
     * unavailable the reason survives unchanged; the page must never turn "could not ask" into
     * "no pool", because one says try again shortly and the other says hold capital from now on.
     */

    PoolUsability usabilityFor(PoolFetch fetched, Loan loan, LenderBond bond,
                                       AssetType collateral, AssetType principal, long now) {
        if (fetched.unavailable() != null) {
            return fetched.unavailable();
        }
        if (loan.datum().collateral().isAda()) {
            return new PoolUsability(PoolUsability.Verdict.UNKNOWN,
                    ADA_COLLATERAL_NOT_LIQUIDATED);
        }
        var numbers = numbersFor(loan, bond, now);
        if (numbers == null) {
            return new PoolUsability(PoolUsability.Verdict.UNKNOWN,
                    "this loan's debt and equity cannot be priced, so pool usability is not known");
        }
        // ⛔ ASK EVERY POOL, DEEPEST FIRST, AND STOP AT THE FIRST THAT CAN ACTUALLY FILL.
        //
        // The pair may have several pools and depth only orders them — output turns on price and fee
        // too. Reporting the deepest pool's verdict would answer "can the biggest pool fill this?"
        // when the operator's question is "can ANY pool fill this?", and those differ exactly when it
        // matters: a deep pool at a worse price returning less than a shallow one at a better one.
        //
        // When none can fill, the DEEPEST one's verdict is the one reported — it carries the smallest
        // shortfall, so it is the most useful statement of how far short the pair is.
        // collateralLenderShouldReceive IS collateral − equity − liquidationFee: what reaches the pool.
        return bestVerdict(fetched.datums(), collateral, principal,
                numbers.collateralLenderShouldReceive(), numbers.remainingDebt());
    }

    /**
     * ⛔ ASK EVERY POOL, DEEPEST FIRST, AND STOP AT THE FIRST THAT CAN ACTUALLY FILL.
     *
     * <p>A pair may have several pools and depth only ORDERS them — constant-product output turns on
     * price and fee as well as reserves, so a deeper pool at a worse price can return less than a
     * shallower one. Reporting the deepest pool's verdict answers "can the biggest pool fill this?"
     * when the operator asked "can ANY pool fill this?", and the two differ exactly when it matters.
     *
     * <p>When none can fill, the DEEPEST one's verdict is reported: it carries the smallest shortfall
     * and is therefore the most useful statement of how far short the pair is.
     *
     * <p>Package-private so the choice can be tested without a Spring context or a live resolver.
     */
    static PoolUsability bestVerdict(java.util.List<MinswapPoolDatum> candidates, AssetType collateral,
                                     AssetType principal, java.math.BigInteger swappable,
                                     java.math.BigInteger remainingDebt) {
        PoolUsability deepest = null;
        for (MinswapPoolDatum candidate : candidates) {
            PoolUsability verdict = PoolUsability.assess(collateral, principal, swappable,
                    remainingDebt, candidate);
            if (verdict.usable()) {
                return verdict;
            }
            if (deepest == null) {
                deepest = verdict;
            }
        }
        return deepest;
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
        return AssetDisplay.of(amount, metadata(unit, memo));
    }

    /** The metadata {@link #display} renders with — one lookup per unit per render, through the memo. */
    private TokenMetadata metadata(String unit, Map<String, TokenMetadata> memo) {
        return memo.computeIfAbsent(unit, u -> {
            TokenMetadataService service = tokenMetadata.getIfAvailable();
            if (service != null) {
                return service.lookup(u);
            }
            return "lovelace".equals(u) ? TokenMetadata.ada() : TokenMetadata.unknown(u);
        });
    }

    /**
     * One pair's pool as fetched. Exactly one field is non-null: the datum when a pool was found, or
     * the reason it could not be. The FETCH is per pair and memoised; the VERDICT is per loan and is
     * computed from this by {@link PoolUsability#assess}.
     */
    /**
     * ⛔ EVERY pool for the pair, deepest first — not just the deepest.
     *
     * <p>Depth orders the candidates; it does not decide the answer. Constant-product output depends
     * on a pool's PRICE and its FEE as well as its reserves, so a deeper pool at a worse price can
     * return less than a shallower one. Keeping only the deepest would report a pair as TOO_THIN
     * while a pool that would have cleared the debt sat unexamined.
     */
    record PoolFetch(java.util.List<MinswapPoolDatum> datums, PoolUsability unavailable) {
    }

    /**
     * ⛔ <b>The pool question is per PAIR, and the index is read once per RENDER.</b>
     *
     * <p>Pools are read from the node's own index (FAB-137): {@link MinswapPoolResolver#snapshot} is ONE
     * unpaged credential query that returns every authentic pool, and asking the snapshot about a pair
     * issues no query at all. So a render takes at most one snapshot — lazily, only when some row needs
     * a pool verdict, and never while syncing — and every pair on the page is answered from it. The
     * memo then keeps one verdict per pair, so loans sharing a pair share the answer.
     *
     * <p>Both are <b>request-scoped</b>: created in {@link #rows}, discarded when the response is. The
     * data rendered is what the page would have shown asking each row separately — <b>same request,
     * same instant</b>.
     *
     * <h2>⚠ Why this is a dedupe and deliberately NOT a TTL cache</h2>
     * The snapshot holds pool UTxOs, and <b>a pool UTxO carries reserves</b>. Reserves held across
     * renders are stale reserves on a page operators read to decide whether to front capital, and the
     * same resolver is injected into
     * {@link com.fluidtokens.aquarium.offchain.service.loans.ConvertLiquidationRouter} on the convert
     * <b>build</b> path — where stale reserves would price a real transaction. A per-request snapshot
     * cannot reach that path and cannot outlive the answer it belongs to; a TTL cache would be a
     * different and money-shaped change. <b>Do not promote the memo or the snapshot to a field.</b>
     */
    PoolFetch resolvePool(AssetType collateral, AssetType principal,
                          Map<String, PoolFetch> poolMemo, Supplier<MinswapPoolResolver.Snapshot> poolSnapshot) {
        return poolMemo.computeIfAbsent(pairKey(collateral, principal),
                key -> lookupPool(collateral, principal, poolSnapshot));
    }

    /**
     * The render's one pool snapshot, taken on first use and then reused — including a FAILED read,
     * which is rethrown rather than retried, so a database that cannot answer costs one query per
     * render too. Call it once per request; the supplier it returns must never be kept in a field.
     */
    Supplier<MinswapPoolResolver.Snapshot> poolSnapshotForOneRender() {
        return new Supplier<>() {
            private boolean taken;
            private MinswapPoolResolver.Snapshot snapshot;
            private RuntimeException failure;

            @Override
            public MinswapPoolResolver.Snapshot get() {
                if (!taken) {
                    taken = true;
                    try {
                        snapshot = poolResolver.getObject().snapshot();
                    } catch (RuntimeException e) {
                        failure = e;
                    }
                }
                if (failure != null) {
                    throw failure;
                }
                return snapshot;
            }
        };
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

    /**
     * One pair's pool verdict, read from the render's snapshot — a local index read, not a provider
     * round trip.
     *
     * <p>⛔ <b>While syncing the index is partial</b>, and a partial index reads "no pool" for a pool it
     * has not reached yet ({@code officina:yaci-store-index-scoping} §5) — the answer that tells an
     * operator to front capital. So while syncing the verdict is CHECK_FAILED and the snapshot is never
     * taken: the same {@code !isSyncing} gate as the wallet panel.
     */
    // Package-private so the dedupe can be proven against a counting index without a Spring context.
    PoolFetch lookupPool(AssetType collateral, AssetType principal,
                         Supplier<MinswapPoolResolver.Snapshot> poolSnapshot) {
        if (poolResolver.getIfAvailable() == null) {
            return new PoolFetch(java.util.List.of(), PoolUsability.notConfigured());
        }
        if (blockEventListener != null && blockEventListener.getIsSyncing().get()) {
            return new PoolFetch(java.util.List.of(), PoolUsability.checkFailed("the node is still syncing"));
        }
        try {
            return poolSnapshot.get().resolveEitherOrder(collateral, principal)
                    .map(pool -> new PoolFetch(java.util.List.of(pool.datum()), null))
                    .orElseGet(() -> new PoolFetch(java.util.List.of(), PoolUsability.noPool()));
        } catch (RuntimeException e) {
            // ⛔ A failed lookup is NOT "no pool exists". One says hold capital for this loan from now
            // on; the other says try again shortly. Collapsing them was the defect this now avoids.
            // A RefusedException is either the index unreadable (LOOKUP_FAILED) or two authentic pools
            // for one pair (AMBIGUOUS_POOL, an index-integrity fault naming both out-refs) — its
            // message is the verdict's detail, so the page names which.
            // ⛔ WARN, NOT DEBUG. This was log.debug, so on a node running at INFO the page told the
            // operator the lookup "did not complete ... worth re-checking" and the logs held NOTHING
            // to re-check. A UI that reports a fault must not be the only place the fault exists.
            log.warn("pool lookup failed for {}/{}: {} — the readiness page shows CHECK FAILED for "
                            + "every loan on this pair until it succeeds",
                    collateral.toUnit(), principal.toUnit(), e.toString(), e);
            String why = e instanceof MinswapPoolResolver.RefusedException ? e.getMessage()
                    : e.getClass().getSimpleName();
            return new PoolFetch(java.util.List.of(), PoolUsability.checkFailed(why));
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

    /** FAB-112: the version of the collateral oracle this loan's datum names, if the registry says. */
    Integer collateralOracleVersion(com.fluidtokens.aquarium.offchain.model.loans.LoanDatum datum) {
        FluidOracleClient client = oracleClient.getIfAvailable();
        if (client == null || datum.collateral().isAda()) {
            return null;
        }
        return client.findEntryByOracleToken(datum.collateral().oracleTokenAsset())
                .filter(e -> e.token().equals(datum.collateral().assetType()))
                .map(OracleEntry::oracleVersion).orElse(null);
    }

    /**
     * ⛔ FAB-115: a closed lending gate overrides whatever a loan's health and market say — the bot
     * refuses every lending transaction, so no row may claim it "would act".
     */
    static ActionNow gatedAction(com.fluidtokens.aquarium.offchain.service.LendingConfigGate lendingGate,
                                 ActionNow otherwise) {
        return lendingGate != null && lendingGate.isBlocked()
                ? ActionNow.refusedByLendingConfig(lendingGate.blockedReason().orElse(""))
                : otherwise;
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
        // ⛔ FAB-111: by the oracle NFT the datum names, exactly as the builder resolves it. This was a
        // token lookup, tolerated as a "controller-only simplification" while every token had one
        // oracle; since 2026-09-30 a token can have two (v1 Lending v3, v2 Lending v4), and these
        // figures must be the ones the transaction for THIS loan would be built from.
        // ⚠ ADA collateral gets NO figures, deliberately: its datum names the NONE sentinel, which has no
        // registry entry, so this returns null. Do not synthesise a 1:1 entry here — these figures feed
        // only the convert and pay-in-advance routes, and neither builds an ada-collateral loan (both
        // refuse it by name, FAB-117), so figures would become a pool verdict and a "would act" the bot
        // cannot honour. (The PLAIN route does liquidate ada collateral, and needs none of these figures.) usabilityFor says why instead (oracle re-slice, round-2 audit finding 1).
        Optional<OracleEntry> oracle = client.findEntryByOracleToken(loan.datum().collateral().oracleTokenAsset())
                .filter(e -> e.token().equals(loan.datum().collateral().assetType()));
        if (oracle.isEmpty()) {
            return null;
        }
        AssetType principalAsset = loan.datum().principalAsset();
        OracleEntry principalOracle = null;
        if (!principalAsset.isAda()) {
            Optional<OracleEntry> principalEntry = client.findEntryByOracleToken(loan.datum().principalOracleAsset())
                    .filter(e -> e.token().equals(principalAsset));
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
