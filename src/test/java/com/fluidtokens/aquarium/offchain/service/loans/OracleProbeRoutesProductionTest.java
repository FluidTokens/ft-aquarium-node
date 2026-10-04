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
import com.fluidtokens.aquarium.offchain.service.AppUtxoService;
import com.fluidtokens.aquarium.offchain.service.BlockEventListener;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.cardanofoundation.conversions.CardanoConverters;
import org.cardanofoundation.conversions.ClasspathConversionsFactory;
import org.cardanofoundation.conversions.domain.NetworkType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * ⛔ FAB-138 T3b: the oracle probe on the two other routes that reference an oracle — PAY-IN-ADVANCE and
 * CONVERT — through the PRODUCTION builders and the executor's cycle. The sibling of
 * {@link LiquidateOracleProbeProductionTest} (T3a, the plain route); sibling parity is the rule (T-043).
 *
 * <p>Each builder here is {@code YaciConfig}'s own bean method — the one Spring calls — handed the probe
 * bean, over a mocked {@link BFBackendService}: its {@code UtxoService} answers the probe and its
 * {@code TransactionService} is the evaluator the bean's lambda calls, so this test sees exactly which
 * Blockfrost reads a build makes and in what order. Only the leaves are fixtures.
 *
 * <p>What is asserted is the FAILURE MODE ({@code ccl-transaction-building-traps} §19), through each
 * route's own router and the executor's own catches for that route: a spent feed arrives as a REFUSED
 * decision and an ERROR naming the out-ref, never reaches the evaluator (where a spent reference input
 * comes back as an empty {@code ScriptFailures} that {@code LiquidationExecutor.spentInputEffect} reads as
 * a spent WALLET input, §13), and leaves the wallet list alone.
 */
class OracleProbeRoutesProductionTest {

    private static final String DROPPED = "dropped wallet utxo";

    // ======================================================================================
    // the shared rig: the mocked backend, the counters, the log capture
    // ======================================================================================

    private abstract static class Rig {
        final UtxoService utxoService = mock(UtxoService.class);
        final TransactionService transactionService = mock(TransactionService.class);
        final BFBackendService backend = mock(BFBackendService.class);
        final AtomicInteger evaluations = new AtomicInteger();
        final AtomicInteger submissions = new AtomicInteger();
        final List<ILoggingEvent> events = new ArrayList<>();
        LiquidationExecutor executor;
        LiquidationDecisionLog log;

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

        void answers(String address, AssetType nft, Result<List<Utxo>> answer) throws Exception {
            when(utxoService.getUtxos(address, nft.toUnit(), 100, 1)).thenReturn(answer);
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

        List<ILoggingEvent> errorsContaining(String text) {
            return events.stream().filter(e -> e.getLevel() == Level.ERROR)
                    .filter(e -> e.getFormattedMessage().contains(text)).toList();
        }

        boolean droppedAWalletUtxo() {
            return events.stream().anyMatch(e -> e.getFormattedMessage().contains(DROPPED));
        }

        List<LiquidationDecision> decisions() {
            return log.newestFirst(10);
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

    private static String ref(TransactionInput input) {
        return input.getTransactionId() + "#" + input.getIndex();
    }

    /** Every refused decision names {@code outRef} and {@code nft}, with an ERROR naming it every cycle. */
    private static void assertRefusedAtErrorEveryCycle(Rig rig, int cycles, TransactionInput outRef, AssetType nft) {
        List<LiquidationDecision> decisions = rig.decisions();
        assertEquals(cycles, decisions.size(), "one decision per cycle — no hold between them: " + decisions);
        for (LiquidationDecision decision : decisions) {
            assertEquals(LiquidationDecision.Outcome.REFUSED, decision.outcome(), decision.detail());
            assertTrue(decision.detail().contains(ref(outRef)), "the detail names the out-ref: " + decision.detail());
            assertTrue(decision.detail().contains(nft.toUnit()), "the detail names the NFT: " + decision.detail());
        }
        assertEquals(cycles, rig.errorsContaining(ref(outRef)).size(),
                "an ERROR naming the out-ref on EVERY cycle: " + rig.events);
        assertEquals(0, rig.evaluations.get(), "a refused probe must never reach the evaluator");
        assertEquals(0, rig.submissions.get(), "nor the wire");
        assertFalse(rig.droppedAWalletUtxo(),
                "a probe refusal is not a spent WALLET input — no wallet utxo may be dropped: " + rig.events);
    }

    // ======================================================================================
    // PAY-IN-ADVANCE — PayInAdvanceLiquidationRouterTest's token-principal scenario (two oracle legs)
    // ======================================================================================

    private static final LoansContractRegistry PREVIEW_REGISTRY = LoanFixtures.registry();
    private static final Network PREVIEW = Networks.preview();
    private static final Account PREVIEW_ACCOUNT = new Account(LoanFixtures.NETWORK);

    private static final String PIA_LOAN_TX = "f855d1b4cae6e1ec6db5aac9ef8038f53927e60004693729ce27d8273199aea1";
    private static final String PIA_LOAN_ID = "1d391e2258a62aeeae1275f2b31df80560e76732b266b2ab63c62e22";
    private static final String PIA_LOAN_DATUM_HEX =
            "d8799f001a01ab3f001b000001a01e60ee00001901cb00d8799f4040ffd8799f4040ff0000d87b9f1864"
                    + "187d1864d87980ffd87b9f181c05ff0000d879805821504f4f4c00183f8ba4d1e645b1e26e9caf5"
                    + "6f802b129b50d833689727c920abe11d8799f581c0b77d150c275bd0a600633e4be7d09f83c4b9f"
                    + "00981e22ac9c9d3f62d8799f490014df1074464c4454ffd8799f581c9a2ec5c92daccbb269611a9"
                    + "eae7a40f9788d3f9c0229661b6234286f49000de1406f766f3633ffffff";
    /** {@code shouldLiquidationConvertToPrincipal == True}, {@code liquidationFeePerMille == 50}. */
    private static final String PIA_BOND_DATUM_HEX =
            "d8799fd8799f581cea1bb1ccd33aeb9e02516c2eb50adbaa63d7b7538b03c96908bfc934ffd8799fd879"
                    + "9fd8799f581c1c5621a0d3f7ee5041ece1c8f41a9f611ab4bca268923c21b6ca8dc3ffffffd87a80"
                    + "1832581d00183f8ba4d1e645b1e26e9caf56f802b129b50d833689727c920abe11d8799f4040ff"
                    + "ff";
    private static final String PIA_LOAN_ADDRESS =
            "addr_test1zzrr2mm7vnwzsnn8eqsqf62dgf84sr3z2rq2xnne5a7mr0y788t0nqjduhey4swhxfp7h42thj"
                    + "hhvnjkmcgaps3ahx5qxanp9j";
    private static final String PIA_BOND_ADDRESS =
            "addr_test1zr3s95d7aq2zhm597lnk76pengtsk2s52jkpnl7ejfen95cu2cs6p5lhaegyrm8per6p48mpr2"
                    + "6tegngjg7zrdk23hps7h96kk";
    private static final AssetType PIA_COLLATERAL =
            new AssetType("0b77d150c275bd0a600633e4be7d09f83c4b9f00981e22ac9c9d3f62", "0014df1074464c4454");
    private static final long PIA_COLLATERAL_AMOUNT = 100_000_000L;

    // the collateral leg: the tFLDT Charli3 oracle
    private static final AssetType PIA_ORACLE_NFT =
            new AssetType("9a2ec5c92daccbb269611a9eae7a40f9788d3f9c0229661b6234286f", "000de1406f766f3633");
    private static final AssetType PIA_C3_NFT =
            new AssetType("decfbd6bdd5c3eb1915564d414fe099db8c08d5e18037562cc7bb4b3", "4f7261636c6546656564");
    private static final String PIA_ORACLE_SCRIPT_HASH = "402c984d6397f508ced0674646bb2fcd67f593c5b79d91e1e5c0b124";
    private static final String PIA_ORACLE_ADDRESS = "addr_test1wpqzexzdvwtl2zxw6pn5v34m9lxk0avnckmemy0puhqtzfqw4jw8q";
    private static final String PIA_C3_ADDRESS = "addr_test1wzgy7cu7mnnjau2qn5th8932tr27f83tfgusm60sklwppmgh6re39";
    private static final TransactionInput PIA_ORACLE_REF =
            new TransactionInput("cc4721afdf4721f8f179b3afddb8e096805c0fad16afe54687d7368d12bd769c", 0);
    private static final TransactionInput PIA_ORACLE_SCRIPT_REF =
            new TransactionInput("ba34f9e5bbf6d148b67208d53f11be9253de0d9df81190bcf034438d3838218f", 0);
    private static final TransactionInput PIA_C3_REF =
            new TransactionInput("a17501465ed79dbc6cb25e2e99edbc421b1baa9d100b6780da89770702b235a5", 0);

    // the principal leg: a token, priced by its own Charli3 oracle with its own provider
    private static final AssetType TOKEN_PRINCIPAL = new AssetType("cc".repeat(28), "0014df105553444d");
    private static final AssetType PRINCIPAL_ORACLE_NFT = new AssetType("8a".repeat(28), "8a".repeat(10));
    private static final AssetType PRINCIPAL_C3_NFT = new AssetType("8f".repeat(28), "4f7261636c6546656564");
    private static final String PRINCIPAL_ORACLE_CREDENTIAL = "8b".repeat(28);
    private static final String PRINCIPAL_ORACLE_ADDRESS = scriptAddress("8b".repeat(28), PREVIEW);
    private static final String PRINCIPAL_C3_ADDRESS = scriptAddress("8c".repeat(28), PREVIEW);
    private static final TransactionInput PRINCIPAL_ORACLE_REF = LoanFixtures.input("fe".repeat(32), 0);
    private static final TransactionInput PRINCIPAL_ORACLE_SCRIPT_REF = LoanFixtures.input("8d".repeat(32), 0);
    private static final TransactionInput PRINCIPAL_C3_REF = LoanFixtures.input("8e".repeat(32), 0);

    private static final long PIA_NOW = 1_787_216_064_000L + 3_600_000L;
    private static final long PIA_FEED_FROM = PIA_NOW - 35_555L;
    private static final long PIA_FEED_TO = PIA_FEED_FROM + 600_000L;

    private static final Utxo PIA_CONFIG = LoanFixtures.syntheticLatestConfigUtxo("f1".repeat(32), 0);
    private static final Utxo PIA_LM_CONFIG = LoanFixtures.syntheticLatestLmConfigUtxo("f2".repeat(32), 0);
    /** Ample principal token for the lender payout and ample ada for the fee and the min-ada rider. */
    private static final Utxo TOKEN_WALLET = LoanFixtures.utxo("e1".repeat(32), 0, PREVIEW_ACCOUNT.baseAddress(),
            List.of(Amount.lovelace(BigInteger.valueOf(60_000_000L)),
                    Amount.asset(TOKEN_PRINCIPAL.toUnit(), BigInteger.valueOf(50_000_000L))), null);
    /** Ada only — the collateral candidate; its tx id sorts BELOW the token utxo's. */
    private static final Utxo ADA_WALLET = LoanFixtures.adaUtxo("e0".repeat(32), 0, PREVIEW_ACCOUNT.baseAddress(),
            60_000_000L);

    private static final Utxo PIA_ORACLE_UTXO = LoanFixtures.utxo(PIA_ORACLE_REF.getTransactionId(),
            PIA_ORACLE_REF.getIndex(), PIA_ORACLE_ADDRESS, List.of(Amount.lovelace(BigInteger.valueOf(1_038_710L)),
                    LoanFixtures.token(PIA_ORACLE_NFT, 1)), null);
    private static final Utxo PIA_C3_UTXO = LoanFixtures.utxo(PIA_C3_REF.getTransactionId(), PIA_C3_REF.getIndex(),
            PIA_C3_ADDRESS, List.of(Amount.lovelace(BigInteger.valueOf(2_000_000L)),
                    LoanFixtures.token(PIA_C3_NFT, 1)), "d8799fd87b9fa3001a000528f30100021b000001a47d6fbc38ffff");
    private static final Utxo PRINCIPAL_ORACLE_UTXO = LoanFixtures.utxo(PRINCIPAL_ORACLE_REF.getTransactionId(),
            PRINCIPAL_ORACLE_REF.getIndex(), PRINCIPAL_ORACLE_ADDRESS,
            List.of(Amount.lovelace(BigInteger.valueOf(1_038_710L)), LoanFixtures.token(PRINCIPAL_ORACLE_NFT, 1)),
            null);
    private static final Utxo PRINCIPAL_C3_UTXO = LoanFixtures.utxo(PRINCIPAL_C3_REF.getTransactionId(),
            PRINCIPAL_C3_REF.getIndex(), PRINCIPAL_C3_ADDRESS,
            List.of(Amount.lovelace(BigInteger.valueOf(2_000_000L)), LoanFixtures.token(PRINCIPAL_C3_NFT, 1)), null);

    private static final class PayInAdvanceRig extends Rig {
        LiquidatePayInAdvanceTransactionBuilder builder;
        OracleReferenceInputProbe probe;

        PayInAdvanceRig() throws Exception {
            super();
        }

        /** Every one of the four oracle out-refs answers live. */
        PayInAdvanceRig allLive() throws Exception {
            answers(PIA_ORACLE_ADDRESS, PIA_ORACLE_NFT, live(PIA_ORACLE_UTXO));
            answers(PIA_C3_ADDRESS, PIA_C3_NFT, live(PIA_C3_UTXO));
            answers(PRINCIPAL_ORACLE_ADDRESS, PRINCIPAL_ORACLE_NFT, live(PRINCIPAL_ORACLE_UTXO));
            answers(PRINCIPAL_C3_ADDRESS, PRINCIPAL_C3_NFT, live(PRINCIPAL_C3_UTXO));
            return this;
        }

        PayInAdvanceRig wire(AppConfig.LiquidationConfiguration.Mode mode, List<Utxo> walletUtxos) {
            return wire(mode, walletUtxos, piaLoanDatum(), piaCollateralOracle(), piaPrincipalOracle());
        }

        /** The same scenario with a chosen loan datum and oracle registry (FAB-135 T4 amendment 3). */
        PayInAdvanceRig wire(AppConfig.LiquidationConfiguration.Mode mode, List<Utxo> walletUtxos, LoanDatum datum,
                             OracleEntry... oracles) {
            Loan loan = new Loan(PIA_LOAN_TX, 1, PIA_LOAN_ADDRESS, PIA_LOAN_ID,
                    BigInteger.valueOf(PIA_COLLATERAL_AMOUNT), BigInteger.valueOf(3_000_000L), datum);
            LenderBond bond = new LenderBond(PIA_LOAN_TX, 3, PIA_BOND_ADDRESS, PIA_LOAN_ID, PIA_BOND_DATUM_HEX,
                    new LenderManagerDatumConverter().deserialize(PIA_BOND_DATUM_HEX));
            Utxo loanUtxo = LoanFixtures.utxo(PIA_LOAN_TX, 1, PIA_LOAN_ADDRESS, List.of(
                    Amount.lovelace(BigInteger.valueOf(3_000_000L)),
                    Amount.asset(PIA_COLLATERAL.toUnit(), BigInteger.valueOf(PIA_COLLATERAL_AMOUNT)),
                    Amount.asset(PREVIEW_REGISTRY.getLoanPolicyId() + PIA_LOAN_ID, BigInteger.ONE)),
                    PIA_LOAN_DATUM_HEX);
            Utxo bondUtxo = LoanFixtures.utxo(PIA_LOAN_TX, 3, PIA_BOND_ADDRESS, List.of(
                    Amount.lovelace(BigInteger.valueOf(1_810_200L)),
                    Amount.asset(PREVIEW_REGISTRY.getLenderBondPolicyId() + PIA_LOAN_ID, BigInteger.ONE)),
                    PIA_BOND_DATUM_HEX);
            LiquidationAssessment assessment = LiquidationAssessment.buildable(bond, loan, "pia probe fixture",
                    BigInteger.valueOf(28_000_147L), BigInteger.valueOf(8_919_184L), false,
                    BigInteger.valueOf(5_000_000L));

            List<Utxo> universe = new ArrayList<>(List.of(PIA_CONFIG, PIA_LM_CONFIG, loanUtxo, bondUtxo,
                    PIA_ORACLE_UTXO, PIA_C3_UTXO, PRINCIPAL_ORACLE_UTXO, PRINCIPAL_C3_UTXO));
            universe.add(Utxo.builder().txHash(PIA_ORACLE_SCRIPT_REF.getTransactionId())
                    .outputIndex(PIA_ORACLE_SCRIPT_REF.getIndex()).address(PIA_ORACLE_ADDRESS)
                    .amount(List.of(Amount.lovelace(BigInteger.valueOf(40_000_000L))))
                    .referenceScriptHash(PIA_ORACLE_SCRIPT_HASH).build());
            universe.addAll(walletUtxos);
            UtxoSupplier contentHold = LoanFixtures.utxoSupplier(universe);
            ScriptSupplier noScripts = hash -> Optional.empty();

            YaciConfig yaciConfig = new YaciConfig();
            probe = yaciConfig.oracleReferenceInputProbe(contentHold, backend);
            builder = yaciConfig.liquidatePayInAdvanceTransactionBuilder(PREVIEW_REGISTRY, network("preview"),
                    contentHold, EvalFixtures.protocolParams(), noScripts, backend, probe);

            AppConfig.LiquidationConfiguration configuration = liquidationConfiguration(mode,
                    LiquidateTransactionBuilder.ReferenceScripts.none(),
                    market("lovelace", AppConfig.LiquidationConfiguration.Action.ANTICIPATE),
                    market(TOKEN_PRINCIPAL.toUnit(), AppConfig.LiquidationConfiguration.Action.ANTICIPATE));
            log = new LiquidationDecisionLog(configuration);
            PayInAdvanceLiquidationRouter router = new PayInAdvanceLiquidationRouter(PREVIEW_REGISTRY,
                    LoanFixtures.converters(), configuration, builder);
            executor = new LiquidationExecutor(configuration, notSyncing(), new FakeAppUtxoService(walletUtxos),
                    PREVIEW_ACCOUNT, new FakeScanner(assessment),
                    new FakeResolver(loanUtxo, bondUtxo, PIA_CONFIG, PIA_LM_CONFIG),
                    new LiquidateTransactionBuilder(PREVIEW_REGISTRY, LoanFixtures.NETWORK, LoanFixtures.converters(),
                            contentHold, EvalFixtures.protocolParams()),
                    router, null, PREVIEW_REGISTRY, log, new MarketCoverageReporter(new SimpleMeterRegistry()),
                    new FixedOracles(oracles), network("preview"),
                    EvalFixtures.protocolParams(), LoanFixtures.converters(),
                    bytes -> {
                        submissions.incrementAndGet();
                        return Result.error("this rig never accepts a submission");
                    });
            return this;
        }

        void verifyEachOutRefProbed(int times) throws Exception {
            verify(utxoService, times(times)).getUtxos(PIA_ORACLE_ADDRESS, PIA_ORACLE_NFT.toUnit(), 100, 1);
            verify(utxoService, times(times)).getUtxos(PIA_C3_ADDRESS, PIA_C3_NFT.toUnit(), 100, 1);
            verify(utxoService, times(times)).getUtxos(PRINCIPAL_ORACLE_ADDRESS, PRINCIPAL_ORACLE_NFT.toUnit(), 100, 1);
            verify(utxoService, times(times)).getUtxos(PRINCIPAL_C3_ADDRESS, PRINCIPAL_C3_NFT.toUnit(), 100, 1);
        }
    }

    private static LoanDatum piaLoanDatum() {
        LoanDatum ada = new LoanDatumConverter().deserialize(PIA_LOAN_DATUM_HEX);
        return new LoanDatum(ada.doneRecasts(), ada.principalAmount(), ada.lendDate(),
                ada.repaidInstallments(), ada.interestRate(), ada.totalInstallments(), TOKEN_PRINCIPAL,
                PRINCIPAL_ORACLE_NFT, ada.installmentPeriod(), ada.initialGracePeriod(),
                ada.liquidationMode(), ada.repaymentMode(), ada.repaymentTimeWindow(),
                ada.penaltyFeeForLateRepayment(), ada.repaymentReceipts(), ada.originId(), ada.collateral());
    }

    private static OracleEntry piaCollateralOracle() {
        return withProviderNft(LoanFixtures.charli3(PIA_COLLATERAL, PIA_ORACLE_NFT, PIA_ORACLE_SCRIPT_HASH,
                OraclePriceFeed.priceDataCharlie(PIA_COLLATERAL, BigInteger.valueOf(338163),
                        BigInteger.valueOf(1_000_000), PIA_FEED_FROM, PIA_FEED_TO),
                PIA_ORACLE_REF, PIA_ORACLE_SCRIPT_REF, PIA_C3_REF), PIA_C3_NFT);
    }

    private static OracleEntry piaPrincipalOracle() {
        return withProviderNft(LoanFixtures.charli3(TOKEN_PRINCIPAL, PRINCIPAL_ORACLE_NFT, PRINCIPAL_ORACLE_CREDENTIAL,
                OraclePriceFeed.priceDataCharlie(TOKEN_PRINCIPAL, BigInteger.ONE, BigInteger.ONE,
                        PIA_FEED_FROM, PIA_FEED_TO),
                PRINCIPAL_ORACLE_REF, PRINCIPAL_ORACLE_SCRIPT_REF, PRINCIPAL_C3_REF), PRINCIPAL_C3_NFT);
    }

    /** (1) PIA: a spent collateral feed (404) refuses at ERROR every cycle, before evaluation, wallet kept. */
    @Test
    void payInAdvanceASpentCollateralFeedIsRefusedAtErrorEveryCycleBeforeEvaluation() throws Exception {
        PayInAdvanceRig rig = new PayInAdvanceRig().allLive();
        rig.answers(PIA_ORACLE_ADDRESS, PIA_ORACLE_NFT, notFound());
        rig.wire(AppConfig.LiquidationConfiguration.Mode.LIVE, List.of(ADA_WALLET, TOKEN_WALLET));

        rig.cycles(PIA_NOW, 2);

        assertRefusedAtErrorEveryCycle(rig, 2, PIA_ORACLE_REF, PIA_ORACLE_NFT);
        verify(rig.utxoService, never()).getTxOutput(anyString(), anyInt());
    }

    /** (1) and (4) PIA: the PRINCIPAL leg is probed too — its spent feed refuses the same way. */
    @Test
    void payInAdvanceASpentPrincipalFeedIsRefusedAtErrorEveryCycleBeforeEvaluation() throws Exception {
        PayInAdvanceRig rig = new PayInAdvanceRig().allLive();
        rig.answers(PRINCIPAL_ORACLE_ADDRESS, PRINCIPAL_ORACLE_NFT, notFound());
        rig.wire(AppConfig.LiquidationConfiguration.Mode.LIVE, List.of(ADA_WALLET, TOKEN_WALLET));

        rig.cycles(PIA_NOW, 2);

        assertRefusedAtErrorEveryCycle(rig, 2, PRINCIPAL_ORACLE_REF, PRINCIPAL_ORACLE_NFT);
    }

    /**
     * (2) and (4) PIA, live: the build proceeds to evaluation exactly as before, and each of the two legs'
     * out-refs — feed and Charli3 provider, collateral and principal — is asked exactly once per build. Run
     * in both wallet-list orders, because the fixture supplier returns list order while production sorts.
     * The scenario is the router test's, priced 1:1 for the WIRING, so the executor's post-build margin
     * verdict on it is UNPROFITABLE — a verdict about a BUILT, priced transaction, which is the point.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void payInAdvanceLiveBuildsAsBeforeWithEachLegsOutRefsProbedOnce(boolean reversed) throws Exception {
        List<Utxo> wallet = reversed ? List.of(TOKEN_WALLET, ADA_WALLET) : List.of(ADA_WALLET, TOKEN_WALLET);
        PayInAdvanceRig rig = new PayInAdvanceRig().allLive()
                .wire(AppConfig.LiquidationConfiguration.Mode.SHADOW, wallet);

        rig.cycles(PIA_NOW, 1);

        List<LiquidationDecision> decisions = rig.decisions();
        assertEquals(1, decisions.size(), decisions.toString());
        LiquidationDecision decision = decisions.getFirst();
        assertEquals(LiquidationDecision.Outcome.UNPROFITABLE, decision.outcome(), decision.detail());
        assertTrue(decision.detail().contains("tx fee"), "a verdict on the built body: " + decision.detail());
        assertEquals(1, rig.evaluations.get(), "the build is priced exactly once, as before the probe");
        rig.verifyEachOutRefProbed(1);
        verify(rig.utxoService, never()).getTxOutput(anyString(), anyInt());
        verifyNoMoreInteractions(rig.utxoService);
        assertEquals(0, rig.submissions.get(), "shadow never submits");
    }

    /** (3) PIA: SHADOW probes too, and refuses on a spent provider. */
    @Test
    void payInAdvanceAShadowBuildIsProbedAndRefusedOnASpentProvider() throws Exception {
        PayInAdvanceRig rig = new PayInAdvanceRig().allLive();
        rig.answers(PRINCIPAL_C3_ADDRESS, PRINCIPAL_C3_NFT, notFound());
        rig.wire(AppConfig.LiquidationConfiguration.Mode.SHADOW, List.of(ADA_WALLET, TOKEN_WALLET));

        rig.cycles(PIA_NOW, 1);

        assertRefusedAtErrorEveryCycle(rig, 1, PRINCIPAL_C3_REF, PRINCIPAL_C3_NFT);
    }

    /**
     * FAB-135 T4 amendment 3 (a), PIA with an ADA principal through the bean-wired, probed builder: an ada
     * principal has no oracle entry and adds no reference input, so only the collateral leg's feed and Charli3
     * provider are probed — each once — and the build is evaluated exactly once. A probe handed
     * {@code List.of(oracle, principalOracle)} unconditionally would meet a null principal oracle here.
     */
    @Test
    void payInAdvanceAnAdaPrincipalProbesOnlyTheCollateralLegAndIsEvaluatedOnce() throws Exception {
        // an ada principal repays the lender in ada: a second, larger ada-only utxo funds that payout
        Utxo adaFunding = LoanFixtures.adaUtxo("e2".repeat(32), 0, PREVIEW_ACCOUNT.baseAddress(), 100_000_000L);
        PayInAdvanceRig rig = new PayInAdvanceRig().allLive().wire(AppConfig.LiquidationConfiguration.Mode.SHADOW,
                List.of(ADA_WALLET, adaFunding), new LoanDatumConverter().deserialize(PIA_LOAN_DATUM_HEX),
                piaCollateralOracle());

        rig.cycles(PIA_NOW, 1);

        List<LiquidationDecision> decisions = rig.decisions();
        assertEquals(1, decisions.size(), decisions.toString());
        assertEquals(1, rig.evaluations.get(), "an ada-principal build is priced exactly once: " + decisions);
        verify(rig.utxoService, times(1)).getUtxos(PIA_ORACLE_ADDRESS, PIA_ORACLE_NFT.toUnit(), 100, 1);
        verify(rig.utxoService, times(1)).getUtxos(PIA_C3_ADDRESS, PIA_C3_NFT.toUnit(), 100, 1);
        verify(rig.utxoService, never()).getTxOutput(anyString(), anyInt());
        verifyNoMoreInteractions(rig.utxoService);
        assertEquals(0, rig.submissions.get(), "shadow never submits");
    }

    /**
     * FAB-135 T4 amendment 3 (b), the PIA analogue: a principal feed whose window has ALREADY closed is
     * refused by the builder's V3 window wall — a cheap, local refusal — and makes no Blockfrost call at all.
     * The probe runs only after every cheap refusal.
     */
    @Test
    void payInAdvanceAPrincipalFeedWindowRefusalMakesNoProviderCall() throws Exception {
        OracleEntry expiredPrincipal = withProviderNft(LoanFixtures.charli3(TOKEN_PRINCIPAL, PRINCIPAL_ORACLE_NFT,
                PRINCIPAL_ORACLE_CREDENTIAL, OraclePriceFeed.priceDataCharlie(TOKEN_PRINCIPAL, BigInteger.ONE,
                        BigInteger.ONE, PIA_FEED_FROM - 1_200_000L, PIA_FEED_FROM - 600_000L),
                PRINCIPAL_ORACLE_REF, PRINCIPAL_ORACLE_SCRIPT_REF, PRINCIPAL_C3_REF), PRINCIPAL_C3_NFT);
        PayInAdvanceRig rig = new PayInAdvanceRig().allLive().wire(AppConfig.LiquidationConfiguration.Mode.SHADOW,
                List.of(ADA_WALLET, TOKEN_WALLET), piaLoanDatum(), piaCollateralOracle(), expiredPrincipal);

        rig.cycles(PIA_NOW, 1);

        List<LiquidationDecision> decisions = rig.decisions();
        assertEquals(1, decisions.size(), decisions.toString());
        assertTrue(decisions.getFirst().detail().contains("principal oracle feed window"),
                "the builder's own window wall refused it: " + decisions.getFirst().detail());
        assertEquals(0, rig.evaluations.get());
        verify(rig.utxoService, never()).getUtxos(anyString(), anyString(), anyInt(), anyInt());
        verifyNoMoreInteractions(rig.utxoService);
    }

    // ======================================================================================
    // CONVERT — the recorded mainnet d832b78e loan, a Charli3 collateral oracle, an indexed pool
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

    /** The provider NFT of the Charli3 variant of the collateral oracle (case 5). */
    private static final AssetType CV_C3_NFT = new AssetType("d0".repeat(28), "4f7261636c6546656564");
    private static final String CV_ORACLE_CREDENTIAL = "a0".repeat(28);
    private static final String CV_ORACLE_ADDRESS = scriptAddress(CV_ORACLE_CREDENTIAL, MAINNET);
    private static final String CV_C3_ADDRESS = scriptAddress("a1".repeat(28), MAINNET);
    private static final TransactionInput CV_ORACLE_REF = new TransactionInput("9a".repeat(32), 0);
    private static final TransactionInput CV_ORACLE_SCRIPT_REF = new TransactionInput("9b".repeat(32), 0);
    private static final TransactionInput CV_C3_REF = new TransactionInput("9c".repeat(32), 0);

    private static final long CV_NOW = 1_790_000_000_000L;

    private static final Utxo CV_WALLET_A = LoanFixtures.adaUtxo("e5".repeat(32), 0, MAINNET_ACCOUNT.baseAddress(),
            200_000_000L);
    private static final Utxo CV_WALLET_B = LoanFixtures.adaUtxo("e0".repeat(32), 0, MAINNET_ACCOUNT.baseAddress(),
            150_000_000L);

    private static final class ConvertRig extends Rig {
        final UtxoRepository poolIndex = mock(UtxoRepository.class);
        ConvertTransactionBuilder builder;
        OracleReferenceInputProbe probe;
        Utxo oracleUtxo;
        Utxo providerUtxo;
        OracleEntry collateralOracle;
        /** The loan datum's {@code repaymentReceipts} flag, forced true (FAB-135 T4 amendment 3). */
        boolean repaymentReceipts;

        ConvertRig() throws Exception {
            super();
        }

        ConvertRig allLive() throws Exception {
            answers(CV_ORACLE_ADDRESS, collateralOracle.oracleToken(), live(oracleUtxo));
            answers(CV_C3_ADDRESS, CV_C3_NFT, live(providerUtxo));
            return this;
        }

        /** The mainnet shape: a MULTISIG collateral feed, which has no Charli3 provider. */
        ConvertRig prepare() throws Exception {
            return prepare(false);
        }

        ConvertRig prepare(boolean charli3) throws Exception {
            LoanDatum datum = new LoanDatumConverter().deserialize(
                    LoanFixtures.fixture("mainnet-loan-datum-d832b78e.hex"));
            // priced below the pool, so the loan is underwater and equity floors to zero
            long from = CV_NOW - 60_000L;
            long to = CV_NOW + 600_000L;
            collateralOracle = charli3
                    ? withProviderNft(LoanFixtures.charli3(FLDT, datum.collateral().oracleTokenAsset(),
                            CV_ORACLE_CREDENTIAL, OraclePriceFeed.priceDataCharlie(FLDT,
                                    BigInteger.valueOf(15_000_000L), BigInteger.valueOf(100_000_000L), from, to),
                            CV_ORACLE_REF, CV_ORACLE_SCRIPT_REF, CV_C3_REF), CV_C3_NFT)
                    : LoanFixtures.multisig(FLDT, datum.collateral().oracleTokenAsset(), CV_ORACLE_CREDENTIAL,
                            new OraclePriceFeed(OraclePriceFeed.Variant.AGGREGATED, FLDT,
                                    BigInteger.valueOf(15_000_000L), BigInteger.valueOf(100_000_000L), from, to),
                            CV_ORACLE_REF, CV_ORACLE_SCRIPT_REF,
                            List.of(new OracleSignature(0, "5a".repeat(64))));
            oracleUtxo = LoanFixtures.utxo(CV_ORACLE_REF.getTransactionId(), 0, CV_ORACLE_ADDRESS,
                    List.of(Amount.lovelace(BigInteger.valueOf(2_000_000L)),
                            LoanFixtures.token(collateralOracle.oracleToken(), 1)), null);
            providerUtxo = LoanFixtures.utxo(CV_C3_REF.getTransactionId(), 0, CV_C3_ADDRESS,
                    List.of(Amount.lovelace(BigInteger.valueOf(2_000_000L)), LoanFixtures.token(CV_C3_NFT, 1)), null);
            return this;
        }

        ConvertRig wire(AppConfig.LiquidationConfiguration.Mode mode, List<Utxo> walletUtxos) throws Exception {
            String loanDatumHex = LoanFixtures.fixture("mainnet-loan-datum-d832b78e.hex");
            String bondDatumHex = LoanFixtures.fixture("mainnet-lender-bond-datum-d832b78e.hex");
            LoanDatum recorded = new LoanDatumConverter().deserialize(loanDatumHex);
            LoanDatum datum = !repaymentReceipts ? recorded : new LoanDatum(recorded.doneRecasts(),
                    recorded.principalAmount(), recorded.lendDate(), recorded.repaidInstallments(),
                    recorded.interestRate(), recorded.totalInstallments(), recorded.principalAsset(),
                    recorded.principalOracleAsset(), recorded.installmentPeriod(), recorded.initialGracePeriod(),
                    recorded.liquidationMode(), recorded.repaymentMode(), recorded.repaymentTimeWindow(),
                    recorded.penaltyFeeForLateRepayment(), true, recorded.originId(), recorded.collateral());
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
            LiquidationAssessment assessment = LiquidationAssessment.buildable(bond, loan, "convert probe fixture",
                    LoanFinance.remainingDebt(datum, CV_NOW), BigInteger.ZERO, false, BigInteger.valueOf(5_000_000L));

            // the six loans-v4 reference scripts a mainnet convert references, at synthetic coordinates
            List<PlutusScript> scripts = List.of(MAINNET_REGISTRY.getLoanScript(),
                    MAINNET_REGISTRY.getLoanSpendScript(), MAINNET_REGISTRY.getLenderManagerScript(),
                    MAINNET_REGISTRY.getLenderManagerSpendScript(), MAINNET_REGISTRY.getLoanClaimActionScript(),
                    MAINNET_REGISTRY.getLmLiquidateAndConvertActionScript());
            List<TransactionInput> at = new ArrayList<>();
            List<Utxo> universe = new ArrayList<>(List.of(loanUtxo, bondUtxo, configUtxo, lmConfigUtxo, oracleUtxo,
                    providerUtxo));
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
            universe.addAll(walletUtxos);
            var references = new LiquidateTransactionBuilder.ReferenceScripts(at.get(0), at.get(1), at.get(2),
                    at.get(3), at.get(4), null, null, null, at.get(5));

            UtxoSupplier contentHold = LoanFixtures.utxoSupplier(universe);
            ScriptSupplier noScripts = hash -> Optional.empty();
            ProtocolParamsSupplier params = EvalFixtures.protocolParams();

            YaciConfig yaciConfig = new YaciConfig();
            probe = yaciConfig.oracleReferenceInputProbe(contentHold, backend);
            builder = yaciConfig.convertTransactionBuilder(MAINNET_REGISTRY, network("mainnet"), contentHold, params,
                    noScripts, backend, probe);

            AppConfig.LiquidationConfiguration configuration = liquidationConfiguration(mode, references,
                    market("lovelace", AppConfig.LiquidationConfiguration.Action.CONVERT));
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
            executor = new LiquidationExecutor(configuration, notSyncing(), new FakeAppUtxoService(walletUtxos),
                    MAINNET_ACCOUNT, new FakeScanner(assessment),
                    new FakeResolver(loanUtxo, bondUtxo, configUtxo, lmConfigUtxo),
                    new LiquidateTransactionBuilder(MAINNET_REGISTRY, MAINNET, MAINNET_CONVERTERS, contentHold, params),
                    unusedPia, convertRouter, MAINNET_REGISTRY, log,
                    new MarketCoverageReporter(new SimpleMeterRegistry()), new FixedOracles(collateralOracle),
                    network("mainnet"), params, MAINNET_CONVERTERS,
                    bytes -> {
                        submissions.incrementAndGet();
                        return Result.error("this rig never accepts a submission");
                    });
            return this;
        }
    }

    /** (1) convert: a spent feed (404) refuses at ERROR every cycle, before evaluation, wallet kept. */
    @Test
    void convertASpentFeedIsRefusedAtErrorEveryCycleBeforeEvaluation() throws Exception {
        ConvertRig rig = new ConvertRig().prepare().allLive();
        rig.answers(CV_ORACLE_ADDRESS, rig.collateralOracle.oracleToken(), notFound());
        rig.wire(AppConfig.LiquidationConfiguration.Mode.LIVE, List.of(CV_WALLET_A, CV_WALLET_B));

        rig.cycles(CV_NOW, 2);

        assertRefusedAtErrorEveryCycle(rig, 2, CV_ORACLE_REF, rig.collateralOracle.oracleToken());
        verify(rig.utxoService, never()).getTxOutput(anyString(), anyInt());
    }

    /**
     * (2) convert, live (the mainnet multisig shape): the build proceeds to evaluation exactly as before
     * (pass 2 only — the layout probe is never evaluated), and the feed is asked exactly once per build
     * although the builder assembles the body twice. Both wallet-list orders.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void convertLiveBuildsAsBeforeWithTheFeedProbedOncePerBuild(boolean reversed) throws Exception {
        List<Utxo> wallet = reversed ? List.of(CV_WALLET_B, CV_WALLET_A) : List.of(CV_WALLET_A, CV_WALLET_B);
        ConvertRig rig = new ConvertRig().prepare().allLive().wire(AppConfig.LiquidationConfiguration.Mode.SHADOW,
                wallet);

        rig.cycles(CV_NOW, 1);

        List<LiquidationDecision> decisions = rig.decisions();
        assertEquals(1, decisions.size(), decisions.toString());
        LiquidationDecision decision = decisions.getFirst();
        // the fixture's collateral is priced far below the pool, so ConvertEconomics' verdict on the BUILT
        // body's measured fee is NET_BELOW_FLOOR — a verdict that exists only once the build was priced
        assertEquals(LiquidationDecision.Outcome.UNPROFITABLE, decision.outcome(), decision.detail());
        assertTrue(decision.detail().contains("measured"), "a verdict on the built body: " + decision.detail());
        assertEquals(1, rig.evaluations.get(), "the convert is priced exactly once (pass 2), as before the probe");
        verify(rig.utxoService, times(1)).getUtxos(CV_ORACLE_ADDRESS, rig.collateralOracle.oracleToken().toUnit(),
                100, 1);
        verify(rig.utxoService, never()).getTxOutput(anyString(), anyInt());
        verifyNoMoreInteractions(rig.utxoService);
        assertEquals(0, rig.submissions.get(), "shadow never submits");
    }

    /** (3) convert: SHADOW probes too, and refuses on a spent feed. */
    @Test
    void convertAShadowBuildIsProbedAndRefusedOnASpentFeed() throws Exception {
        ConvertRig rig = new ConvertRig().prepare().allLive();
        rig.answers(CV_ORACLE_ADDRESS, rig.collateralOracle.oracleToken(), notFound());
        rig.wire(AppConfig.LiquidationConfiguration.Mode.SHADOW, List.of(CV_WALLET_A));

        rig.cycles(CV_NOW, 1);

        assertRefusedAtErrorEveryCycle(rig, 1, CV_ORACLE_REF, rig.collateralOracle.oracleToken());
    }

    /**
     * (5) convert with a Charli3 collateral oracle, live: the feed and the provider are each asked exactly
     * once per build, the provider with the c3 NFT. ⚠ The build then stops where it stopped before this
     * slice: the convert builder encodes its oracle redeemer with the signed-feed overload, which refuses a
     * PRICE_DATA_CHARLIE feed — pinned here so the probe is seen to run AHEAD of that refusal.
     */
    @Test
    void convertWithAC3CollateralOracleProbesTheFeedAndTheProviderWithTheC3Nft() throws Exception {
        ConvertRig rig = new ConvertRig().prepare(true).allLive().wire(AppConfig.LiquidationConfiguration.Mode.SHADOW,
                List.of(CV_WALLET_A));

        rig.cycles(CV_NOW, 1);

        verify(rig.utxoService, times(1)).getUtxos(CV_ORACLE_ADDRESS, rig.collateralOracle.oracleToken().toUnit(),
                100, 1);
        verify(rig.utxoService, times(1)).getUtxos(CV_C3_ADDRESS, CV_C3_NFT.toUnit(), 100, 1);
        verifyNoMoreInteractions(rig.utxoService);
        LiquidationDecision decision = rig.decisions().getFirst();
        assertTrue(decision.detail().contains("PRICE_DATA_CHARLIE"), decision.detail());
        assertFalse(rig.droppedAWalletUtxo(), rig.events.toString());
    }

    /** (3) and (5) convert: SHADOW probes too, and a spent c3 provider refuses — named with the c3 NFT. */
    @Test
    void convertAShadowBuildIsProbedAndASpentC3ProviderRefuses() throws Exception {
        ConvertRig rig = new ConvertRig().prepare(true).allLive();
        rig.answers(CV_C3_ADDRESS, CV_C3_NFT, notFound());
        rig.wire(AppConfig.LiquidationConfiguration.Mode.SHADOW, List.of(CV_WALLET_A));

        rig.cycles(CV_NOW, 1);

        assertRefusedAtErrorEveryCycle(rig, 1, CV_C3_REF, CV_C3_NFT);
        verify(rig.utxoService).getUtxos(CV_C3_ADDRESS, CV_C3_NFT.toUnit(), 100, 1);
        verify(rig.utxoService, never()).getUtxos(CV_C3_ADDRESS, rig.collateralOracle.oracleToken().toUnit(), 100, 1);
    }

    /**
     * FAB-135 T4 amendment 3 (b): a convert the builder refuses CHEAPLY — {@code repaymentReceipts = true}, a
     * shape it does not model — makes ZERO Blockfrost calls. The probe runs after every cheap refusal, so a
     * candidate the bot would refuse anyway costs nothing.
     */
    @Test
    void convertARepaymentReceiptsRefusalMakesNoProviderCall() throws Exception {
        ConvertRig rig = new ConvertRig().prepare().allLive();
        rig.repaymentReceipts = true;
        rig.wire(AppConfig.LiquidationConfiguration.Mode.SHADOW, List.of(CV_WALLET_A, CV_WALLET_B));

        rig.cycles(CV_NOW, 1);

        List<LiquidationDecision> decisions = rig.decisions();
        assertEquals(1, decisions.size(), decisions.toString());
        assertTrue(decisions.getFirst().detail().contains("REPAYMENT_RECEIPTS"),
                "the builder's own repayment-receipts refusal: " + decisions.getFirst().detail());
        assertEquals(0, rig.evaluations.get());
        verify(rig.utxoService, never()).getUtxos(anyString(), anyString(), anyInt(), anyInt());
        verifyNoMoreInteractions(rig.utxoService);
    }

    /** The rigs really are the beans: each builder they drive holds the probe the container would inject. */
    @Test
    void theRigsDriveProbedBuilders() throws Exception {
        PayInAdvanceRig pia = new PayInAdvanceRig().wire(AppConfig.LiquidationConfiguration.Mode.SHADOW,
                List.of(ADA_WALLET));
        assertSame(pia.probe, fieldOf(pia.builder, "oracleReferenceInputProbe"));
        ConvertRig convert = new ConvertRig().prepare().wire(AppConfig.LiquidationConfiguration.Mode.SHADOW,
                List.of(CV_WALLET_A));
        assertSame(convert.probe, fieldOf(convert.builder, "oracleReferenceInputProbe"));
    }

    // ======================================================================================
    // fixtures
    // ======================================================================================

    private static Object fieldOf(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

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
                                                                    AppConfig.LiquidationConfiguration.Action action) {
        var market = new AppConfig.LiquidationConfiguration.Market();
        market.setUnit(unit);
        market.setMode(AppConfig.LiquidationConfiguration.Mode.LIVE);
        market.setAction(action);
        market.setCap(BigInteger.valueOf(1_000_000_000_000L));
        return market;
    }

    private static AppConfig.LiquidationConfiguration liquidationConfiguration(
            AppConfig.LiquidationConfiguration.Mode mode, LiquidateTransactionBuilder.ReferenceScripts references,
            AppConfig.LiquidationConfiguration.Market... markets) {
        var configuration = new AppConfig.LiquidationConfiguration(mode, 60, 120, 30,
                BigInteger.valueOf(1_500_000), 200, references);
        configuration.setMarkets(List.of(markets));
        return configuration;
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

    private static final class FakeResolver extends LiquidationUtxoResolver {
        private final Utxo loanUtxo;
        private final Utxo bondUtxo;
        private final Utxo configUtxo;
        private final Utxo lmConfigUtxo;

        FakeResolver(Utxo loanUtxo, Utxo bondUtxo, Utxo configUtxo, Utxo lmConfigUtxo) {
            super(null, null, null);
            this.loanUtxo = loanUtxo;
            this.bondUtxo = bondUtxo;
            this.configUtxo = configUtxo;
            this.lmConfigUtxo = lmConfigUtxo;
        }

        @Override
        public Optional<Utxo> resolveLoanUtxo(Loan loan) {
            return Optional.of(loanUtxo);
        }

        @Override
        public Optional<Utxo> resolveBondUtxo(LenderBond bond) {
            return Optional.of(bondUtxo);
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
