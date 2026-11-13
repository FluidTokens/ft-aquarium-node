package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration.Action;
import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration.Mode;
import com.fluidtokens.aquarium.offchain.config.HashCheckedScriptSupplier;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationDecision;
import com.fluidtokens.aquarium.offchain.model.loans.Loan;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.service.AppUtxoService;
import com.fluidtokens.aquarium.offchain.service.BlockEventListener;
import com.fluidtokens.aquarium.offchain.service.loans.AdaCollateralUsdmFixture.Oracle;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.cardanofoundation.conversions.CardanoConverters;
import org.cardanofoundation.conversions.ClasspathConversionsFactory;
import org.cardanofoundation.conversions.domain.NetworkType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static com.fluidtokens.aquarium.offchain.service.loans.AdaCollateralUsdmFixture.ACQUIRED;
import static com.fluidtokens.aquarium.offchain.service.loans.AdaCollateralUsdmFixture.EQUITY;
import static com.fluidtokens.aquarium.offchain.service.loans.AdaCollateralUsdmFixture.LIQUIDATION_FEE;
import static com.fluidtokens.aquarium.offchain.service.loans.AdaCollateralUsdmFixture.PAYOUT;
import static com.fluidtokens.aquarium.offchain.service.loans.AdaCollateralUsdmFixture.PRICE;
import static com.fluidtokens.aquarium.offchain.service.loans.AdaCollateralUsdmFixture.REMAINING_DEBT;
import static com.fluidtokens.aquarium.offchain.service.loans.AdaCollateralUsdmFixture.T0;
import static com.fluidtokens.aquarium.offchain.service.loans.AdaCollateralUsdmFixture.aiken;
import static com.fluidtokens.aquarium.offchain.service.loans.AdaCollateralUsdmFixture.bond;
import static com.fluidtokens.aquarium.offchain.service.loans.AdaCollateralUsdmFixture.bondUtxo;
import static com.fluidtokens.aquarium.offchain.service.loans.AdaCollateralUsdmFixture.feed;
import static com.fluidtokens.aquarium.offchain.service.loans.AdaCollateralUsdmFixture.loan;
import static com.fluidtokens.aquarium.offchain.service.loans.AdaCollateralUsdmFixture.loanUtxo;
import static com.fluidtokens.aquarium.offchain.service.loans.AdaCollateralUsdmFixture.oracle;
import static com.fluidtokens.aquarium.offchain.service.loans.AdaCollateralUsdmFixture.rawScripts;
import static com.fluidtokens.aquarium.offchain.service.loans.AdaCollateralUsdmFixture.universe;
import static com.fluidtokens.aquarium.offchain.service.loans.StrikeUsdmFixture.NETWORK;
import static com.fluidtokens.aquarium.offchain.service.loans.StrikeUsdmFixture.REGISTRY;
import static com.fluidtokens.aquarium.offchain.service.loans.StrikeUsdmFixture.USDM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ FAB-226-2: ADA-collateral pay-in-advance against a token principal through a REAL, LIVE
 * {@link LiquidationExecutor} cycle — the recorded mainnet 8,100 ADA / 551 USDM loan {@code 2d067dae…#1} of
 * {@link AdaCollateralUsdmFixture}, priced by one rig USDM oracle at 12.5 ADA per USDM.
 * <p>
 * Every collaborator the candidate reaches is the production one: the executor's 17-argument constructor, the real
 * {@link PayInAdvanceLiquidationRouter}, and {@link LiquidatePayInAdvanceTransactionBuilder}'s production
 * constructor over the real Aiken machine. One real {@link FluidOracleClient}, loaded with the whole rewritten
 * registry payload, prices both the market gate's legs and the executor's REPLACED bracket. Only the leaves are
 * fakes — the scan, the UTxO resolver, the wallet listing, the oracle provider and the submitter — and none of them
 * computes economics. Every body is read off {@link LiquidationDecision#txCborHex()}, the bytes the executor priced.
 *
 * <h2>The floor of this shape: pay-in-advance × ADA collateral × token principal</h2>
 * {@code record()} REPLACES the fee-slice floor with the real trade: {@code acquired − outlay − tx fee − min-ada}.
 * <table>
 *   <caption>Each term, where it is read and why</caption>
 *   <tr><th>term</th><th>read from</th><th>why</th></tr>
 *   <tr><td>acquired</td><td>{@code collateralAmount − equity} = 7,628,849,003 lovelace, priced through the
 *       collateral leg, which is the identity for lovelace</td><td>the bot takes the whole collateral less the
 *       borrower's equity, the fee slice included</td></tr>
 *   <tr><td>outlay</td><td>the payout the built body pays the lender (584,387,921 USDM), priced through the
 *       principal leg at floor + 1 (7,304,849,013 lovelace)</td><td>a cost, so it is biased up, never
 *       flattering the bot</td></tr>
 *   <tr><td>tx fee</td><td>the built body's fee</td><td>never an estimate: the body is what is submitted
 *       (CCL traps 8 and 13)</td></tr>
 *   <tr><td>min-ada</td><td>Σ coin(asset-manager outputs) − equity = 472,814,657 − 471,150,997 = 1,663,660,
 *       the lender output's min-ADA</td><td>only the equity flows from the collateral into the asset-manager
 *       outputs; the rest of their coin is the bot's own (CCL trap 6)</td></tr>
 * </table>
 * CCL's withdrawal dummy, the named remainder output (the token output's leftover USDM at its own min-ADA) and the
 * ADA-only change all return to the bot's address and are not costs (CCL traps 1, 17a and 17b). So the identity
 * that holds, gate-3 H3, is {@code floor == Σ coin(outputs at the bot's address) − Σ coin(wallet inputs) −
 * outlay}: what actually comes back to the bot, less what it paid in principal.
 *
 * <h2>Funding</h2>
 * The seized ADA pays every ADA need of this shape, so the token output must carry only
 * {@code max(2 ADA, tokenOutputRequiredAda(fee ceiling) − seized)} = 2,000,000 lovelace beside the token, for both
 * the market gate's count and the selector. One lovelace less is refused before anything is built, by name.
 */
class PayInAdvanceAdaCollateralGateTest {

    /** Thirty seconds past T0: the executor's 30 s backdate puts the body's validity start at T0. */
    private static final long NOW = T0 + 30_000L;

    private static final String ACCEPTED = "ab".repeat(32);

    private static final CardanoConverters CONVERTERS = ClasspathConversionsFactory.createConverters(NetworkType.MAINNET);

    private static final BigInteger FIVE_USDM = BigInteger.valueOf(5_000_000L);

    /** ceil(584,387,921 × 12.5): the outlay the REPLACED bracket charges, floor + 1. */
    private static final BigInteger OUTLAY_LOVELACE = BigInteger.valueOf(7_304_849_013L);

    /** acquired − outlay: the floor before the tx fee and the min-ada. */
    private static final BigInteger TRADE = ACQUIRED.subtract(OUTLAY_LOVELACE);

    /** The lender output's min-ADA on the recorded loan: Σ coin(asset-manager outputs) − equity. */
    private static final BigInteger LENDER_MIN_ADA = BigInteger.valueOf(1_663_660L);

    private static final BigInteger PLACEHOLDER = BigInteger.valueOf(10_000L);

    /** The token output: {@code e1…e1#0} at {@code address}. */
    private static Function<String, List<Utxo>> tokenOutput(long lovelace, BigInteger usdm) {
        return address -> List.of(StrikeUsdmFixture.tokenOutput(address, lovelace, usdm));
    }

    // ======================================================================================

    /**
     * (a) The floor is the real trade read off the built body: token output 10,000,000 lovelace + payout + 5 USDM,
     * margin 0, min-absolute 0.
     */
    @Test
    void theAdaCollateralFloorIsTheRealTradeReadOffTheBuiltBody() throws Exception {
        Oracle oracle = oracle(feed(PRICE));
        Account account = new Account(Networks.mainnet());
        Cycle cycle = cycle(oracle, account, tokenOutput(10_000_000L, PAYOUT.add(FIVE_USDM)), BigInteger.ZERO,
                BigInteger.ZERO);

        assertTrue(cycle.assessment.buildable(), cycle.assessment.toString());
        assertEquals(REMAINING_DEBT, cycle.assessment.remainingDebt());
        assertEquals(EQUITY, cycle.assessment.equity());
        assertEquals(LIQUIDATION_FEE, cycle.assessment.liquidationFee());

        LiquidationDecision decision = cycle.onlyDecision();
        assertEquals(LiquidationDecision.Outcome.SUBMITTED, decision.outcome(), decision.detail());
        assertNull(decision.submitVeto(), decision.detail());
        assertEquals(LiquidationDecision.VARIANT_CONVERT, decision.variant());
        assertEquals(1, cycle.submitted.size(), "one submission");

        // The row's body is the unsigned priced body; the submitted bytes are it plus one vkey witness.
        byte[] priced = HexUtil.decodeHexString(decision.txCborHex());
        Transaction body = Transaction.deserialize(priced);
        Transaction signed = Transaction.deserialize(cycle.submitted.getFirst());
        assertTrue(body.getWitnessSet().getVkeyWitnesses() == null
                || body.getWitnessSet().getVkeyWitnesses().isEmpty(), "the row's body is unsigned");
        assertEquals(1, signed.getWitnessSet().getVkeyWitnesses().size(), "the submitted bytes carry one signature");
        assertEquals(decision.txHash(), TransactionUtil.getTxHash(priced));
        assertEquals(decision.txHash(), TransactionUtil.getTxHash(cycle.submitted.getFirst()));
        BigInteger fee = body.getBody().getFee();
        assertEquals(fee, decision.txFeeLovelace(), "the row's tx fee is the built body's");

        BigInteger floor = TRADE.subtract(fee).subtract(LENDER_MIN_ADA);
        String expected = "fee slice 324000000 lovelace - tx fee " + fee + " - min-ada 1663660 [REPLACED (F1, token "
                + "principal): acquired 7628849003 lovelace (7628849003 lovelace) - outlay 584387921 "
                + USDM.toUnit() + " (7304849013 lovelace, ceil-biased)] = floor " + floor + "; - margin 0 = " + floor
                + "; submitted as " + ACCEPTED;
        assertEquals(expected, decision.detail());

        assertEquals(StrikeUsdmFixture.slot(T0), body.getBody().getValidityStartInterval());
        assertEquals(202_963_709L, body.getBody().getValidityStartInterval());
        assertEquals(StrikeUsdmFixture.slot(T0 + 150_000L), body.getBody().getTtl());
        assertEquals(202_963_859L, body.getBody().getTtl());

        BigInteger minAda = ConvertExecutorRig.minAda(decision.detail());
        assertEquals(coinAtAssetManager(body).subtract(EQUITY), minAda, "min-ada = asset-manager coin − equity");
        assertEquals(LENDER_MIN_ADA, minAda, "the lender output's min-ADA");
        assertEquals(TRADE.subtract(fee).subtract(minAda), ConvertExecutorRig.floor(decision.detail()));
        assertH3(decision, body, cycle);

        assertEquals(List.of(ref(cycle.token())), walletInputs(body, cycle));
        assertEquals(List.of(ref(cycle.c())), collateralRefs(body));

        for (Redeemer redeemer : body.getWitnessSet().getRedeemers()) {
            assertTrue(redeemer.getExUnits().getMem().compareTo(PLACEHOLDER) > 0
                            && redeemer.getExUnits().getSteps().compareTo(PLACEHOLDER) > 0,
                    "CCL's placeholder ex-units on the built body (trap 8): " + redeemer);
        }
    }

    /** (b) The absolute floor tests exactly this shape's floor: F clears it, F + 1 does not. */
    @Test
    void theAbsoluteFloorTestsExactlyTheAdaCollateralFloor() throws Exception {
        Oracle oracle = oracle(feed(PRICE));
        Account account = new Account(Networks.mainnet());
        Function<String, List<Utxo>> wallet = tokenOutput(10_000_000L, PAYOUT.add(FIVE_USDM));
        BigInteger floor = ConvertExecutorRig.floor(
                cycle(oracle, account, wallet, BigInteger.ZERO, BigInteger.ZERO).onlyDecision().detail());

        Cycle atFloor = cycle(oracle, account, wallet, BigInteger.ZERO, floor);
        LiquidationDecision cleared = atFloor.onlyDecision();
        assertEquals(LiquidationDecision.Outcome.SUBMITTED, cleared.outcome(), cleared.detail());
        assertEquals(1, atFloor.submitted.size());
        assertTrue(cleared.detail().contains("= floor " + floor + ";"), cleared.detail());

        Cycle aboveFloor = cycle(oracle, account, wallet, BigInteger.ZERO, floor.add(BigInteger.ONE));
        LiquidationDecision refused = aboveFloor.onlyDecision();
        assertEquals(LiquidationDecision.Outcome.UNPROFITABLE, refused.outcome(), refused.detail());
        assertEquals(LiquidationExecutor.SubmitVeto.NOT_PROFITABLE.name(), refused.submitVeto());
        assertTrue(aboveFloor.submitted.isEmpty(), "no submission");
        assertTrue(refused.detail().contains("= floor " + floor + ";"), refused.detail());
    }

    /** (c) The margin lever tests the floor minus the margin: F − 1 leaves 1 and submits, F leaves 0 and does not. */
    @Test
    void theMarginLeverTestsTheFloorMinusTheMargin() throws Exception {
        Oracle oracle = oracle(feed(PRICE));
        Account account = new Account(Networks.mainnet());
        Function<String, List<Utxo>> wallet = tokenOutput(10_000_000L, PAYOUT.add(FIVE_USDM));
        BigInteger floor = ConvertExecutorRig.floor(
                cycle(oracle, account, wallet, BigInteger.ZERO, BigInteger.ZERO).onlyDecision().detail());

        Cycle underMargin = cycle(oracle, account, wallet, floor.subtract(BigInteger.ONE), BigInteger.ZERO);
        LiquidationDecision cleared = underMargin.onlyDecision();
        assertEquals(LiquidationDecision.Outcome.SUBMITTED, cleared.outcome(), cleared.detail());
        assertEquals(1, underMargin.submitted.size());
        assertTrue(cleared.detail().contains("- margin " + floor.subtract(BigInteger.ONE) + " = 1; submitted as"),
                cleared.detail());

        Cycle atMargin = cycle(oracle, account, wallet, floor, BigInteger.ZERO);
        LiquidationDecision refused = atMargin.onlyDecision();
        assertEquals(LiquidationDecision.Outcome.UNPROFITABLE, refused.outcome(), refused.detail());
        assertEquals(LiquidationExecutor.SubmitVeto.NOT_PROFITABLE.name(), refused.submitVeto());
        assertTrue(atMargin.submitted.isEmpty(), "no submission");
        assertTrue(refused.detail().endsWith("- margin " + floor + " = 0"), refused.detail());
    }

    /**
     * (d) A 2 ADA token output is funded: the seized ADA (7,628,849,003 lovelace) pays the transaction's ADA needs,
     * so the token output carries only its own min-ADA ceiling beside the token.
     */
    @Test
    void aTwoAdaTokenOutputIsFundedBecauseTheSeizedAdaPaysTheAdaNeeds() throws Exception {
        Oracle oracle = oracle(feed(PRICE));
        Account account = new Account(Networks.mainnet());

        Cycle withLeftover = cycle(oracle, account, tokenOutput(2_000_000L, PAYOUT.add(FIVE_USDM)), BigInteger.ZERO,
                BigInteger.ZERO);
        LiquidationDecision first = withLeftover.onlyDecision();
        assertEquals(LiquidationDecision.Outcome.SUBMITTED, first.outcome(), first.detail());
        Transaction firstBody = Transaction.deserialize(HexUtil.decodeHexString(first.txCborHex()));
        assertEquals(List.of(ref(withLeftover.token())), walletInputs(firstBody, withLeftover),
                "the token output is the only wallet input");
        assertH3(first, firstBody, withLeftover);

        Cycle exact = cycle(oracle, account, tokenOutput(2_000_000L, PAYOUT), BigInteger.ZERO, BigInteger.ZERO);
        LiquidationDecision second = exact.onlyDecision();
        assertEquals(LiquidationDecision.Outcome.SUBMITTED, second.outcome(), second.detail());
        Transaction secondBody = Transaction.deserialize(HexUtil.decodeHexString(second.txCborHex()));
        assertH3(second, secondBody, exact);
        assertTrue(secondBody.getBody().getOutputs().stream()
                        .filter(output -> account.baseAddress().equals(output.getAddress()))
                        .allMatch(PayInAdvanceAdaCollateralGateTest::adaOnly),
                "no leftover token, so no bot output carries one");
    }

    /**
     * (d2) The only USDM output carries 1,999,999 lovelace: the market gate does not count it, so the candidate is
     * refused INSUFFICIENT_BALANCE before the selector is asked or anything is built.
     */
    @Test
    void aTokenOutputOneLovelaceUnderTheFloorIsNotCountedByTheMarketGate() throws Exception {
        Oracle oracle = oracle(feed(PRICE));
        Account account = new Account(Networks.mainnet());
        Cycle cycle = cycle(oracle, account, tokenOutput(1_999_999L, PAYOUT.add(FIVE_USDM)), BigInteger.ZERO,
                BigInteger.ZERO);

        LiquidationDecision decision = cycle.onlyDecision();
        assertEquals(LiquidationDecision.Outcome.REFUSED, decision.outcome(), decision.detail());
        assertTrue(decision.reason().startsWith("INSUFFICIENT_BALANCE: market " + USDM.toUnit() + " holds 0"),
                decision.reason());
        assertTrue(decision.detail().contains("carrying less than 2000000 lovelace"), decision.detail());
        assertNull(decision.txCborHex(), "nothing was built");
        assertTrue(cycle.submitted.isEmpty(), "nor submitted");
    }

    /**
     * (d3) A second USDM output at 3 ADA lets the market gate count the payout, but the designated output — the one
     * holding the most USDM, at 1,999,999 lovelace — is one lovelace short of 2 ADA: WALLET_INPUT_TOO_SMALL.
     */
    @Test
    void aDesignatedTokenOutputOneLovelaceUnderTheFloorIsRefusedWalletInputTooSmall() throws Exception {
        Oracle oracle = oracle(feed(PRICE));
        Account account = new Account(Networks.mainnet());
        Function<String, List<Utxo>> wallet = address -> List.of(
                StrikeUsdmFixture.tokenOutput(address, 1_999_999L, PAYOUT.add(FIVE_USDM)),
                LoanFixtures.utxo("e3".repeat(32), 0, address, List.of(Amount.lovelace(BigInteger.valueOf(3_000_000L)),
                        Amount.asset(USDM.toUnit(), PAYOUT)), null));
        Cycle cycle = cycle(oracle, account, wallet, BigInteger.ZERO, BigInteger.ZERO);

        LiquidationDecision decision = cycle.onlyDecision();
        assertEquals(LiquidationDecision.Outcome.REFUSED, decision.outcome(), decision.detail());
        assertEquals("WALLET_INPUT_TOO_SMALL", decision.reason(), decision.detail());
        String expected = "holds 589387921 " + USDM.toUnit() + " + 1999999 lovelace; needed 584387921 " + USDM.toUnit()
                + " + 2000000 lovelace, short by 1 lovelace";
        assertTrue(decision.detail().contains(expected), decision.detail());
        assertNull(decision.txCborHex(), "nothing was built");
        assertTrue(cycle.submitted.isEmpty(), "nor submitted");
    }

    // ======================================================================================
    // the cycle
    // ======================================================================================

    /** What one LIVE cycle left behind, and the wallet it ran against. */
    private record Cycle(Account account, List<Utxo> wallet, List<Utxo> universe, LiquidationAssessment assessment,
                         LiquidationDecisionLog log, List<byte[]> submitted) {

        LiquidationDecision onlyDecision() {
            List<LiquidationDecision> decisions = log.newestFirst(10);
            assertEquals(1, decisions.size(), decisions.toString());
            return decisions.getFirst();
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
     * ONE LIVE {@code cycle(NOW)} of the executor wired as production wires it, over a wallet of C, U and
     * {@code tokenOutputs} at {@code account}'s base address, with an ANTICIPATE USDM market.
     */
    private static Cycle cycle(Oracle oracle, Account account, Function<String, List<Utxo>> tokenOutputs,
                               BigInteger margin, BigInteger minAbsolute) throws Exception {
        String address = account.baseAddress();
        List<Utxo> wallet = new ArrayList<>(List.of(StrikeUsdmFixture.collateral(address), StrikeUsdmFixture.u(address)));
        wallet.addAll(tokenOutputs.apply(address));
        List<Utxo> universe = universe(oracle, wallet);
        var params = LoanFixtures.protocolParams();

        LiquidatePayInAdvanceTransactionBuilder builder = StrikeUsdmFixture.production(universe,
                new HashCheckedScriptSupplier(rawScripts(oracle)), aiken(universe, oracle));

        var configuration = new AppConfig.LiquidationConfiguration(Mode.LIVE, 60, 120, 30, margin, 200, true,
                minAbsolute, BigInteger.ZERO, StrikeUsdmFixture.REFERENCE_SCRIPTS);
        configuration.setMarkets(List.of(RouteStageHarness.market(USDM, Mode.LIVE, Action.ANTICIPATE,
                1_000_000_000_000L)));
        LiquidationDecisionLog log = new LiquidationDecisionLog(configuration);

        LiquidationAssessment assessment = LoanFixtures.assess(bond(), loan(), oracle.leg().entry().feed(),
                OraclePriceFeed.unit(), T0);

        BlockEventListener chain = new BlockEventListener(null);
        chain.getIsSyncing().set(false);

        List<byte[]> submitted = Collections.synchronizedList(new ArrayList<>());
        LiquidationExecutor executor = new LiquidationExecutor(configuration, chain, new FakeAppUtxoService(wallet),
                account, new FakeScanner(assessment),
                new FakeResolver(loanUtxo(), bondUtxo(), StrikeUsdmFixture.configUtxo(), StrikeUsdmFixture.lmConfigUtxo()),
                new LiquidateTransactionBuilder(REGISTRY, NETWORK, CONVERTERS, LoanFixtures.utxoSupplier(universe), params),
                new PayInAdvanceLiquidationRouter(REGISTRY, CONVERTERS, configuration, builder),
                (ConvertLiquidationRouter) null, REGISTRY, log, new MarketCoverageReporter(new SimpleMeterRegistry()),
                new FixedOracles(oracle.leg().client()), network(), params, CONVERTERS,
                bytes -> {
                    submitted.add(bytes);
                    return RouteStageHarness.accepting(ACCEPTED).submit(bytes);
                });
        executor.setSubmitClock(() -> NOW);
        executor.cycle(NOW);
        return new Cycle(account, List.copyOf(wallet), universe, assessment, log, submitted);
    }

    // ======================================================================================
    // reading the body
    // ======================================================================================

    /** Gate-3 H3: the floor is what comes back to the bot's address, less what it put in, less the outlay. */
    private static void assertH3(LiquidationDecision decision, Transaction body, Cycle cycle) {
        BigInteger back = body.getBody().getOutputs().stream()
                .filter(output -> cycle.account.baseAddress().equals(output.getAddress()))
                .map(output -> output.getValue().getCoin())
                .reduce(BigInteger.ZERO, BigInteger::add);
        BigInteger in = body.getBody().getInputs().stream()
                .map(input -> resolve(input, cycle.universe))
                .filter(utxo -> cycle.account.baseAddress().equals(utxo.getAddress()))
                .map(PayInAdvanceAdaCollateralGateTest::lovelaceOf)
                .reduce(BigInteger.ZERO, BigInteger::add);
        assertEquals(back.subtract(in).subtract(OUTLAY_LOVELACE), ConvertExecutorRig.floor(decision.detail()),
                "H3: floor == Σ coin(bot outputs) − Σ coin(wallet inputs) − outlay");
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

    private static boolean adaOnly(TransactionOutput output) {
        return output.getValue().getMultiAssets() == null || output.getValue().getMultiAssets().stream()
                .allMatch(multiAsset -> multiAsset.getAssets() == null || multiAsset.getAssets().isEmpty());
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
