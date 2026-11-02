package com.fluidtokens.aquarium.offchain.service.loans;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.ScriptSupplier;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.TransactionService;
import com.bloxbean.cardano.client.backend.api.UtxoService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.store.common.domain.Amt;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.AddressUtxoEntity;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.config.YaciConfig;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.model.loans.LenderManagerDatum;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationDecision;
import com.fluidtokens.aquarium.offchain.model.loans.Loan;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.model.loans.OracleSignature;
import com.fluidtokens.aquarium.offchain.model.loans.Rational;
import com.fluidtokens.aquarium.offchain.model.loans.RepaymentMode;
import com.fluidtokens.aquarium.offchain.service.AppUtxoService;
import com.fluidtokens.aquarium.offchain.service.BlockEventListener;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.cardanofoundation.conversions.CardanoConverters;
import org.cardanofoundation.conversions.ClasspathConversionsFactory;
import org.cardanofoundation.conversions.domain.NetworkType;
import org.junit.jupiter.api.Test;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ⛔ FAB-183 (A4): the refusals decided BEFORE the build cost nothing on Blockfrost — through the PRODUCTION
 * builders and the executor's own cycle.
 *
 * <p>Built like {@link OracleProbeRoutesProductionTest}'s rig: every builder is {@code YaciConfig}'s own bean
 * method — the one Spring calls — handed the probe bean, over a mocked {@link BFBackendService}. Its
 * {@code UtxoService} answers the probe and its {@code TransactionService} is the evaluator the bean's lambda
 * calls, and both COUNT. Those are the only two Blockfrost calls a candidate makes (probe P, evaluate E), so a
 * refusal that holds both counters at zero cost nothing; the controls show the same counters moving when a
 * build does happen, so a zero is a measurement and not a deaf counter.
 *
 * <p>Cases (a)–(f) are the slice contract's: a DISABLED market on the plain and convert routes, a certainly
 * unprofitable plain candidate (on the boundary of each floor), the ample control, SHADOW still rehearsing,
 * and an oracle out-ref the node cannot see yet, which is a wait and not a fault.
 */
class PreBuildRefusalProductionTest {

    private static final String DROPPED = "dropped wallet utxo";

    // ======================================================================================
    // the shared rig: the counting backend, the log capture
    // ======================================================================================

    private abstract static class Rig {
        final UtxoService utxoService = mock(UtxoService.class);
        final TransactionService transactionService = mock(TransactionService.class);
        final BFBackendService backend = mock(BFBackendService.class);
        final AtomicInteger probes = new AtomicInteger();
        final AtomicInteger evaluations = new AtomicInteger();
        final AtomicInteger submissions = new AtomicInteger();
        final Map<String, Result<List<Utxo>>> answers = new LinkedHashMap<>();
        final List<ILoggingEvent> events = new ArrayList<>();
        LiquidationExecutor executor;
        LiquidationDecisionLog log;

        Rig() throws Exception {
            when(backend.getUtxoService()).thenReturn(utxoService);
            when(backend.getTransactionService()).thenReturn(transactionService);
            // probe P: every live-UTxO question, counted, answered from the table (404 when not in it)
            when(utxoService.getUtxos(anyString(), anyString(), anyInt(), anyInt())).thenAnswer(invocation -> {
                probes.incrementAndGet();
                Result<List<Utxo>> answer = answers.get(invocation.getArgument(0) + "|" + invocation.getArgument(1));
                return answer != null ? answer : notFound();
            });
            // evaluate E: prices every redeemer the body carries, counted
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

        void answers(String address, AssetType nft, Result<List<Utxo>> answer) {
            answers.put(address + "|" + nft.toUnit(), answer);
        }

        void cycles(long start, int n) {
            var logger = (Logger) LoggerFactory.getLogger(LiquidationExecutor.class);
            var appender = new ListAppender<ILoggingEvent>();
            appender.start();
            logger.addAppender(appender);
            try {
                for (int i = 0; i < n; i++) {
                    executor.cycle(start + i * 60_000L);
                }
            } finally {
                logger.detachAppender(appender);
            }
            events.addAll(appender.list);
        }

        List<String> errors() {
            return events.stream().filter(e -> e.getLevel() == Level.ERROR).map(ILoggingEvent::getFormattedMessage)
                    .toList();
        }

        boolean droppedAWalletUtxo() {
            return events.stream().anyMatch(e -> e.getFormattedMessage().contains(DROPPED));
        }

        List<LiquidationDecision> decisions() {
            return log.newestFirst(10);
        }

        LiquidationDecision onlyDecision() {
            List<LiquidationDecision> decisions = decisions();
            assertEquals(1, decisions.size(), "exactly one decision: " + decisions);
            return decisions.getFirst();
        }

        /** The refusal cost nothing: no probe, no evaluation, nothing on the wire. */
        void assertNoBlockfrostCall() {
            assertEquals(0, probes.get(), "no oracle probe may be spent on a refused candidate");
            assertEquals(0, evaluations.get(), "no evaluation may be spent on a refused candidate");
            assertEquals(0, submissions.get(), "nothing submitted");
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
    // PLAIN — LiquidateOracleProbeProductionTest's token-collateral scenario, its own copy here
    // ======================================================================================

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
    private static final Utxo WALLET_A = LoanFixtures.adaUtxo("e5".repeat(32), 0, ACCOUNT.baseAddress(),
            200_000_000L);
    private static final Utxo WALLET_B = LoanFixtures.adaUtxo("e0".repeat(32), 0, ACCOUNT.baseAddress(),
            150_000_000L);

    private static final AssetType COLLATERAL_TOKEN = new AssetType("c0".repeat(28), "544f4b");
    private static final AssetType ORACLE_NFT = new AssetType("b0".repeat(28), "4f52434c");
    private static final AssetType C3_PROVIDER_NFT = new AssetType("d0".repeat(28), "4f7261636c6546656564");
    private static final String ORACLE_CREDENTIAL = "a0".repeat(28);
    private static final String TX_ORACLE_NFT = "9a".repeat(32);
    private static final String TX_ORACLE_SCRIPT = "9b".repeat(32);
    private static final String TX_C3_PROVIDER = "9c".repeat(32);
    private static final String ORACLE_ADDRESS = "addr_test1wpqzexzdvwtl2zxw6pn5v34m9lxk0avnckmemy0puhqtzfqw4jw8q";
    private static final String C3_PROVIDER_ADDRESS =
            "addr_test1wzgy7cu7mnnjau2qn5th8932tr27f83tfgusm60sklwppmgh6re39";

    private static final Utxo ORACLE_NFT_UTXO = LoanFixtures.utxo(TX_ORACLE_NFT, 0, ORACLE_ADDRESS,
            List.of(Amount.lovelace(BigInteger.valueOf(2_000_000)), LoanFixtures.token(ORACLE_NFT, 1)), null);
    private static final Utxo C3_PROVIDER_UTXO = LoanFixtures.utxo(TX_C3_PROVIDER, 0, C3_PROVIDER_ADDRESS,
            List.of(Amount.lovelace(BigInteger.valueOf(2_000_000)), LoanFixtures.token(C3_PROVIDER_NFT, 1)), null);

    private static final String TX_LOAN = "aa".repeat(32);
    private static final String TX_BOND = "dd".repeat(32);

    /** 50 ADA principal against 1_000_000 TOK at 50 lovelace: under water, equity zero, a 500‰ fee slice. */
    private static LiquidationAssessment plainAssessment(LoanFixtures.LoanUtxo loan, LoanFixtures.BondUtxo bond) {
        return LoanFixtures.assess(bond.bond(), loan.loan(), OraclePriceFeed.unit(), plainCollateralFeed(),
                VALID_FROM);
    }

    private static LoanFixtures.LoanUtxo plainLoan() {
        LoanDatum datum = LoanFixtures.loanDatum(AssetType.ada(), BigInteger.valueOf(50_000_000),
                BigInteger.valueOf(1000), LoanFixtures.tokenCollateral(COLLATERAL_TOKEN, ORACLE_NFT),
                LATE_LEND_DATE, LoanFixtures.liquidation(), new RepaymentMode.PrincipalAndInterestOnInstallments(),
                false);
        return LoanFixtures.loanUtxo(TX_LOAN, 0, LOAN_ID, datum, 2_000_000L,
                List.of(LoanFixtures.token(COLLATERAL_TOKEN, 1_000_000L)));
    }

    private static LoanFixtures.BondUtxo plainBond() {
        return LoanFixtures.bondUtxo(TX_BOND, 0, LOAN_ID,
                LoanFixtures.bondDatum(FEE_PER_MILLE, LoanFixtures.inlineKeyStakeCredential(STAKE_KEY),
                        AssetType.ada()),
                2_000_000L);
    }

    private static OraclePriceFeed plainCollateralFeed() {
        return OraclePriceFeed.priceDataCharlie(COLLATERAL_TOKEN, BigInteger.valueOf(50), BigInteger.ONE,
                NOW - 60_000L, NOW + 600_000L);
    }

    private static OracleEntry plainCollateralOracle() {
        OracleEntry base = LoanFixtures.charli3(COLLATERAL_TOKEN, ORACLE_NFT, ORACLE_CREDENTIAL, plainCollateralFeed(),
                LoanFixtures.input(TX_ORACLE_NFT, 0), LoanFixtures.input(TX_ORACLE_SCRIPT, 0),
                LoanFixtures.input(TX_C3_PROVIDER, 0));
        return withProviderNft(base, C3_PROVIDER_NFT);
    }

    /**
     * The fee slice {@code record()} would price this candidate at — the same expression, computed here so
     * a fixture can sit exactly on a floor.
     */
    private static BigInteger plainFeeSlice() {
        LiquidationAssessment assessment = plainAssessment(plainLoan(), plainBond());
        return LoanFinance.toLovelace(Rational.fromInt(assessment.liquidationFee()), plainCollateralFeed()).floor();
    }

    private static final class PlainRig extends Rig {
        LiquidateTransactionBuilder builder;
        /** Whether the content hold knows the collateral feed's out-ref (false: not yet visible to the node). */
        boolean feedContentKnown = true;

        PlainRig() throws Exception {
            super();
            answers(ORACLE_ADDRESS, ORACLE_NFT, live(ORACLE_NFT_UTXO));
            answers(C3_PROVIDER_ADDRESS, C3_PROVIDER_NFT, live(C3_PROVIDER_UTXO));
        }

        PlainRig wire(AppConfig.LiquidationConfiguration configuration) {
            LoanFixtures.LoanUtxo loan = plainLoan();
            LoanFixtures.BondUtxo bond = plainBond();
            LiquidationAssessment assessment = plainAssessment(loan, bond);
            // FAB-185: role C, sized from the parameters the executor below reads, beside U.
            List<Utxo> walletUtxos = LoanFixtures.shapedWallet(LoanFixtures.protocolParams(), ACCOUNT.baseAddress(),
                    WALLET_A, WALLET_B);
            List<Utxo> universe = new ArrayList<>(List.of(CONFIG_UTXO, LM_CONFIG_UTXO, C3_PROVIDER_UTXO,
                    loan.utxo(), bond.utxo()));
            if (feedContentKnown) {
                universe.add(ORACLE_NFT_UTXO);
            }
            universe.addAll(walletUtxos);
            UtxoSupplier contentHold = LoanFixtures.utxoSupplier(universe);
            ScriptSupplier noScripts = hash -> Optional.empty();

            YaciConfig yaciConfig = new YaciConfig();
            builder = yaciConfig.liquidateTransactionBuilder(LoanFixtures.registry(), network("preview"),
                    LoanFixtures.converters(), contentHold, LoanFixtures.protocolParams(), noScripts, backend,
                    yaciConfig.oracleReferenceInputProbe(contentHold, backend));

            BlockEventListener blockEventListener = new BlockEventListener(null);
            blockEventListener.getIsSyncing().set(false);
            log = new LiquidationDecisionLog(configuration);
            Map<String, Utxo> unspent = new LinkedHashMap<>();
            unspent.put(loan.loan().utxoRef(), loan.utxo());
            unspent.put(bond.bond().utxoRef(), bond.utxo());
            PayInAdvanceLiquidationRouter payInAdvanceRouter = new PayInAdvanceLiquidationRouter(
                    LoanFixtures.registry(), LoanFixtures.converters(), configuration,
                    new LiquidatePayInAdvanceTransactionBuilder(LoanFixtures.registry(), LoanFixtures.NETWORK,
                            contentHold, LoanFixtures.protocolParams()));
            executor = new LiquidationExecutor(configuration, blockEventListener,
                    new FakeAppUtxoService(walletUtxos), ACCOUNT, new FakeScanner(assessment),
                    new MapResolver(unspent, CONFIG_UTXO, LM_CONFIG_UTXO), builder, payInAdvanceRouter,
                    LoanFixtures.registry(), log, new MarketCoverageReporter(new SimpleMeterRegistry()),
                    new FixedOracles(plainCollateralOracle()), network("preview"), LoanFixtures.protocolParams(),
                    LoanFixtures.converters(),
                    bytes -> {
                        submissions.incrementAndGet();
                        return Result.error("this rig never accepts a submission");
                    });
            executor.setSubmitClock(() -> NOW);
            return this;
        }
    }

    /** A plain-route configuration on preview: the node at {@code nodeMode}, the ada market at {@code marketMode}. */
    private static AppConfig.LiquidationConfiguration plainConfiguration(AppConfig.LiquidationConfiguration.Mode nodeMode,
                                                                         AppConfig.LiquidationConfiguration.Mode marketMode,
                                                                         BigInteger margin, BigInteger absoluteFloor) {
        var configuration = new AppConfig.LiquidationConfiguration(nodeMode, 60, 120, 30, margin, 200, true,
                absoluteFloor, LiquidateTransactionBuilder.ReferenceScripts.none());
        configuration.setMarkets(List.of(market("lovelace", marketMode,
                AppConfig.LiquidationConfiguration.Action.ANTICIPATE)));
        return configuration;
    }

    private static AppConfig.LiquidationConfiguration plainConfiguration(AppConfig.LiquidationConfiguration.Mode nodeMode,
                                                                         AppConfig.LiquidationConfiguration.Mode marketMode,
                                                                         BigInteger margin) {
        return plainConfiguration(nodeMode, marketMode, margin, BigInteger.ZERO);
    }

    private static final AppConfig.LiquidationConfiguration.Mode LIVE = AppConfig.LiquidationConfiguration.Mode.LIVE;
    private static final AppConfig.LiquidationConfiguration.Mode SHADOW =
            AppConfig.LiquidationConfiguration.Mode.SHADOW;
    private static final AppConfig.LiquidationConfiguration.Mode DISABLED =
            AppConfig.LiquidationConfiguration.Mode.DISABLED;

    /** (a) A DISABLED plain market on a LIVE node is refused before the build, and costs nothing. */
    @Test
    void aDisabledPlainMarketIsRefusedBeforeAnyBlockfrostCall() throws Exception {
        PlainRig rig = new PlainRig().wire(plainConfiguration(LIVE, DISABLED, SMALL_MARGIN));

        rig.cycles(NOW, 1);

        LiquidationDecision decision = rig.onlyDecision();
        assertEquals(LiquidationDecision.Outcome.REFUSED, decision.outcome(), decision.detail());
        assertEquals("MARKET_DISABLED", decision.reason(), decision.detail());
        assertTrue(decision.detail().contains("market lovelace is DISABLED (node mode LIVE)"), decision.detail());
        assertTrue(decision.detail().contains("refused before any build"), decision.detail());
        assertNull(decision.txHash(), "nothing was built");
        rig.assertNoBlockfrostCall();
        assertEquals(List.of(), rig.errors(), "a policy refusal is not a fault");
    }

    /**
     * (c) A LIVE plain candidate whose fee slice equals the margin — exactly on the expected-profit floor, so
     * {@code feeSlice − margin ≤ 0} holds by equality — is refused UNPROFITABLE / NOT_PROFITABLE before the
     * build: any transaction fee only lowers it below. A strict {@code <} would build it.
     */
    @Test
    void aCandidateWhoseFeeSliceEqualsTheMarginIsRefusedUnprofitableBeforeAnyCall() throws Exception {
        BigInteger feeSlice = plainFeeSlice();
        PlainRig rig = new PlainRig().wire(plainConfiguration(LIVE, LIVE, feeSlice));

        rig.cycles(NOW, 1);

        LiquidationDecision decision = rig.onlyDecision();
        assertEquals(LiquidationDecision.Outcome.UNPROFITABLE, decision.outcome(), decision.detail());
        assertEquals("UNPROFITABLE", decision.reason(), decision.detail());
        assertEquals(LiquidationExecutor.SubmitVeto.NOT_PROFITABLE.name(), decision.submitVeto(), decision.detail());
        assertTrue(decision.detail().contains("fee slice " + feeSlice + " lovelace"), decision.detail());
        assertTrue(decision.detail().contains("margin " + feeSlice), decision.detail());
        assertTrue(decision.detail().contains("any transaction fee only lowers it — nothing built"),
                decision.detail());
        assertNull(decision.txHash(), "nothing was built");
        rig.assertNoBlockfrostCall();
    }

    /**
     * (c, the other floor) The ABSOLUTE floor on its boundary: no margin, check-profitability on, the floor
     * set to the fee slice itself. {@code floorProfit ≤ feeSlice − 1 < floor}, so S3(a) would certainly refuse.
     */
    @Test
    void aCandidateWhoseFeeSliceEqualsTheAbsoluteFloorIsRefusedUnprofitableBeforeAnyCall() throws Exception {
        BigInteger feeSlice = plainFeeSlice();
        PlainRig rig = new PlainRig().wire(plainConfiguration(LIVE, LIVE, BigInteger.ZERO, feeSlice));

        rig.cycles(NOW, 1);

        LiquidationDecision decision = rig.onlyDecision();
        assertEquals(LiquidationDecision.Outcome.UNPROFITABLE, decision.outcome(), decision.detail());
        assertEquals(LiquidationExecutor.SubmitVeto.NOT_PROFITABLE.name(), decision.submitVeto(), decision.detail());
        assertTrue(decision.detail().contains("absolute floor " + feeSlice), decision.detail());
        rig.assertNoBlockfrostCall();
    }

    /**
     * (d) The control: the same candidate with an ample fee slice is built, so the counters DO see the probe
     * and the evaluation — the zeros above are measurements, not a deaf rig.
     */
    @Test
    void theSameCandidateWithAnAmpleFeeSliceIsBuiltAndTheCountersSeeIt() throws Exception {
        PlainRig rig = new PlainRig().wire(plainConfiguration(LIVE, LIVE, SMALL_MARGIN));

        rig.cycles(NOW, 1);

        LiquidationDecision decision = rig.onlyDecision();
        assertNotNull(decision.txHash(), "built: " + decision.detail());
        assertTrue(rig.probes.get() >= 1, "the oracle probe ran: " + rig.probes.get());
        assertTrue(rig.evaluations.get() >= 1, "the evaluation ran: " + rig.evaluations.get());
    }

    /**
     * (e) SHADOW still rehearses: the (c) candidate on a SHADOW node is built, priced and recorded — calls are
     * made — and vetoed MODE_NOT_LIVE with its transaction hash. The pre-build refusal is LIVE-only.
     */
    @Test
    void theSameUnprofitableCandidateOnAShadowNodeIsStillBuiltAndRecorded() throws Exception {
        PlainRig rig = new PlainRig().wire(plainConfiguration(SHADOW, LIVE, plainFeeSlice()));

        rig.cycles(NOW, 1);

        LiquidationDecision decision = rig.onlyDecision();
        assertEquals(LiquidationExecutor.SubmitVeto.MODE_NOT_LIVE.name(), decision.submitVeto(), decision.detail());
        assertEquals(LiquidationDecision.Outcome.UNPROFITABLE, decision.outcome(), decision.detail());
        assertNotNull(decision.txHash(), "the rehearsal produced a transaction");
        assertTrue(rig.probes.get() >= 1 && rig.evaluations.get() >= 1,
                "built: probes " + rig.probes.get() + ", evaluations " + rig.evaluations.get());
        assertEquals(0, rig.submissions.get(), "shadow never submits");
    }

    /** (e, per market) And a SHADOW market on a LIVE node rehearses the same way, vetoed MARKET_NOT_LIVE. */
    @Test
    void theSameUnprofitableCandidateInAShadowMarketIsStillBuiltAndRecorded() throws Exception {
        PlainRig rig = new PlainRig().wire(plainConfiguration(LIVE, SHADOW, plainFeeSlice()));

        rig.cycles(NOW, 1);

        LiquidationDecision decision = rig.onlyDecision();
        assertEquals(LiquidationExecutor.SubmitVeto.MARKET_NOT_LIVE.name(), decision.submitVeto(),
                decision.detail());
        assertNotNull(decision.txHash(), "the rehearsal produced a transaction");
        assertTrue(rig.probes.get() >= 1 && rig.evaluations.get() >= 1,
                "built: probes " + rig.probes.get() + ", evaluations " + rig.evaluations.get());
    }

    /** ignore-profit-check on: never refused before the build, whatever the fee slice says. */
    @Test
    void ignoreProfitCheckDisablesThePreBuildProfitabilityRefusal() throws Exception {
        AppConfig.LiquidationConfiguration configuration = plainConfiguration(LIVE, LIVE, plainFeeSlice());
        Field ignore = AppConfig.LiquidationConfiguration.class.getDeclaredField("ignoreProfitCheck");
        ignore.setAccessible(true);
        ignore.setBoolean(configuration, true);
        PlainRig rig = new PlainRig().wire(configuration);

        rig.cycles(NOW, 1);

        assertNotNull(rig.onlyDecision().txHash(), "built: " + rig.onlyDecision().detail());
        assertTrue(rig.evaluations.get() >= 1, "the evaluation ran");
    }

    /**
     * FAB-183-3 audit residue (b): {@code check-profitability=false} exempts the ABSOLUTE floor before the build
     * as it does after it. Node and market LIVE, no margin, the absolute floor ON the fee slice — the boundary
     * {@link #aCandidateWhoseFeeSliceEqualsTheAbsoluteFloorIsRefusedUnprofitableBeforeAnyCall} refuses with the
     * check on. With it off the candidate is NOT refused UNPROFITABLE before the build: it is evaluated. Whatever
     * the rig's post-build vetoes then say is not this test's question.
     */
    @Test
    void checkProfitabilityOffExemptsTheAbsoluteFloorFromThePreBuildRefusal() throws Exception {
        BigInteger feeSlice = plainFeeSlice();
        assertEquals(BigInteger.valueOf(25_000_000L), feeSlice, "fixture: the plain candidate's fee slice");
        var configuration = new AppConfig.LiquidationConfiguration(LIVE, 60, 120, 30, BigInteger.ZERO, 200, false,
                feeSlice, LiquidateTransactionBuilder.ReferenceScripts.none());
        configuration.setMarkets(List.of(market("lovelace", LIVE, AppConfig.LiquidationConfiguration.Action.ANTICIPATE)));
        PlainRig rig = new PlainRig().wire(configuration);

        rig.cycles(NOW, 1);

        LiquidationDecision decision = rig.onlyDecision();
        assertFalse(decision.outcome() == LiquidationDecision.Outcome.UNPROFITABLE && decision.txHash() == null,
                "not a pre-build UNPROFITABLE: " + decision.detail());
        assertTrue(rig.evaluations.get() >= 1, "the build was evaluated: " + decision.detail());
    }

    /**
     * (f, plain) The collateral feed's out-ref is unknown to the content source — a fresher price announced it
     * and this node has not stored its block yet. A WAIT: REFUSED ORACLE_INPUT_NOT_YET_VISIBLE on every block,
     * no ERROR, no wallet utxo dropped, and nothing evaluated.
     */
    @Test
    void aPlainCandidateWhoseOracleOutRefIsNotYetVisibleWaitsAtInfo() throws Exception {
        PlainRig rig = new PlainRig();
        rig.feedContentKnown = false;
        rig.wire(plainConfiguration(LIVE, LIVE, SMALL_MARGIN));

        rig.cycles(NOW, 2);

        assertNotYetVisibleOnEveryCycle(rig, 2, TX_ORACLE_NFT + "#0");
    }

    private static void assertNotYetVisibleOnEveryCycle(Rig rig, int cycles, String outRef) {
        List<LiquidationDecision> decisions = rig.decisions();
        assertEquals(cycles, decisions.size(), "one decision per cycle: " + decisions);
        for (LiquidationDecision decision : decisions) {
            assertEquals(LiquidationDecision.Outcome.REFUSED, decision.outcome(), decision.detail());
            assertEquals("ORACLE_INPUT_NOT_YET_VISIBLE", decision.reason(), decision.detail());
            assertTrue(decision.detail().contains(outRef), "names the out-ref: " + decision.detail());
            assertTrue(decision.detail().contains("retried on the next block"), decision.detail());
        }
        assertEquals(List.of(), rig.errors(), "a wait is not a fault");
        assertFalse(rig.droppedAWalletUtxo(), "nothing says a wallet utxo is spent: " + rig.events);
        assertEquals(0, rig.evaluations.get(), "nothing was evaluated");
        assertEquals(0, rig.submissions.get(), "nothing submitted");
        assertEquals(cycles, rig.events.stream().filter(e -> e.getLevel() == Level.INFO)
                        .filter(e -> e.getFormattedMessage().contains("retried on the next block")).count(),
                "INFO on every cycle: " + rig.events);
    }

    // ======================================================================================
    // CONVERT — the recorded mainnet d832b78e loan, a multisig collateral oracle, an indexed pool
    // ======================================================================================

    private static final Network MAINNET = Networks.mainnet();
    private static final Account MAINNET_ACCOUNT = new Account(MAINNET);
    private static final CardanoConverters MAINNET_CONVERTERS =
            ClasspathConversionsFactory.createConverters(NetworkType.MAINNET);

    private static final String CONFIG_POLICY_ID = "235b32040fe1177c03b1d34febc470440c6eaaa2228a9c1b0e375200";
    private static final String LM_CONFIG_POLICY_ID = "fb6ae2027358b4a0b62710eb95102d87fa13f66ecf55d8943699c492";
    private static final String CONFIG_ASSET_NAME = "706172616d6574657273";
    private static final String SMART_TOKENS_SPEND = "fca77bcce1e5e73c97a0bfa8c90f7cd2faff6fd6ed5b6fec1c04eefa";
    private static final String MS_POOL_POLICY = "f5808c2c990d86da54bfc97d89cee6efa20cd8461616359478d96b4c";
    private static final String MS_POOL_SPEND = "ea07b733d932129c378af627436e7cbc2ef0bf96e0036bb51b3bde6b";
    private static final String MS_ORDER_SPEND = "c3e28c36c3447315ba5a56f33da6a6ddc1770a876a8d9f0cb3a97c4c";
    private static final LoansContractRegistry MAINNET_REGISTRY = new LoansContractRegistry(CONFIG_POLICY_ID,
            LM_CONFIG_POLICY_ID, CONFIG_ASSET_NAME, SMART_TOKENS_SPEND, MS_POOL_POLICY, MS_POOL_SPEND,
            MS_ORDER_SPEND);

    private static final String CV_LOAN_TX = "d832b78e3d4a9ff99dfa8f238ae378b37dbd36b30efd24d68e5786f99786cf99";
    private static final String CV_LOAN_ID = "1b6fda505ea9b739e42b5871d274344af37c196ddb70619541a7d06d";
    private static final AssetType FLDT =
            new AssetType("577f0b1342f8f8f4aed3388b80a8535812950c7a892495c0ecdf0f1e", "0014df10464c4454");
    private static final long CV_COLLATERAL_AMOUNT = 100_000_000L;
    private static final String POOL_ADDRESS =
            "addr1z84q0denmyep98ph3tmzwsmw0j7zau9ljmsqx6a4rvaau66j2c79gy9l76sdg0xwhd7r0c0kna0tycz4y5s6mlenh8pq777e2a";

    private static final String CV_ORACLE_CREDENTIAL = "a0".repeat(28);
    private static final String CV_ORACLE_ADDRESS = scriptAddress(CV_ORACLE_CREDENTIAL, MAINNET);
    private static final TransactionInput CV_ORACLE_REF = new TransactionInput("9a".repeat(32), 0);
    private static final TransactionInput CV_ORACLE_SCRIPT_REF = new TransactionInput("9b".repeat(32), 0);

    private static final long CV_NOW = 1_790_000_000_000L;

    private static final Utxo CV_WALLET_A = LoanFixtures.adaUtxo("e5".repeat(32), 0, MAINNET_ACCOUNT.baseAddress(),
            200_000_000L);
    private static final Utxo CV_WALLET_B = LoanFixtures.adaUtxo("e0".repeat(32), 0, MAINNET_ACCOUNT.baseAddress(),
            150_000_000L);

    private static final class ConvertRig extends Rig {
        final UtxoRepository poolIndex = mock(UtxoRepository.class);
        OracleEntry collateralOracle;
        Utxo oracleUtxo;
        /** Whether the content hold knows the feed's out-ref (false: not yet visible to the node). */
        boolean feedContentKnown = true;

        ConvertRig() throws Exception {
            super();
            LoanDatum datum = new LoanDatumConverter().deserialize(
                    LoanFixtures.fixture("mainnet-loan-datum-d832b78e.hex"));
            collateralOracle = LoanFixtures.multisig(FLDT, datum.collateral().oracleTokenAsset(), CV_ORACLE_CREDENTIAL,
                    new OraclePriceFeed(OraclePriceFeed.Variant.AGGREGATED, FLDT, BigInteger.valueOf(15_000_000L),
                            BigInteger.valueOf(100_000_000L), CV_NOW - 60_000L, CV_NOW + 600_000L),
                    CV_ORACLE_REF, CV_ORACLE_SCRIPT_REF, List.of(new OracleSignature(0, "5a".repeat(64))));
            oracleUtxo = LoanFixtures.utxo(CV_ORACLE_REF.getTransactionId(), 0, CV_ORACLE_ADDRESS,
                    List.of(Amount.lovelace(BigInteger.valueOf(2_000_000L)),
                            LoanFixtures.token(collateralOracle.oracleToken(), 1)), null);
            answers(CV_ORACLE_ADDRESS, collateralOracle.oracleToken(), live(oracleUtxo));
        }

        ConvertRig wire(AppConfig.LiquidationConfiguration.Mode nodeMode,
                        AppConfig.LiquidationConfiguration.Mode marketMode) throws Exception {
            String loanDatumHex = LoanFixtures.fixture("mainnet-loan-datum-d832b78e.hex");
            String bondDatumHex = LoanFixtures.fixture("mainnet-lender-bond-datum-d832b78e.hex");
            LoanDatum datum = new LoanDatumConverter().deserialize(loanDatumHex);
            LenderManagerDatum bondDatum = new LenderManagerDatumConverter().deserialize(bondDatumHex);
            String loanAddress = scriptAddress(MAINNET_REGISTRY.getLoanSpendScriptHash(), MAINNET);
            String bondAddress = scriptAddress(MAINNET_REGISTRY.getLenderManagerSpendScriptHash(), MAINNET);
            Loan loan = new Loan(CV_LOAN_TX, 1, loanAddress, CV_LOAN_ID, BigInteger.valueOf(CV_COLLATERAL_AMOUNT),
                    BigInteger.valueOf(2_000_000L), datum);
            LenderBond bond = new LenderBond(CV_LOAN_TX, 3, bondAddress, CV_LOAN_ID, bondDatumHex, bondDatum);
            Utxo loanUtxo = LoanFixtures.utxo(CV_LOAN_TX, 1, loanAddress, List.of(
                    Amount.lovelace(BigInteger.valueOf(2_000_000L)),
                    Amount.asset(FLDT.toUnit(), BigInteger.valueOf(CV_COLLATERAL_AMOUNT)),
                    Amount.asset(MAINNET_REGISTRY.getLoanPolicyId() + CV_LOAN_ID, BigInteger.ONE)), loanDatumHex);
            Utxo bondUtxo = LoanFixtures.utxo(CV_LOAN_TX, 3, bondAddress, List.of(
                    Amount.lovelace(BigInteger.valueOf(2_000_000L)),
                    Amount.asset(MAINNET_REGISTRY.getLenderBondPolicyId() + CV_LOAN_ID, BigInteger.ONE)),
                    bondDatumHex);
            Utxo configUtxo = LoanFixtures.utxo("3d800e98a4da21dc9abcce30c145729406fef7db4d5cd3b4ecd6813aa228a75c", 0,
                    scriptAddress(CONFIG_POLICY_ID, MAINNET), List.of(Amount.lovelace(BigInteger.valueOf(5_000_000L)),
                            Amount.asset(CONFIG_POLICY_ID + CONFIG_ASSET_NAME, BigInteger.ONE)),
                    LoanFixtures.fixture("mainnet-config-datum-2026-10-01.hex"));
            Utxo lmConfigUtxo = LoanFixtures.utxo("ab3e3aafe7ea0e6fec24d9ac9249e01edb242dee55aeaa2399da097a21177620",
                    0, scriptAddress(LM_CONFIG_POLICY_ID, MAINNET),
                    List.of(Amount.lovelace(BigInteger.valueOf(5_000_000L)),
                            Amount.asset(LM_CONFIG_POLICY_ID + CONFIG_ASSET_NAME, BigInteger.ONE)),
                    LoanFixtures.fixture("mainnet-lm-config-datum-2026-10-01.hex"));
            LiquidationAssessment assessment = LiquidationAssessment.buildable(bond, loan, "convert pre-build fixture",
                    LoanFinance.remainingDebt(datum, CV_NOW), BigInteger.ZERO, false, BigInteger.valueOf(5_000_000L));

            // the six loans-v4 reference scripts a mainnet convert references, at synthetic coordinates
            List<PlutusScript> scripts = List.of(MAINNET_REGISTRY.getLoanScript(),
                    MAINNET_REGISTRY.getLoanSpendScript(), MAINNET_REGISTRY.getLenderManagerScript(),
                    MAINNET_REGISTRY.getLenderManagerSpendScript(), MAINNET_REGISTRY.getLoanClaimActionScript(),
                    MAINNET_REGISTRY.getLmLiquidateAndConvertActionScript());
            List<TransactionInput> at = new ArrayList<>();
            List<Utxo> universe = new ArrayList<>(List.of(loanUtxo, bondUtxo, configUtxo, lmConfigUtxo));
            if (feedContentKnown) {
                universe.add(oracleUtxo);
            }
            for (int i = 0; i < scripts.size(); i++) {
                String hash = HexUtil.encodeHexString(scripts.get(i).getScriptHash());
                TransactionInput input = new TransactionInput(String.format("a%d", i + 1).repeat(32), 0);
                at.add(input);
                universe.add(Utxo.builder().txHash(input.getTransactionId()).outputIndex(0)
                        .address(scriptAddress(hash, MAINNET))
                        .amount(List.of(Amount.lovelace(BigInteger.valueOf(20_000_000L))))
                        .referenceScriptHash(hash).build());
            }
            universe.add(Utxo.builder().txHash(CV_ORACLE_SCRIPT_REF.getTransactionId()).outputIndex(0)
                    .address(CV_ORACLE_ADDRESS).amount(List.of(Amount.lovelace(BigInteger.valueOf(20_000_000L))))
                    .referenceScriptHash(CV_ORACLE_CREDENTIAL).build());
            // FAB-185: role C, sized from the parameters the executor below reads, beside U.
            List<Utxo> walletUtxos = LoanFixtures.shapedWallet(EvalFixtures.protocolParams(),
                    MAINNET_ACCOUNT.baseAddress(), CV_WALLET_A, CV_WALLET_B);
            universe.addAll(walletUtxos);
            var references = new LiquidateTransactionBuilder.ReferenceScripts(at.get(0), at.get(1), at.get(2),
                    at.get(3), at.get(4), null, null, null, at.get(5));

            UtxoSupplier contentHold = LoanFixtures.utxoSupplier(universe);
            ScriptSupplier noScripts = hash -> Optional.empty();
            ProtocolParamsSupplier params = EvalFixtures.protocolParams();

            YaciConfig yaciConfig = new YaciConfig();
            OracleReferenceInputProbe probe = yaciConfig.oracleReferenceInputProbe(contentHold, backend);
            ConvertTransactionBuilder builder = yaciConfig.convertTransactionBuilder(MAINNET_REGISTRY,
                    network("mainnet"), contentHold, params, noScripts, backend, probe);

            var configuration = new AppConfig.LiquidationConfiguration(nodeMode, 60, 120, 30,
                    BigInteger.valueOf(1_500_000), 200, references);
            configuration.setMarkets(List.of(market("lovelace", marketMode,
                    AppConfig.LiquidationConfiguration.Action.CONVERT)));
            log = new LiquidationDecisionLog(configuration);
            when(poolIndex.findUnspentByOwnerPaymentCredential(eq(MS_POOL_SPEND), any()))
                    .thenReturn(Optional.of(List.of(adaFldtPool())));
            ConvertLiquidationRouter convertRouter = new ConvertLiquidationRouter(MAINNET_REGISTRY,
                    minswapConfigured(), configuration, new MinswapPoolResolver(poolIndex, MS_POOL_SPEND,
                    MS_POOL_POLICY), new ConvertEconomics(new AppConfig.ConvertConfiguration(), configuration,
                    network("mainnet")), builder, MAINNET_CONVERTERS, MAINNET);
            PayInAdvanceLiquidationRouter unusedPia = new PayInAdvanceLiquidationRouter(MAINNET_REGISTRY,
                    MAINNET_CONVERTERS, configuration, new LiquidatePayInAdvanceTransactionBuilder(MAINNET_REGISTRY,
                    MAINNET, contentHold, params));
            Map<String, Utxo> unspent = new LinkedHashMap<>();
            unspent.put(loan.utxoRef(), loanUtxo);
            unspent.put(bond.utxoRef(), bondUtxo);
            executor = new LiquidationExecutor(configuration, notSyncing(), new FakeAppUtxoService(walletUtxos),
                    MAINNET_ACCOUNT, new FakeScanner(assessment), new MapResolver(unspent, configUtxo, lmConfigUtxo),
                    new LiquidateTransactionBuilder(MAINNET_REGISTRY, MAINNET, MAINNET_CONVERTERS, contentHold, params),
                    unusedPia, convertRouter, MAINNET_REGISTRY, log,
                    new MarketCoverageReporter(new SimpleMeterRegistry()), new FixedOracles(collateralOracle),
                    network("mainnet"), params, MAINNET_CONVERTERS,
                    bytes -> {
                        submissions.incrementAndGet();
                        return Result.error("this rig never accepts a submission");
                    });
            executor.setSubmitClock(() -> CV_NOW);
            return this;
        }
    }

    /** (b) A DISABLED convert market on a LIVE node: the same — refused before the build, nothing spent. */
    @Test
    void aDisabledConvertMarketIsRefusedBeforeAnyBlockfrostCall() throws Exception {
        ConvertRig rig = new ConvertRig().wire(LIVE, DISABLED);

        rig.cycles(CV_NOW, 1);

        LiquidationDecision decision = rig.onlyDecision();
        assertEquals(LiquidationDecision.Outcome.REFUSED, decision.outcome(), decision.detail());
        assertEquals("MARKET_DISABLED", decision.reason(), decision.detail());
        assertEquals(LiquidationDecision.VARIANT_CONVERT_MINSWAP, decision.variant(), "the convert route's row");
        assertTrue(decision.detail().contains("refused before any build"), decision.detail());
        assertNull(decision.txHash(), "nothing was built");
        rig.assertNoBlockfrostCall();
        assertEquals(List.of(), rig.errors(), "a policy refusal is not a fault");
    }

    /** The convert control: the same LIVE market is built, and the counters see the probe and the evaluation. */
    @Test
    void theSameConvertCandidateInALiveMarketIsBuiltAndTheCountersSeeIt() throws Exception {
        ConvertRig rig = new ConvertRig().wire(SHADOW, LIVE);

        rig.cycles(CV_NOW, 1);

        assertTrue(rig.probes.get() >= 1, "the oracle probe ran: " + rig.decisions());
        assertTrue(rig.evaluations.get() >= 1, "the evaluation ran: " + rig.decisions());
    }

    /** (f, convert) The feed's out-ref unknown to the content source: a wait at INFO, as on the plain route. */
    @Test
    void aConvertCandidateWhoseOracleOutRefIsNotYetVisibleWaitsAtInfo() throws Exception {
        ConvertRig rig = new ConvertRig();
        rig.feedContentKnown = false;
        rig.wire(LIVE, LIVE);

        rig.cycles(CV_NOW, 2);

        assertNotYetVisibleOnEveryCycle(rig, 2, CV_ORACLE_REF.getTransactionId() + "#0");
    }

    // ======================================================================================
    // fixtures
    // ======================================================================================

    private static OracleEntry withProviderNft(OracleEntry base, AssetType providerNft) {
        return new OracleEntry(base.token(), base.oracleToken(), base.rewardAddress(), base.withdrawCredentialHash(),
                base.referenceInput(), base.referenceScript(), base.verificationKeys(), base.threshold(), base.feed(),
                base.signatures(), base.charlieProviderReferenceInput(), base.oracleVersion(), providerNft);
    }

    private static String scriptAddress(String scriptHash, Network network) {
        return AddressProvider.getEntAddress(Credential.fromScript(HexUtil.decodeHexString(scriptHash)), network)
                .getAddress();
    }

    private static AppConfig.Network network(String name) {
        var network = new AppConfig.Network();
        network.setNetworkForTest(name);
        return network;
    }

    private static BlockEventListener notSyncing() {
        BlockEventListener listener = new BlockEventListener(null);
        listener.getIsSyncing().set(false);
        return listener;
    }

    private static AppConfig.LiquidationConfiguration.Market market(String unit,
                                                                    AppConfig.LiquidationConfiguration.Mode mode,
                                                                    AppConfig.LiquidationConfiguration.Action action) {
        var market = new AppConfig.LiquidationConfiguration.Market();
        market.setUnit(unit);
        market.setMode(mode);
        market.setAction(action);
        market.setCap(BigInteger.valueOf(1_000_000_000_000L));
        return market;
    }

    private static AppConfig.LoansConfiguration minswapConfigured() {
        return new AppConfig.LoansConfiguration() {
            @Override
            public String getMinswapPoolAddress() {
                return POOL_ADDRESS;
            }

            @Override
            public String getMinswapPoolPolicyId() {
                return MS_POOL_POLICY;
            }

            @Override
            public String getMinswapPoolSpendScriptHash() {
                return MS_POOL_SPEND;
            }

            @Override
            public String getMinswapOrderSpendScriptHash() {
                return MS_ORDER_SPEND;
            }
        };
    }

    /** The live ada/FLDT pool's recorded datum at its recorded coordinate, amounts synthesised. */
    private static AddressUtxoEntity adaFldtPool() {
        var row = new AddressUtxoEntity();
        row.setTxHash("665195ca95aac79331ce9d83f2902849999e0f2ba98f663df37191ecda3d03c6");
        row.setOutputIndex(1);
        row.setOwnerAddr(POOL_ADDRESS);
        row.setOwnerPaymentCredential(MS_POOL_SPEND);
        row.setInlineDatum(LoanFixtures.fixture("mainnet-minswap-pool-ada-fldt.hex").trim());
        List<Amt> amounts = new ArrayList<>();
        for (Object[] unitAndQty : new Object[][]{{"lovelace", 1_692_342_884_761L},
                {MS_POOL_POLICY + ConvertTxEncoder.POOL_NFT_ASSET_NAME, 1L},
                {MS_POOL_POLICY + ConvertTxEncoder.computeLpAssetName(AssetType.ada(), FLDT), 1_000L},
                {FLDT.toUnit(), 7_596_442_927_398L}}) {
            var amt = new Amt();
            amt.setUnit((String) unitAndQty[0]);
            amt.setQuantity(BigInteger.valueOf((Long) unitAndQty[1]));
            amounts.add(amt);
        }
        row.setAmounts(amounts);
        return row;
    }

    private static final class FakeScanner extends LiquidationCandidateScanner {
        private final LiquidationAssessment assessment;

        FakeScanner(LiquidationAssessment assessment) {
            super(null, null, null);
            this.assessment = assessment;
        }

        @Override
        public Scan scan(long atTimeMillis) {
            return new Scan(List.of(assessment), new LoanService.Census(List.of(), 1, 0, 0));
        }
    }

    private static final class MapResolver extends LiquidationUtxoResolver {
        private final Map<String, Utxo> unspent;
        private final Utxo configUtxo;
        private final Utxo lmConfigUtxo;

        MapResolver(Map<String, Utxo> unspent, Utxo configUtxo, Utxo lmConfigUtxo) {
            super(null, null, null);
            this.unspent = unspent;
            this.configUtxo = configUtxo;
            this.lmConfigUtxo = lmConfigUtxo;
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
            return Optional.of(configUtxo);
        }

        @Override
        public Optional<Utxo> resolveLmConfigUtxo() {
            return Optional.of(lmConfigUtxo);
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
