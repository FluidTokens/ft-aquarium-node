package com.fluidtokens.aquarium.offchain.service.loans;

import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.CompoundAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.CompoundExclusion;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The compound profitability gate, including the case Giovanni ruled on 2026-09-02:
 * <i>"there should be a check compoudingFeePerMille in the bot to accept to process if zero … as long
 * as operator checks this himself and owns it."</i>
 *
 * <p>The shape that encodes "owns it" is the same one the liquidation margin uses: a floor the
 * operator <b>states</b>, never a check they switch off. So there is no boolean here to disable the
 * gate — accepting a measured loss means stating a negative number, which still bounds that loss.
 *
 * <p>Since the oracle-pricing slice (2026-09-09), {@link CompoundEconomics#assess} also prices a
 * non-ada principal's fee slice through {@link PricingService} — every test up to that heading uses
 * ada throughout, unchanged from before that slice; the ones under it exercise native pricing.
 */
class CompoundEconomicsTest {

    private static final BigInteger ESCROW_45_ADA = BigInteger.valueOf(45_000_000L);
    private static final BigInteger TX_FEE = BigInteger.valueOf(300_000L);
    private static final BigInteger ONE_ADA = BigInteger.valueOf(1_000_000L);
    private static final long AT_MILLIS = 1_700_000_000_000L;

    private static final AssetType TOKEN =
            new AssetType("c0eaf6cea665d1b99647a9d24461984103de0492da93e7af29fe5d9", "5553444d");

    /** Ada never reaches the oracle, so a dummy, un-loaded client is exactly as good as a real one. */
    private static PricingService dummyPricingService() {
        return new PricingService(new FluidOracleClient("http://unused.invalid"));
    }

    private static CompoundEconomics economics(boolean enabled, long floorLovelace, String network) {
        var cfg = new AppConfig.CompoundConfiguration(enabled, 60L, BigInteger.valueOf(floorLovelace));
        var net = new AppConfig.Network();
        ReflectionTestUtils.setField(net, "network", network);
        return new CompoundEconomics(cfg, net, dummyPricingService());
    }

    private static CompoundEconomics economics(boolean enabled, long floorLovelace, String network,
                                               PricingService pricingService) {
        var cfg = new AppConfig.CompoundConfiguration(enabled, 60L, BigInteger.valueOf(floorLovelace));
        var net = new AppConfig.Network();
        ReflectionTestUtils.setField(net, "network", network);
        return new CompoundEconomics(cfg, net, pricingService);
    }

    private static CompoundEconomics armed(long floorLovelace) {
        return economics(true, floorLovelace, "preview");
    }

    // ---- the ada path, unchanged since before the oracle-pricing slice -------------------------

    /**
     * A paying ADA pool below the crossover: 45 ADA at 5‰ is 225_000 per mille, so the 1 ADA floor binds
     * (FAB-167) — and a 1.3 ADA transaction still costs more than that floor pays.
     */
    @Test
    void aFeeThatDoesNotCoverTheTransactionIsRefusedAtTheSafeDefault() {
        CompoundAssessment a = armed(0).assess(true, true, AssetType.ada(), ESCROW_45_ADA, 5L,
                BigInteger.valueOf(1_300_000L), AT_MILLIS);

        assertEquals(ONE_ADA, a.expectedFee(), "max(225_000, 1 ADA)");
        assertEquals(ONE_ADA, a.expectedFeeLovelace(),
                "for an ada principal the priced figure must equal the on-chain one exactly");
        assertEquals(BigInteger.valueOf(-300_000L), a.net());
        assertFalse(a.approved());
        assertEquals(CompoundExclusion.NET_BELOW_FLOOR, a.exclusion());
    }

    /** Above the crossover the per-mille fee binds: 300 ADA at 5‰ earns 1_500_000. */
    @Test
    void aFeeThatCoversTheTransactionIsApproved() {
        CompoundAssessment a = armed(0)
                .assess(true, true, AssetType.ada(), BigInteger.valueOf(300_000_000L), 5L, TX_FEE, AT_MILLIS);

        assertEquals(BigInteger.valueOf(1_500_000L), a.expectedFee());
        assertEquals(BigInteger.valueOf(1_200_000L), a.net());
        assertTrue(a.approved());
    }

    /** Exact break-even is allowed by the default floor of 0 — it is not a loss. */
    @Test
    void exactBreakEvenIsApprovedAtTheDefaultFloor() {
        CompoundAssessment a = armed(0).assess(true, true, AssetType.ada(),
                BigInteger.valueOf(60_000_000L), 5L, ONE_ADA, AT_MILLIS);

        assertEquals(BigInteger.ZERO, a.net(), "the 1 ADA floor minus a 1 ADA transaction");
        assertTrue(a.approved(), "net == floor must pass: the floor is a minimum, not a strict bound");
    }

    /**
     * ⛔ THE RULE THAT CHANGED (FAB-167). Until FluidTokens' 2026-10-26 update a zero-rate ADA pool paid
     * nothing and was refused out of the box. The live {@code lm_compound_action} now makes every ADA pool
     * pay at least 1 ADA, so the same pool is approved at the default floor whenever the transaction costs
     * no more than that — and nothing here skips {@code c == 0}.
     */
    @Test
    void aZeroRateAdaPoolPaysTheOneAdaFloorAndIsApprovedOutOfTheBox() {
        CompoundAssessment a = armed(0).assess(true, true, AssetType.ada(), ESCROW_45_ADA, 0L, TX_FEE, AT_MILLIS);

        assertTrue(a.zeroFeePool(), "the rate is still 0");
        assertEquals(ONE_ADA, a.expectedFee(), "but the fee is the 1 ADA floor");
        assertEquals(BigInteger.valueOf(700_000L), a.net());
        assertTrue(a.approved());
    }

    /**
     * ⛔ THE SAFE DEFAULT, where it still applies: a TOKEN pool at rate 0 pays nothing (no token floor), so
     * with no recovered escrow ADA the net is minus the transaction fee and the default floor refuses it.
     */
    @Test
    void aZeroRateTokenPoolIsRefusedOutOfTheBoxWithoutRecoveredAda() {
        CompoundAssessment a = armed(0).assessFinal(true, true, TOKEN, BigInteger.valueOf(100_000_000L), 0L,
                facts(BigInteger.ZERO, BigInteger.ZERO, TX_FEE), PricingService.Priced.priced(BigInteger.ZERO));

        assertEquals(BigInteger.ZERO, a.expectedFee(), "Q*0/1000, and no floor for a token");
        assertEquals(TX_FEE.negate(), a.net(), "the whole outlay is the transaction fee");
        assertFalse(a.approved());
        assertEquals(CompoundExclusion.NET_BELOW_FLOOR, a.exclusion());
    }

    /**
     * ⛔ AND THE OPERATOR'S RULED CHOICE (FAB-167 task 4: unchanged). A compound that costs more than the
     * 1 ADA floor pays is refused at the default floor and ADMITTED under a stated negative margin — and
     * the margin still bounds the loss: a worse one is refused.
     */
    @Test
    void aStatedNegativeFloorAdmitsABelowCostCompoundAndStillBoundsTheLoss() {
        BigInteger costlyTx = BigInteger.valueOf(1_300_000L);
        CompoundAssessment atTheDefault =
                armed(0).assess(true, true, AssetType.ada(), ESCROW_45_ADA, 0L, costlyTx, AT_MILLIS);
        assertEquals(BigInteger.valueOf(-300_000L), atTheDefault.net(), "1 ADA floor - 1.3 ADA");
        assertFalse(atTheDefault.approved(), "below cost is refused at the safe default");

        CompoundEconomics armedAtALoss = armed(-2_000_000L);
        CompoundAssessment allowed =
                armedAtALoss.assess(true, true, AssetType.ada(), ESCROW_45_ADA, 0L, costlyTx, AT_MILLIS);
        assertTrue(allowed.approved(), "a -2 ADA stated bound must accept a 0.3 ADA loss");
        assertTrue(allowed.zeroFeePool());

        // Still a bound, not an off switch: a loss beyond the stated figure is refused.
        CompoundAssessment tooExpensive = armedAtALoss.assess(true, true, AssetType.ada(),
                ESCROW_45_ADA, 0L, BigInteger.valueOf(3_500_000L), AT_MILLIS);
        assertEquals(BigInteger.valueOf(-2_500_000L), tooExpensive.net());
        assertFalse(tooExpensive.approved(), "a stated bound must still refuse a worse loss");
        assertEquals(CompoundExclusion.NET_BELOW_FLOOR, tooExpensive.exclusion());
    }

    @Test
    void theStructuralRefusalsFireBeforeAnyArithmetic() {
        assertEquals(CompoundExclusion.NOT_ARMED,
                economics(false, 0, "preview")
                        .assess(true, true, AssetType.ada(), ESCROW_45_ADA, 50L, TX_FEE, AT_MILLIS).exclusion());
        assertEquals(CompoundExclusion.BOND_NAMES_NO_POOL,
                armed(0).assess(false, true, AssetType.ada(), ESCROW_45_ADA, 50L, TX_FEE, AT_MILLIS).exclusion());
        assertEquals(CompoundExclusion.POOL_NOT_LIVE,
                armed(0).assess(true, false, AssetType.ada(), ESCROW_45_ADA, 50L, TX_FEE, AT_MILLIS).exclusion());
    }

    /**
     * ⛔ THE ADA PATH MUST NOT CHANGE. Driven through an oracle client that throws on ANY call, so
     * this test would fail loudly the moment the ada path started consulting the oracle at all — the
     * invariant is "byte-for-byte unchanged", not merely "produces the same number".
     */
    @Test
    void theAdaPathNeverConsultsTheOracle() {
        var throwingClient = new FluidOracleClient("http://unused.invalid") {
            @Override
            public Optional<OraclePriceFeed> findFeed(AssetType asset, long atMillis) {
                throw new AssertionError("the ada path must not consult the oracle at all");
            }

            @Override
            public Optional<OraclePriceFeed> findFeedIgnoringValidity(AssetType asset) {
                throw new AssertionError("the ada path must not consult the oracle at all");
            }
        };
        CompoundEconomics economics =
                economics(true, 0, "preview", new PricingService(throwingClient));

        CompoundAssessment a = assertDoesNotThrow(() ->
                economics.assess(true, true, AssetType.ada(), ESCROW_45_ADA, 5L, TX_FEE, AT_MILLIS));

        assertEquals(ONE_ADA, a.expectedFeeLovelace(), "the 1 ADA floor, priced by identity");
        assertEquals(BigInteger.valueOf(700_000L), a.net());
        assertTrue(a.approved());
    }

    /**
     * ⛔ The on-chain expression truncates, and so must this one. Rounding UP would claim a fee the
     * validator does not require the pool to give up, and the transaction would fail phase 2 — after
     * collateral is committed. Shown on a token principal, where no floor hides the per-mille term.
     */
    @Test
    void theFeeTruncatesExactlyAsTheValidatorDoes() {
        // 1999 * 1 / 1000 = 1 on chain, not 2.
        assertEquals(BigInteger.ONE, CompoundEconomics.compoundingFee(BigInteger.valueOf(1999L), 1L, TOKEN));
        assertEquals(BigInteger.ZERO, CompoundEconomics.compoundingFee(BigInteger.valueOf(999L), 1L, TOKEN));
        assertEquals(BigInteger.valueOf(225_000L), CompoundEconomics.compoundingFee(ESCROW_45_ADA, 5L, TOKEN));
        // The same per-mille term on ADA is lifted to the floor; past the crossover it binds again.
        assertEquals(ONE_ADA, CompoundEconomics.compoundingFee(ESCROW_45_ADA, 5L, AssetType.ada()));
        assertEquals(BigInteger.valueOf(1_000_001L),
                CompoundEconomics.compoundingFee(BigInteger.valueOf(200_000_200L), 5L, AssetType.ada()));
    }

    /**
     * ⛔ FAB-167 invariant: the node's fee equals {@code get_compounding_fee} for every {@code (Q, c,
     * principal)}. Checked against {@link #getCompoundingFee}, a line-for-line port of the Aiken function
     * written independently of {@link CompoundEconomics#compoundingFee} (long arithmetic, {@code floorDiv},
     * {@code Math.max}), over the edges — {@code c = 0}, {@code Q < 1 ADA}, {@code Q = 1 ADA}, the 200 ADA
     * crossover at 5‰, the largest decodable rate — and a seeded random sweep, for ADA and a token.
     */
    @Test
    void theFeeIsGetCompoundingFeeForEveryQuantityRateAndPrincipal() {
        List<Long> quantities = new ArrayList<>(List.of(0L, 1L, 999L, 1_000L, 999_999L, 1_000_000L, 1_000_001L,
                29_109_268L, 150_000_000L, 199_999_999L, 200_000_000L, 200_000_001L, 200_000_200L, 300_000_000L,
                45_000_000_000_000L));
        List<Long> rates = new ArrayList<>(List.of(0L, 1L, 4L, 5L, 6L, 10L, 50L, 999L, 1_000L, 2_500L));
        java.util.Random random = new java.util.Random(167L);
        for (int i = 0; i < 200; i++) {
            quantities.add(Math.floorMod(random.nextLong(), 1_000_000_000_000L));
        }
        for (int i = 0; i < 20; i++) {
            rates.add((long) random.nextInt(1_000));
        }
        int checked = 0;
        for (long q : quantities) {
            for (long c : rates) {
                assertEquals(BigInteger.valueOf(getCompoundingFee(q, c, true)),
                        CompoundEconomics.compoundingFee(BigInteger.valueOf(q), c, AssetType.ada()),
                        "ADA Q=" + q + " c=" + c);
                assertEquals(BigInteger.valueOf(getCompoundingFee(q, c, false)),
                        CompoundEconomics.compoundingFee(BigInteger.valueOf(q), c, TOKEN),
                        "token Q=" + q + " c=" + c);
                checked += 2;
            }
        }
        assertEquals(quantities.size() * rates.size() * 2, checked);

        // The named edges, stated outright rather than only through the port.
        assertEquals(ONE_ADA, CompoundEconomics.compoundingFee(BigInteger.valueOf(50_000_000L), 0L, AssetType.ada()),
                "upstream test compounding_fee_zero_per_mille_ada_pays_floor");
        assertEquals(BigInteger.valueOf(2_500_000L),
                CompoundEconomics.compoundingFee(BigInteger.valueOf(500_000_000L), 5L, AssetType.ada()),
                "upstream test compounding_fee_per_mille_above_floor");
        assertEquals(BigInteger.ZERO, CompoundEconomics.compoundingFee(BigInteger.valueOf(50_000_000L), 0L, TOKEN),
                "upstream test compounding_fee_token_pool_has_no_floor");
        assertEquals(ONE_ADA, CompoundEconomics.compoundingFee(BigInteger.valueOf(999_999L), 0L, AssetType.ada()),
                "Q < 1 ADA still owes the 1 ADA floor, so Q - F <= 0");
        assertEquals(ONE_ADA, CompoundEconomics.compoundingFee(ONE_ADA, 0L, AssetType.ada()), "Q = 1 ADA");
        assertEquals(ONE_ADA, CompoundEconomics.compoundingFee(BigInteger.valueOf(200_000_000L), 5L, AssetType.ada()),
                "the crossover: per mille and floor agree at 200 ADA");
        // ⚠ A token whose policy is ADA's empty policy IS ada to the validator, whatever its name.
        assertEquals(ONE_ADA, CompoundEconomics.compoundingFee(BigInteger.valueOf(50_000_000L), 0L,
                AssetType.fromPlutusData(new byte[0], new byte[0])));
    }

    /** {@code expect compoudingFeePerMille >= 0}: a negative rate aborts on chain, so it throws here. */
    @Test
    void aNegativeRateIsRefusedAsTheValidatorRefusesIt() {
        assertThrows(IllegalArgumentException.class,
                () -> CompoundEconomics.compoundingFee(BigInteger.valueOf(50_000_000L), -1L, AssetType.ada()),
                "upstream test compounding_fee_negative_per_mille_fails");
        assertThrows(IllegalArgumentException.class,
                () -> CompoundEconomics.compoundingFee(BigInteger.valueOf(50_000_000L), -1L, TOKEN));
    }

    /**
     * A direct port of {@code finance.get_compounding_fee} (upstream {@code a02d4e2}) — NOT a call into
     * production code. Aiken's {@code /} floors.
     */
    private static long getCompoundingFee(long addedLiquidity, long compoudingFeePerMille, boolean adaPolicy) {
        if (compoudingFeePerMille < 0) {
            throw new IllegalArgumentException("expect compoudingFeePerMille >= 0");
        }
        long perMilleFee = Math.floorDiv(Math.multiplyExact(addedLiquidity, compoudingFeePerMille), 1000L);
        return adaPolicy ? Math.max(perMilleFee, 1_000_000L) : perMilleFee;
    }

    /**
     * ⛔ <b>A negative floor is HONOURED on mainnet.</b> The inverse of what this asserted until
     * 2026-09-03, on Giovanni's ruling: <i>"operating at a loss MUST be implemented even on
     * mainnet."</i> Compounding a pool whose {@code compoudingFeePerMille} is 0 is exactly the
     * protocol-health work a stated loss is for. <b>The mutant is the old hard-fail returning</b>; the
     * protection is the DEFAULT of 0, which no copy-paste turns negative.
     */
    @Test
    void aNegativeFloorIsHonouredOnMainnetAndAnnouncedLoudly() {
        var logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(CompoundEconomics.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            economics(true, -1L, "mainnet").announceAndGuard();
            // An unrecognised network is still treated as mainnet — for the LOUDER line, not a refusal.
            economics(true, -1L, "wonderland").announceAndGuard();
        } finally {
            logger.detachAppender(appender);
        }

        long announcements = appender.list.stream()
                .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("OPERATING AT A LOSS ON MAINNET") && m.contains("compound"))
                .count();
        assertEquals(2L, announcements,
                "both the mainnet node and the unrecognised-network node must announce it");
    }

    @Test
    void aNegativeFloorOnPreviewWarnsAndProceeds() {
        armed(-2_000_000L).announceAndGuard();
        economics(true, 0L, "mainnet").announceAndGuard();
    }

    // ---- the oracle-pricing slice: a non-ada principal is priced, not blanket-refused ------------

    /**
     * ⛔ THE OLD BEHAVIOUR THIS REPLACES. Before this slice a non-ada principal was refused outright
     * with {@code PRINCIPAL_NOT_ADA} regardless of whether a price existed. {@link CompoundEconomics}
     * itself no longer does that — it prices the fee slice and decides on the number.
     */
    @Test
    void aTokenPrincipalWithAnAvailablePriceIsPricedAndDecidedOnTheNumber() {
        var client = new StubOracleClient();
        // 2 lovelace per base unit — a fee of 500_000 base units prices to 1_000_000 lovelace.
        client.usable = OraclePriceFeed.aggregated(TOKEN, BigInteger.TWO, BigInteger.ONE,
                AT_MILLIS - 1_000, AT_MILLIS + 1_000);
        CompoundEconomics economics = economics(true, 0, "preview", new PricingService(client));

        // escrow 100_000_000 base units at 5‰ = 500_000 base units of fee.
        CompoundAssessment a = economics.assess(true, true, TOKEN,
                BigInteger.valueOf(100_000_000L), 5L, TX_FEE, AT_MILLIS);

        assertEquals(BigInteger.valueOf(500_000L), a.expectedFee(), "still in the principal's own unit");
        assertEquals(BigInteger.valueOf(1_000_000L), a.expectedFeeLovelace(), "priced: 500_000 * 2");
        assertEquals(BigInteger.valueOf(700_000L), a.net(), "1_000_000 - 300_000 tx fee");
        assertTrue(a.approved());
    }

    @Test
    void aTokenPrincipalWithNoFeedRefusesAsPriceUnavailable() {
        CompoundEconomics economics =
                economics(true, 0, "preview", new PricingService(new StubOracleClient()));

        CompoundAssessment a = economics.assess(true, true, TOKEN,
                BigInteger.valueOf(999_000_000L), 50L, TX_FEE, AT_MILLIS);

        assertFalse(a.approved());
        assertEquals(CompoundExclusion.PRICE_NO_FEED, a.exclusion());
        assertNullNet(a);
    }

    @Test
    void aTokenPrincipalWithAStaleFeedRefusesAsPriceUnavailable() {
        var client = new StubOracleClient();
        client.anyHeld = OraclePriceFeed.aggregated(TOKEN, BigInteger.ONE, BigInteger.ONE, 1_000L, 2_000L);
        CompoundEconomics economics = economics(true, 0, "preview", new PricingService(client));

        CompoundAssessment a = economics.assess(true, true, TOKEN,
                BigInteger.valueOf(999_000_000L), 50L, TX_FEE, AT_MILLIS);

        assertFalse(a.approved());
        assertEquals(CompoundExclusion.PRICE_NOT_USABLE, a.exclusion());
        assertNullNet(a);
    }

    /** ⛔ A POOLED feed must refuse, never throw — asserted here at the assess() boundary too. */
    @Test
    void aTokenPrincipalWithAPooledFeedRefusesWithoutThrowing() {
        var client = new StubOracleClient();
        client.usable = new OraclePriceFeed(OraclePriceFeed.Variant.POOLED, TOKEN,
                BigInteger.ONE, BigInteger.ONE, AT_MILLIS - 1_000, AT_MILLIS + 1_000);
        CompoundEconomics economics = economics(true, 0, "preview", new PricingService(client));

        CompoundAssessment a = assertDoesNotThrow(() -> economics.assess(true, true, TOKEN,
                BigInteger.valueOf(999_000_000L), 50L, TX_FEE, AT_MILLIS));

        assertFalse(a.approved());
        assertEquals(CompoundExclusion.PRICE_POOLED, a.exclusion());
    }

    @Test
    void evidenceNativeZeroFeeUsesRecoveredAdaWithoutAnyOracleLookup() {
        var throwingClient = new FluidOracleClient("http://unused.invalid") {
            @Override
            public Optional<OraclePriceFeed> findFeed(AssetType asset, long atMillis) {
                throw new AssertionError("F=0 must not query a price feed");
            }

            @Override
            public Optional<OraclePriceFeed> findFeedIgnoringValidity(AssetType asset) {
                throw new AssertionError("F=0 must not query a price feed");
            }
        };
        CompoundEconomics profitable = economics(true, 0, "preview", new PricingService(throwingClient));
        BigInteger q = BigInteger.valueOf(176_000_000L);
        BigInteger e = BigInteger.valueOf(1_986_910L);
        var quote = assertDoesNotThrow(() -> profitable.quote(TOKEN, BigInteger.ZERO, AT_MILLIS));
        assertEquals(Optional.of(e), assertDoesNotThrow(() -> profitable.grossRecoverableLovelace(
                TOKEN, q, 0L, e, AT_MILLIS)), "F=0 ranking is E and also makes no oracle call");

        CompoundAssessment approved = profitable.assessFinal(true, true, TOKEN, q, 0L,
                facts(e, BigInteger.valueOf(100_000L), BigInteger.valueOf(300_000L)), quote);
        assertTrue(approved.approved());
        assertEquals(BigInteger.valueOf(1_586_910L), approved.net(), "E-L-T");

        CompoundAssessment belowFloor = economics(true, 1_600_000L, "preview",
                new PricingService(throwingClient)).assessFinal(true, true, TOKEN, q, 0L,
                facts(e, BigInteger.valueOf(100_000L), BigInteger.valueOf(300_000L)), quote);
        assertFalse(belowFloor.approved());
        assertEquals(CompoundExclusion.NET_BELOW_FLOOR, belowFloor.exclusion());

        BigInteger roundedF = CompoundEconomics.compoundingFee(BigInteger.valueOf(999L), 1L, TOKEN);
        assertEquals(BigInteger.ZERO, roundedF);
        CompoundAssessment roundedZero = profitable.assessFinal(true, true, TOKEN,
                BigInteger.valueOf(999L), 1L,
                facts(e, BigInteger.valueOf(100_000L), BigInteger.valueOf(300_000L)),
                assertDoesNotThrow(() -> profitable.quote(TOKEN, roundedF, AT_MILLIS)));
        assertTrue(roundedZero.approved(), "rounded native F=0 also has no oracle dependency");
    }

    @Test
    void largeZeroFeeAdaNeverCountsEscrowCoinAsRecoveredProfit() {
        BigInteger q = BigInteger.valueOf(9_000_000_000L);
        BigInteger l = BigInteger.valueOf(1_400_000L);
        CompoundTransactionBuilder.KernelFacts deliberatelyNoisyFacts =
                facts(q, BigInteger.valueOf(777_777L), l);

        CompoundAssessment safeDefault = armed(0).assessFinal(true, true, AssetType.ada(), q, 0L,
                deliberatelyNoisyFacts, PricingService.Priced.priced(ONE_ADA));
        assertEquals(ONE_ADA.subtract(l), safeDefault.net(), "ADA net is F-L with F the 1 ADA floor, never Q+F-L");
        assertEquals(BigInteger.ZERO, safeDefault.recoveredEscrowLovelace());
        assertEquals(BigInteger.ZERO, safeDefault.poolTopUpLovelace());
        assertFalse(safeDefault.approved());

        CompoundAssessment statedLoss = armed(-400_000L).assessFinal(true, true, AssetType.ada(), q, 0L,
                deliberatelyNoisyFacts, PricingService.Priced.priced(ONE_ADA));
        assertTrue(statedLoss.approved());
    }

    /**
     * ⛔ An ADA decision is the validator's fee minus the transaction fee — a quote computed from any
     * other fee (the pre-FAB-167 {@code Q*c/1000 = 0}, say) cannot move it.
     */
    @Test
    void anAdaDecisionUsesTheValidatorFeeWhateverQuoteTheCallerHolds() {
        CompoundAssessment a = armed(0).assessFinal(true, true, AssetType.ada(), ESCROW_45_ADA, 0L,
                facts(BigInteger.ZERO, BigInteger.ZERO, TX_FEE), PricingService.Priced.priced(BigInteger.ZERO));

        assertEquals(ONE_ADA, a.expectedFee());
        assertEquals(ONE_ADA, a.expectedFeeLovelace());
        assertEquals(BigInteger.valueOf(700_000L), a.net());
        assertTrue(a.approved());
    }

    @Test
    void nativeFinalAssessmentUsesTheExactRawRationalQuoteAndKernelFacts() {
        var client = new StubOracleClient();
        client.usable = OraclePriceFeed.aggregated(TOKEN, BigInteger.TEN, BigInteger.valueOf(3L),
                AT_MILLIS - 1_000, AT_MILLIS + 1_000);
        CompoundEconomics economics = economics(true, 0, "preview", new PricingService(client));
        BigInteger q = BigInteger.valueOf(1_999L);
        BigInteger f = CompoundEconomics.compoundingFee(q, 1L, TOKEN);
        PricingService.Priced quote = economics.quote(TOKEN, f, AT_MILLIS);

        CompoundAssessment assessment = economics.assessFinal(true, true, TOKEN, q, 1L,
                facts(BigInteger.valueOf(2_000_000L), BigInteger.valueOf(700_000L),
                        BigInteger.valueOf(400_000L)), quote);

        assertEquals(BigInteger.ONE, assessment.expectedFee(), "F is floor(Q*c/1000) in raw units");
        assertEquals(BigInteger.valueOf(3L), assessment.expectedFeeLovelace(), "floor(F*10/3)");
        assertEquals(BigInteger.valueOf(900_003L), assessment.net(), "pricedF+E-L-T");
        assertEquals(client.usable, assessment.priceObservation());
    }

    @Test
    void finalObservationMustRemainTheSameAndUsable() {
        var client = new StubOracleClient();
        OraclePriceFeed first = OraclePriceFeed.aggregated(TOKEN, BigInteger.TWO, BigInteger.ONE,
                AT_MILLIS - 1_000, AT_MILLIS + 10_000);
        client.usable = first;
        CompoundEconomics economics = economics(true, 0, "preview", new PricingService(client));
        BigInteger q = BigInteger.valueOf(100_000L);
        PricingService.Priced quote = economics.quote(TOKEN,
                CompoundEconomics.compoundingFee(q, 10L, TOKEN), AT_MILLIS);
        CompoundAssessment assessment = economics.assessFinal(true, true, TOKEN, q, 10L,
                facts(BigInteger.valueOf(2_000_000L), BigInteger.ZERO, BigInteger.valueOf(300_000L)), quote);

        client.usable = OraclePriceFeed.aggregated(TOKEN, BigInteger.valueOf(3L), BigInteger.ONE,
                AT_MILLIS - 1_000, AT_MILLIS + 10_000);
        assertEquals(CompoundExclusion.PRICE_OBSERVATION_CHANGED,
                economics.revalidateObservation(assessment, TOKEN, AT_MILLIS));

        client.usable = null;
        client.anyHeld = first;
        assertEquals(CompoundExclusion.PRICE_OBSERVATION_EXPIRED,
                economics.revalidateObservation(assessment, TOKEN, AT_MILLIS + 20_000L));
    }

    @Test
    void nonPositiveAndConversionPriceFailuresKeepTheirProductionExclusionNames() {
        assertEquals(CompoundExclusion.PRICE_NON_POSITIVE,
                CompoundEconomics.priceExclusion(new PricingService.PriceRefusal(TOKEN, AT_MILLIS,
                        PricingService.RefusalReason.NON_POSITIVE, 1L, 2L, 1L)));
        assertEquals(CompoundExclusion.PRICE_CONVERSION_FAILED,
                CompoundEconomics.priceExclusion(new PricingService.PriceRefusal(TOKEN, AT_MILLIS,
                        PricingService.RefusalReason.CONVERSION_FAILED, null, null, null)));
    }

    private static CompoundTransactionBuilder.KernelFacts facts(BigInteger recovered,
                                                                 BigInteger topUp,
                                                                 BigInteger fee) {
        return new CompoundTransactionBuilder.KernelFacts(recovered, topUp, fee, new byte[]{1, 2, 3});
    }

    private static void assertNullNet(CompoundAssessment a) {
        assertTrue(a.net() == null && a.expectedFee() == null,
                "a structural refusal must not publish arithmetic that was never valid");
    }

    /** Same shape as {@code PricingServiceTest}'s stub — local so this file stays self-contained. */
    private static final class StubOracleClient extends FluidOracleClient {
        private OraclePriceFeed usable;
        private OraclePriceFeed anyHeld;

        StubOracleClient() {
            super("http://unused.invalid");
        }

        @Override
        public Optional<OraclePriceFeed> findFeed(AssetType asset, long atMillis) {
            return Optional.ofNullable(usable);
        }

        @Override
        public Optional<OraclePriceFeed> findFeedIgnoringValidity(AssetType asset) {
            return Optional.ofNullable(anyHeld);
        }
    }
}
