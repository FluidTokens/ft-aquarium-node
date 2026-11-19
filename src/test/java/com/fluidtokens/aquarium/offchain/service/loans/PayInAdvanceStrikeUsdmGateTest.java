package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration.Action;
import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration.Mode;
import com.fluidtokens.aquarium.offchain.config.HashCheckedScriptSupplier;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationDecision;
import com.fluidtokens.aquarium.offchain.model.loans.Loan;
import com.fluidtokens.aquarium.offchain.service.AppUtxoService;
import com.fluidtokens.aquarium.offchain.service.BlockEventListener;
import com.fluidtokens.aquarium.offchain.service.loans.TestOracleRig.Leg;
import com.fluidtokens.aquarium.offchain.service.wallet.WalletRoles;
import com.fluidtokens.aquarium.offchain.util.LedgerCeilings;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.cardanofoundation.conversions.CardanoConverters;
import org.cardanofoundation.conversions.ClasspathConversionsFactory;
import org.cardanofoundation.conversions.domain.NetworkType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.io.InputStream;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static com.fluidtokens.aquarium.offchain.service.loans.StrikeUsdmFixture.NETWORK;
import static com.fluidtokens.aquarium.offchain.service.loans.StrikeUsdmFixture.REGISTRY;
import static com.fluidtokens.aquarium.offchain.service.loans.StrikeUsdmFixture.REGISTRY_PAYLOAD;
import static com.fluidtokens.aquarium.offchain.service.loans.StrikeUsdmFixture.STRIKE;
import static com.fluidtokens.aquarium.offchain.service.loans.StrikeUsdmFixture.T0;
import static com.fluidtokens.aquarium.offchain.service.loans.StrikeUsdmFixture.USDM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ FAB-225-2: both profitability checks on the token-principal pay-in-advance, through a REAL
 * {@link LiquidationExecutor} cycle on the recorded mainnet STRIKE→USDM loan {@code 8554c4d1…#0} of
 * {@link StrikeUsdmFixture}, priced by two rig oracles (STRIKE at 1.4 ADA, USDM at 4 ADA).
 * <p>
 * Every collaborator the candidate reaches is the production one: the executor's 17-argument constructor, the real
 * {@link PayInAdvanceLiquidationRouter}, and {@link LiquidatePayInAdvanceTransactionBuilder}'s production
 * constructor over the real Aiken machine. One real {@link FluidOracleClient}, loaded from the recorded registry
 * payload with both legs' rewritten entries in place of their recorded ones, prices the market gate and the
 * executor's REPLACED bracket. Only the leaves are fakes — the scan, the UTxO resolver, the wallet listing, the
 * oracle provider and the submitter — and none of them computes economics. The node runs in SHADOW, and its
 * submitter fails the test if it is called. Every body is read off {@link LiquidationDecision#txCborHex()}, the
 * bytes the executor priced.
 *
 * <h2>The floor of this shape: pay-in-advance × token collateral × token principal</h2>
 * {@code record()} REPLACES the fee-slice floor with the real trade: {@code acquired − outlay − tx fee − min-ada}.
 * <table>
 *   <caption>Each term, where it is read and why</caption>
 *   <tr><th>term</th><th>read from</th><th>why</th></tr>
 *   <tr><td>acquired</td><td>{@code collateralAmount − equity} = 2,348,788,420 STRIKE, priced through the
 *       collateral leg, floor-rounded (3,288,303,788 lovelace)</td><td>the bot takes the whole collateral less the
 *       borrower's equity, the fee slice included; an earned amount, so it is never rounded up</td></tr>
 *   <tr><td>outlay</td><td>the payout the built body pays the lender (787,661,892 USDM), priced through the
 *       principal leg at floor + 1 (3,150,647,569 lovelace)</td><td>a cost, so it is biased up, never
 *       flattering the bot</td></tr>
 *   <tr><td>tx fee</td><td>the built body's fee</td><td>never an estimate: the body is what is submitted
 *       (CCL traps 8 and 13)</td></tr>
 *   <tr><td>min-ada</td><td>Σ coin(asset-manager outputs) − the loan UTxO's 3,000,000 + coin(the bot output
 *       carrying STRIKE) = 3,305,770 − 3,000,000 + 1,163,700 = 1,469,470</td><td>the loan's own ADA covers part of
 *       the asset-manager outputs' min-ADA and the bot funds the rest; the rider is locked with the acquired STRIKE
 *       (CCL trap 6)</td></tr>
 * </table>
 * CCL's withdrawal dummy and the change (the token output's leftover USDM) also land at the bot's address, but they
 * come straight back to the bot and are not costs, so they are excluded (CCL trap 1); the rider is included because
 * its ADA is locked with the STRIKE. So the identity that holds, gate-3 H3, is {@code floor == (acquired − outlay) +
 * Σ coin(bot outputs carrying no STRIKE) − Σ coin(wallet inputs)}: the trade, plus the ADA the bot can spend again,
 * less the ADA its wallet put in.
 *
 * <h2>Funding</h2>
 * The token output must carry {@code WalletRoles.tokenOutputRequiredAda(LedgerCeilings.maxPossibleFee(…))} =
 * 7,905,077 lovelace beside the USDM; one lovelace less is refused before anything is built, by name.
 */
class PayInAdvanceStrikeUsdmGateTest {

    /** Thirty seconds past T0: the executor's 30 s backdate puts the body's validity start at T0. */
    private static final long NOW = T0 + 30_000L;

    private static final CardanoConverters CONVERTERS = ClasspathConversionsFactory.createConverters(NetworkType.MAINNET);

    /** The token output's USDM: the payout + 5 USDM, which comes back in change. */
    private static final BigInteger WALLET_USDM = BigInteger.valueOf(792_661_892L);
    private static final BigInteger PAYOUT = BigInteger.valueOf(787_661_892L);
    private static final BigInteger LEFTOVER_USDM = BigInteger.valueOf(5_000_000L);

    /** collateralAmount − equity: what the bot acquires, the fee slice included. */
    private static final BigInteger ACQUIRED = BigInteger.valueOf(2_348_788_420L);
    private static final BigInteger ACQUIRED_LOVELACE = BigInteger.valueOf(3_288_303_788L);
    /** The payout through the principal leg, floor + 1. */
    private static final BigInteger OUTLAY_LOVELACE = BigInteger.valueOf(3_150_647_569L);
    /** acquired − outlay: the floor before the tx fee and the min-ada. */
    private static final BigInteger TRADE = BigInteger.valueOf(137_656_219L);
    private static final BigInteger MIN_ADA = BigInteger.valueOf(1_469_470L);
    private static final BigInteger LOAN_LOVELACE = BigInteger.valueOf(3_000_000L);

    /** {@code tokenOutputRequiredAda(maxPossibleFee(protocolParams()))}: this shape's token-output requirement. */
    private static final long REQUIRED = 7_905_077L;

    private static final BigInteger PLACEHOLDER = BigInteger.valueOf(10_000L);

    // ======================================================================================

    /**
     * (a) The token-principal floor is the real trade read off the built body: the token output carries exactly the
     * shipped requirement, margin 0, min-absolute 0.
     */
    @Test
    void theTokenPrincipalFloorIsTheRealTradeReadOffTheBuiltBody() throws Exception {
        long required = WalletRoles.tokenOutputRequiredAda(
                LedgerCeilings.maxPossibleFee(LoanFixtures.protocolParams().getProtocolParams())).longValueExact();
        assertEquals(REQUIRED, required, "this shape's token-output requirement moved; re-measure it");
        StrikeUsdmFixture.Legs legs = StrikeUsdmFixture.legs(StrikeUsdmFixture.collateralFeed(),
                StrikeUsdmFixture.principalFeed());
        Account account = new Account(Networks.mainnet());
        Cycle cycle = cycle(legs, account, required, BigInteger.ZERO, BigInteger.ZERO);

        assertTrue(cycle.assessment.buildable(), cycle.assessment.toString());
        assertEquals(BigInteger.valueOf(747_341_770L), cycle.assessment.remainingDebt());
        assertEquals(BigInteger.valueOf(109_358_384L), cycle.assessment.equity());
        assertEquals(BigInteger.valueOf(98_325_872L), cycle.assessment.liquidationFee());

        LiquidationDecision decision = cycle.onlyDecision();
        assertEquals(LiquidationDecision.Outcome.WOULD_SUBMIT, decision.outcome(), decision.detail());
        assertEquals(LiquidationDecision.VARIANT_CONVERT, decision.variant());
        assertEquals(LiquidationExecutor.SubmitVeto.MODE_NOT_LIVE.name(), decision.submitVeto(), decision.detail());

        Transaction body = cycle.body(decision);
        BigInteger fee = body.getBody().getFee();
        BigInteger floor = TRADE.subtract(fee).subtract(MIN_ADA);
        String expected = "fee slice 137656220 lovelace - tx fee " + fee + " - min-ada 1469470 [REPLACED (F1, token "
                + "principal): acquired 2348788420 " + STRIKE.toUnit() + " (3288303788 lovelace) - outlay 787661892 "
                + USDM.toUnit() + " (3150647569 lovelace, ceil-biased)] = floor " + floor + "; - margin 0 = " + floor;
        assertEquals(expected, decision.detail());
        assertEquals(ACQUIRED_LOVELACE.subtract(OUTLAY_LOVELACE), TRADE);

        assertEquals(StrikeUsdmFixture.slot(T0), body.getBody().getValidityStartInterval());
        assertEquals(202_754_715L, body.getBody().getValidityStartInterval());
        assertEquals(StrikeUsdmFixture.slot(T0 + 150_000L), body.getBody().getTtl());
        assertEquals(202_754_865L, body.getBody().getTtl());

        // min-ada, computed off the body by credential and role, never by position.
        List<TransactionOutput> strikeAtBot = botOutputs(body, account).stream()
                .filter(output -> quantityOf(output, STRIKE).signum() > 0).toList();
        assertEquals(1, strikeAtBot.size(), "exactly one bot output carries STRIKE: " + strikeAtBot);
        assertEquals(ACQUIRED, quantityOf(strikeAtBot.getFirst(), STRIKE), "the acquired STRIKE");
        BigInteger minAda = coinAtAssetManager(body).subtract(LOAN_LOVELACE)
                .add(strikeAtBot.getFirst().getValue().getCoin());
        assertEquals(minAda, ConvertExecutorRig.minAda(decision.detail()),
                "min-ada = Σ coin(asset-manager outputs) − the loan's lovelace + the STRIKE rider's coin");
        assertEquals(MIN_ADA, minAda);
        List<TransactionOutput> usdmAtBot = botOutputs(body, account).stream()
                .filter(output -> quantityOf(output, USDM).signum() > 0).toList();
        assertEquals(1, usdmAtBot.size(), "exactly one bot output carries USDM: " + usdmAtBot);
        assertEquals(LEFTOVER_USDM, quantityOf(usdmAtBot.getFirst(), USDM), "the leftover USDM comes back in change");
        assertEquals(BigInteger.ZERO, quantityOf(usdmAtBot.getFirst(), STRIKE), "the change carries no STRIKE");

        assertH3(decision, body, cycle);

        assertEquals(List.of(ref(cycle.token())), walletInputs(body, cycle),
                "the wallet spends its token output alone");
        assertEquals(List.of(ref(cycle.c())), collateralRefs(body), "the collateral is exactly C");

        for (Redeemer redeemer : body.getWitnessSet().getRedeemers()) {
            assertTrue(redeemer.getExUnits().getMem().compareTo(PLACEHOLDER) > 0
                            && redeemer.getExUnits().getSteps().compareTo(PLACEHOLDER) > 0,
                    "CCL's placeholder ex-units on the built body (trap 8): " + redeemer);
        }
        assertEquals(0, cycle.submissions.get(), "SHADOW never reaches the submitter");
    }

    /**
     * (b) The absolute floor tests exactly the token-principal floor: F clears it, F + 1 does not. SHADOW's
     * {@code wouldSubmit} is the predicate this tests; with min-expected-profit 0, the only value this configuration
     * states, it is the armed S3's.
     */
    @Test
    void theAbsoluteFloorTestsExactlyTheTokenPrincipalFloor() throws Exception {
        StrikeUsdmFixture.Legs legs = StrikeUsdmFixture.legs(StrikeUsdmFixture.collateralFeed(),
                StrikeUsdmFixture.principalFeed());
        Account account = new Account(Networks.mainnet());
        Cycle first = cycle(legs, account, REQUIRED, BigInteger.ZERO, BigInteger.ZERO);
        LiquidationDecision measured = first.onlyDecision();
        first.body(measured);
        BigInteger floor = ConvertExecutorRig.floor(measured.detail());
        assertEquals(0, first.submissions.get(), "SHADOW never reaches the submitter");

        Cycle atFloor = cycle(legs, account, REQUIRED, BigInteger.ZERO, floor);
        LiquidationDecision cleared = atFloor.onlyDecision();
        atFloor.body(cleared);
        assertEquals(LiquidationDecision.Outcome.WOULD_SUBMIT, cleared.outcome(), cleared.detail());
        assertEquals(LiquidationExecutor.SubmitVeto.MODE_NOT_LIVE.name(), cleared.submitVeto(), cleared.detail());
        assertTrue(cleared.detail().contains("= floor " + floor + ";"), cleared.detail());
        assertEquals(0, atFloor.submissions.get(), "SHADOW never reaches the submitter");

        Cycle aboveFloor = cycle(legs, account, REQUIRED, BigInteger.ZERO, floor.add(BigInteger.ONE));
        LiquidationDecision refused = aboveFloor.onlyDecision();
        aboveFloor.body(refused);
        assertEquals(LiquidationDecision.Outcome.UNPROFITABLE, refused.outcome(), refused.detail());
        assertEquals(LiquidationExecutor.SubmitVeto.MODE_NOT_LIVE.name(), refused.submitVeto(), refused.detail());
        assertTrue(refused.detail().contains("= floor " + floor + ";"), refused.detail());
        assertEquals(0, aboveFloor.submissions.get(), "SHADOW never reaches the submitter");
    }

    /**
     * (c) The margin lever tests the floor minus the margin: F − 1 leaves 1 and would submit, F leaves 0 and would
     * not. SHADOW's {@code wouldSubmit} is the predicate this tests; with min-expected-profit 0, the only value this
     * configuration states, it is the armed S3's.
     */
    @Test
    void theMarginLeverTestsTheFloorMinusTheMargin() throws Exception {
        StrikeUsdmFixture.Legs legs = StrikeUsdmFixture.legs(StrikeUsdmFixture.collateralFeed(),
                StrikeUsdmFixture.principalFeed());
        Account account = new Account(Networks.mainnet());
        Cycle first = cycle(legs, account, REQUIRED, BigInteger.ZERO, BigInteger.ZERO);
        LiquidationDecision measured = first.onlyDecision();
        first.body(measured);
        BigInteger floor = ConvertExecutorRig.floor(measured.detail());
        assertEquals(0, first.submissions.get(), "SHADOW never reaches the submitter");

        Cycle underMargin = cycle(legs, account, REQUIRED, floor.subtract(BigInteger.ONE), BigInteger.ZERO);
        LiquidationDecision cleared = underMargin.onlyDecision();
        underMargin.body(cleared);
        assertEquals(LiquidationDecision.Outcome.WOULD_SUBMIT, cleared.outcome(), cleared.detail());
        assertTrue(cleared.detail().endsWith("; - margin " + floor.subtract(BigInteger.ONE) + " = 1"),
                cleared.detail());
        assertEquals(0, underMargin.submissions.get(), "SHADOW never reaches the submitter");

        Cycle atMargin = cycle(legs, account, REQUIRED, floor, BigInteger.ZERO);
        LiquidationDecision refused = atMargin.onlyDecision();
        atMargin.body(refused);
        assertEquals(LiquidationDecision.Outcome.UNPROFITABLE, refused.outcome(), refused.detail());
        assertTrue(refused.detail().endsWith("; - margin " + floor + " = 0"), refused.detail());
        assertEquals(0, atMargin.submissions.get(), "SHADOW never reaches the submitter");
    }

    /**
     * (d) A token output one lovelace under this shape's requirement (7,905,076) is refused WALLET_INPUT_TOO_SMALL by
     * the selector, before anything is built.
     */
    @Test
    void aTokenOutputOneLovelaceUnderThisShapesRequirementIsRefusedBeforeAnyBuild() throws Exception {
        StrikeUsdmFixture.Legs legs = StrikeUsdmFixture.legs(StrikeUsdmFixture.collateralFeed(),
                StrikeUsdmFixture.principalFeed());
        Account account = new Account(Networks.mainnet());
        Cycle cycle = cycle(legs, account, REQUIRED - 1L, BigInteger.ZERO, BigInteger.ZERO);

        LiquidationDecision decision = cycle.onlyDecision();
        assertEquals(LiquidationDecision.Outcome.REFUSED, decision.outcome(), decision.detail());
        assertEquals("WALLET_INPUT_TOO_SMALL", decision.reason(), decision.detail());
        String expected = "holds 792661892 " + USDM.toUnit() + " + 7905076 lovelace; needed 787661892 " + USDM.toUnit()
                + " + 7905077 lovelace, short by 1 lovelace";
        assertTrue(decision.detail().contains(expected), decision.detail());
        assertNull(decision.txCborHex(), "nothing was built");
        assertEquals(0, cycle.submissions.get(), "nor submitted");
    }

    // ======================================================================================
    // the cycle
    // ======================================================================================

    /** What one SHADOW cycle left behind, and the wallet it ran against. */
    private record Cycle(Account account, List<Utxo> wallet, List<Utxo> universe, LiquidationAssessment assessment,
                         LiquidationDecisionLog log, AtomicInteger submissions) {

        LiquidationDecision onlyDecision() {
            List<LiquidationDecision> decisions = log.newestFirst(10);
            assertEquals(1, decisions.size(), decisions.toString());
            return decisions.getFirst();
        }

        /** The body the executor priced, deserialised from the row; its fee is the row's tx fee. */
        Transaction body(LiquidationDecision decision) throws Exception {
            assertNotNull(decision.txCborHex(), "the row carries the priced body: " + decision.detail());
            Transaction body = Transaction.deserialize(HexUtil.decodeHexString(decision.txCborHex()));
            assertEquals(body.getBody().getFee(), decision.txFeeLovelace(), "the row's tx fee is the built body's");
            return body;
        }

        /** Role C, the wallet's first output. */
        Utxo c() {
            return wallet.getFirst();
        }

        /** The token output {@code e1…e1#0}. */
        Utxo token() {
            return wallet.stream().filter(utxo -> utxo.getTxHash().equals("e1".repeat(32))).findFirst().orElseThrow();
        }
    }

    /**
     * ONE SHADOW {@code cycle(NOW)} of the executor wired as production wires it, over a wallet of C, U and a token
     * output of {@code tokenOutputLovelace} + 792,661,892 USDM at {@code account}'s base address, with an ANTICIPATE
     * USDM market.
     */
    private static Cycle cycle(StrikeUsdmFixture.Legs legs, Account account, long tokenOutputLovelace,
                               BigInteger margin, BigInteger minAbsolute) throws Exception {
        String address = account.baseAddress();
        List<Utxo> wallet = List.of(StrikeUsdmFixture.collateral(address), StrikeUsdmFixture.u(address),
                StrikeUsdmFixture.tokenOutput(address, tokenOutputLovelace, WALLET_USDM));
        List<Utxo> universe = StrikeUsdmFixture.universe(0, legs, wallet);
        var params = LoanFixtures.protocolParams();

        LiquidatePayInAdvanceTransactionBuilder builder = StrikeUsdmFixture.production(universe,
                new HashCheckedScriptSupplier(StrikeUsdmFixture.rawScripts(legs)),
                StrikeUsdmFixture.aiken(universe, legs));

        var configuration = new AppConfig.LiquidationConfiguration(Mode.SHADOW, 60, 120, 30, margin, 200, true,
                minAbsolute, BigInteger.ZERO, StrikeUsdmFixture.REFERENCE_SCRIPTS);
        // Without the ANTICIPATE market the convert-permitted bond routes to convert.
        configuration.setMarkets(List.of(RouteStageHarness.market(USDM, Mode.LIVE, Action.ANTICIPATE,
                1_000_000_000_000L)));
        LiquidationDecisionLog log = new LiquidationDecisionLog(configuration);

        LiquidationAssessment assessment = LoanFixtures.assess(StrikeUsdmFixture.bond(0), StrikeUsdmFixture.loan(0),
                legs.principal().entry().feed(), legs.collateral().entry().feed(), T0);

        // Not syncing, no committed block: the anchor is NOW.
        BlockEventListener chain = new BlockEventListener(null);
        chain.getIsSyncing().set(false);

        AtomicInteger submissions = new AtomicInteger();
        LiquidationExecutor executor = new LiquidationExecutor(configuration, chain, new FakeAppUtxoService(wallet),
                account, new FakeScanner(assessment),
                new FakeResolver(StrikeUsdmFixture.loanUtxo(0), StrikeUsdmFixture.bondUtxo(0),
                        StrikeUsdmFixture.configUtxo(), StrikeUsdmFixture.lmConfigUtxo()),
                new LiquidateTransactionBuilder(REGISTRY, NETWORK, CONVERTERS, LoanFixtures.utxoSupplier(universe),
                        params),
                new PayInAdvanceLiquidationRouter(REGISTRY, CONVERTERS, configuration, builder),
                (ConvertLiquidationRouter) null, REGISTRY, log, new MarketCoverageReporter(new SimpleMeterRegistry()),
                new FixedOracles(oracleClient(legs.collateral(), legs.principal())), network(), params, CONVERTERS,
                bytes -> {
                    submissions.incrementAndGet();
                    throw new AssertionError("a SHADOW cycle reached the submitter");
                });
        executor.cycle(NOW);
        return new Cycle(account, wallet, universe, assessment, log, submissions);
    }

    /**
     * The recorded registry payload with each leg's {@code recordedEntry} replaced by its {@code rewrittenEntry},
     * loaded into one production client.
     */
    private static FluidOracleClient oracleClient(Leg... legs) throws Exception {
        ArrayNode payload;
        try (InputStream in = PayInAdvanceStrikeUsdmGateTest.class.getResourceAsStream(REGISTRY_PAYLOAD)) {
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
        for (Leg leg : legs) {
            assertEquals(Optional.of(leg.entry()), client.findEntryByOracleToken(leg.entry().oracleToken()),
                    "the leg " + leg.entry().oracleToken().toUnit());
        }
        return client;
    }

    // ======================================================================================
    // reading the body
    // ======================================================================================

    /**
     * Gate-3 H3: the floor is acquired − outlay, plus the ADA of the bot outputs the bot can spend again (every one
     * at its address carrying no STRIKE), less the ADA taken from its wallet inputs.
     */
    private static void assertH3(LiquidationDecision decision, Transaction body, Cycle cycle) {
        BigInteger back = botOutputs(body, cycle.account).stream()
                .filter(output -> quantityOf(output, STRIKE).signum() == 0)
                .map(output -> output.getValue().getCoin())
                .reduce(BigInteger.ZERO, BigInteger::add);
        BigInteger in = body.getBody().getInputs().stream()
                .map(input -> resolve(input, cycle.universe))
                .filter(utxo -> cycle.account.baseAddress().equals(utxo.getAddress()))
                .map(PayInAdvanceStrikeUsdmGateTest::lovelaceOf)
                .reduce(BigInteger.ZERO, BigInteger::add);
        assertEquals(TRADE.add(back).subtract(in), ConvertExecutorRig.floor(decision.detail()),
                "H3: floor == (acquired − outlay) + Σ coin(bot outputs carrying no STRIKE) − Σ coin(wallet inputs)");
    }

    private static List<TransactionOutput> botOutputs(Transaction body, Account account) {
        return body.getBody().getOutputs().stream()
                .filter(output -> account.baseAddress().equals(output.getAddress())).toList();
    }

    private static BigInteger coinAtAssetManager(Transaction body) {
        String assetManagerSpend = REGISTRY.getAssetManagerSpendScriptHash();
        return body.getBody().getOutputs().stream()
                .filter(output -> assetManagerSpend.equals(paymentCredentialOf(output.getAddress())))
                .map(output -> output.getValue().getCoin())
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    /** The refs of {@code body}'s inputs at the bot's address, in body order. */
    private static List<String> walletInputs(Transaction body, Cycle cycle) {
        return body.getBody().getInputs().stream()
                .filter(input -> cycle.account.baseAddress().equals(resolve(input, cycle.universe).getAddress()))
                .map(input -> input.getTransactionId() + "#" + input.getIndex())
                .toList();
    }

    private static List<String> collateralRefs(Transaction body) {
        assertNotNull(body.getBody().getCollateral(), "a script transaction declares collateral");
        return body.getBody().getCollateral().stream().map(in -> in.getTransactionId() + "#" + in.getIndex()).toList();
    }

    private static Utxo resolve(TransactionInput input, List<Utxo> universe) {
        return universe.stream()
                .filter(u -> u.getTxHash().equals(input.getTransactionId()) && u.getOutputIndex() == input.getIndex())
                .findFirst()
                .orElseThrow(() -> new AssertionError("an out-ref the universe does not hold: " + input));
    }

    private static String ref(Utxo utxo) {
        return utxo.getTxHash() + "#" + utxo.getOutputIndex();
    }

    private static BigInteger lovelaceOf(Utxo utxo) {
        return utxo.getAmount().stream().filter(a -> "lovelace".equals(a.getUnit())).map(Amount::getQuantity)
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    private static BigInteger quantityOf(TransactionOutput output, AssetType asset) {
        if (output.getValue().getMultiAssets() == null) {
            return BigInteger.ZERO;
        }
        return output.getValue().getMultiAssets().stream()
                .filter(multiAsset -> multiAsset.getPolicyId().equals(asset.policyId()))
                .flatMap(multiAsset -> multiAsset.getAssets().stream())
                .filter(a -> HexUtil.encodeHexString(a.getNameAsBytes()).equals(asset.assetName()))
                .map(com.bloxbean.cardano.client.transaction.spec.Asset::getValue)
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    private static String paymentCredentialOf(String address) {
        return new com.bloxbean.cardano.client.address.Address(address)
                .getPaymentCredentialHash().map(HexUtil::encodeHexString).orElse("");
    }

    private static AppConfig.Network network() {
        var network = new AppConfig.Network();
        network.setNetworkForTest("mainnet");
        return network;
    }

    // ---- private copies of ConvertTokenToTokenExecutorTest's leaves --------------------------------

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
