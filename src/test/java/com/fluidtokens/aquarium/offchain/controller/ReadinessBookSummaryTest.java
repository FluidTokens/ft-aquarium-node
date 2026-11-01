package com.fluidtokens.aquarium.offchain.controller;

import com.fluidtokens.aquarium.offchain.model.AssetDisplay;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.LoanAge;
import com.fluidtokens.aquarium.offchain.model.TokenMetadata;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.service.loans.AnticipateAndSell;
import com.fluidtokens.aquarium.offchain.service.loans.FluidOracleClient;
import com.fluidtokens.aquarium.offchain.service.loans.PoolUsability;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    // ---- FAB-182: the prices strip, and the one USD rate ---------------------------------------------

    private static final String USDCX = "1f3aec8bfe7ea4fe14c5f121e2a92e301afe414147860d557cac7e345553444378";
    private static final String TOKD = "dd".repeat(28) + "444444";
    private static final String TOKE = "ee".repeat(28) + "454545";

    /** The render instant every strip test measures from. */
    private static final long NOW = 1_800_000_000_000L;
    /** A feed published 9 minutes 5 seconds before NOW, valid for the 50 minutes FluidTokens publish. */
    private static final long FRESH_FROM = NOW - 545_000L;
    /** A feed that lapsed 70 minutes before NOW. */
    private static final long EXPIRED_FROM = NOW - 7_200_000L;

    /** The page's metadata lookup for the strip: the book's, plus USDCx at 6 and two more tickers. */
    private static final Map<String, TokenMetadata> STRIP_EXTRA = Map.of(
            USDCX, new TokenMetadata(USDCX, "USDCx", "USDCx", 6, TokenMetadata.Source.REGISTRY),
            TOKD, new TokenMetadata(TOKD, "bab", "Token D", 6, TokenMetadata.Source.REGISTRY),
            TOKE, new TokenMetadata(TOKE, "AAA", "Token E", 6, TokenMetadata.Source.REGISTRY));
    private static final Function<String, TokenMetadata> STRIP_METADATA = unit ->
            STRIP_EXTRA.getOrDefault(unit, METADATA.getOrDefault(unit, TokenMetadata.unknown(unit)));

    /** USDCx with no published decimals. */
    private static final Function<String, TokenMetadata> USDCX_UNKNOWN = unit ->
            USDCX.equals(unit) ? TokenMetadata.unknown(unit) : STRIP_METADATA.apply(unit);

    /** One registry entry, in the registry's own JSON, as the real client parses it. */
    private static String registryEntry(String tokenUnit, int version, long price, long denominator,
                                        long validFrom, long validTo) {
        // A distinct oracle NFT per (token, version): the client keys entries by it.
        String oraclePolicy = (tokenUnit.substring(0, 2) + version).repeat(19).substring(0, 56);
        return """
                {"token":{"policyId":"%s","assetName":"%s"},
                 "fluidOracle":{"policyId":"%s","assetName":"6f7261636c65"},
                 "active":true,"oracleVersion":%d,"preferredOracle":"multisig",
                 "supportedOracle":{"multisig":{"tokenPriceInLovelaces":%d,"tokenPriceDenominator":%d,
                     "validFrom":%d,"validTo":%d}}}
                """.formatted(tokenUnit.substring(0, 56), tokenUnit.substring(56), oraclePolicy, version,
                price, denominator, validFrom, validTo);
    }

    private static String fresh(String tokenUnit, int version, long price, long denominator) {
        return registryEntry(tokenUnit, version, price, denominator, FRESH_FROM, FRESH_FROM + 3_000_000L);
    }

    private static String expired(String tokenUnit, int version, long price, long denominator) {
        return registryEntry(tokenUnit, version, price, denominator, EXPIRED_FROM, EXPIRED_FROM + 3_000_000L);
    }

    /**
     * ⛔ The REAL client, loaded from registry JSON through its own (package-private) parse — so the strip is
     * proven against the selection the bot prices with, not against a stand-in that re-implements it.
     */
    private static FluidOracleClient client(String... entries) {
        var client = new FluidOracleClient("http://unused.invalid");
        try {
            ReflectionTestUtils.invokeMethod(client, "load", new ObjectMapper().readTree("[" + String.join(",", entries) + "]"));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return client;
    }

    /**
     * The registry every strip test reads unless it says otherwise.
     * <pre>
     *   USDCx  v2 usable   3/2 lovelace per base unit, 6 dec  → 1 USDCx = 1.5 ADA → USD per ADA = 2/3
     *   AAA    v1 usable   3,456,789/1,000,000, 6 dec          v2 EXPIRED 9,999,999/1,000,000
     *   BBB    v1 EXPIRED  1/1                                 v2 usable  1,234,567/1, 0 dec
     *   CCC    v1 usable   5,000,000/1, NO metadata
     * </pre>
     */
    private static FluidOracleClient registry() {
        return client(
                fresh(USDCX, 2, 3, 2),
                fresh(TOKA, 1, 3_456_789, 1_000_000), expired(TOKA, 2, 9_999_999, 1_000_000),
                expired(TOKB, 1, 1, 1), fresh(TOKB, 2, 1_234_567, 1),
                fresh(TOKC, 1, 5_000_000, 1));
    }

    /** The strip as the controller builds it: the reference feed through the client's own findFeed. */
    private static LiquidationReadinessController.PriceStrip strip(FluidOracleClient client,
                                                                   Function<String, TokenMetadata> metadata) {
        return LiquidationReadinessController.priceStrip(client, metadata,
                client.findFeed(AssetType.fromUnit(USDCX), NOW), USDCX, NOW);
    }

    private static LiquidationReadinessController.TokenPrice entry(LiquidationReadinessController.PriceStrip strip,
                                                                   String unit) {
        return strip.entries().stream().filter(e -> e.unit().equals(unit)).findFirst()
                .orElseThrow(() -> new AssertionError("no strip entry for " + unit + ": " + strip.entries()));
    }

    /**
     * ⛔ The feed the strip shows is the one {@code findFeed} picks — usable NOW, highest version — never the
     * highest version regardless of validity. Both directions, because only one of them can tell the two
     * selections apart:
     * <pre>
     *   AAA: v1 usable 3.456789 ADA, v2 expired 9.999999 ADA  → 3.457, not stale (findEntry alone shows 10)
     *   BBB: v1 expired 0.000001 ADA, v2 usable 1.234567 ADA → 1.235, not stale
     * </pre>
     * And a token with two versions is ONE entry.
     */
    @Test
    void theStripShowsTheFeedUsableNowAtTheHighestVersion() {
        var strip = strip(registry(), STRIP_METADATA);

        assertEquals("3.457", entry(strip, TOKA).adaText(), "AAA's usable v1, not its expired v2");
        assertFalse(entry(strip, TOKA).stale(), "a usable feed is not stale");
        assertEquals(FRESH_FROM, entry(strip, TOKA).validFrom(), "the age is the usable feed's");
        assertEquals("1.235", entry(strip, TOKB).adaText(), "BBB's usable v2");
        assertFalse(entry(strip, TOKB).stale());
        assertEquals(1, strip.entries().stream().filter(e -> e.unit().equals(TOKA)).count(),
                "two versions of one token are one entry");
        assertEquals(5, strip.entries().size(), "ADA plus four distinct tokens: " + strip.entries());
    }

    /**
     * Every feed for the token has lapsed: the strip shows the last one held — the highest version, which is
     * what {@code findEntry} holds — and flags it stale rather than hiding the token or pretending it is live.
     * <pre>
     *   v1 expired 2,000,000/1,000,000 from NOW−2h; v2 expired 4,000,000/1,000,000 from NOW−3h → 4, stale, 3h0m
     * </pre>
     */
    @Test
    void aTokenWithNoUsableFeedShowsTheLastOneHeldFlaggedStale() {
        var client = client(fresh(USDCX, 2, 3, 2),
                expired(TOKA, 1, 2_000_000, 1_000_000),
                registryEntry(TOKA, 2, 4_000_000, 1_000_000, NOW - 10_800_000L, NOW - 7_800_000L));
        var aaa = entry(strip(client, STRIP_METADATA), TOKA);

        assertTrue(aaa.stale(), "no feed usable now: the entry is flagged stale");
        assertEquals("4", aaa.adaText(), "the last one held: the highest version");
        assertEquals("$2.667", aaa.usdText(), "4 ADA × 2/3 = 2.6666…");
        assertEquals(NOW - 10_800_000L, aaa.validFrom());
        assertEquals("3h0m", aaa.ageText(), "an age of an hour or more reads XhYm");
    }

    /**
     * ⛔ The scaling, by hand. AAA has 6 decimals and costs 3.456789 lovelace per base unit.
     * <pre>
     *   ADA per whole AAA = 3.456789 × 10^6 ÷ 10^6           = 3.456789  → 3.457
     *   USD per ADA       = 1 ÷ (3/2 × 10^6 ÷ 10^6) = 1 ÷ 1.5 = 0.6666…  → $0.6667  (the ADA entry)
     *   USD per whole AAA = 3.456789 × 2/3                   = 2.304526  → $2.305
     *   USDCx itself      = 1.5 ADA × 2/3                    = 1         → $1
     * </pre>
     * Without the 10^decimals scale AAA would read 0.000003457; with the reference price used directly instead
     * of its reciprocal, $5.185 — and ADA $1.5.
     */
    @Test
    void aSixDecimalTokenIsPricedPerWholeTokenInAdaAndUsd() {
        var strip = strip(registry(), STRIP_METADATA);

        assertNull(strip.usdUnavailableReason());
        assertEquals("3.457", entry(strip, TOKA).adaText());
        assertEquals("$2.305", entry(strip, TOKA).usdText());
        assertFalse(entry(strip, TOKA).metadataUnknown());
        assertEquals("$0.6667", entry(strip, "lovelace").usdText(), "the ADA entry is USD per ADA");
        assertEquals("1.5", entry(strip, USDCX).adaText(), "the reference token's own row, like any other");
        assertEquals("$1", entry(strip, USDCX).usdText());
        assertEquals("AAA", entry(strip, TOKA).label());
        assertEquals(TOKA.substring(0, 56), entry(strip, TOKA).policyId(), "the policy id, for the hover");
    }

    /**
     * A 0-decimal token is not scaled, and a token with NO published decimals stays per base unit and is marked
     * — never scaled by a guess.
     * <pre>
     *   BBB (0 dec)  1,234,567 lovelace per unit × 10^0 ÷ 10^6 = 1.234567 → 1.235;  × 2/3 = 0.82304… → $0.823
     *   CCC (none)   5,000,000 lovelace per BASE unit ÷ 10^6   = 5        → 5, marked; × 2/3 = 3.333… → $3.333
     * </pre>
     */
    @Test
    void aZeroDecimalTokenIsNotScaledAndAnUnknownOneIsPerBaseUnitAndMarked() {
        var strip = strip(registry(), STRIP_METADATA);

        assertEquals("1.235", entry(strip, TOKB).adaText());
        assertEquals("$0.823", entry(strip, TOKB).usdText());
        assertFalse(entry(strip, TOKB).metadataUnknown());
        assertEquals("5", entry(strip, TOKC).adaText(), "per raw base unit, unscaled");
        assertTrue(entry(strip, TOKC).metadataUnknown(), "and marked as such");
        assertEquals("$3.333", entry(strip, TOKC).usdText());
    }

    /** ⛔ No reference feed: every USD cell is a dash — the ADA entry's too — and the reason is stated once. */
    @Test
    void withoutAReferenceFeedEveryUsdCellIsADashAndTheReasonIsStatedOnce() {
        var client = registry();
        var strip = LiquidationReadinessController.priceStrip(client, STRIP_METADATA, Optional.empty(), USDCX, NOW);

        assertEquals("no usable USDCx oracle price right now", strip.usdUnavailableReason(),
                "the reason names the reference by its own label");
        for (var e : strip.entries()) {
            assertEquals("—", e.usdText(), "no USD figure without the reference: " + e);
        }
        assertEquals("3.457", entry(strip, TOKA).adaText(), "ADA is unaffected");

        var off = LiquidationReadinessController.priceStrip(client, STRIP_METADATA,
                client.findFeed(AssetType.fromUnit(USDCX), NOW), "", NOW);
        assertTrue(off.usdUnavailableReason().contains("LOANS_UI_USD_REFERENCE_UNIT"), off.usdUnavailableReason());
        assertTrue(off.entries().stream().allMatch(e -> "—".equals(e.usdText())), "turned off: all dashes");

        var unscaled = strip(client, USDCX_UNKNOWN);
        assertTrue(unscaled.usdUnavailableReason().contains("decimals"), unscaled.usdUnavailableReason());
        assertTrue(unscaled.entries().stream().allMatch(e -> "—".equals(e.usdText())),
                "a reference with no decimals gives no dollar figure at all");
    }

    /** ADA first, then by label case-insensitively; two tokens sharing a label are told apart by policy id. */
    @Test
    void adaComesFirstThenTokensByLabelWithSharedLabelsDisambiguated() {
        var client = client(fresh(USDCX, 2, 3, 2), fresh(TOKB, 1, 1, 1), fresh(TOKD, 1, 1, 1),
                fresh(TOKA, 1, 1, 1), fresh(TOKC, 1, 1, 1), fresh(TOKE, 1, 1, 1));
        var labels = strip(client, STRIP_METADATA).entries().stream()
                .map(LiquidationReadinessController.TokenPrice::label).toList();

        assertEquals(List.of("ADA", "AAA (aaaaaaaa…)", "AAA (eeeeeeee…)", "bab", "BBB", "CCC", "USDCx"), labels);
    }

    /** The age is now − validFrom, as XmYs, and XhYm from one hour. */
    @Test
    void theAgeIsNowMinusValidFromInTheStripsFormat() {
        assertEquals("9m5s", entry(strip(registry(), STRIP_METADATA), TOKA).ageText(),
                "the feed was published 545 s before the render");
        assertEquals("0m0s", LiquidationReadinessController.age(0L));
        assertEquals("59m59s", LiquidationReadinessController.age(3_599_999L));
        assertEquals("1h0m", LiquidationReadinessController.age(3_600_000L));
        assertEquals("3h25m", LiquidationReadinessController.age(12_310_000L));
        assertEquals("0m0s", LiquidationReadinessController.age(-5_000L), "a future validFrom is not negative");
    }

    /** A tiny price keeps its four significant digits rather than rounding to zero. */
    @Test
    void aTinyPriceKeepsFourSignificantDigits() {
        assertEquals("0.0000003333", LiquidationReadinessController.significant(
                com.fluidtokens.aquarium.offchain.model.loans.Rational.required(1, 3_000_000)));
        assertEquals("12350", LiquidationReadinessController.significant(
                com.fluidtokens.aquarium.offchain.model.loans.Rational.required(123_456, 10)));
    }

    /** Lending off: no oracle client, no strip — and nothing thrown. */
    @Test
    void withoutAnOracleClientThereIsNoStrip() {
        assertNull(LiquidationReadinessController.priceStrip(null, STRIP_METADATA, Optional.empty(), USDCX, NOW));
    }

    /** The book names the reference by its label too: configured USDCx, its reasons say USDCx. */
    @Test
    void theBooksUsdReasonsNameTheConfiguredReferenceByItsLabel() {
        BiFunction<String, BigInteger, AssetDisplay> display = (unit, amount) ->
                AssetDisplay.of(amount, STRIP_METADATA.apply(unit));
        var summary = LiquidationReadinessController.summarise(book(), display, Optional.empty(), USDCX);

        assertEquals("no usable USDCx oracle price right now", summary.usdUnavailableReason());
    }

    /**
     * ⛔ The shipped default (FAB-182): the page's one USD rate goes through USDCx on mainnet, and preview stays
     * blank. Parsed, not grepped — the comment above the key names tokens too.
     */
    @Test
    void theShippedUsdReferenceIsUsdcxOnMainnetAndBlankOnPreview() throws Exception {
        String yaml = Files.readString(Path.of("src/main/resources/application.yaml"));
        String mainnet = null;
        String preview = null;
        for (Object document : new org.yaml.snakeyaml.Yaml().loadAll(yaml)) {
            Object value = path(document, "loans", "ui", "usd-reference-unit");
            if (value == null) {
                continue;
            }
            Object profile = path(document, "spring", "config", "activate", "on-profile");
            if (profile == null) {
                mainnet = value.toString();
            } else if ("preview".equals(profile)) {
                preview = value.toString();
            }
        }
        assertEquals("${LOANS_UI_USD_REFERENCE_UNIT:" + USDCX + "}", mainnet,
                "the mainnet default is USDCx, with the env override intact");
        assertEquals("", preview, "preview stays blank: a mainnet unit means nothing there");
    }

    private static Object path(Object document, String... keys) {
        Object at = document;
        for (String key : keys) {
            if (!(at instanceof Map<?, ?> m)) {
                return null;
            }
            at = m.get(key);
        }
        return at;
    }
}
