package com.fluidtokens.aquarium.offchain.service.loans;

import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.MinswapPoolDatum;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>The point of this estimate is that it answers a DIFFERENT question from
 * {@link PoolUsability}, so the tests that matter are the ones where the two disagree.</b>
 *
 * <p>A {@code CONVERT} is atomic and the validator fixes {@code minimum_receive} at the debt — a
 * pool that cannot deliver the whole debt refunds, with both fees paid. An anticipate has no such
 * minimum: the operator owns the collateral and may sell it at any price. So a loan the convert
 * path refuses can still be worth doing, and a test suite that only checked "positive when the
 * pool is deep" would never show that.
 */
class AnticipateAndSellTest {

    private static final AssetType ADA = AssetType.ada();
    private static final AssetType TOKEN = new AssetType("22".repeat(28), "4153434e44");

    /** Collateral is the TOKEN, principal is ada — the swap is token → ada. */
    private static MinswapPoolDatum pool(long reserveToken, long reserveAda, long fee, boolean dynamic) {
        return new MinswapPoolDatum(ADA, TOKEN, BigInteger.TEN,
                BigInteger.valueOf(reserveAda), BigInteger.valueOf(reserveToken),
                BigInteger.valueOf(fee), BigInteger.valueOf(fee), dynamic);
    }

    private static MinswapPoolDatum pool(long reserveToken, long reserveAda) {
        return pool(reserveToken, reserveAda, 30L, false);
    }

    private static final BigInteger SWAPPABLE = BigInteger.valueOf(1_000_000L);
    private static final BigInteger FEE_SLICE = BigInteger.valueOf(200_000L);
    private static final BigInteger ORDER_COST = BigInteger.valueOf(4_000L);

    private static AnticipateAndSell estimate(BigInteger advanceCost, List<MinswapPoolDatum> pools) {
        return AnticipateAndSell.estimate(TOKEN, ADA, SWAPPABLE, FEE_SLICE, advanceCost,
                ORDER_COST, pools);
    }

    /**
     * ⛔ <b>THE CASE THAT JUSTIFIES THE WHOLE COLUMN.</b> The pool cannot deliver the debt, so
     * {@code CONVERT} is refused — but anticipating and selling still clears, because the operator
     * sells {@code swappable + fee} against the ORACLE price they paid, not against the debt.
     */
    @Test
    void aLoanConvertRefusesCanStillBeWorthAnticipating() {
        MinswapPoolDatum thin = pool(20_000_000L, 20_000_000L);
        BigInteger debt = BigInteger.valueOf(2_000_000L);

        PoolUsability convert = PoolUsability.assess(TOKEN, ADA, SWAPPABLE, debt, thin);
        assertFalse(convert.usable(), "the fixture must be a pool CONVERT refuses: " + convert);

        // The lender is paid the oracle value of `swappable`, which is well below the debt here.
        AnticipateAndSell hybrid = estimate(BigInteger.valueOf(900_000L), List.of(thin));

        assertTrue(hybrid.profitable(),
                "the same pool that cannot clear the debt atomically can still leave the operator "
                        + "ahead when they buy the collateral at the oracle price: " + hybrid);
    }

    /** And the converse: a bad enough price loses money, and the estimate must say so, not hide it. */
    @Test
    void anUnderwaterHybridReportsANegativeNumberRatherThanNothing() {
        AnticipateAndSell hybrid = estimate(BigInteger.valueOf(5_000_000L),
                List.of(pool(20_000_000L, 20_000_000L)));

        assertTrue(hybrid.known(), "a loss is a known number, not an unknown one");
        assertFalse(hybrid.profitable(), "this must not read as profitable: " + hybrid);
        assertTrue(hybrid.netInPrincipal().signum() < 0, "expected a negative net: " + hybrid);
        assertTrue(hybrid.detail().contains("NEGATIVE"), "the detail must say so: " + hybrid.detail());
    }

    /**
     * ⚠ The fee slice is sold too, and it cost the operator nothing — it is the cushion that makes
     * the trade clear when Minswap is a little below the oracle. Dropping it from the sale would
     * under-report, which is the direction that hides a viable route.
     */
    @Test
    void theOperatorsOwnFeeSliceIsPartOfWhatGetsSold() {
        List<MinswapPoolDatum> pools = List.of(pool(20_000_000L, 20_000_000L));
        BigInteger advance = BigInteger.valueOf(900_000L);

        AnticipateAndSell withFee = estimate(advance, pools);
        AnticipateAndSell withoutFee = AnticipateAndSell.estimate(TOKEN, ADA, SWAPPABLE,
                BigInteger.ZERO, advance, ORDER_COST, pools);

        assertTrue(withFee.netInPrincipal().compareTo(withoutFee.netInPrincipal()) > 0,
                "selling the fee slice as well must improve the net: " + withFee + " vs " + withoutFee);
    }

    /**
     * ⛔ BEST PROCEEDS, NOT DEEPEST POOL — the same lesson as {@code bestVerdict}. Depth orders
     * pools; output turns on price and fee too.
     */
    @Test
    void theBestPayingPoolIsChosenEvenWhenItIsNotTheDeepest() {
        MinswapPoolDatum deepButPoorlyPriced = pool(900_000_000L, 90_000_000L);
        MinswapPoolDatum shallowerButWellPriced = pool(50_000_000L, 60_000_000L);

        AnticipateAndSell both = estimate(BigInteger.valueOf(900_000L),
                List.of(deepButPoorlyPriced, shallowerButWellPriced));
        AnticipateAndSell deepOnly = estimate(BigInteger.valueOf(900_000L),
                List.of(deepButPoorlyPriced));

        assertTrue(both.netInPrincipal().compareTo(deepOnly.netInPrincipal()) > 0,
                "the well-priced shallower pool must win: " + both + " vs " + deepOnly);
    }

    /** A pool for another pair cannot sell this collateral, and must not be quoted as if it could. */
    @Test
    void aPoolForAnotherPairIsIgnored() {
        AssetType other = new AssetType("33".repeat(28), "4f544845");
        MinswapPoolDatum wrongPair = new MinswapPoolDatum(ADA, other, BigInteger.TEN,
                BigInteger.valueOf(90_000_000L), BigInteger.valueOf(90_000_000L),
                BigInteger.valueOf(30L), BigInteger.valueOf(30L), false);

        AnticipateAndSell hybrid = estimate(BigInteger.valueOf(900_000L), List.of(wrongPair));

        assertFalse(hybrid.known(), "a different pair must not produce a number: " + hybrid);
        assertNull(hybrid.netInPrincipal());
    }

    /**
     * ⚠ A dynamic-fee pool understates the cost, and understating is the direction that FLATTERS
     * this number into looking profitable. Refused, exactly as {@link ConvertOrderPlan} refuses it.
     */
    @Test
    void aDynamicFeePoolIsRefusedRatherThanQuotedOptimistically() {
        AnticipateAndSell hybrid = estimate(BigInteger.valueOf(900_000L),
                List.of(pool(20_000_000L, 20_000_000L, 30L, true)));

        assertFalse(hybrid.known(), "a dynamic-fee pool must not be quoted: " + hybrid);
        assertTrue(hybrid.detail().contains("dynamic fee"), "the reason must say why: " + hybrid.detail());
    }

    /** No pool, no sale — and the cell must say that rather than render as zero. */
    @Test
    void noPoolIsUnknownAndNotZero() {
        AnticipateAndSell hybrid = estimate(BigInteger.valueOf(900_000L), List.of());
        assertFalse(hybrid.known());
        assertNull(hybrid.netInPrincipal(), "a missing pool must never be reported as a zero net");
    }
}
