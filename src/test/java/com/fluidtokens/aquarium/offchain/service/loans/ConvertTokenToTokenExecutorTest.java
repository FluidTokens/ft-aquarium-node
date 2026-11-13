package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.aiken.AikenTransactionEvaluator;
import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.ScriptSupplier;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.common.model.SlotConfigs;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.config.HashCheckedScriptSupplier;
import com.fluidtokens.aquarium.offchain.config.YaciConfig;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationDecision;
import com.fluidtokens.aquarium.offchain.model.loans.Loan;
import com.fluidtokens.aquarium.offchain.model.loans.MinswapPoolDatum;
import com.fluidtokens.aquarium.offchain.service.AppUtxoService;
import com.fluidtokens.aquarium.offchain.service.BlockEventListener;
import com.fluidtokens.aquarium.offchain.service.loans.TestOracleRig.Feed;
import com.fluidtokens.aquarium.offchain.service.loans.TestOracleRig.Leg;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.io.InputStream;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ⛔ FAB-223-2: the token→token convert through a REAL {@link LiquidationExecutor} cycle — the recorded mainnet
 * NIGHT→USDM loan of {@link ConvertTokenToTokenFixture}, and the recorded STRIKE→USDM loan of
 * {@link StrikeUsdmFixture} against its recorded, nearly empty USDM/STRIKE pool.
 * <p>
 * Every collaborator a convert reaches is the production one: the executor's full constructor with the convert
 * seam, {@code YaciConfig}'s router bean over a {@link MinswapPoolResolver} that reads both recorded pools from a
 * mocked index, {@link ConvertEconomics}, and the production {@link ConvertTransactionBuilder} — a Mockito spy that
 * calls the real {@code build} and keeps what it returns — whose evaluator is the real Aiken machine. One real
 * {@link FluidOracleClient} serves both rig legs, loaded from the recorded registry payload with each leg's
 * rewritten entry in place of its recorded one. Only the leaves are fixtures: the scan, the UTxO resolver, the
 * wallet listing, the chain position. The node runs in SHADOW, and its submitter fails the test if it is called.
 * <p>
 * Pinned: the executor's floor gate on the convert route ({@code record()}: fee slice − tx fee − min-ada, then the
 * margin), {@link ConvertEconomics}' refusal when the shared margin exceeds the convert's net, and the STRIKE/USDM
 * loan refused {@code POOL_TOO_THIN} before anything is built. The fee {@code F} is read off the transaction the
 * real builder returned, never off an evaluation result or a constant (CCL traps 8 and 13).
 * <p>
 * ⚠ The executor's convert-route floor does not charge the Minswap order's 4 ADA, which {@link ConvertEconomics}
 * does. That is pre-existing and pinned here as it is; whoever adds the overhead to the floor updates (a)'s detail.
 */
class ConvertTokenToTokenExecutorTest {

    /** Five seconds past the last committed block: the anchor is the block, T0. */
    private static final long NOW = T0 + 5_000L;
    /** The executor's window floor: the anchor (T0) less the 30 s backdate. Both feeds open before it. */
    private static final long BODY_FROM = T0 - 30_000L;
    private static final BigInteger MARGIN = BigInteger.valueOf(5_000_000L);
    private static final BigInteger ORDER_COST = BigInteger.valueOf(4_000_000L);
    private static final BigInteger DEX_FLOOR = BigInteger.valueOf(5_000_000L);
    private static final BigInteger FEE_SLICE = BigInteger.valueOf(20_000_000L);

    // ======================================================================================

    /**
     * (a) The executor's floor gate on the NIGHT→USDM convert: the fee slice is the 160 NIGHT fee priced through
     * the COLLATERAL feed (20,000,000), the body funds no min-ada beyond the loan's own 3 ADA, and the margin is
     * the shared 5,000,000.
     */
    @Test
    void theTokenToTokenConvertClearsBothGatesWithTheFiguresPinned() throws Exception {
        ConvertTokenToTokenFixture f = spec().build();
        Harness h = nightUsdm(f, MARGIN);

        h.executor.cycle(NOW);

        List<LiquidationDecision> decisions = h.log.newestFirst(10);
        assertEquals(1, decisions.size(), decisions.toString());
        LiquidationDecision decision = decisions.getFirst();
        assertEquals(LiquidationDecision.Outcome.WOULD_SUBMIT, decision.outcome(), decision.detail());
        assertEquals(LiquidationDecision.VARIANT_CONVERT_MINSWAP, decision.variant());

        Transaction built = h.builtOnce();
        BigInteger fee = built.getBody().getFee();
        // FAB-224-3: the gate now counts the order output's 4 ADA and the 2 ADA equity output, less the loan's own 3 ADA.
        String expected = "fee slice 20000000 lovelace - tx fee " + fee + " - min-ada 3000000 = floor "
                + BigInteger.valueOf(17_000_000L).subtract(fee) + "; - margin 5000000 = "
                + BigInteger.valueOf(12_000_000L).subtract(fee);
        assertTrue(decision.detail().contains(expected), "expected [" + expected + "] in: " + decision.detail());
        assertEquals(fee, decision.txFeeLovelace(), "the row's tx fee is the built body's");

        withdrawalIndex(built, f.collateralRig.scriptHash());
        withdrawalIndex(built, f.principalRig.scriptHash());
        assertEquals(9, built.getWitnessSet().getRedeemers().size(), "both oracle legs' withdrawals are redeemed");
        for (Redeemer redeemer : built.getWitnessSet().getRedeemers()) {
            assertTrue(redeemer.getExUnits().getMem().compareTo(PLACEHOLDER) > 0
                            && redeemer.getExUnits().getSteps().compareTo(PLACEHOLDER) > 0,
                    "CCL's placeholder ex-units on the built body (trap 8): " + redeemer);
        }
        assertEquals(0, h.submissions.get(), "SHADOW never reaches the submitter");

        assertEquals(new ShapeRow("convert", "token", "token", FEE_SLICE, BigInteger.valueOf(3_000_000L)),
                ShapeRow.of(decision, f.loan.collateral().isAda(), f.loan.principalAsset().isAda()));
    }

    /**
     * (b) The same convert with a shared margin of 20,000,000: the router's {@link ConvertEconomics} refuses it
     * {@code NET_BELOW_FLOOR} after the build — its net is the 20,000,000 fee value less the outlay, and the outlay
     * is the measured fee plus the order's 4 ADA, floored at the 5 ADA dex floor.
     */
    @Test
    void aMarginAboveTheConvertNetIsRefusedByConvertEconomicsWithItsFiguresPinned() throws Exception {
        ConvertTokenToTokenFixture f = spec().build();
        Harness h = nightUsdm(f, BigInteger.valueOf(20_000_000L));

        h.executor.cycle(NOW);

        List<LiquidationDecision> decisions = h.log.newestFirst(10);
        assertEquals(1, decisions.size(), decisions.toString());
        LiquidationDecision decision = decisions.getFirst();
        assertEquals(LiquidationDecision.Outcome.UNPROFITABLE, decision.outcome(), decision.detail());
        assertEquals("NET_BELOW_FLOOR", decision.reason(), decision.detail());

        BigInteger fee = h.builtOnce().getBody().getFee();
        BigInteger measured = fee.add(ORDER_COST);
        BigInteger outlay = measured.max(DEX_FLOOR);
        String expected = "convert refused as NET_BELOW_FLOOR: fee 160000000 of collateral is worth 20000000 "
                + "lovelace, outlay " + outlay + " (measured " + measured + ", dex floor 5000000), net "
                + FEE_SLICE.subtract(outlay) + " against a floor of 20000000";
        assertTrue(decision.detail().contains(expected), "expected [" + expected + "] in: " + decision.detail());
        assertEquals(0, h.submissions.get(), "SHADOW never reaches the submitter");
    }

    /**
     * (c) The recorded STRIKE→USDM loan {@code 8554c4d1…#0}: liquidatable with zero equity, so the whole
     * 2,359,820,932 STRIKE after the fee is swapped — and the recorded pool, holding 23,210 / 25,066 base units,
     * quotes about 23,209 USDM units against a debt of about 748 USDM. Refused {@code POOL_TOO_THIN} by the order
     * plan, before the builder is asked anything.
     */
    @Test
    void theStrikeUsdmLoanIsRefusedPoolTooThinBeforeAnyBuild() throws Exception {
        Feed strike = new Feed(BigInteger.valueOf(100_000_000L), DENOMINATOR, T0 - 300_000L, T0 + 1_500_000L);
        Feed usdm = new Feed(USDM_PRICE, DENOMINATOR, T0 - 300_000L, T0 + 1_500_000L);
        StrikeUsdmFixture.Legs legs = StrikeUsdmFixture.legs(strike, usdm);
        Loan loan = StrikeUsdmFixture.loan(0);
        LenderBond bond = StrikeUsdmFixture.bond(0);
        LiquidationAssessment assessment = LoanFixtures.assess(bond, loan, legs.principal().entry().feed(),
                legs.collateral().entry().feed(), BODY_FROM);

        // The figures the refusal rests on, independently of the router.
        assertTrue(assessment.buildable(), assessment.toString());
        assertEquals(BigInteger.ZERO, assessment.equity(), "underwater: equity floors to zero");
        assertEquals(BigInteger.valueOf(98_325_872L), assessment.liquidationFee(), "40‰ of 2,458,146,804 STRIKE");
        MinswapPoolDatum pool = new MinswapPoolDatumConverter().deserialize(
                LoanFixtures.fixture("mainnet-minswap-pool-usdm-strike.hex"));
        assertEquals(USDM, pool.assetA());
        assertEquals(StrikeUsdmFixture.STRIKE, pool.assetB());
        assertEquals(USDM_STRIKE_POOL_LP_ASSET_NAME, ConvertTxEncoder.computeLpAssetName(pool.assetA(), pool.assetB()),
                "the recorded LP asset name is the pair's");
        BigInteger swappable = loan.collateralAmount().subtract(assessment.liquidationFee());
        assertEquals(BigInteger.valueOf(2_359_820_932L), swappable);
        assertEquals(BigInteger.valueOf(23_209L), ConvertOrderPlan.constantProductOut(swappable, pool.reserveB(),
                pool.reserveA(), pool.baseFeeBNumerator()), "STRIKE is asset_b: b → a at fee_b");

        Account account = new Account(Networks.mainnet());
        List<Utxo> wallet = wallet(account);
        List<Utxo> universe = new ArrayList<>();
        universe.addAll(List.of(StrikeUsdmFixture.loanUtxo(0), StrikeUsdmFixture.bondUtxo(0),
                StrikeUsdmFixture.configUtxo(), StrikeUsdmFixture.lmConfigUtxo(), usdmStrikePoolUtxo()));
        universe.addAll(legs.collateral().utxos());
        universe.addAll(legs.principal().utxos());
        universe.addAll(referenceScriptUtxos());
        universe.addAll(wallet);
        Harness h = new Harness(account, wallet, MARGIN, assessment, StrikeUsdmFixture.loanUtxo(0),
                StrikeUsdmFixture.bondUtxo(0), StrikeUsdmFixture.configUtxo(), StrikeUsdmFixture.lmConfigUtxo(),
                universe, StrikeUsdmFixture.rawScripts(legs), legs.collateral(), legs.principal());

        h.executor.cycle(NOW);

        List<LiquidationDecision> decisions = h.log.newestFirst(10);
        assertEquals(1, decisions.size(), decisions.toString());
        LiquidationDecision decision = decisions.getFirst();
        assertEquals(LiquidationDecision.Outcome.REFUSED, decision.outcome(), decision.detail());
        assertEquals("POOL_TOO_THIN", decision.reason(), decision.detail());
        assertTrue(decision.detail().contains("would return about 23209"), decision.detail());
        verify(h.builder, never()).build(any());
        assertTrue(h.built.isEmpty(), "nothing was built");
        assertEquals(0, h.submissions.get(), "nor submitted");
    }

    // ======================================================================================
    // The harness
    // ======================================================================================

    /** The executor around the NIGHT→USDM fixture, with its own wallet in place of the fixture's bot wallet. */
    private static Harness nightUsdm(ConvertTokenToTokenFixture f, BigInteger margin) throws Exception {
        Account account = new Account(Networks.mainnet());
        List<Utxo> wallet = wallet(account);
        Loan loan = new Loan(LOAN_TX, LOAN_IX, LOAN_ADDRESS, LOAN_ID, COLLATERAL_AMOUNT, LOAN_LOVELACE, f.loan);
        LenderBond bond = new LenderBond(LOAN_TX, BOND_IX, BOND_ADDRESS, LOAN_ID,
                LoanFixtures.fixture("mainnet-lender-bond-datum-328e44f9.hex"), f.bond);
        LiquidationAssessment assessment = LoanFixtures.assess(bond, loan, f.principalLeg.entry().feed(),
                f.collateralLeg.entry().feed(), BODY_FROM);
        assertTrue(assessment.buildable(), assessment.toString());
        assertEquals(BigInteger.valueOf(160_000_000L), assessment.liquidationFee(), "40‰ of 4,000 NIGHT");
        return new Harness(account, wallet, margin, assessment, f.request.loanUtxo(), f.request.bondUtxo(),
                f.request.configUtxo(), f.request.lmConfigUtxo(), f.universeWith(wallet), f.scripts(),
                f.collateralLeg, f.principalLeg);
    }

    /** {@link LoanFixtures#shapedWallet}: a 50-ADA U and role C, at {@code account}'s base address. */
    private static List<Utxo> wallet(Account account) {
        return LoanFixtures.shapedWallet(EvalFixtures.protocolParams(), account.baseAddress(),
                LoanFixtures.adaUtxo("e5".repeat(32), 0, account.baseAddress(), 50_000_000L));
    }

    private static final class Harness {
        final AtomicInteger submissions = new AtomicInteger();
        /** Every transaction the real {@code build} returned. */
        final List<Transaction> built = new CopyOnWriteArrayList<>();
        final ConvertTransactionBuilder builder;
        final LiquidationDecisionLog log;
        final LiquidationExecutor executor;

        Harness(Account account, List<Utxo> wallet, BigInteger margin, LiquidationAssessment assessment,
                Utxo loanUtxo, Utxo bondUtxo, Utxo configUtxo, Utxo lmConfigUtxo, List<Utxo> universe,
                ScriptSupplier scripts, Leg collateralLeg, Leg principalLeg) throws Exception {
            var params = EvalFixtures.protocolParams();
            var utxos = LoanFixtures.utxoSupplier(universe);

            // ONE real client, both legs' rewritten entries in place of their recorded ones.
            FluidOracleClient oracles = oracleClient(collateralLeg, principalLeg);
            assertEquals(Optional.of(collateralLeg.entry()),
                    oracles.findEntryByOracleToken(collateralLeg.entry().oracleToken()), "the collateral leg");
            assertEquals(Optional.of(principalLeg.entry()),
                    oracles.findEntryByOracleToken(principalLeg.entry().oracleToken()), "the principal leg");

            // The production builder, its evaluator the real Aiken machine over this universe.
            AikenTransactionEvaluator aiken = new AikenTransactionEvaluator(utxos, params, scripts,
                    SlotConfigs.mainnet());
            builder = spy(new ConvertTransactionBuilder(REGISTRY, NETWORK, utxos, params,
                    new HashCheckedScriptSupplier(scripts), aiken));
            doAnswer(invocation -> {
                Transaction transaction = (Transaction) invocation.callRealMethod();
                built.add(transaction);
                return transaction;
            }).when(builder).build(any());

            var configuration = new AppConfig.LiquidationConfiguration(AppConfig.LiquidationConfiguration.Mode.SHADOW,
                    60, 120, 30, margin, 200, referenceScriptSlots());
            configuration.setMarkets(List.of());
            log = new LiquidationDecisionLog(configuration);

            UtxoRepository poolIndex = mock(UtxoRepository.class);
            when(poolIndex.findUnspentByOwnerPaymentCredential(eq(MS_POOL_SPEND), any()))
                    .thenReturn(Optional.of(List.of(poolIndexRow(nightUsdmPoolUtxo()),
                            poolIndexRow(usdmStrikePoolUtxo()))));
            AppConfig.Network mainnet = network();
            ConvertLiquidationRouter router = new YaciConfig().convertLiquidationRouter(REGISTRY, minswapConfigured(),
                    new MinswapPoolResolver(poolIndex, MS_POOL_SPEND, MS_POOL_POLICY),
                    new ConvertEconomics(new AppConfig.ConvertConfiguration(true), configuration, mainnet), builder,
                    configuration, CONVERTERS, mainnet);
            PayInAdvanceLiquidationRouter unusedPia = new PayInAdvanceLiquidationRouter(REGISTRY, CONVERTERS,
                    configuration, new LiquidatePayInAdvanceTransactionBuilder(REGISTRY, NETWORK, utxos, params));

            BlockEventListener chain = new BlockEventListener(null);
            chain.getIsSyncing().set(false);
            chain.getLastCommittedSlot().set(CONVERTERS.time().toSlot(
                    LocalDateTime.ofEpochSecond(T0 / 1000L, 0, ZoneOffset.UTC)));

            executor = new LiquidationExecutor(configuration, chain, new FakeAppUtxoService(wallet), account,
                    new FakeScanner(assessment), new FakeResolver(loanUtxo, bondUtxo, configUtxo, lmConfigUtxo),
                    new LiquidateTransactionBuilder(REGISTRY, NETWORK, CONVERTERS, utxos, params),
                    unusedPia, router, REGISTRY, log, new MarketCoverageReporter(new SimpleMeterRegistry()),
                    new FixedOracles(oracles), mainnet, params, CONVERTERS,
                    bytes -> {
                        submissions.incrementAndGet();
                        throw new AssertionError("a SHADOW cycle reached the submitter");
                    });
        }

        /** The one transaction the real builder returned, deserialised: the body the executor priced. */
        Transaction builtOnce() throws Exception {
            assertEquals(1, built.size(), "exactly one build");
            Transaction reread = Transaction.deserialize(built.getFirst().serialize());
            assertEquals(built.getFirst().getBody().getFee(), reread.getBody().getFee());
            return reread;
        }
    }

    /**
     * The recorded registry payload with each leg's {@code recordedEntry} replaced by its {@code rewrittenEntry},
     * loaded into one production client.
     */
    private static FluidOracleClient oracleClient(Leg... legs) throws Exception {
        ArrayNode payload;
        try (InputStream in = ConvertTokenToTokenExecutorTest.class.getResourceAsStream(REGISTRY_PAYLOAD)) {
            payload = (ArrayNode) new ObjectMapper().readTree(in);
        }
        for (Leg leg : legs) {
            int replaced = 0;
            for (int i = 0; i < payload.size(); i++) {
                if (payload.get(i).equals(leg.recordedEntry())) {
                    payload.set(i, leg.rewrittenEntry().deepCopy());
                    replaced++;
                }
            }
            assertEquals(1, replaced, "the recorded entry of " + leg.entry().oracleToken().toUnit());
        }
        FluidOracleClient client = new FluidOracleClient("http://unused.invalid");
        client.load(payload);
        return client;
    }

    /** One row of the per-shape floor table: the route, each leg's kind, the fee slice and the min-ada funded. */
    private record ShapeRow(String route, String collateral, String principal, BigInteger feeSlice,
                            BigInteger minAda) {

        private static final Pattern MIN_ADA = Pattern.compile(" - min-ada (\\d+)");

        static ShapeRow of(LiquidationDecision decision, boolean adaCollateral, boolean adaPrincipal) {
            String route = switch (decision.variant()) {
                case LiquidationDecision.VARIANT_CONVERT_MINSWAP -> "convert";
                case LiquidationDecision.VARIANT_CONVERT -> "pay-in-advance";
                default -> "plain";
            };
            Matcher minAda = MIN_ADA.matcher(decision.detail());
            assertTrue(minAda.find(), "no min-ada term in: " + decision.detail());
            return new ShapeRow(route, adaCollateral ? "ada" : "token", adaPrincipal ? "ada" : "token",
                    decision.expectedFeeLovelace(), new BigInteger(minAda.group(1)));
        }
    }

    private static AppConfig.Network network() {
        var network = new AppConfig.Network();
        network.setNetworkForTest("mainnet");
        return network;
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

    // ---- private copies of OracleProbeRoutesProductionTest's leaves --------------------------------

    private static final class FakeScanner extends LiquidationCandidateScanner {
        private final List<LiquidationAssessment> assessments;

        FakeScanner(LiquidationAssessment... assessments) {
            super(null, null, null);
            this.assessments = List.of(assessments);
        }

        @Override
        public Scan scan(long atTimeMillis) {
            return new Scan(assessments, new LoanService.Census(List.of(), assessments.size(), 0, 0));
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

    private static final class FixedOracles implements ObjectProvider<FluidOracleClient> {
        private final FluidOracleClient client;

        FixedOracles(FluidOracleClient client) {
            this.client = client;
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
