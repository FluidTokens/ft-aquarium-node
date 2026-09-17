package com.fluidtokens.aquarium.offchain.service.loans;

import java.math.BigInteger;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.MinswapPoolDatum;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ The four outcomes an operator acts on differently must be <b>distinguishable</b>, not merely
 * rendered differently.
 *
 * <p>The failure that costs money is a liquidation that does not happen because the capital was not
 * ready. Every verdict here exists because it changes what the operator does next:
 *
 * <ul>
 *   <li><b>CHECK_FAILED</b> — try again shortly. Nothing is known.</li>
 *   <li><b>NO_POOL</b> — hold capital for this loan from now on. Permanent until a pool is created.</li>
 *   <li><b>TOO_THIN</b> — hold capital for <em>this</em> loan; a smaller loan on the same pair may
 *       still convert, and this one may convert later as the pool grows.</li>
 *   <li><b>USABLE</b> — convert will cover it; capital is not needed here.</li>
 * </ul>
 *
 * <p>A test that cannot tell two of these apart is the defect this page exists to prevent.
 */
class PoolUsabilityTest {

    private static final AssetType FLDT = new AssetType("11".repeat(28), "464c4454");
    private static final AssetType USDM = new AssetType("22".repeat(28), "5553444d");

    /** A deep ada/FLDT pool: 1.69M ada against 7.6M FLDT, the live mainnet shape. */
    private static MinswapPoolDatum deepPool() {
        return new MinswapPoolDatum(AssetType.ada(), FLDT, BigInteger.valueOf(1_000_000L),
                new BigInteger("1690000000000"), new BigInteger("7600000000000"),
                BigInteger.valueOf(30), BigInteger.valueOf(30), false);
    }

    /** The same pair, almost no depth. */
    private static MinswapPoolDatum thinPool() {
        return new MinswapPoolDatum(AssetType.ada(), FLDT, BigInteger.TEN,
                BigInteger.valueOf(1_000_000L), BigInteger.valueOf(2_000_000L),
                BigInteger.valueOf(30), BigInteger.valueOf(30), false);
    }

    // ---- the four outcomes -----------------------------------------------------------------------

    @Test
    void aDeepPoolThatClearsTheDebtIsUsable() {
        PoolUsability u = PoolUsability.assess(AssetType.ada(), FLDT,
                BigInteger.valueOf(10_000_000L), BigInteger.valueOf(1_000_000L), deepPool());

        assertEquals(PoolUsability.Verdict.USABLE, u.verdict());
        assertTrue(u.usable());
    }

    /**
     * ⛔ The per-loan case, and the one Giovanni named: *"some smaller loan could use the convert,
     * other might not"*. Same pool, same pair — only the loan's size differs.
     */
    @Test
    void theSamePoolIsUsableForASmallLoanAndTooThinForALargeOne() {
        MinswapPoolDatum pool = thinPool();

        PoolUsability small = PoolUsability.assess(AssetType.ada(), FLDT,
                BigInteger.valueOf(1_000L), BigInteger.valueOf(1_000L), pool);
        PoolUsability large = PoolUsability.assess(AssetType.ada(), FLDT,
                BigInteger.valueOf(1_000L), new BigInteger("999999999"), pool);

        assertEquals(PoolUsability.Verdict.USABLE, small.verdict(), "a small debt clears");
        assertEquals(PoolUsability.Verdict.TOO_THIN, large.verdict(), "a large one does not");
        assertNotEquals(small.verdict(), large.verdict(),
                "if the same pool gives the same verdict regardless of the loan, the page is lying");
    }

    /** The shortfall is stated as a real quantity, and never as a percentage or a price. */
    @Test
    void aThinPoolNamesTheShortfallAndNotAPercentage() {
        PoolUsability u = PoolUsability.assess(AssetType.ada(), FLDT,
                BigInteger.valueOf(1_000L), new BigInteger("999999999"), thinPool());

        assertEquals(PoolUsability.Verdict.TOO_THIN, u.verdict());
        assertTrue(u.detail().contains("short by"), "the shortfall must be named: " + u.detail());
        assertTrue(u.detail().contains("refunded"),
                "and the consequence — a refund at the operator's expense — must be stated");
        assertFalse(u.detail().contains("%"),
                "no slippage percentage: it reads as precision a moving reserve snapshot cannot claim");
    }

    @Test
    void aFailedLookupIsNotTheSameThingAsNoPool() {
        PoolUsability failed = PoolUsability.checkFailed("SocketTimeoutException");
        PoolUsability none = PoolUsability.noPool();

        assertEquals(PoolUsability.Verdict.CHECK_FAILED, failed.verdict());
        assertEquals(PoolUsability.Verdict.NO_POOL, none.verdict());
        assertNotEquals(failed.verdict(), none.verdict(),
                "collapsing these two tells an operator to hold capital forever over a timeout");
        assertTrue(failed.detail().contains("not evidence that no pool exists"),
                "the failed check must say it proves nothing: " + failed.detail());
        assertTrue(none.detail().contains("no Minswap pool exists"));
    }

    // ---- the states that are about the pool rather than the loan ---------------------------------

    @Test
    void aPoolForAnotherPairIsRefusedRatherThanQuoted() {
        MinswapPoolDatum other = new MinswapPoolDatum(FLDT, USDM, BigInteger.TEN,
                BigInteger.valueOf(1_000L), BigInteger.valueOf(1_000L),
                BigInteger.valueOf(30), BigInteger.valueOf(30), false);

        assertEquals(PoolUsability.Verdict.WRONG_PAIR,
                PoolUsability.assess(AssetType.ada(), FLDT, BigInteger.TEN, BigInteger.ONE, other).verdict());
    }

    /** A dynamic-fee pool cannot be quoted honestly, so it says so rather than guessing. */
    @Test
    void aDynamicFeePoolRefusesToQuoteRatherThanGuess() {
        MinswapPoolDatum dynamic = new MinswapPoolDatum(AssetType.ada(), FLDT, BigInteger.TEN,
                new BigInteger("1690000000000"), new BigInteger("7600000000000"),
                BigInteger.valueOf(30), BigInteger.valueOf(30), true);

        PoolUsability u = PoolUsability.assess(AssetType.ada(), FLDT,
                BigInteger.valueOf(10_000L), BigInteger.ONE, dynamic);

        assertEquals(PoolUsability.Verdict.CANNOT_QUOTE, u.verdict());
        assertNotEquals(PoolUsability.Verdict.USABLE, u.verdict(),
                "an unquotable pool must never read as usable");
    }

    @Test
    void nothingLeftToSwapIsTooThinRatherThanUsable() {
        assertEquals(PoolUsability.Verdict.TOO_THIN,
                PoolUsability.assess(AssetType.ada(), FLDT, BigInteger.ZERO, BigInteger.ONE, deepPool()).verdict());
    }

    @Test
    void missingFiguresAreUnknownRatherThanAGuess() {
        assertEquals(PoolUsability.Verdict.UNKNOWN,
                PoolUsability.assess(AssetType.ada(), FLDT, null, BigInteger.ONE, deepPool()).verdict());
        assertEquals(PoolUsability.Verdict.UNKNOWN,
                PoolUsability.assess(AssetType.ada(), FLDT, BigInteger.TEN, BigInteger.ONE, null).verdict());
    }

    /**
     * ⛔ THE STRUCTURAL GUARD. Every verdict must carry its own distinct wording — if two ever collapse
     * to the same text, the page shows the same sentence for situations needing different actions, and
     * no per-state test would catch it because each would still pass alone.
     */
    @Test
    void everyVerdictIsDistinguishableFromEveryOther() {
        List<PoolUsability> all = List.of(
                PoolUsability.assess(AssetType.ada(), FLDT, BigInteger.valueOf(10_000_000L),
                        BigInteger.valueOf(1_000_000L), deepPool()),
                PoolUsability.assess(AssetType.ada(), FLDT, BigInteger.valueOf(1_000L),
                        new BigInteger("999999999"), thinPool()),
                PoolUsability.noPool(),
                PoolUsability.checkFailed("boom"),
                PoolUsability.notConfigured());

        Set<PoolUsability.Verdict> verdicts = new LinkedHashSet<>();
        Set<String> details = new LinkedHashSet<>();
        for (PoolUsability u : all) {
            verdicts.add(u.verdict());
            details.add(u.detail());
        }
        assertEquals(all.size(), verdicts.size(), "two outcomes share a verdict: " + verdicts);
        assertEquals(all.size(), details.size(), "two outcomes share wording, so the page cannot tell "
                + "an operator which one they are looking at");
        assertEquals(1, all.stream().filter(PoolUsability::usable).count(),
                "exactly one of these is a pool that will actually cover the loan");
    }
}
