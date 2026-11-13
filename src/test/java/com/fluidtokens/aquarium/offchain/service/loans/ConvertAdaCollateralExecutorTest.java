package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationDecision;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationMode;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.model.loans.Rational;
import com.fluidtokens.aquarium.offchain.service.loans.TestOracleRig.Feed;
import com.fluidtokens.aquarium.offchain.util.LedgerCeilings;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static com.fluidtokens.aquarium.offchain.service.loans.ConvertAdaCollateralFixture.BOND_IX;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertAdaCollateralFixture.COLLATERAL_AMOUNT;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertAdaCollateralFixture.LOAN_IX;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertAdaCollateralFixture.LOAN_TX;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertAdaCollateralFixture.T0;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertAdaCollateralFixture.USDM_FEED;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertExecutorRig.ACCOUNT;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertExecutorRig.coinAtBot;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertExecutorRig.coinAtCredential;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertExecutorRig.floor;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertExecutorRig.minAda;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.CONVERTERS;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.DENOMINATOR;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.REGISTRY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ FAB-224-3: the ADA-collateral convert through a REAL {@link LiquidationExecutor} cycle — the recorded mainnet
 * ADA→USDM loan {@code 2d067dae…#1} of {@link ConvertAdaCollateralFixture}, over {@link ConvertExecutorRig}'s
 * production convert wiring (the router bean, a real {@link ConvertEconomics}, the production builder evaluated by the
 * real Aiken machine). The wallet the executor lists is a 100-ADA U and role C at the executor's own address; the cycle
 * runs at {@code T0 + 5,000}.
 * <p>
 * Pinned: the executor's floor gate on this shape ({@code record()}: fee slice − tx fee − min-ada, then the margin).
 * Its min-ada now counts the Minswap order output, which sits at the order credential and which the gate never saw
 * before: for an ada collateral that output holds the swapped ada plus the 4,000,000 overhead, the swapped part is the
 * collateral's own (the {@code collateralAmount − liquidationFee} offset cancels it), and the 4,000,000 the bot fronts
 * is what remains. And a convert built from the loan alone names no wallet output: its row designates nothing and its
 * SUBMITTED row carries no "U stays" note.
 * <p>
 * Gate-3 H3, asserted on every pinned shape off the CAPTURED body, never off a constant: the floor equals
 * {@code Σ coin(outputs at the bot's address) − Σ coin(wallet inputs)} (the fee value term is 0 for an ada collateral,
 * whose fee is paid in ada). The rows, as the tests below assert them ({@code F} the built body's fee, {@code E} the
 * asset-manager output's coin, {@code e} the claim equity at the body's validFrom):
 * <table>
 *   <caption>The ADA-collateral convert floor per shape</caption>
 *   <tr><th>test</th><th>bond</th><th>fee slice</th><th>wallet in</th><th>min-ada</th><th>floor</th></tr>
 *   <tr><td>(a), (b)</td><td>40‰</td><td>324,000,000</td><td>none</td><td>4,000,000</td><td>320,000,000 − F</td></tr>
 *   <tr><td>(c)</td><td>1‰ (synthetic)</td><td>8,100,000</td><td>U, 100,000,000</td><td>4,000,000</td>
 *       <td>4,100,000 − F</td></tr>
 *   <tr><td>(e)</td><td>0‰ (synthetic)</td><td>0</td><td>U, 100,000,000</td><td>4,000,000</td>
 *       <td>−4,000,000 − F</td></tr>
 *   <tr><td>(f)</td><td>40‰, USDM at 13.27</td><td>324,000,000</td><td>none</td><td>4,000,000 + (E − e)</td>
 *       <td>320,000,000 − F − (E − e)</td></tr>
 * </table>
 */
class ConvertAdaCollateralExecutorTest {

    /** Five seconds past the anchor: the body opens at NOW − 30 s, inside the USDM feed's window. */
    private static final long NOW = T0 + 5_000L;
    private static final BigInteger ORDER_OVERHEAD = BigInteger.valueOf(4_000_000L);
    private static final BigInteger U_LOVELACE = BigInteger.valueOf(100_000_000L);
    private static final BigInteger MARGIN = BigInteger.valueOf(5_000_000L);
    private static final String ACCEPTED = "ab".repeat(32);

    // ======================================================================================

    /**
     * (a) 40‰ in SHADOW: the fee pays for everything, so the body spends {loan, bond} only and the gate charges the
     * 4,000,000 order overhead — and nothing of the swapped collateral.
     */
    @Test
    void theZeroWalletAdaConvertClearsBothGatesWithTheFiguresPinned() throws Exception {
        Shape s = shape(ConvertAdaCollateralFixture.spec());
        ConvertExecutorRig rig = ConvertExecutorRig.shadow(MARGIN, BigInteger.ZERO, s.candidate()).cycle(NOW);

        LiquidationDecision decision = rig.onlyDecision();
        assertEquals(LiquidationDecision.Outcome.WOULD_SUBMIT, decision.outcome(), decision.detail());
        assertEquals(LiquidationDecision.VARIANT_CONVERT_MINSWAP, decision.variant());
        Transaction body = rig.builtOnce();
        BigInteger fee = body.getBody().getFee();
        String expected = "fee slice 324000000 lovelace - tx fee " + fee + " - min-ada 4000000 = floor "
                + BigInteger.valueOf(320_000_000L).subtract(fee) + "; - margin 5000000 = "
                + BigInteger.valueOf(315_000_000L).subtract(fee);
        assertTrue(decision.detail().contains(expected), "expected [" + expected + "] in: " + decision.detail());
        assertEquals(Set.of(new TransactionInput(LOAN_TX, LOAN_IX), new TransactionInput(LOAN_TX, BOND_IX)),
                Set.copyOf(body.getBody().getInputs()), "the inputs are the loan and the bond only");
        assertEquals(2, body.getBody().getInputs().size(), "two inputs");
        assertRow(rig, decision, body, 324_000_000L, BigInteger.ZERO, ORDER_OVERHEAD);
        assertTrue(rig.submitted.isEmpty(), "SHADOW never reaches the submitter");
    }

    /**
     * (b) The same convert on a LIVE node: SUBMITTED, and its row names no wallet output — no "U stays in this check"
     * note, because the body spends none and U was never at risk.
     */
    @Test
    void aLiveZeroWalletConvertIsSubmittedNamingNoWalletOutput() throws Exception {
        Shape s = shape(ConvertAdaCollateralFixture.spec());
        ConvertExecutorRig rig = ConvertExecutorRig.live(MARGIN, BigInteger.ZERO, ACCEPTED, s.candidate()).cycle(NOW);

        LiquidationDecision decision = rig.onlyDecision();
        assertEquals(LiquidationDecision.Outcome.SUBMITTED, decision.outcome(), decision.detail());
        Transaction body = rig.builtOnce();
        BigInteger fee = body.getBody().getFee();
        assertEquals("fee slice 324000000 lovelace - tx fee " + fee + " - min-ada 4000000 = floor "
                        + BigInteger.valueOf(320_000_000L).subtract(fee) + "; - margin 5000000 = "
                        + BigInteger.valueOf(315_000_000L).subtract(fee) + "; submitted as " + ACCEPTED,
                decision.detail());
        assertFalse(decision.detail().contains(LiquidationExecutor.U_STAYS_NOTE), decision.detail());
        assertEquals(1, rig.submitted.size(), "one submission");
        assertEquals(TransactionUtil.getTxHash(body), decision.txHash(), "the row is the built body's");
        assertEquals(Set.of(), rig.walletInputs(body), "the body spends no wallet output");
        assertEquals(Set.of(), rig.walletInputs(Transaction.deserialize(rig.submitted.getFirst())),
                "nor do the submitted bytes");
        assertRow(rig, decision, body, 324_000_000L, BigInteger.ZERO, ORDER_OVERHEAD);
    }

    /**
     * (c) A SYNTHETIC 1‰ bond under margin 0: the router asks for its 3,900,000 shortfall, the executor's selector
     * funds it from U, and the floor is the real trade — the fee slice less the overhead and the transaction fee.
     */
    @Test
    void aShortfallIsFundedFromUAndTheFloorIsTheRealTrade() throws Exception {
        Shape s = shape(ConvertAdaCollateralFixture.spec().bondFeePerMille(1));
        ConvertExecutorRig rig = ConvertExecutorRig.shadow(BigInteger.ZERO, BigInteger.ZERO, s.candidate()).cycle(NOW);

        LiquidationDecision decision = rig.onlyDecision();
        assertEquals(LiquidationDecision.Outcome.WOULD_SUBMIT, decision.outcome(), decision.detail());
        Transaction body = rig.builtOnce();
        BigInteger fee = body.getBody().getFee();
        BigInteger floor = BigInteger.valueOf(4_100_000L).subtract(fee);
        String expected = "fee slice 8100000 lovelace - tx fee " + fee + " - min-ada 4000000 = floor " + floor
                + "; - margin 0 = " + floor;
        assertTrue(decision.detail().contains(expected), "expected [" + expected + "] in: " + decision.detail());
        Utxo u = s.u();
        assertEquals(Set.of(new TransactionInput(LOAN_TX, LOAN_IX), new TransactionInput(LOAN_TX, BOND_IX),
                        new TransactionInput(u.getTxHash(), u.getOutputIndex())),
                Set.copyOf(body.getBody().getInputs()), "the loan, the bond and U, nothing else");
        assertEquals(3, body.getBody().getInputs().size(), "three inputs");
        BigInteger requirement = BigInteger.valueOf(3_900_000L).add(
                LedgerCeilings.maxPossibleFee(EvalFixtures.protocolParams().getProtocolParams()));
        String funded = LOAN_TX + "#" + LOAN_IX + " is funded from wallet output " + u.getTxHash() + "#"
                + u.getOutputIndex() + " (role U, 100000000 lovelace) for a requirement of " + requirement
                + " lovelace";
        assertTrue(rig.info.contains(funded), "expected the funding line [" + funded + "] in: " + rig.info);
        assertRow(rig, decision, body, 8_100_000L, U_LOVELACE, ORDER_OVERHEAD);
    }

    /** (d) The same 1‰ convert under a 5 ADA margin: {@code ConvertEconomics} refuses it after the build. */
    @Test
    void aShortfallUnderTheMarginIsRefusedByConvertEconomics() throws Exception {
        Shape s = shape(ConvertAdaCollateralFixture.spec().bondFeePerMille(1));
        ConvertExecutorRig rig = ConvertExecutorRig.shadow(MARGIN, BigInteger.ZERO, s.candidate()).cycle(NOW);

        LiquidationDecision decision = rig.onlyDecision();
        assertEquals(LiquidationDecision.Outcome.UNPROFITABLE, decision.outcome(), decision.detail());
        assertEquals("NET_BELOW_FLOOR", decision.reason(), decision.detail());
        BigInteger fee = rig.builtOnce().getBody().getFee();
        BigInteger measured = fee.add(ORDER_OVERHEAD);
        BigInteger outlay = measured.max(BigInteger.valueOf(5_000_000L));
        String expected = "convert refused as NET_BELOW_FLOOR: fee 8100000 of collateral is worth 8100000 lovelace, "
                + "outlay " + outlay + " (measured " + measured + ", dex floor 5000000), net "
                + BigInteger.valueOf(8_100_000L).subtract(outlay) + " against a floor of 5000000";
        assertTrue(decision.detail().contains(expected), "expected [" + expected + "] in: " + decision.detail());
    }

    /**
     * (e) A SYNTHETIC 0‰ bond under margin −6,000,000: a loss of {@code 4,000,000 + F}. {@code ConvertEconomics}
     * admits it (its floor is the margin), so it is built; the executor submits it only when the absolute floor
     * admits it too.
     */
    @Test
    void aLossIsBuiltOnlyWhenBothStatedFloorsAdmitIt() throws Exception {
        BigInteger margin = BigInteger.valueOf(-6_000_000L);
        BigInteger absolute = BigInteger.valueOf(-6_000_000L);
        for (BigInteger minProfitAbsolute : List.of(absolute, BigInteger.ZERO)) {
            Shape s = shape(ConvertAdaCollateralFixture.spec().bondFeePerMille(0));
            ConvertExecutorRig rig = ConvertExecutorRig.shadow(margin, minProfitAbsolute, s.candidate()).cycle(NOW);

            LiquidationDecision decision = rig.onlyDecision();
            LiquidationDecision.Outcome expectedOutcome = minProfitAbsolute.equals(absolute)
                    ? LiquidationDecision.Outcome.WOULD_SUBMIT : LiquidationDecision.Outcome.UNPROFITABLE;
            assertEquals(expectedOutcome, decision.outcome(), "absolute floor " + minProfitAbsolute + ": "
                    + decision.detail());
            Transaction body = rig.builtOnce();
            BigInteger fee = body.getBody().getFee();
            String expected = "fee slice 0 lovelace - tx fee " + fee + " - min-ada 4000000 = floor "
                    + BigInteger.valueOf(-4_000_000L).subtract(fee) + "; - margin -6000000 = "
                    + BigInteger.valueOf(2_000_000L).subtract(fee);
            assertTrue(decision.detail().contains(expected), "expected [" + expected + "] in: " + decision.detail());
            assertRow(rig, decision, body, 0L, U_LOVELACE, ORDER_OVERHEAD);
        }
    }

    /**
     * (f) USDM at 1,327,000,000 / 10⁸: the claim equity at the body's validFrom is below the ledger minimum, so the
     * asset-manager output is raised to it out of the bot's change, and that top-up is part of the min-ada the bot
     * funds, beside the order overhead.
     */
    @Test
    void anEquityTopUpIsPartOfTheMinAdaTheBotFunds() throws Exception {
        Feed thin = new Feed(BigInteger.valueOf(1_327_000_000L), DENOMINATOR, USDM_FEED.validFrom(),
                USDM_FEED.validTo());
        Shape s = shape(ConvertAdaCollateralFixture.spec().usdmFeed(thin));
        ConvertExecutorRig rig = ConvertExecutorRig.shadow(MARGIN, BigInteger.ZERO, s.candidate()).cycle(NOW);

        LiquidationDecision decision = rig.onlyDecision();
        assertEquals(LiquidationDecision.Outcome.WOULD_SUBMIT, decision.outcome(), decision.detail());
        Transaction body = rig.builtOnce();

        BigInteger equityOutput = coinAtCredential(body, REGISTRY.getAssetManagerSpendScriptHash());
        long validFromMillis = CONVERTERS.slot().slotToTime(body.getBody().getValidityStartInterval())
                .toInstant(ZoneOffset.UTC).toEpochMilli();
        var loan = s.f().loan;
        BigInteger equity = LoanFinance.redeemerEquity((LiquidationMode.Liquidation) loan.liquidationMode(),
                Rational.fromInt(COLLATERAL_AMOUNT), Rational.fromInt(LoanFinance.remainingDebt(loan, validFromMillis)),
                s.f().principalEntry.feed(), OraclePriceFeed.unit());
        assertTrue(equity.signum() > 0 && equity.compareTo(equityOutput) < 0,
                "a positive equity " + equity + " below the output's " + equityOutput + ": topped up");
        assertRow(rig, decision, body, 324_000_000L, BigInteger.ZERO,
                ORDER_OVERHEAD.add(equityOutput.subtract(equity)));
    }

    // ======================================================================================
    // The leaves
    // ======================================================================================

    /** The fixture with the executor's wallet in its universe, and the candidate the executor is handed. */
    private record Shape(ConvertAdaCollateralFixture f, List<Utxo> wallet, ConvertExecutorRig.Candidate candidate) {

        Utxo u() {
            return wallet.getFirst();
        }
    }

    private static Shape shape(ConvertAdaCollateralFixture.Spec spec) throws Exception {
        List<Utxo> wallet = ConvertExecutorRig.wallet(U_LOVELACE.longValueExact());
        assertEquals(ACCOUNT.baseAddress(), wallet.getLast().getAddress(), "C is the executor's");
        ConvertAdaCollateralFixture f = spec.walletOutputs(wallet.getFirst(), wallet.getLast()).build();
        var assessment = LoanFixtures.assess(f.bond(), f.loan(), f.principalEntry.feed(), OraclePriceFeed.unit(), T0);
        assertTrue(assessment.buildable(), assessment.toString());
        return new Shape(f, wallet, new ConvertExecutorRig.Candidate(f.universe, f.scripts(), wallet, assessment,
                f.loanUtxo, f.bondUtxo, f.request.configUtxo(), f.request.lmConfigUtxo(), f.referenceScripts,
                List.of(ConvertAdaCollateralFixture.poolRow()), List.of(f.principalEntry)));
    }

    /**
     * One row of the class table, off the captured body: the fee slice, the wallet lovelace spent, the min-ada, and
     * H3 — the floor is what came back to the bot's address less what the wallet put in.
     */
    private static void assertRow(ConvertExecutorRig rig, LiquidationDecision decision, Transaction body,
                                  long feeSlice, BigInteger walletIn, BigInteger minAda) {
        assertEquals(BigInteger.valueOf(feeSlice), decision.expectedFeeLovelace(), "the fee slice");
        assertEquals(body.getBody().getFee(), decision.txFeeLovelace(), "the row's tx fee is the built body's");
        assertEquals(walletIn, rig.walletInputCoin(body), "the wallet lovelace the body spends");
        assertEquals(minAda, minAda(decision.detail()), "the min-ada the bot funds: " + decision.detail());
        assertEquals(floor(decision.detail()), coinAtBot(body).subtract(walletIn),
                "H3: floor == Σ coin(outputs at the bot's address) − Σ coin(wallet inputs)");
    }
}
