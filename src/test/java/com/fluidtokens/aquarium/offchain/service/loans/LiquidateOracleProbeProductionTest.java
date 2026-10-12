package com.fluidtokens.aquarium.offchain.service.loans;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.ScriptSupplier;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.TransactionService;
import com.bloxbean.cardano.client.backend.api.UtxoService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.config.YaciConfig;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationDecision;
import com.fluidtokens.aquarium.offchain.model.loans.Loan;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.model.loans.RepaymentMode;
import com.fluidtokens.aquarium.offchain.service.AppUtxoService;
import com.fluidtokens.aquarium.offchain.service.BlockEventListener;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * ⛔ FAB-138 T3a: the oracle probe, through the PRODUCTION builder and the executor's cycle.
 *
 * <p>The builder here is {@code YaciConfig}'s own bean method — the one Spring calls — over a mocked
 * {@link BFBackendService}: its {@code UtxoService} answers the probe and its {@code TransactionService}
 * is the evaluator the bean's lambda calls, so this test sees exactly which Blockfrost reads a build makes
 * and in what order. Only the leaves are fixtures (the loan, the configs, the wallet, the content hold).
 *
 * <p>The assertion that matters is the FAILURE MODE ({@code ccl-transaction-building-traps} §19): a spent
 * feed must arrive at the operator as a REFUSED decision and an ERROR naming the feed — never as
 * Blockfrost's empty {@code ScriptFailures}, which {@code LiquidationExecutor.spentInputEffect} reads as a
 * spent WALLET input and answers by dropping the nominated wallet utxo (§13). So a refused probe must
 * never reach the evaluator, and must leave the wallet list alone.
 */
class LiquidateOracleProbeProductionTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final long LATE_LEND_DATE = NOW - 30L * 24 * 3_600_000L;
    private static final long VALID_FROM = NOW - 30_000L;

    private static final String LOAN_ID = "a1b2c3d4e5f6a1b2";
    private static final String STAKE_KEY = "33".repeat(28);
    private static final BigInteger FEE_PER_MILLE = BigInteger.valueOf(500);
    private static final BigInteger SMALL_MARGIN = BigInteger.valueOf(1_500_000);

    private static final Account ACCOUNT = new Account(LoanFixtures.NETWORK);

    private static final Utxo CONFIG_UTXO = LoanFixtures.syntheticLatestConfigUtxo("f1".repeat(32), 0);
    private static final Utxo LM_CONFIG_UTXO = LoanFixtures.syntheticLatestLmConfigUtxo("f2".repeat(32), 0);
    // two wallet utxos whose list order is the reverse of their tx-id order, run in both orders
    private static final Utxo WALLET_A = LoanFixtures.adaUtxo("e5".repeat(32), 0, ACCOUNT.baseAddress(),
            200_000_000L);
    private static final Utxo WALLET_B = LoanFixtures.adaUtxo("e0".repeat(32), 0, ACCOUNT.baseAddress(),
            150_000_000L);

    // a token collateral leg priced by a Charli3 feed
    private static final AssetType COLLATERAL_TOKEN = new AssetType("c0".repeat(28), "544f4b");
    private static final AssetType ORACLE_NFT = new AssetType("b0".repeat(28), "4f52434c");
    private static final AssetType C3_PROVIDER_NFT = new AssetType("d0".repeat(28), "4f7261636c6546656564");
    private static final String ORACLE_CREDENTIAL = "a0".repeat(28);
    private static final String TX_ORACLE_NFT = "9a".repeat(32);
    private static final String TX_ORACLE_SCRIPT = "9b".repeat(32);
    private static final String TX_C3_PROVIDER = "9c".repeat(32);
    private static final String ORACLE_ADDRESS =
            "addr_test1wpqzexzdvwtl2zxw6pn5v34m9lxk0avnckmemy0puhqtzfqw4jw8q";
    private static final String C3_PROVIDER_ADDRESS =
            "addr_test1wzgy7cu7mnnjau2qn5th8932tr27f83tfgusm60sklwppmgh6re39";

    /** The oracle's two out-refs as the content hold knows them: an address and the NFT, nothing live. */
    private static final Utxo ORACLE_NFT_UTXO = LoanFixtures.utxo(TX_ORACLE_NFT, 0, ORACLE_ADDRESS,
            List.of(Amount.lovelace(BigInteger.valueOf(2_000_000)), LoanFixtures.token(ORACLE_NFT, 1)), null);
    private static final Utxo C3_PROVIDER_UTXO = LoanFixtures.utxo(TX_C3_PROVIDER, 0, C3_PROVIDER_ADDRESS,
            List.of(Amount.lovelace(BigInteger.valueOf(2_000_000)), LoanFixtures.token(C3_PROVIDER_NFT, 1)), null);

    private static final String TX_LOAN = "aa".repeat(32);
    private static final String TX_BOND = "dd".repeat(32);

    // ======================================================================================
    // the rig
    // ======================================================================================

    /** Everything one test drives and reads back. */
    private static final class Rig {
        final UtxoService utxoService = mock(UtxoService.class);
        final TransactionService transactionService = mock(TransactionService.class);
        final BFBackendService backend = mock(BFBackendService.class);
        final AtomicInteger evaluations = new AtomicInteger();
        final AtomicInteger submissions = new AtomicInteger();
        final List<ILoggingEvent> events = new ArrayList<>();
        LiquidationExecutor executor;
        LiquidationDecisionLog log;
        LiquidateTransactionBuilder builder;

        Rig() throws Exception {
            when(backend.getUtxoService()).thenReturn(utxoService);
            when(backend.getTransactionService()).thenReturn(transactionService);
            // the evaluator the bean's lambda calls: prices every redeemer the body carries
            when(transactionService.evaluateTx(any(byte[].class))).thenAnswer(invocation -> {
                evaluations.incrementAndGet();
                Transaction tx = Transaction.deserialize(invocation.getArgument(0));
                List<EvaluationResult> costs = new ArrayList<>();
                for (Redeemer redeemer : tx.getWitnessSet().getRedeemers()) {
                    costs.add(new EvaluationResult(redeemer.getTag(), redeemer.getIndex().intValue(),
                            new ExUnits(BigInteger.valueOf(400_000), BigInteger.valueOf(150_000_000))));
                }
                @SuppressWarnings({"unchecked", "rawtypes"})
                Result<List<EvaluationResult>> ok =
                        (Result<List<EvaluationResult>>) Result.success("ok").code(200).withValue(costs);
                return ok;
            });
        }

        Rig feedAnswers(Result<List<Utxo>> answer) throws Exception {
            when(utxoService.getUtxos(ORACLE_ADDRESS, ORACLE_NFT.toUnit(), 100, 1)).thenReturn(answer);
            return this;
        }

        Rig providerAnswers(Result<List<Utxo>> answer) throws Exception {
            when(utxoService.getUtxos(C3_PROVIDER_ADDRESS, C3_PROVIDER_NFT.toUnit(), 100, 1)).thenReturn(answer);
            return this;
        }

        Rig wire(AppConfig.LiquidationConfiguration.Mode mode, List<Utxo> walletUtxos) {
            Scenario scenario = tokenScenario();
            List<Utxo> universe = new ArrayList<>(List.of(CONFIG_UTXO, LM_CONFIG_UTXO, ORACLE_NFT_UTXO,
                    C3_PROVIDER_UTXO, scenario.loanUtxo(), scenario.bondUtxo()));
            universe.addAll(walletUtxos);
            var contentHold = LoanFixtures.utxoSupplier(universe);
            ScriptSupplier noScripts = hash -> Optional.empty();

            YaciConfig yaciConfig = new YaciConfig();
            builder = yaciConfig.liquidateTransactionBuilder(LoanFixtures.registry(), previewNetwork(),
                    LoanFixtures.converters(), contentHold, LoanFixtures.protocolParams(), noScripts, backend,
                    yaciConfig.oracleReferenceInputProbe(contentHold, backend));

            AppConfig.LiquidationConfiguration configuration = config(mode);
            BlockEventListener blockEventListener = new BlockEventListener(null);
            blockEventListener.getIsSyncing().set(false);
            log = new LiquidationDecisionLog(configuration);
            Map<String, Utxo> unspent = new LinkedHashMap<>();
            unspent.put(scenario.loan().utxoRef(), scenario.loanUtxo());
            unspent.put(scenario.bond().utxoRef(), scenario.bondUtxo());

            PayInAdvanceLiquidationRouter payInAdvanceRouter = new PayInAdvanceLiquidationRouter(
                    LoanFixtures.registry(), LoanFixtures.converters(), configuration,
                    new LiquidatePayInAdvanceTransactionBuilder(LoanFixtures.registry(), LoanFixtures.NETWORK,
                            contentHold, LoanFixtures.protocolParams()));
            executor = new LiquidationExecutor(configuration, blockEventListener,
                    new FakeAppUtxoService(walletUtxos), ACCOUNT, new FakeScanner(List.of(scenario.assessment())),
                    new FakeResolver(unspent), builder, payInAdvanceRouter, LoanFixtures.registry(), log,
                    new MarketCoverageReporter(new SimpleMeterRegistry()), new FixedOracles(collateralOracle()),
                    previewNetwork(), LoanFixtures.protocolParams(), LoanFixtures.converters(),
                    bytes -> {
                        submissions.incrementAndGet();
                        return Result.error("this rig never accepts a submission");
                    });
            return this;
        }

        void cycles(int n) {
            var logger = (Logger) LoggerFactory.getLogger(LiquidationExecutor.class);
            var appender = new ListAppender<ILoggingEvent>();
            appender.start();
            logger.addAppender(appender);
            try {
                for (int i = 0; i < n; i++) {
                    executor.cycle(NOW + i * 60_000L);
                }
            } finally {
                logger.detachAppender(appender);
            }
            events.addAll(appender.list);
        }

        List<ILoggingEvent> errorsContaining(String text) {
            return events.stream().filter(e -> e.getLevel() == Level.ERROR)
                    .filter(e -> e.getFormattedMessage().contains(text)).toList();
        }

        boolean droppedAWalletUtxo() {
            return events.stream().anyMatch(e -> e.getFormattedMessage().contains("dropped wallet utxo"));
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Result<List<Utxo>> live(Utxo... utxos) {
        return (Result<List<Utxo>>) Result.success("ok").code(200).withValue(new ArrayList<>(List.of(utxos)));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Result<List<Utxo>> notFound() {
        return (Result<List<Utxo>>) Result.error("{\"status_code\":404,\"error\":\"Not Found\"}").code(404);
    }

    // ======================================================================================
    // the cases
    // ======================================================================================

    /** (1) A spent feed (404) refuses, loudly, every cycle — and never reaches the evaluator or the wire. */
    @Test
    void aSpentFeedIsRefusedAtErrorEveryCycleBeforeEvaluationAndKeepsTheWallet() throws Exception {
        Rig rig = new Rig().feedAnswers(notFound()).providerAnswers(live(C3_PROVIDER_UTXO))
                .wire(AppConfig.LiquidationConfiguration.Mode.LIVE, List.of(WALLET_A, WALLET_B));

        rig.cycles(2);

        List<LiquidationDecision> decisions = rig.log.newestFirst(10);
        assertEquals(2, decisions.size(), "one decision per cycle — no hold between them: " + decisions);
        for (LiquidationDecision decision : decisions) {
            assertEquals(LiquidationDecision.Outcome.REFUSED, decision.outcome(), decision.detail());
            assertTrue(decision.detail().contains(TX_ORACLE_NFT + "#0"),
                    "the detail names the feed out-ref: " + decision.detail());
            assertTrue(decision.detail().contains(ORACLE_NFT.toUnit()),
                    "the detail names the NFT it expected: " + decision.detail());
        }
        assertEquals(2, rig.errorsContaining(TX_ORACLE_NFT + "#0").size(),
                "an ERROR naming the feed on EVERY cycle: " + rig.events);
        assertEquals(0, rig.evaluations.get(), "a refused probe must never reach the evaluator");
        assertEquals(0, rig.submissions.get(), "nor the wire");
        assertTrue(!rig.droppedAWalletUtxo(),
                "a probe refusal is not a spent WALLET input — no wallet utxo may be dropped: " + rig.events);
        verify(rig.utxoService, times(2)).getUtxos(ORACLE_ADDRESS, ORACLE_NFT.toUnit(), 100, 1);
        verify(rig.utxoService, never()).getTxOutput(anyString(), anyInt());
    }

    /**
     * (2) and (5) A live feed and provider: the build proceeds to evaluation exactly once, and Blockfrost is
     * asked exactly one {@code getUtxos} per oracle out-ref — in both wallet-list orders, because the fixture
     * supplier returns list order while production sorts by tx id.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aLiveFeedBuildsAsBeforeWithOneProbePerOracleOutRef(boolean reversed) throws Exception {
        List<Utxo> wallet = reversed ? List.of(WALLET_B, WALLET_A) : List.of(WALLET_A, WALLET_B);
        Rig rig = new Rig().feedAnswers(live(ORACLE_NFT_UTXO)).providerAnswers(live(C3_PROVIDER_UTXO))
                .wire(AppConfig.LiquidationConfiguration.Mode.SHADOW, wallet);

        rig.cycles(1);

        List<LiquidationDecision> decisions = rig.log.newestFirst(10);
        assertEquals(1, decisions.size(), decisions.toString());
        LiquidationDecision decision = decisions.getFirst();
        assertEquals(LiquidationDecision.Outcome.WOULD_SUBMIT, decision.outcome(), decision.detail());
        assertNotNull(decision.txCborHex(), "the built transaction is recorded");
        assertEquals(1, rig.evaluations.get(), "the build is priced exactly once, as before the probe");
        verify(rig.utxoService, times(1)).getUtxos(ORACLE_ADDRESS, ORACLE_NFT.toUnit(), 100, 1);
        verify(rig.utxoService, times(1)).getUtxos(C3_PROVIDER_ADDRESS, C3_PROVIDER_NFT.toUnit(), 100, 1);
        verify(rig.utxoService, never()).getTxOutput(anyString(), anyInt());
        verifyNoMoreInteractions(rig.utxoService);
        assertEquals(0, rig.submissions.get(), "shadow never submits");
    }

    /** (3) SHADOW probes too: a shadow build that would have referenced a spent feed is refused the same way. */
    @Test
    void aShadowBuildIsProbedAndRefusedOnASpentFeed() throws Exception {
        Rig rig = new Rig().feedAnswers(notFound()).providerAnswers(live(C3_PROVIDER_UTXO))
                .wire(AppConfig.LiquidationConfiguration.Mode.SHADOW, List.of(WALLET_A));

        rig.cycles(1);

        LiquidationDecision decision = rig.log.newestFirst(10).getFirst();
        assertEquals(LiquidationDecision.Outcome.REFUSED, decision.outcome(), decision.detail());
        assertTrue(decision.detail().contains(TX_ORACLE_NFT + "#0"), decision.detail());
        assertEquals(0, rig.evaluations.get(), "a refused probe must never reach the evaluator");
        verify(rig.utxoService).getUtxos(ORACLE_ADDRESS, ORACLE_NFT.toUnit(), 100, 1);
    }

    /** (4) The Charli3 provider out-ref is probed with the c3 NFT — never the feed's — and a spent one refuses. */
    @Test
    void theC3ProviderIsProbedWithTheC3NftAndASpentProviderRefuses() throws Exception {
        Rig rig = new Rig().feedAnswers(live(ORACLE_NFT_UTXO)).providerAnswers(notFound())
                .wire(AppConfig.LiquidationConfiguration.Mode.SHADOW, List.of(WALLET_A));

        rig.cycles(1);

        LiquidationDecision decision = rig.log.newestFirst(10).getFirst();
        assertEquals(LiquidationDecision.Outcome.REFUSED, decision.outcome(), decision.detail());
        assertTrue(decision.detail().contains(TX_C3_PROVIDER + "#0"), decision.detail());
        assertTrue(decision.detail().contains(C3_PROVIDER_NFT.toUnit()), decision.detail());
        verify(rig.utxoService).getUtxos(C3_PROVIDER_ADDRESS, C3_PROVIDER_NFT.toUnit(), 100, 1);
        verify(rig.utxoService, never()).getUtxos(C3_PROVIDER_ADDRESS, ORACLE_NFT.toUnit(), 100, 1);
        assertEquals(0, rig.evaluations.get());
        assertTrue(!rig.droppedAWalletUtxo(), rig.events.toString());
    }

    /** The rig really is the bean: the builder it drives holds a probe (6d pins the same on the bean alone). */
    @Test
    void theRigDrivesAProbedBuilder() throws Exception {
        Rig rig = new Rig().wire(AppConfig.LiquidationConfiguration.Mode.SHADOW, List.of(WALLET_A));
        Field field = LiquidateTransactionBuilder.class.getDeclaredField("oracleReferenceInputProbe");
        field.setAccessible(true);
        assertNotNull(field.get(rig.builder));
    }

    // ======================================================================================
    // fixtures — LiquidationExecutorTest's token-collateral scenario, duplicated so this class owns them
    // ======================================================================================

    private record Scenario(LoanFixtures.LoanUtxo loanFixture, LoanFixtures.BondUtxo bondFixture,
                            LiquidationAssessment assessment) {
        Loan loan() {
            return loanFixture.loan();
        }

        LenderBond bond() {
            return bondFixture.bond();
        }

        Utxo loanUtxo() {
            return loanFixture.utxo();
        }

        Utxo bondUtxo() {
            return bondFixture.utxo();
        }
    }

    /** 50 ADA principal against 1_000_000 TOK at 50 lovelace: under water, equity zero, a 500‰ fee slice. */
    private static Scenario tokenScenario() {
        LoanDatum datum = LoanFixtures.loanDatum(AssetType.ada(), BigInteger.valueOf(50_000_000),
                BigInteger.valueOf(1000), LoanFixtures.tokenCollateral(COLLATERAL_TOKEN, ORACLE_NFT),
                LATE_LEND_DATE, LoanFixtures.liquidation(), new RepaymentMode.PrincipalAndInterestOnInstallments(),
                false);
        LoanFixtures.LoanUtxo loan = LoanFixtures.loanUtxo(TX_LOAN, 0, LOAN_ID, datum, 2_000_000L,
                List.of(LoanFixtures.token(COLLATERAL_TOKEN, 1_000_000L)));
        LoanFixtures.BondUtxo bond = LoanFixtures.bondUtxo(TX_BOND, 0, LOAN_ID,
                LoanFixtures.bondDatum(FEE_PER_MILLE, LoanFixtures.inlineKeyStakeCredential(STAKE_KEY),
                        AssetType.ada()),
                2_000_000L);
        LiquidationAssessment assessment = LoanFixtures.assess(bond.bond(), loan.loan(),
                OraclePriceFeed.unit(), collateralFeed(), VALID_FROM);
        return new Scenario(loan, bond, assessment);
    }

    private static OraclePriceFeed collateralFeed() {
        return OraclePriceFeed.priceDataCharlie(COLLATERAL_TOKEN, BigInteger.valueOf(50), BigInteger.ONE,
                NOW - 60_000L, NOW + 600_000L);
    }

    /** The c3 oracle, with the provider NFT the registry's c3 policy names (FAB-138). */
    private static OracleEntry collateralOracle() {
        OracleEntry base = LoanFixtures.charli3(COLLATERAL_TOKEN, ORACLE_NFT, ORACLE_CREDENTIAL, collateralFeed(),
                LoanFixtures.input(TX_ORACLE_NFT, 0), LoanFixtures.input(TX_ORACLE_SCRIPT, 0),
                LoanFixtures.input(TX_C3_PROVIDER, 0));
        return new OracleEntry(base.token(), base.oracleToken(), base.rewardAddress(), base.withdrawCredentialHash(),
                base.referenceInput(), base.referenceScript(), base.verificationKeys(), base.threshold(), base.feed(),
                base.signatures(), base.charlieProviderReferenceInput(), base.oracleVersion(), C3_PROVIDER_NFT);
    }

    private static AppConfig.LiquidationConfiguration config(AppConfig.LiquidationConfiguration.Mode mode) {
        var configuration = new AppConfig.LiquidationConfiguration(mode, 60, 120, 30, SMALL_MARGIN, 200);
        var market = new AppConfig.LiquidationConfiguration.Market();
        market.setUnit("lovelace");
        market.setMode(AppConfig.LiquidationConfiguration.Mode.LIVE);
        market.setAction(AppConfig.LiquidationConfiguration.Action.ANTICIPATE);
        market.setCap(BigInteger.valueOf(1_000_000_000_000L));
        configuration.setMarkets(List.of(market));
        return configuration;
    }

    private static AppConfig.Network previewNetwork() {
        var network = new AppConfig.Network();
        network.setNetworkForTest("preview");
        return network;
    }

    private static final class FakeScanner extends LiquidationCandidateScanner {
        private final List<LiquidationAssessment> assessments;

        FakeScanner(List<LiquidationAssessment> assessments) {
            super(null, null, null);
            this.assessments = assessments;
        }

        @Override
        public Scan scan(long atTimeMillis) {
            return new Scan(assessments, new LoanService.Census(List.of(), assessments.size(), 0, 0));
        }
    }

    private static final class FakeResolver extends LiquidationUtxoResolver {
        private final Map<String, Utxo> unspent;

        FakeResolver(Map<String, Utxo> unspent) {
            super(null, null, null);
            this.unspent = unspent;
        }

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
    }

    private static final class FakeAppUtxoService extends AppUtxoService {
        private final List<Utxo> utxos;

        FakeAppUtxoService(List<Utxo> utxos) {
            super(null, null);
            this.utxos = utxos;
        }

        @Override
        public List<Utxo> listWalletUtxo() {
            return utxos;
        }
    }

    private static final class FakeOracleClient extends FluidOracleClient {
        private final List<OracleEntry> entries;

        FakeOracleClient(List<OracleEntry> entries) {
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

    private static final class FixedOracles implements ObjectProvider<FluidOracleClient> {
        private final FluidOracleClient client;

        FixedOracles(OracleEntry... entries) {
            this.client = new FakeOracleClient(List.of(entries));
        }

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
    }
}
