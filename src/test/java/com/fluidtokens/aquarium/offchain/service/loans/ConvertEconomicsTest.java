package com.fluidtokens.aquarium.offchain.service.loans;

import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.ConvertAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.ConvertExclusion;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The convert profitability gate. Stage 1 of the LiquidateAndConvert build.
 *
 * <p>The cases here are chosen so that the two figures most likely to be got wrong — the validator's
 * integer-truncated fee, and the 2.8 ada a token collateral forces into the Minswap order — each have
 * a test that goes red on their own if the arithmetic drifts.
 */
class ConvertEconomicsTest {

    private static final BigInteger ADA = BigInteger.valueOf(1_000_000L);

    private static AssetType token() {
        return new AssetType("aa".repeat(28), "544f4b454e");
    }

    /** A feed pricing one unit of the collateral at {@code lovelaces/denominator}. */
    private static OraclePriceFeed feed(long lovelaces, long denominator) {
        return OraclePriceFeed.aggregated(token(), BigInteger.valueOf(lovelaces),
                BigInteger.valueOf(denominator), 0L, Long.MAX_VALUE);
    }

    /**
     * The DEX-cost floor is set to 0 here so each case exercises the figure it is about. The floor has
     * its own tests below; leaving the shipped 5 ada in every case would make every other assertion a
     * test of the floor instead.
     */
    private static ConvertEconomics economics(boolean enabled, long floorLovelace) {
        return economics(enabled, floorLovelace, 0L);
    }

    private static ConvertEconomics economics(boolean enabled, long floorLovelace, long dexCostFloor) {
        return new ConvertEconomics(
                new AppConfig.ConvertConfiguration(enabled, BigInteger.valueOf(floorLovelace),
                        BigInteger.valueOf(dexCostFloor)),
                network("preview"));
    }

    private static AppConfig.Network network(String name) {
        var n = new AppConfig.Network();
        ReflectionTestUtils.setField(n, "network", name);
        return n;
    }

    // ---- arming and eligibility -------------------------------------------------------------

    @Test
    void aDisabledPathRefusesBeforeAnythingElseIsEvenLookedAt() {
        ConvertAssessment a = economics(false, 0)
                .assess(true, BigInteger.valueOf(1_000_000_000L), 50L, true, feed(1, 1), ADA);

        assertFalse(a.approved());
        assertEquals(ConvertExclusion.NOT_ARMED, a.exclusion());
        assertNull(a.net(), "a refusal before the arithmetic must not invent numbers to report");
    }

    /**
     * ⛔ The lender's choice, not the operator's. {@code shouldLiquidationConvertToPrincipal} is the
     * validator's first conjunct; converting a bond that forbids it fails on chain after the fee is
     * spent.
     */
    @Test
    void aBondThatForbidsConversionIsRefusedNoMatterHowProfitableItLooks() {
        ConvertAssessment a = economics(true, 0)
                .assess(false, BigInteger.valueOf(1_000_000_000L), 500L, true, feed(1, 1), ADA);

        assertFalse(a.approved());
        assertEquals(ConvertExclusion.BOND_FORBIDS_CONVERSION, a.exclusion());
    }

    // ---- the fee is in COLLATERAL units, so it needs a price ---------------------------------

    @Test
    void noCollateralFeedMeansTheFeeCannotBeValuedAtAllAndIsRefusedRatherThanGuessed() {
        ConvertAssessment a = economics(true, 0)
                .assess(true, BigInteger.valueOf(1_000_000_000L), 500L, false, null, ADA);

        assertFalse(a.approved());
        assertEquals(ConvertExclusion.COLLATERAL_UNPRICEABLE, a.exclusion());
    }

    /** A {@code Pooled} feed is an outright {@code fail} in finance.ak — it must not price to zero. */
    @Test
    void aPooledFeedIsUnpriceableRatherThanWorthNothing() {
        OraclePriceFeed pooled = new OraclePriceFeed(OraclePriceFeed.Variant.POOLED, token(),
                BigInteger.ONE, BigInteger.ONE, 0L, Long.MAX_VALUE);

        ConvertAssessment a = economics(true, 0)
                .assess(true, BigInteger.valueOf(1_000_000_000L), 500L, false, pooled, ADA);

        assertEquals(ConvertExclusion.COLLATERAL_UNPRICEABLE, a.exclusion());
    }

    // ---- the validator's arithmetic ----------------------------------------------------------

    /**
     * {@code collateral * feePerMille / 1000}, truncated. Rounding UP would make the order short of
     * collateral the validator computed from the same expression, which is a phase-2 failure.
     */
    @Test
    void theFeeTruncatesExactlyAsTheValidatorDoes() {
        assertEquals(BigInteger.ZERO, ConvertEconomics.liquidationFee(BigInteger.valueOf(999L), 1L),
                "999 * 1 / 1000 is 0 on chain; a rounded-up 1 is collateral the order would not have");
        assertEquals(BigInteger.valueOf(1L), ConvertEconomics.liquidationFee(BigInteger.valueOf(1999L), 1L));
        assertEquals(BigInteger.valueOf(50L), ConvertEconomics.liquidationFee(BigInteger.valueOf(1000L), 50L));
    }

    // ---- the Minswap order overhead, the term most likely to be forgotten --------------------

    /**
     * ⛔ THE ONE THAT PAYS FOR THIS FILE. Every convert funds {@code minswap_order_overhead}
     * (4,000,000) into the order, and that ada leaves with the order — the receiver is the lender's
     * asset manager, not the bot.
     *
     * <p>⚑ <b>This test used to assert the OPPOSITE for an ada collateral, and that asymmetry was
     * the defect.</b> Until db5069e the ada branch required the order to hold exactly the swappable
     * amount and nothing else, so the model recorded a zero cost — truthfully, because the validator
     * really did mandate no extra ada. It was the validator that was wrong: an order with no room for
     * a batcher fee can never be batched (findings §57.9). The assertion is inverted rather than
     * deleted, so the symmetry is pinned and a regression to the old shape is visible.
     */
    @Test
    void everyConvertFundsTheMinswapOverhead_theOldAdaExemptionWasTheDefect() {
        // Fee income worth 2_000_000 lovelace, tx fee 1_000_000.
        ConvertEconomics gate = economics(true, 0);
        BigInteger collateral = BigInteger.valueOf(1_000_000L);
        long feePerMille = 4L;          // -> 4_000 collateral units
        OraclePriceFeed price = feed(500, 1);   // -> 2_000_000 lovelace

        ConvertAssessment asAda = gate.assess(true, collateral, feePerMille, true, price, ADA);
        ConvertAssessment asToken = gate.assess(true, collateral, feePerMille, false, price, ADA);

        assertEquals(BigInteger.valueOf(4_000L), asAda.liquidationFee());
        assertEquals(BigInteger.valueOf(2_000_000L), asAda.feeValueLovelace());

        // ⛔ Both kinds now, and the ada case is the one that changed: `>= swappable + overhead`.
        assertEquals(BigInteger.valueOf(4_000_000L), asAda.orderAdaFunded(),
                "an ada collateral funds the overhead too since db5069e — the old zero was the "
                        + "unbatchable shape, not a saving");
        assertEquals(BigInteger.valueOf(4_000_000L), asToken.orderAdaFunded());

        assertEquals(BigInteger.valueOf(5_000_000L), asAda.outlay());
        assertEquals(BigInteger.valueOf(-3_000_000L), asAda.net());
        assertFalse(asAda.approved(), "the overhead is the bot's and it does not come back — a model "
                + "that omits it approves a loss, whichever kind the collateral is");
        assertEquals(ConvertExclusion.NET_BELOW_FLOOR, asAda.exclusion());

        assertEquals(BigInteger.valueOf(5_000_000L), asToken.outlay());
        assertEquals(BigInteger.valueOf(-3_000_000L), asToken.net());
        assertFalse(asToken.approved());
        assertEquals(ConvertExclusion.NET_BELOW_FLOOR, asToken.exclusion());
    }

    // ---- the DEX-cost floor, Giovanni's 4-5 ada -------------------------------------------------

    /**
     * ⛔ THE FLOOR IS A max(), NOT A SUM. Giovanni's ruling rounds the whole DEX interaction — batcher
     * fee plus transaction fee — to 4 or 5 ada. Adding the floor to the measured cost would charge the
     * batcher twice; taking the larger of the two charges it once and never less than he stated.
     */
    @Test
    void theDexCostFloorBindsWhenTheMeasuredCostIsSmallerAndIsNeverAddedToIt() {
        // Fee income 10_000_000 lovelace; measured cost is only the 1_000_000 tx fee.
        ConvertEconomics gate = economics(true, 0, 5_000_000L);
        ConvertAssessment a = gate.assess(true, BigInteger.valueOf(1_000_000L), 10L, true,
                feed(1_000, 1), ADA);

        assertEquals(BigInteger.valueOf(10_000_000L), a.feeValueLovelace());
        assertEquals(BigInteger.valueOf(5_000_000L), a.measuredOutlay(),
                "what this tx demonstrably costs: 1 ada tx fee + the 4 ada Minswap overhead");
        assertEquals(BigInteger.valueOf(5_000_000L), a.dexCostFloor());
        assertEquals(BigInteger.valueOf(5_000_000L), a.outlay(),
                "max(measured, floor) — a sum would be 10_000_000 and would charge the batcher twice");
        assertEquals(BigInteger.valueOf(5_000_000L), a.net());
    }

    /**
     * ⚑ The floor still binds when the measurement is small. Since db5069e an ada collateral also
     * funds the 4 ada overhead, so the gap the floor closes is narrower than it was — but a cheap
     * transaction can still measure below the operator's stated cost of doing DEX work.
     */
    @Test
    void theFloorStillBindsWhenTheMeasuredCostFallsBelowIt() {
        // Fee worth 3_000_000; tx fee 500_000. Profitable on the measurement, refused under the floor.
        ConvertEconomics gate = economics(true, 0, 5_000_000L);
        ConvertAssessment a = gate.assess(true, BigInteger.valueOf(1_000_000L), 3L, true,
                feed(1_000, 1), BigInteger.valueOf(500_000L));

        assertEquals(BigInteger.valueOf(4_500_000L), a.measuredOutlay(),
                "0.5 ada tx fee + the 4 ada Minswap overhead");
        assertEquals(BigInteger.valueOf(5_000_000L), a.outlay());
        assertEquals(BigInteger.valueOf(-2_000_000L), a.net());
        assertFalse(a.approved(), "the measurement alone would have approved this");
    }

    /** A measured cost above the floor governs; the floor is a minimum, never a cap. */
    @Test
    void aMeasuredCostAboveTheFloorGovernsInstead() {
        ConvertEconomics gate = economics(true, 0, 5_000_000L);
        ConvertAssessment a = gate.assess(true, BigInteger.valueOf(1_000_000L), 10L, false,
                feed(1_000, 1), BigInteger.valueOf(4_000_000L));

        assertEquals(BigInteger.valueOf(8_000_000L), a.measuredOutlay(), "4 ada tx fee + 4 ada order");
        assertEquals(BigInteger.valueOf(8_000_000L), a.outlay());
        assertFalse(a.boundByDexCostFloor());
    }

    @Test
    void aNegativeOrAbsentDexCostFloorIsRefusedOnEveryNetwork() {
        for (String net : new String[]{"preview", "mainnet"}) {
            var bad = new AppConfig.ConvertConfiguration(true, BigInteger.ZERO, BigInteger.valueOf(-1L));
            assertThrows(IllegalStateException.class,
                    () -> new ConvertEconomics(bad, network(net)).announceAndGuard(),
                    "a negative cost of doing work is a typo, not a bound an operator can state");

            var unset = new AppConfig.ConvertConfiguration(true, BigInteger.ZERO, null);
            assertThrows(IllegalStateException.class,
                    () -> new ConvertEconomics(unset, network(net)).announceAndGuard());
        }
    }

    // ---- the margin floor -----------------------------------------------------------------------

    @Test
    void theFloorIsInclusiveOnItsExactValueAndRefusesOneLovelaceBelow() {
        // ⚠ Scaled past the 4 ada Minswap overhead, which every convert now funds: the property
        // under test is that net == floor is ALLOWED, and it needs an income that clears the
        // overhead before a hundred lovelace of margin means anything.
        // fee 1_000 units at 4_001 lovelace each = 4_001_000; txFee 900 + overhead 4_000_000 -> net 100.
        ConvertEconomics gate = economics(true, 100L);
        BigInteger collateral = BigInteger.valueOf(1_000_000L);

        ConvertAssessment onTheLine =
                gate.assess(true, collateral, 1L, true, feed(4_001, 1), BigInteger.valueOf(900L));
        assertEquals(BigInteger.valueOf(100L), onTheLine.net());
        assertTrue(onTheLine.approved(), "net == floor must be allowed; a strict > silently raises "
                + "every operator's stated bound by one lovelace");

        ConvertAssessment justUnder =
                gate.assess(true, collateral, 1L, true, feed(4_001, 1), BigInteger.valueOf(901L));
        assertEquals(BigInteger.valueOf(99L), justUnder.net());
        assertFalse(justUnder.approved());
    }

    /**
     * A lender who set no liquidation fee pays the bot nothing. The default floor refuses it with no
     * special case, exactly as the compound path does — and the assessment still says so out loud, so
     * an operator reading a decision log sees WHY rather than only that the net was negative.
     */
    @Test
    void aZeroFeeBondIsRefusedByTheDefaultFloorAndSaysSo() {
        ConvertAssessment a = economics(true, 0)
                .assess(true, BigInteger.valueOf(1_000_000_000L), 0L, true, feed(1, 1), ADA);

        assertEquals(BigInteger.ZERO, a.liquidationFee());
        assertTrue(a.zeroFeeBond());
        assertFalse(a.approved());
        assertEquals(ConvertExclusion.NET_BELOW_FLOOR, a.exclusion());
    }

    // ---- the boot guard ------------------------------------------------------------------------

    /**
     * ⛔ <b>A negative margin is HONOURED on mainnet, and announced loudly.</b> Giovanni's ruling,
     * 2026-09-03: <i>"it's fundamental to allow operators to operate at a loss … operating at a loss
     * MUST be implemented even on mainnet."</i> The bot is a protocol-health tool, and a stated-loss
     * convert that clears a loan nobody else will profitably touch is the intended function.
     *
     * <p><b>The mutant this guards is a hard-fail appearing here</b> — which would disable exactly the
     * operator the capability exists for. What protects everyone else is the DEFAULT of 0, tested
     * separately: only an explicit negative reaches this path.
     */
    @Test
    void aNegativeMarginIsHonouredOnEveryNetworkAndAnnouncedLoudlyOnMainnet() {
        var negative = new AppConfig.ConvertConfiguration(true, BigInteger.valueOf(-1L));
        var logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(ConvertEconomics.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            // None of these may throw: an unrecognised network is treated as mainnet for the LOUDER
            // line, not for a refusal.
            new ConvertEconomics(negative, network("mainnet")).announceAndGuard();
            new ConvertEconomics(negative, network("sanchonet")).announceAndGuard();
            new ConvertEconomics(negative, null).announceAndGuard();
            new ConvertEconomics(negative, network("preview")).announceAndGuard();
        } finally {
            logger.detachAppender(appender);
        }

        var loud = appender.list.stream()
                .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("OPERATING AT A LOSS ON MAINNET") && m.contains("convert"))
                .toList();
        assertEquals(3, loud.size(),
                "mainnet, an unrecognised network and an absent one all get the loud line; preview gets "
                        + "the quieter one. Saw: " + loud);
    }

    /**
     * ⚠ §26.2's lesson, applied before it can bite: a {@code @Value} default is not a default of the
     * class. Every construction path that is not Spring's must agree with what the shipped
     * configuration says, or the effective default silently differs from the documented one.
     *
     * <p>⛔ <b>THE ARMING DEFAULT WAS REVERSED ON 2026-09-09, and the earlier ruling is recorded here
     * rather than erased.</b> This test previously asserted <i>"convert defaults ON per Giovanni's
     * ruling"</i>. It now ships OFF, under the later standing rule that every mode is exposed to the
     * chart user and documented but <b>none ships armed</b> — the same rule that put
     * {@code scheduling.transaction-processor.enabled} at false. <b>Convert spends the bot's own ada
     * on a batcher fee and an order's min-ada, so on-by-default was the wrong way round.</b>
     *
     * <p>⚠ <b>CONSEQUENCE, and it is the reason to read this paragraph:</b> a mainnet deployment must
     * now set {@code LOANS_LIQUIDATION_CONVERT_ENABLED=true} explicitly and the chart must pass it,
     * or the next image runs with convert silently off. <b>If that trade is not what was intended,
     * this is the line to revisit — the two rulings genuinely conflict and the later one was taken.</b>
     */
    @Test
    void theDefaultsLiveOnTheFieldsNotOnlyInTheAnnotation() {
        var fresh = new AppConfig.ConvertConfiguration();

        assertFalse(fresh.isEnabled(), "convert must ship DISARMED: application.yaml states "
                + "enabled: ${LOANS_LIQUIDATION_CONVERT_ENABLED:false}, and a field left true would "
                + "make every non-Spring construction disagree with it — armed where the shipped "
                + "configuration says disarmed is the worst direction for that disagreement");
        assertEquals(BigInteger.ZERO, fresh.getProfitMarginLovelace());
        assertEquals(BigInteger.valueOf(5_000_000L), fresh.getDexCostFloorLovelace(),
                "the conservative end of Giovanni's \"4 ada or 5 ada\"");
    }
}
