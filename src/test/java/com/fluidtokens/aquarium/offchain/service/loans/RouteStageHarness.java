package com.fluidtokens.aquarium.offchain.service.loans;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.Loan;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.model.loans.RepaymentMode;
import com.fluidtokens.aquarium.offchain.service.AppUtxoService;
import com.fluidtokens.aquarium.offchain.service.BlockEventListener;
import com.fluidtokens.aquarium.offchain.service.StageTally;
import com.fluidtokens.aquarium.offchain.service.SubmitFence;
import com.fluidtokens.aquarium.offchain.service.WorkResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * FAB-194-1 — ONE {@code cycle(now, fence, tally)} of a REAL {@link LiquidationExecutor}, and everything it
 * left behind: its answer, the decision log, the executor's log events (DEBUG and up), what reached the
 * submitter, the tally, and how often the plain builder and the convert router were asked to build.
 *
 * <p>The plain builder and the pay-in-advance router are the real ones over {@link LoanFixtures}, wired as
 * {@code LiquidationSubmitVetoTest.stage(...)} wires them; only the CONVERT router is a stand-in, because a
 * Minswap order needs a pool index this harness does not have. Both stand-ins below are built over a REAL
 * {@link ConvertEconomics}, and {@link BuildingConvertRouter} applies the executor's wallet selector exactly
 * as the real router does, so U's designation and sizing stay the executor's own.
 *
 * <p>⛔ <b>Frozen from FAB-194-1 on</b>, together with {@code MarketActionRoutePinTest}: it compiles against
 * the code BEFORE {@code CONVERT_BEFORE_ANTICIPATE} existed and names no enum value of its own, so the pins
 * it serves are proven on the old code and the new. Not a test class, and deliberately not named
 * {@code *Test}.
 */
final class RouteStageHarness {

    private RouteStageHarness() {
    }

    static final long NOW = 1_700_000_000_000L;

    private static final long LATE_LEND_DATE = NOW - 30L * 24 * 3_600_000L;

    private static final long VALID_FROM = NOW - 30_000L;

    private static final String STAKE_KEY = "33333333333333333333333333333333333333333333333333333333";

    private static final String TX_WALLET = "e0".repeat(32);
    private static final String TX_TOKEN_WALLET = "e7".repeat(32);
    private static final String TX_CONFIG = "f1".repeat(32);
    private static final String TX_LM_CONFIG = "f2".repeat(32);
    private static final String TX_REF_SCRIPTS = "cc".repeat(32);

    /** 500 per mille — a fee slice far above any plausible transaction fee. */
    private static final BigInteger FAT_FEE_PER_MILLE = BigInteger.valueOf(500);

    private static final BigInteger SMALL_MARGIN = BigInteger.valueOf(1_500_000);

    /** The bot's account. Random per JVM: anything that depends on its address is derived, never pinned. */
    static final Account ACCOUNT = new Account(LoanFixtures.NETWORK);

    static final Utxo CONFIG_UTXO = LoanFixtures.adaUtxo(TX_CONFIG, 0,
            LoanFixtures.entAddress(LoanFixtures.CONFIG_POLICY_ID), 5_000_000L);
    static final Utxo LM_CONFIG_UTXO = LoanFixtures.adaUtxo(TX_LM_CONFIG, 0,
            LoanFixtures.entAddress(LoanFixtures.LM_CONFIG_POLICY_ID), 5_000_000L);

    /** 200 ADA, ada-only: role U whenever it is the largest ada-only output beside C. */
    static final Utxo U_UTXO = LoanFixtures.adaUtxo(TX_WALLET, 0, ACCOUNT.baseAddress(), 200_000_000L);

    // Token collateral, priced by a Charli3 feed.
    static final AssetType COLLATERAL_TOKEN = new AssetType("c0".repeat(28), "544f4b");
    private static final AssetType ORACLE_TOKEN = new AssetType("b0".repeat(28), "4f52434c");
    private static final String ORACLE_CREDENTIAL = "a0".repeat(28);
    private static final String TX_ORACLE_NFT = "9a".repeat(32);
    private static final String TX_ORACLE_SCRIPT = "9b".repeat(32);
    private static final String TX_CHARLI3_PROVIDER = "9c".repeat(32);

    // A token principal, priced by a Charli3 feed of its own.
    static final AssetType PRINCIPAL_TOKEN = new AssetType("d0".repeat(28), "505249");
    private static final AssetType PRINCIPAL_ORACLE_TOKEN = new AssetType("e0".repeat(28), "504f5243");
    private static final String PRINCIPAL_ORACLE_CREDENTIAL = "a1".repeat(28);
    private static final String TX_PRINCIPAL_ORACLE_NFT = "8a".repeat(32);
    private static final String TX_PRINCIPAL_ORACLE_SCRIPT = "8b".repeat(32);
    private static final String TX_PRINCIPAL_CHARLI3_PROVIDER = "8c".repeat(32);

    /** The feeds' windows close 600 s after NOW, a real preview c3 window. */
    private static final long FEED_VALID_TO = NOW + 600_000L;

    /** A TOKEN output: ample PRI for a pay-in-advance lender payout, and ada for its floor. */
    static final Utxo TOKEN_WALLET_UTXO = LoanFixtures.utxo(TX_TOKEN_WALLET, 0, ACCOUNT.baseAddress(),
            List.of(Amount.lovelace(BigInteger.valueOf(60_000_000L)),
                    Amount.asset(PRINCIPAL_TOKEN.toUnit(), BigInteger.valueOf(100_000_000L))),
            null);

    // ======================================================================================
    // scenarios — each parameterised by an index, so two distinct loans can share a market
    // ======================================================================================

    /**
     * A loan, its bond, the assessment the scanner would have produced, and whether the resolver finds the
     * loan's UTxO unspent.
     */
    record Scenario(LoanFixtures.LoanUtxo loan, LoanFixtures.BondUtxo bond, LiquidationAssessment assessment,
                    boolean resolvable) {

        /** This scenario with its loan UTxO spent since the scan: the resolver no longer finds it. */
        Scenario unresolvable() {
            return new Scenario(loan, bond, assessment, false);
        }

        /** The loan's {@code txHash#index}. */
        String loanRef() {
            return loan.loan().utxoRef();
        }
    }

    private static String loanTx(int index) {
        return "%02x".formatted(0x40 + index).repeat(32);
    }

    private static String bondTx(int index) {
        return "%02x".formatted(0x60 + index).repeat(32);
    }

    private static String loanId(int index) {
        return "a1b2c3d4e5f6%04x".formatted(index);
    }

    /** A plain ADA-principal, ADA-collateral loan: 100 ADA against 110 ADA of debt, so equity zero. */
    static Scenario adaPrincipalPlain(int index) {
        LoanDatum datum = LoanFixtures.loanDatum(AssetType.ada(), BigInteger.valueOf(100_000_000),
                BigInteger.valueOf(1000), LoanFixtures.adaCollateral(), LATE_LEND_DATE,
                LoanFixtures.liquidation(), new RepaymentMode.PrincipalAndInterestOnInstallments(), false);
        LoanFixtures.LoanUtxo loan = LoanFixtures.loanUtxo(loanTx(index), 0, loanId(index), datum,
                100_000_000L, List.of());
        LoanFixtures.BondUtxo bond = LoanFixtures.bondUtxo(bondTx(index), 0, loanId(index),
                LoanFixtures.bondDatum(FAT_FEE_PER_MILLE, LoanFixtures.inlineKeyStakeCredential(STAKE_KEY),
                        AssetType.ada()),
                2_000_000L);
        return new Scenario(loan, bond, LoanFixtures.assess(bond.bond(), loan.loan(),
                OraclePriceFeed.unit(), OraclePriceFeed.unit(), VALID_FROM), true);
    }

    /**
     * The shape of {@code LiquidationSubmitVetoTest.tokenPrincipalPayInAdvanceScenario}: 1,000,000 TOK of
     * collateral at 50 lovelace against 100,000,000 PRI of debt at 1 lovelace, under water, and a bond that
     * converts to principal at 500 per mille. Needs {@link #oracles()}.
     */
    static Scenario tokenPrincipalConvertBond(int index) {
        LoanDatum datum = LoanFixtures.loanDatum(PRINCIPAL_TOKEN, PRINCIPAL_ORACLE_TOKEN,
                BigInteger.valueOf(100_000_000), BigInteger.valueOf(1000),
                LoanFixtures.tokenCollateral(COLLATERAL_TOKEN, ORACLE_TOKEN), LATE_LEND_DATE,
                LoanFixtures.liquidation(), new RepaymentMode.PrincipalAndInterestOnInstallments(), false);
        LoanFixtures.LoanUtxo loan = LoanFixtures.loanUtxo(loanTx(index), 0, loanId(index), datum, 2_000_000L,
                List.of(LoanFixtures.token(COLLATERAL_TOKEN, 1_000_000L)));
        LoanFixtures.BondUtxo bond = LoanFixtures.bondUtxo(bondTx(index), 0, loanId(index),
                LoanFixtures.convertToPrincipalBondDatum(FAT_FEE_PER_MILLE,
                        LoanFixtures.inlineKeyStakeCredential(STAKE_KEY), PRINCIPAL_TOKEN),
                2_000_000L);
        return new Scenario(loan, bond, LoanFixtures.assess(bond.bond(), loan.loan(),
                principalFeed(), collateralFeed(), VALID_FROM), true);
    }

    /**
     * A token-collateral, ADA-principal loan whose bond converts to principal: 1,000,000 TOK at 50 lovelace
     * (50 ADA) against a 50 ADA principal at 10%, under water. Needs {@link #oracles()}.
     */
    static Scenario adaPrincipalTokenCollateralConvertBond(int index) {
        LoanDatum datum = LoanFixtures.loanDatum(AssetType.ada(), BigInteger.valueOf(50_000_000),
                BigInteger.valueOf(1000), LoanFixtures.tokenCollateral(COLLATERAL_TOKEN, ORACLE_TOKEN),
                LATE_LEND_DATE, LoanFixtures.liquidation(), new RepaymentMode.PrincipalAndInterestOnInstallments(),
                false);
        LoanFixtures.LoanUtxo loan = LoanFixtures.loanUtxo(loanTx(index), 0, loanId(index), datum, 2_000_000L,
                List.of(LoanFixtures.token(COLLATERAL_TOKEN, 1_000_000L)));
        LoanFixtures.BondUtxo bond = LoanFixtures.bondUtxo(bondTx(index), 0, loanId(index),
                LoanFixtures.convertToPrincipalBondDatum(FAT_FEE_PER_MILLE,
                        LoanFixtures.inlineKeyStakeCredential(STAKE_KEY), AssetType.ada()),
                2_000_000L);
        return new Scenario(loan, bond, LoanFixtures.assess(bond.bond(), loan.loan(),
                OraclePriceFeed.unit(), collateralFeed(), VALID_FROM), true);
    }

    private static OraclePriceFeed principalFeed() {
        return OraclePriceFeed.priceDataCharlie(PRINCIPAL_TOKEN, BigInteger.ONE, BigInteger.ONE,
                NOW - 60_000L, FEED_VALID_TO);
    }

    private static OraclePriceFeed collateralFeed() {
        return OraclePriceFeed.priceDataCharlie(COLLATERAL_TOKEN, BigInteger.valueOf(50), BigInteger.ONE,
                NOW - 60_000L, FEED_VALID_TO);
    }

    private static OracleEntry principalOracle() {
        return LoanFixtures.charli3(PRINCIPAL_TOKEN, PRINCIPAL_ORACLE_TOKEN, PRINCIPAL_ORACLE_CREDENTIAL,
                principalFeed(), LoanFixtures.input(TX_PRINCIPAL_ORACLE_NFT, 0),
                LoanFixtures.input(TX_PRINCIPAL_ORACLE_SCRIPT, 0),
                LoanFixtures.input(TX_PRINCIPAL_CHARLI3_PROVIDER, 0));
    }

    private static OracleEntry collateralOracle() {
        return LoanFixtures.charli3(COLLATERAL_TOKEN, ORACLE_TOKEN, ORACLE_CREDENTIAL, collateralFeed(),
                LoanFixtures.input(TX_ORACLE_NFT, 0), LoanFixtures.input(TX_ORACLE_SCRIPT, 0),
                LoanFixtures.input(TX_CHARLI3_PROVIDER, 0));
    }

    /** Both feeds, answerable by token and by oracle NFT, so a pay-in-advance can be priced to a verdict. */
    static FluidOracleClient oracles() {
        return new LegPricingOracleClient(List.of(principalOracle(), collateralOracle()));
    }

    /** {@link FluidOracleClient} over a fixed list, answering the two leg lookups pay-in-advance prices by. */
    static final class LegPricingOracleClient extends FluidOracleClient {

        private final List<OracleEntry> entries;

        LegPricingOracleClient(List<OracleEntry> entries) {
            super("http://unused.invalid");
            this.entries = entries;
        }

        @Override
        public Collection<OracleEntry> entries() {
            return entries;
        }

        @Override
        public Optional<OracleEntry> findEntry(AssetType token) {
            return entries.stream().filter(e -> e.token().equals(token)).findFirst();
        }

        @Override
        public Optional<OracleEntry> findEntryByOracleToken(AssetType oracleToken) {
            return entries.stream().filter(e -> e.oracleToken().equals(oracleToken)).findFirst();
        }
    }

    // ======================================================================================
    // wallet and configuration
    // ======================================================================================

    /** {@code others}, in order, then role C sized from the fixture parameters the executor reads. */
    static List<Utxo> wallet(Utxo... others) {
        return LoanFixtures.shapedWallet(LoanFixtures.protocolParams(), ACCOUNT.baseAddress(), others);
    }

    /** A node at {@code mode} with the plain AND pay-in-advance reference scripts published. */
    static AppConfig.LiquidationConfiguration node(AppConfig.LiquidationConfiguration.Mode mode,
                                                   AppConfig.LiquidationConfiguration.Market... markets) {
        var configuration = new AppConfig.LiquidationConfiguration(mode, 60, 120, 30, SMALL_MARGIN, 200,
                new LiquidateTransactionBuilder.ReferenceScripts(
                        new TransactionInput(TX_REF_SCRIPTS, 0),
                        new TransactionInput(TX_REF_SCRIPTS, 1),
                        new TransactionInput(TX_REF_SCRIPTS, 2),
                        new TransactionInput(TX_REF_SCRIPTS, 3),
                        new TransactionInput(TX_REF_SCRIPTS, 4),
                        new TransactionInput(TX_REF_SCRIPTS, 5),
                        null,
                        new TransactionInput(TX_REF_SCRIPTS, 6),
                        null));
        configuration.setMarkets(List.of(markets));
        return configuration;
    }

    /** One market entry; a null {@code mode} inherits the node's, a null {@code cap} states none. */
    static AppConfig.LiquidationConfiguration.Market market(AssetType principal,
                                                            AppConfig.LiquidationConfiguration.Mode mode,
                                                            AppConfig.LiquidationConfiguration.Action action,
                                                            Long cap) {
        var market = new AppConfig.LiquidationConfiguration.Market();
        market.setUnit(principal.toUnit());
        market.setMode(mode);
        market.setAction(action);
        market.setCap(cap == null ? null : BigInteger.valueOf(cap));
        return market;
    }

    // ======================================================================================
    // the convert-router stand-ins
    // ======================================================================================

    /**
     * A REAL {@link ConvertEconomics} with conversion enabled. The stand-ins below hand it to their super
     * constructor rather than a null, so a later slice that asks the router whether conversion is enabled
     * finds the real answer instead of a NullPointerException.
     */
    private static ConvertEconomics enabledEconomics() {
        return new ConvertEconomics(new AppConfig.ConvertConfiguration(true), null, null);
    }

    /**
     * Applies the executor's wallet selector to {@code orderLovelace} — as the real router does with its
     * plan's order lovelace — records every UTxO it is handed, throws the real router's
     * {@link ConvertLiquidationRouter.WalletInputTooSmallException} when it is handed none, and otherwise
     * returns {@code canned}. Its counters are shared objects, so a Mockito spy of it counts into the same
     * ones.
     */
    static class BuildingConvertRouter extends ConvertLiquidationRouter {

        private final Transaction canned;
        private final BigInteger orderLovelace;
        private final AtomicInteger calls = new AtomicInteger();
        private final List<Utxo> offered = Collections.synchronizedList(new ArrayList<>());

        BuildingConvertRouter(Transaction canned, BigInteger orderLovelace) {
            super(null, null, null, null, enabledEconomics(), null, null, null);
            this.canned = canned;
            this.orderLovelace = orderLovelace;
        }

        @Override
        public Transaction buildConvertLiquidation(LiquidationAssessment assessment, Utxo loanUtxo,
                                                    Utxo bondUtxo, Utxo configUtxo, Utxo lmConfigUtxo,
                                                    Function<BigInteger, Optional<Utxo>> walletSelector,
                                                    Utxo collateralUtxo,
                                                    Map<String, OracleEntry> oraclesByOracleTokenUnit,
                                                    String changeAddress, long validFromMillis,
                                                    long validToMillis) {
            calls.incrementAndGet();
            Optional<Utxo> selected = walletSelector.apply(orderLovelace);
            selected.ifPresent(offered::add);
            if (selected.isEmpty()) {
                throw new ConvertLiquidationRouter.WalletInputTooSmallException(
                        "no nominable wallet utxo can fund this convert: the Minswap order carries "
                                + orderLovelace + " lovelace");
            }
            return canned;
        }

        int calls() {
            return calls.get();
        }

        /** Every UTxO the selector handed this router, in order. */
        List<Utxo> offered() {
            return List.copyOf(offered);
        }
    }

    /** Counts its calls, never touches the selector, and throws {@code toThrow}. */
    static class ThrowingConvertRouter extends ConvertLiquidationRouter {

        private final RuntimeException toThrow;
        private final AtomicInteger calls = new AtomicInteger();

        ThrowingConvertRouter(RuntimeException toThrow) {
            super(null, null, null, null, enabledEconomics(), null, null, null);
            this.toThrow = toThrow;
        }

        @Override
        public Transaction buildConvertLiquidation(LiquidationAssessment assessment, Utxo loanUtxo,
                                                    Utxo bondUtxo, Utxo configUtxo, Utxo lmConfigUtxo,
                                                    Function<BigInteger, Optional<Utxo>> walletSelector,
                                                    Utxo collateralUtxo,
                                                    Map<String, OracleEntry> oraclesByOracleTokenUnit,
                                                    String changeAddress, long validFromMillis,
                                                    long validToMillis) {
            calls.incrementAndGet();
            throw toThrow;
        }

        int calls() {
            return calls.get();
        }
    }

    /** A submitter that accepts everything, answering {@code txHash}. */
    static LiquidationExecutor.TransactionSubmitter accepting(String txHash) {
        return bytes -> Result.success(txHash).withValue(txHash);
    }

    // ======================================================================================
    // the run
    // ======================================================================================

    /**
     * What one stage call left behind.
     *
     * @param events       the {@link LiquidationExecutor} log events, DEBUG and up, in order
     * @param submitted    every byte string the submitter received, in order
     * @param plainBuilds  how often the plain {@link LiquidateTransactionBuilder} was asked to build
     * @param convertBuilds how often the convert router was asked to build; 0 when there is none
     */
    record Run(WorkResult result, LiquidationDecisionLog log, List<ILoggingEvent> events, List<byte[]> submitted,
               StageTally tally, int plainBuilds, int convertBuilds) {

        /** The formatted messages logged at {@code level}, in order. */
        List<String> messages(Level level) {
            return events.stream().filter(event -> event.getLevel() == level)
                    .map(ILoggingEvent::getFormattedMessage).toList();
        }
    }

    /**
     * ONE {@code cycle(NOW, fence, tally)}. A null {@code convertRouter} builds the executor through its
     * 16-argument test constructor, which passes no router at all — a node that cannot convert.
     */
    static Run run(AppConfig.LiquidationConfiguration configuration, List<Scenario> scenarios,
                   List<Utxo> walletUtxos, FluidOracleClient oracles, ConvertLiquidationRouter convertRouter,
                   LiquidationExecutor.TransactionSubmitter submitter, SubmitFence fence) {
        List<Utxo> universe = new ArrayList<>(List.of(CONFIG_UTXO, LM_CONFIG_UTXO));
        universe.addAll(walletUtxos);
        Map<String, Utxo> unspent = new LinkedHashMap<>();
        List<LiquidationAssessment> assessments = new ArrayList<>();
        for (Scenario scenario : scenarios) {
            universe.add(scenario.loan().utxo());
            universe.add(scenario.bond().utxo());
            if (scenario.resolvable()) {
                unspent.put(scenario.loan().loan().utxoRef(), scenario.loan().utxo());
            }
            unspent.put(scenario.bond().bond().utxoRef(), scenario.bond().utxo());
            assessments.add(scenario.assessment());
        }
        LiquidationUtxoResolver resolver = new LiquidationUtxoResolver(null, null, null) {
            @Override
            public Optional<Utxo> resolveLoanUtxo(Loan loan) {
                return Optional.ofNullable(unspent.get(loan.utxoRef()));
            }

            @Override
            public Optional<Utxo> resolveBondUtxo(LenderBond bond) {
                return Optional.ofNullable(unspent.get(bond.utxoRef()));
            }

            @Override
            public Optional<Utxo> resolveConfigUtxo() {
                return Optional.of(CONFIG_UTXO);
            }

            @Override
            public Optional<Utxo> resolveLmConfigUtxo() {
                return Optional.of(LM_CONFIG_UTXO);
            }
        };
        LiquidationCandidateScanner scanner = new LiquidationCandidateScanner(null, null, null) {
            @Override
            public Scan scan(long atTimeMillis) {
                return new Scan(assessments, new LoanService.Census(List.of(), assessments.size(), 0, 0));
            }
        };
        AppUtxoService appUtxoService = new AppUtxoService(null, null) {
            @Override
            public List<Utxo> listWalletUtxo() {
                return walletUtxos;
            }
        };
        List<byte[]> submitted = Collections.synchronizedList(new ArrayList<>());
        LiquidationExecutor.TransactionSubmitter recording = bytes -> {
            submitted.add(bytes);
            return submitter.submit(bytes);
        };

        BlockEventListener blockEventListener = new BlockEventListener(null);
        blockEventListener.getIsSyncing().set(false);
        PayInAdvanceLiquidationRouter payInAdvanceRouter = new PayInAdvanceLiquidationRouter(
                LoanFixtures.registry(), LoanFixtures.converters(), configuration,
                new LiquidatePayInAdvanceTransactionBuilder(LoanFixtures.registry(), LoanFixtures.NETWORK,
                        LoanFixtures.utxoSupplier(universe), LoanFixtures.protocolParams()));
        // Spies, so the builds can be counted; each delegates every call to the real object.
        LiquidateTransactionBuilder builder = Mockito.spy(new LiquidateTransactionBuilder(
                LoanFixtures.registry(), LoanFixtures.NETWORK, LoanFixtures.converters(),
                LoanFixtures.utxoSupplier(universe), LoanFixtures.protocolParams()));
        ConvertLiquidationRouter router = convertRouter == null ? null : Mockito.spy(convertRouter);
        LiquidationDecisionLog log = new LiquidationDecisionLog(configuration);
        MarketCoverageReporter metrics = new MarketCoverageReporter(new SimpleMeterRegistry());
        ObjectProvider<FluidOracleClient> oracleProvider = provider(oracles);
        AppConfig.Network network = new AppConfig.Network() {
            @Override
            public String getNetwork() {
                return "preview";
            }
        };
        LiquidationExecutor executor = router == null
                ? new LiquidationExecutor(configuration, blockEventListener, appUtxoService, ACCOUNT, scanner,
                        resolver, builder, payInAdvanceRouter, LoanFixtures.registry(), log, metrics,
                        oracleProvider, network, LoanFixtures.protocolParams(), LoanFixtures.converters(),
                        recording)
                : new LiquidationExecutor(configuration, blockEventListener, appUtxoService, ACCOUNT, scanner,
                        resolver, builder, payInAdvanceRouter, router, LoanFixtures.registry(), log, metrics,
                        oracleProvider, network, LoanFixtures.protocolParams(), LoanFixtures.converters(),
                        recording);
        executor.setSubmitClock(() -> NOW);

        var logger = (Logger) LoggerFactory.getLogger(LiquidationExecutor.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        Level previous = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(appender);
        StageTally tally = new StageTally();
        WorkResult result;
        try {
            result = executor.cycle(NOW, fence, tally);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
        }
        return new Run(result, log, List.copyOf(appender.list), List.copyOf(submitted), tally,
                builds(builder, "build"), router == null ? 0 : builds(router, "buildConvertLiquidation"));
    }

    private static int builds(Object spy, String method) {
        return (int) Mockito.mockingDetails(spy).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals(method)).count();
    }

    private static ObjectProvider<FluidOracleClient> provider(FluidOracleClient client) {
        return new ObjectProvider<>() {
            @Override
            public FluidOracleClient getObject() {
                return client;
            }

            @Override
            public FluidOracleClient getObject(Object... args) {
                return client;
            }

            @Override
            public FluidOracleClient getIfAvailable() {
                return client;
            }

            @Override
            public FluidOracleClient getIfUnique() {
                return client;
            }
        };
    }
}
