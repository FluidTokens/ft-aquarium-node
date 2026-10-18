package com.fluidtokens.aquarium.offchain.controller;

import com.fluidtokens.aquarium.offchain.model.AssetDisplay;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.LoanAge;
import com.fluidtokens.aquarium.offchain.model.TokenMetadata;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.service.loans.AnticipateAndSell;
import com.fluidtokens.aquarium.offchain.service.loans.PoolUsability;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ FAB-145 T2: the book above the readiness table — loan counts, the collateral locked per token (TVL),
 * its estimated ADA value and that total in USD through the USDM oracle feed.
 *
 * <p>Every expected figure here is worked out BY HAND in the comment beside it, never by calling the
 * production arithmetic: a summary whose expectations came from the code under test would agree with
 * itself whatever it computed.
 */
class ReadinessBookSummaryTest {

    private static final String TOKA = "aa".repeat(28) + "414141";
    private static final String TOKB = "bb".repeat(28) + "424242";
    private static final String TOKC = "cc".repeat(28) + "434343";
    private static final String USDM = "c48cbb3d5e57ed56e276bc45f99ab39abe94e6cd7ac39fb402da47ad0014df105553444d";

    /** What the page's metadata lookup would answer: AAA and USDM at 6 decimals, BBB at 0, CCC unknown. */
    private static final Map<String, TokenMetadata> METADATA = Map.of(
            "lovelace", TokenMetadata.ada(),
            TOKA, new TokenMetadata(TOKA, "AAA", "Token A", 6, TokenMetadata.Source.REGISTRY),
            TOKB, new TokenMetadata(TOKB, "BBB", "Token B", 0, TokenMetadata.Source.REGISTRY),
            USDM, new TokenMetadata(USDM, "USDM", "USDM", 6, TokenMetadata.Source.REGISTRY));

    private static final BiFunction<String, BigInteger, AssetDisplay> DISPLAY = (unit, amount) ->
            AssetDisplay.of(amount, METADATA.getOrDefault(unit, TokenMetadata.unknown(unit)));

    /** USDM with no published decimals: an unscaled figure that must never be read as dollars. */
    private static final BiFunction<String, BigInteger, AssetDisplay> USDM_UNKNOWN = (unit, amount) ->
            USDM.equals(unit) ? AssetDisplay.of(amount, TokenMetadata.unknown(unit)) : DISPLAY.apply(unit, amount);

    /** 1 USDM base unit = 3/2 lovelace, i.e. 1 USDM = 1.5 ADA and 1 ADA = $0.666…. */
    private static Optional<OraclePriceFeed> usdmFeed(long numerator, long denominator) {
        return Optional.of(OraclePriceFeed.aggregated(AssetType.fromUnit(USDM), BigInteger.valueOf(numerator),
                BigInteger.valueOf(denominator), 0L, Long.MAX_VALUE));
    }

    private static LiquidationReadinessController.Row row(String id, String collateralUnit, long collateral,
                                                          Long valueLovelace, boolean liquidatable,
                                                          boolean blocked) {
        return new LiquidationReadinessController.Row(id, id + "#0", "lovelace", BigInteger.TEN, BigInteger.TEN,
                collateralUnit, BigInteger.valueOf(collateral), null, null, liquidatable, "n/a",
                null, null, null, "PLAIN LIQUIDATE", "", null,
                new LoanAge("1d", "2026-10-17T00:00:00Z"),
                DISPLAY.apply("lovelace", BigInteger.TEN), DISPLAY.apply(collateralUnit, BigInteger.valueOf(collateral)),
                PoolUsability.noPool(), null, null, null, null,
                AnticipateAndSell.unknown("n/a"), null,
                new ActionNow("NONE", "n/a", false),
                blocked ? new ProcessingBlocker("funds", "short of principal") : ProcessingBlocker.NONE,
                null, null, null,
                valueLovelace == null ? null : BigInteger.valueOf(valueLovelace));
    }

    /**
     * Seven loans over four collateral tokens.
     * <pre>
     *   ada   50,000,000 lovelace  value 50,000,000    liquidatable
     *   AAA    1,000,000           value  2,000,000                  blocked
     *   AAA    3,000,000           value  6,000,000
     *   AAA      500,000           UNPRICED
     *   BBB           10           value 70,000,000    liquidatable  blocked
     *   CCC            9           UNPRICED
     *   CCC            1           UNPRICED
     * </pre>
     */
    private static List<LiquidationReadinessController.Row> book() {
        return List.of(
                row("ada", "lovelace", 50_000_000L, 50_000_000L, true, false),
                row("a1", TOKA, 1_000_000L, 2_000_000L, false, true),
                row("a2", TOKA, 3_000_000L, 6_000_000L, false, false),
                row("a3", TOKA, 500_000L, null, false, false),
                row("b1", TOKB, 10L, 70_000_000L, true, true),
                row("c1", TOKC, 9L, null, false, false),
                row("c2", TOKC, 1L, null, false, false));
    }

    @Test
    void theThreeCountsUseThePagesOwnPredicates() {
        var summary = LiquidationReadinessController.summarise(book(), DISPLAY, usdmFeed(3, 2), USDM);

        assertEquals(7, summary.loans(), "every row is a loan");
        assertEquals(2, summary.liquidatable(), "ada and b1 are liquidatable");
        assertEquals(2, summary.blocked(), "a1 and b1 carry a blocker");
    }

    @Test
    void perTokenAmountsCountsAndValuesOverPricedRowsOnly() {
        var summary = LiquidationReadinessController.summarise(book(), DISPLAY, usdmFeed(3, 2), USDM);
        var tokens = summary.tokens();

        // Value descending, null last: BBB 70,000,000 > ada 50,000,000 > AAA 8,000,000 > CCC (null).
        assertEquals(List.of(TOKB, "lovelace", TOKA, TOKC),
                tokens.stream().map(LiquidationReadinessController.TokenTvl::unit).toList());

        var bbb = tokens.get(0);
        assertEquals(1, bbb.loans());
        assertEquals(BigInteger.valueOf(10L), bbb.amount().rawAmount());
        assertEquals("10.00", bbb.amount().amountText(), "BBB has 0 decimals: 10 base units read 10.00");
        assertEquals(BigInteger.valueOf(70_000_000L), bbb.valueLovelace());
        assertEquals("70.00", bbb.valueDisplay().amountText(), "70,000,000 lovelace is 70 ADA");
        assertEquals(0, bbb.unpricedLoans());

        var ada = tokens.get(1);
        assertEquals(1, ada.loans());
        assertEquals(BigInteger.valueOf(50_000_000L), ada.amount().rawAmount());
        assertEquals(BigInteger.valueOf(50_000_000L), ada.valueLovelace());

        // ⛔ The unpriced AAA loan's 500,000 is IN the amount (1,000,000 + 3,000,000 + 500,000 =
        // 4,500,000) and OUT of the value (2,000,000 + 6,000,000 = 8,000,000) — and it is counted.
        var aaa = tokens.get(2);
        assertEquals(3, aaa.loans());
        assertEquals(BigInteger.valueOf(4_500_000L), aaa.amount().rawAmount(),
                "an unpriced loan's collateral is never dropped from the locked amount");
        assertEquals("4.50", aaa.amount().amountText(), "4,500,000 base units at 6 decimals");
        assertEquals("AAA", aaa.amount().label());
        assertEquals(BigInteger.valueOf(8_000_000L), aaa.valueLovelace(),
                "an unpriced loan is never valued at zero, it is excluded from the value");
        assertEquals(1, aaa.unpricedLoans());

        // CCC: no loan priced, so no value at all — null, not zero — and both loans counted unpriced.
        var ccc = tokens.get(3);
        assertEquals(2, ccc.loans());
        assertEquals(BigInteger.TEN, ccc.amount().rawAmount(), "9 + 1");
        assertTrue(ccc.amount().metadataUnknown(), "CCC has no metadata: the amount is marked raw");
        assertNull(ccc.valueLovelace(), "a token with no priced loan has no value, never zero");
        assertNull(ccc.valueDisplay());
        assertEquals(2, ccc.unpricedLoans());
    }

    @Test
    void theAdaTotalExcludesUnpricedLoansAndSaysHowManyItExcluded() {
        var summary = LiquidationReadinessController.summarise(book(), DISPLAY, usdmFeed(3, 2), USDM);

        // 70,000,000 + 50,000,000 + 8,000,000 = 128,000,000 lovelace = 128 ADA.
        assertEquals(BigInteger.valueOf(128_000_000L), summary.totalLovelace());
        assertEquals("128.00", summary.totalAdaDisplay().amountText());
        assertEquals("ADA", summary.totalAdaDisplay().label());
        // a3 + c1 + c2.
        assertEquals(3, summary.unpricedLoans(), "every excluded loan is counted, not hidden");
    }

    @Test
    void tiesAreOrderedByUnitAndSoAreTokensWithNoValue() {
        var rows = List.of(
                row("b", TOKB, 1L, 5_000_000L, false, false),
                row("a", TOKA, 1L, 5_000_000L, false, false),
                row("c", TOKC, 1L, null, false, false),
                row("ada", "lovelace", 1L, null, false, false));
        var summary = LiquidationReadinessController.summarise(rows, DISPLAY, usdmFeed(3, 2), USDM);

        // AAA and BBB tie at 5,000,000 → by unit ("aa…" < "bb…"); then the two nulls by unit
        // ("cc…" < "lovelace").
        assertEquals(List.of(TOKA, TOKB, TOKC, "lovelace"),
                summary.tokens().stream().map(LiquidationReadinessController.TokenTvl::unit).toList());
    }

    /**
     * USDM feed price 3/2 lovelace per USDM base unit.
     * <pre>
     *   total   128,000,000 lovelace × 2/3 = 85,333,333.33… → floor 85,333,333 base units = $85.33
     *   rate      1,000,000 lovelace × 2/3 =    666,666.66… → floor    666,666 base units = $0.666666
     * </pre>
     */
    @Test
    void theUsdFigureIsTheAdaTotalThroughTheUsdmFeedFlooredAndScaled() {
        var summary = LiquidationReadinessController.summarise(book(), DISPLAY, usdmFeed(3, 2), USDM);

        assertNull(summary.usdUnavailableReason());
        assertNotNull(summary.usdDisplay());
        assertEquals(BigInteger.valueOf(85_333_333L), summary.usdDisplay().rawAmount());
        assertEquals("85.33", summary.usdDisplay().amountText());
        assertEquals(BigInteger.valueOf(666_666L), summary.adaUsdRateDisplay().rawAmount());
        assertEquals("0.666666", summary.adaUsdRateDisplay().amountText());
    }

    @Test
    void aBlankReferenceUnitMakesUsdUnavailableAndLeavesTheAdaFiguresAlone() {
        for (String blank : new String[] {"", "  ", null}) {
            var summary = LiquidationReadinessController.summarise(book(), DISPLAY, usdmFeed(3, 2), blank);
            assertNull(summary.usdDisplay(), "no reference unit, no dollar figure");
            assertNull(summary.adaUsdRateDisplay());
            assertNotNull(summary.usdUnavailableReason());
            assertTrue(summary.usdUnavailableReason().contains("LOANS_UI_USD_REFERENCE_UNIT"),
                    "the reason names the env var an operator sets: " + summary.usdUnavailableReason());
            assertEquals(BigInteger.valueOf(128_000_000L), summary.totalLovelace(), "ada is unaffected");
        }
    }

    @Test
    void aMissingFeedMakesUsdUnavailableWithItsOwnReason() {
        var summary = LiquidationReadinessController.summarise(book(), DISPLAY, Optional.empty(), USDM);

        assertNull(summary.usdDisplay());
        assertNull(summary.adaUsdRateDisplay());
        assertNotNull(summary.usdUnavailableReason());
        assertTrue(summary.usdUnavailableReason().contains("no usable USDM oracle price"),
                "the reason says the feed is the problem: " + summary.usdUnavailableReason());
        assertEquals(BigInteger.valueOf(128_000_000L), summary.totalLovelace(), "ada is unaffected");
    }

    @Test
    void aFeedThatCannotBeConvertedMakesUsdUnavailableRatherThanFailingTheSummary() {
        // A zero price: fromLovelace divides by it and throws, as the contract does.
        var zero = LiquidationReadinessController.summarise(book(), DISPLAY, usdmFeed(0, 1), USDM);
        assertNull(zero.usdDisplay());
        assertTrue(zero.usdUnavailableReason().contains("could not be converted"), zero.usdUnavailableReason());
        assertEquals(BigInteger.valueOf(128_000_000L), zero.totalLovelace());

        // A Pooled feed: price() refuses it outright.
        var pooled = LiquidationReadinessController.summarise(book(), DISPLAY,
                Optional.of(new OraclePriceFeed(OraclePriceFeed.Variant.POOLED, AssetType.fromUnit(USDM),
                        BigInteger.ONE, BigInteger.ONE, 0L, Long.MAX_VALUE)), USDM);
        assertNull(pooled.usdDisplay());
        assertTrue(pooled.usdUnavailableReason().contains("could not be converted"), pooled.usdUnavailableReason());
    }

    /** ⛔ An unscaled USDM figure would be off by 10^6 and read as dollars. It is never shown. */
    @Test
    void aUsdmDisplayWithUnknownMetadataIsNeverShownAsDollars() {
        var summary = LiquidationReadinessController.summarise(book(), USDM_UNKNOWN, usdmFeed(3, 2), USDM);

        assertNull(summary.usdDisplay(), "a raw base-unit figure must not be presented as dollars");
        assertNull(summary.adaUsdRateDisplay(), "nor a raw rate");
        assertTrue(summary.usdUnavailableReason().contains("decimals"), summary.usdUnavailableReason());
        assertEquals("128.00", summary.totalAdaDisplay().amountText(), "ada is unaffected");
    }

    /** No loan priced at all: there is no ada total to speak of, and certainly no dollar one. */
    @Test
    void aBookWithNoPricedLoanHasNoTotalRatherThanAZeroOne() {
        var rows = List.of(row("c1", TOKC, 9L, null, false, false), row("c2", TOKC, 1L, null, false, false));
        var summary = LiquidationReadinessController.summarise(rows, DISPLAY, usdmFeed(3, 2), USDM);

        assertNull(summary.totalLovelace(), "nothing priced is unknown, not zero");
        assertNull(summary.totalAdaDisplay());
        assertEquals(2, summary.unpricedLoans());
        assertNull(summary.usdDisplay());
        assertNotNull(summary.usdUnavailableReason());
    }

    /** An empty (or fully filtered-out) book is genuinely zero, and says so. */
    @Test
    void anEmptyBookIsZero() {
        var summary = LiquidationReadinessController.summarise(List.of(), DISPLAY, usdmFeed(3, 2), USDM);

        assertEquals(0, summary.loans());
        assertTrue(summary.tokens().isEmpty());
        assertEquals(BigInteger.ZERO, summary.totalLovelace());
        assertEquals(0, summary.unpricedLoans());
        assertEquals(BigInteger.ZERO, summary.usdDisplay().rawAmount());
    }

    @Test
    void everyUnavailableReasonIsDistinct() {
        var reasons = new HashSet<String>();
        reasons.add(LiquidationReadinessController.summarise(book(), DISPLAY, usdmFeed(3, 2), "").usdUnavailableReason());
        reasons.add(LiquidationReadinessController.summarise(book(), DISPLAY, Optional.empty(), USDM).usdUnavailableReason());
        reasons.add(LiquidationReadinessController.summarise(book(), DISPLAY, usdmFeed(0, 1), USDM).usdUnavailableReason());
        reasons.add(LiquidationReadinessController.summarise(book(), USDM_UNKNOWN, usdmFeed(3, 2), USDM).usdUnavailableReason());
        assertEquals(4, reasons.size(), "each failure says its own thing: " + reasons);
    }
}
