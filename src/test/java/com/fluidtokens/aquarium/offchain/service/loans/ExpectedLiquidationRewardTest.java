package com.fluidtokens.aquarium.offchain.service.loans;

import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.CollateralAsset;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.model.loans.Loan;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.model.loans.RepaymentMode;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * FAB-144: the one function behind the readiness page's "Expected reward" and the bot's liquidation
 * order. Every expected value below is computed by hand in the test, so a change to the arithmetic
 * cannot quietly move both the page and the order together and still pass.
 */
class ExpectedLiquidationRewardTest {

    private static final AssetType TOKEN = new AssetType("c0".repeat(28), "544f4b");
    private static final AssetType TOKEN_ORACLE = new AssetType("b0".repeat(28), "4f52434c");
    private static final long NOW = 1_700_000_000_000L;

    private static Loan loan(CollateralAsset collateral, long collateralAmount) {
        LoanDatum datum = LoanFixtures.loanDatum(AssetType.ada(), BigInteger.valueOf(100_000_000L),
                BigInteger.ZERO, collateral, 0L, LoanFixtures.liquidation(),
                new RepaymentMode.PrincipalAndInterestOnInstallments(), false);
        return new Loan("f0".repeat(32), 0, "addr_test1_placeholder", "loanid00",
                BigInteger.valueOf(collateralAmount), BigInteger.valueOf(2_000_000L), datum);
    }

    private static LenderBond bond(long feePerMille) {
        return new LenderBond("f0".repeat(32), 1, "addr_test1_placeholder", "loanid00", "",
                LoanFixtures.bondDatum(BigInteger.valueOf(feePerMille), LoanFixtures.noStakeCredential(),
                        AssetType.ada()));
    }

    /** A Charli3 feed for {@link #TOKEN} at {@code numerator/denominator} lovelace per base unit. */
    private static OraclePriceFeed tokenFeed(long numerator, long denominator) {
        return OraclePriceFeed.priceDataCharlie(TOKEN, BigInteger.valueOf(numerator),
                BigInteger.valueOf(denominator), NOW - 60_000L, NOW + 600_000L);
    }

    /** 1,234,567 × 33 / 1000 = 40,740.711 — truncated, never rounded, to 40,740. */
    @Test
    void feeTokensIsTheTruncatedPerMilleSliceOfTheCollateral() {
        assertEquals(BigInteger.valueOf(40_740L),
                ExpectedLiquidationReward.feeTokens(loan(LoanFixtures.tokenCollateral(TOKEN, TOKEN_ORACLE),
                        1_234_567L), bond(33)));
    }

    /**
     * 300,000,000 TOK at 50/1000 is a 15,000,000-TOK slice; at a price of 2/7 lovelace per TOK that is
     * 30,000,000 / 7 = 4,285,714.28…, floored to 4,285,714 lovelace.
     */
    @Test
    void aTokenSliceIsPricedThroughTheCollateralFeedAndFloored() {
        Loan loan = loan(LoanFixtures.tokenCollateral(TOKEN, TOKEN_ORACLE), 300_000_000L);

        assertEquals(BigInteger.valueOf(15_000_000L), ExpectedLiquidationReward.feeTokens(loan, bond(50)));
        assertEquals(BigInteger.valueOf(4_285_714L),
                ExpectedLiquidationReward.lovelace(BigInteger.valueOf(15_000_000L), tokenFeed(2, 7)));
        assertEquals(Optional.of(BigInteger.valueOf(4_285_714L)),
                ExpectedLiquidationReward.estimate(loan, bond(50), Optional.of(tokenFeed(2, 7))));
    }

    /**
     * Ada collateral needs no oracle: the client answers the 1:1 unit feed for ada whatever the datum
     * names, without consulting its registry, so the estimate is the slice itself — 100,000,000 × 37 /
     * 1000 = 3,700,000 lovelace.
     */
    @Test
    void adaCollateralPricesOneToOneThroughTheUnitFeedTheClientReturnsForAda() {
        Loan loan = loan(LoanFixtures.adaCollateral(), 100_000_000L);
        FluidOracleClient client = new FluidOracleClient("http://unused.invalid");

        Optional<OraclePriceFeed> feed = client.findFeedForLeg(loan.datum().collateral().assetType(),
                loan.datum().collateral().oracleTokenAsset(), NOW);

        assertEquals(Optional.of(OraclePriceFeed.unit()), feed, "fixture premise: ada gets the unit feed");
        assertEquals(Optional.of(BigInteger.valueOf(3_700_000L)),
                ExpectedLiquidationReward.estimate(loan, bond(37), feed));
    }

    @Test
    void noFeedIsUnpriced() {
        Loan loan = loan(LoanFixtures.tokenCollateral(TOKEN, TOKEN_ORACLE), 300_000_000L);

        assertEquals(Optional.empty(), ExpectedLiquidationReward.estimate(loan, bond(50), Optional.empty()));
        assertEquals(Optional.empty(), ExpectedLiquidationReward.estimate(loan, bond(50), null));
    }

    /**
     * A pooled feed cannot be priced ({@code finance.ak} fails on it): {@code lovelace} throws, as the
     * page has always reported, and {@code estimate} turns that into unpriced rather than throwing.
     */
    @Test
    void aFeedThatThrowsIsUnpricedAndEstimateNeverThrows() {
        Loan loan = loan(LoanFixtures.tokenCollateral(TOKEN, TOKEN_ORACLE), 300_000_000L);
        OraclePriceFeed pooled = new OraclePriceFeed(OraclePriceFeed.Variant.POOLED, TOKEN,
                BigInteger.TWO, BigInteger.valueOf(7), NOW - 60_000L, NOW + 600_000L);

        assertThrows(UnsupportedOperationException.class,
                () -> ExpectedLiquidationReward.lovelace(BigInteger.TEN, pooled));
        assertEquals(Optional.empty(), ExpectedLiquidationReward.estimate(loan, bond(50), Optional.of(pooled)));
    }

    @Test
    void aMissingBondOrLoanOrAmountIsUnpriced() {
        Loan loan = loan(LoanFixtures.tokenCollateral(TOKEN, TOKEN_ORACLE), 300_000_000L);
        Loan noAmount = new Loan(loan.txHash(), loan.outputIndex(), loan.address(), loan.loanId(), null,
                loan.lovelace(), loan.datum());

        assertEquals(Optional.empty(), ExpectedLiquidationReward.estimate(loan, null, Optional.of(tokenFeed(2, 7))));
        assertEquals(Optional.empty(), ExpectedLiquidationReward.estimate(null, bond(50), Optional.of(tokenFeed(2, 7))));
        assertEquals(Optional.empty(),
                ExpectedLiquidationReward.estimate(noAmount, bond(50), Optional.of(tokenFeed(2, 7))));
    }
}
