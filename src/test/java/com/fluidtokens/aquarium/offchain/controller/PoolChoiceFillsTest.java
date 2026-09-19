package com.fluidtokens.aquarium.offchain.controller;

import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.MinswapPoolDatum;
import com.fluidtokens.aquarium.offchain.service.loans.PoolUsability;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * ⛔ <b>DEPTH ORDERS THE CANDIDATES; THE FILL TEST DECIDES BETWEEN THEM.</b>
 *
 * <p>Once a pair can have several pools, "which pool" stops being a lookup and becomes a question
 * about this loan. Constant-product output is
 * {@code in·(1−fee)·reserveOut / (reserveIn + in·(1−fee))} — it turns on the pool's <b>price</b>
 * ({@code reserveOut/reserveIn}) and its <b>fee</b>, not on depth alone. Two pools of one pair are
 * separate AMMs: arbitrage keeps their prices close but not equal, and their fee numerators can
 * differ outright.
 *
 * <p>⚠ So ranking by depth and reporting the winner answers <i>"can the biggest pool fill this?"</i>
 * when the operator asked <i>"can ANY pool fill this?"</i> — and the answers differ precisely when
 * it matters: a deep pool at a poor price returning less than a shallow one at a good price. The
 * page would then read TOO_THIN, the market would be routed to CAPITAL IN ADVANCE, and the operator
 * would be told to find principal for a loan a pool would have cleared for free.
 */
class PoolChoiceFillsTest {

    private static final AssetType ADA = AssetType.ada();
    private static final AssetType TOKEN = new AssetType("11".repeat(28), "464c4454");

    /** Collateral is the TOKEN, principal is ada — so the swap is token → ada. */
    private static MinswapPoolDatum pool(long reserveToken, long reserveAda, long feeNumerator) {
        return new MinswapPoolDatum(ADA, TOKEN, BigInteger.TEN,
                BigInteger.valueOf(reserveAda), BigInteger.valueOf(reserveToken),
                BigInteger.valueOf(feeNumerator), BigInteger.valueOf(feeNumerator), false);
    }

    private static final BigInteger SWAPPABLE = BigInteger.valueOf(1_000_000L);
    private static final BigInteger DEBT = BigInteger.valueOf(900_000L);

    /**
     * The case the depth-only choice gets wrong: the DEEPER pool prices the token badly (lots of
     * token, little ada) and cannot clear the debt; the shallower one prices it well and can.
     */
    @Test
    void aShallowerPoolThatCanFillBeatsADeeperOneThatCannot() {
        MinswapPoolDatum deepButPoorlyPriced = pool(900_000_000L, 90_000_000L, 30);
        MinswapPoolDatum shallowerButWellPriced = pool(50_000_000L, 60_000_000L, 30);

        assertEquals(PoolUsability.Verdict.TOO_THIN,
                PoolUsability.assess(TOKEN, ADA, SWAPPABLE, DEBT, deepButPoorlyPriced).verdict(),
                "fixture check: the deeper pool must genuinely fail to clear the debt");
        assertEquals(PoolUsability.Verdict.USABLE,
                PoolUsability.assess(TOKEN, ADA, SWAPPABLE, DEBT, shallowerButWellPriced).verdict(),
                "fixture check: the shallower pool must genuinely clear it");

        // deepest first, exactly as the resolver ranks them
        var chosen = LiquidationReadinessController.bestVerdict(
                List.of(deepButPoorlyPriced, shallowerButWellPriced), TOKEN, ADA, SWAPPABLE, DEBT);

        assertEquals(PoolUsability.Verdict.USABLE, chosen.verdict(),
                "a pool that clears the debt exists, so the pair is usable — reporting the deepest "
                        + "pool's TOO_THIN would send the operator to find capital unnecessarily");
    }

    /** And the converse still holds: when nothing can fill, the pair is honestly too thin. */
    @Test
    void whenNoPoolCanFillThePairIsStillTooThin() {
        var chosen = LiquidationReadinessController.bestVerdict(
                List.of(pool(900_000_000L, 90_000_000L, 30), pool(50_000_000L, 5_000_000L, 30)),
                TOKEN, ADA, SWAPPABLE, DEBT);

        assertEquals(PoolUsability.Verdict.TOO_THIN, chosen.verdict(),
                "no pool clears the debt, so the pair is genuinely too thin");
    }

    /**
     * ⚠ And the shortfall reported is the DEEPEST pool's, not an arbitrary one — it is the smallest,
     * so it states how far short the pair actually is rather than how far short its worst pool is.
     */
    @Test
    void theShortfallReportedIsTheSmallestOne() {
        MinswapPoolDatum deepest = pool(900_000_000L, 90_000_000L, 30);
        MinswapPoolDatum worse = pool(900_000_000L, 1_000_000L, 30);

        var chosen = LiquidationReadinessController.bestVerdict(List.of(deepest, worse),
                TOKEN, ADA, SWAPPABLE, DEBT);
        var worseOnly = PoolUsability.assess(TOKEN, ADA, SWAPPABLE, DEBT, worse);

        assertEquals(PoolUsability.assess(TOKEN, ADA, SWAPPABLE, DEBT, deepest).detail(),
                chosen.detail(), "the deepest pool's shortfall is the one worth reporting");
        assertNotEquals(worseOnly.detail(), chosen.detail(),
                "not the worst pool's, which would overstate how far short the pair is");
    }
}
