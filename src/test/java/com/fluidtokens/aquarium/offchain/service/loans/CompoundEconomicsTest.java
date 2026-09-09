package com.fluidtokens.aquarium.offchain.service.loans;

import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.CompoundAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.CompoundExclusion;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigInteger;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The compound profitability gate, including the case Giovanni ruled on 2026-09-02:
 * <i>"there should be a check compoudingFeePerMille in the bot to accept to process if zero … as long
 * as operator checks this himself and owns it."</i>
 *
 * <p>The shape that encodes "owns it" is the same one the liquidation margin uses: a floor the
 * operator <b>states</b>, never a check they switch off. So there is no boolean here to disable the
 * gate — arming zero-fee work means stating a negative number, which still bounds the loss.
 *
 * <p>Since the oracle-pricing slice (2026-09-09), {@link CompoundEconomics#assess} also prices a
 * non-ada principal's fee slice through {@link PricingService} — every test up to that heading uses
 * ada throughout, unchanged from before that slice; the ones under it exercise the new pricing path
 * directly, independent of whether {@code CompoundCandidateScanner} routes a live candidate to it
 * today (it does not yet — see {@code CompoundExclusion.PRINCIPAL_NOT_ADA}'s javadoc).
 */
class CompoundEconomicsTest {

    private static final BigInteger ESCROW_45_ADA = BigInteger.valueOf(45_000_000L);
    private static final BigInteger TX_FEE = BigInteger.valueOf(300_000L);
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

    /** A paying pool: 45 ADA escrow at 5‰ earns 225_000, which does not clear a 300_000 fee. */
    @Test
    void aFeeThatDoesNotCoverTheTransactionIsRefusedAtTheSafeDefault() {
        CompoundAssessment a = armed(0).assess(true, true, AssetType.ada(), ESCROW_45_ADA, 5L, TX_FEE, AT_MILLIS);

        assertEquals(BigInteger.valueOf(225_000L), a.expectedFee());
        assertEquals(BigInteger.valueOf(225_000L), a.expectedFeeLovelace(),
                "for an ada principal the priced figure must equal the on-chain one exactly");
        assertEquals(BigInteger.valueOf(-75_000L), a.net());
        assertFalse(a.approved());
        assertEquals(CompoundExclusion.NET_BELOW_FLOOR, a.exclusion());
    }

    /** The same pool with enough escrow clears it: 200 ADA at 5‰ earns 1_000_000. */
    @Test
    void aFeeThatCoversTheTransactionIsApproved() {
        CompoundAssessment a = armed(0)
                .assess(true, true, AssetType.ada(), BigInteger.valueOf(200_000_000L), 5L, TX_FEE, AT_MILLIS);

        assertEquals(BigInteger.valueOf(1_000_000L), a.expectedFee());
        assertEquals(BigInteger.valueOf(700_000L), a.net());
        assertTrue(a.approved());
    }

    /** Exact break-even is allowed by the default floor of 0 — it is not a loss. */
    @Test
    void exactBreakEvenIsApprovedAtTheDefaultFloor() {
        CompoundAssessment a = armed(0).assess(true, true, AssetType.ada(),
                BigInteger.valueOf(60_000_000L), 5L, BigInteger.valueOf(300_000L), AT_MILLIS);

        assertEquals(BigInteger.ZERO, a.net());
        assertTrue(a.approved(), "net == floor must pass: the floor is a minimum, not a strict bound");
    }

    /**
     * ⛔ THE SAFE DEFAULT. The only live preview pool published {@code compoudingFeePerMille = 0}
     * (findings §20.2). Out of the box that work is refused, so the node can never quietly do it.
     */
    @Test
    void aZeroFeePoolIsRefusedOutOfTheBox() {
        CompoundAssessment a = armed(0).assess(true, true, AssetType.ada(), ESCROW_45_ADA, 0L, TX_FEE, AT_MILLIS);

        assertTrue(a.zeroFeePool());
        assertEquals(BigInteger.ZERO, a.expectedFee());
        assertEquals(TX_FEE.negate(), a.net(), "the whole outlay is the transaction fee");
        assertFalse(a.approved());
        assertEquals(CompoundExclusion.NET_BELOW_FLOOR, a.exclusion());
    }

    /**
     * ⛔ AND THE OPERATOR'S RULED CHOICE. A stated negative floor arms exactly that work — and still
     * bounds it: the loss may not exceed the number the operator wrote down.
     */
    @Test
    void aStatedNegativeFloorArmsZeroFeeWorkAndStillBoundsTheLoss() {
        CompoundEconomics armedAtALoss = armed(-2_000_000L);

        CompoundAssessment allowed =
                armedAtALoss.assess(true, true, AssetType.ada(), ESCROW_45_ADA, 0L, TX_FEE, AT_MILLIS);
        assertTrue(allowed.approved(), "a -2 ADA stated bound must accept a 0.3 ADA loss");
        assertTrue(allowed.zeroFeePool());

        // Still a bound, not an off switch: a loss beyond the stated figure is refused.
        CompoundAssessment tooExpensive = armedAtALoss.assess(true, true, AssetType.ada(),
                ESCROW_45_ADA, 0L, BigInteger.valueOf(2_500_000L), AT_MILLIS);
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

        assertEquals(BigInteger.valueOf(225_000L), a.expectedFeeLovelace());
        assertEquals(BigInteger.valueOf(-75_000L), a.net());
        assertFalse(a.approved());
    }

    /**
     * ⛔ The on-chain expression truncates, and so must this one. Rounding UP would claim a fee the
     * validator does not require the pool to give up, and the transaction would fail phase 2 — after
     * collateral is committed.
     */
    @Test
    void theFeeTruncatesExactlyAsTheValidatorDoes() {
        // 1999 * 1 / 1000 = 1 on chain, not 2.
        assertEquals(BigInteger.ONE, CompoundEconomics.expectedFee(BigInteger.valueOf(1999L), 1L));
        assertEquals(BigInteger.ZERO, CompoundEconomics.expectedFee(BigInteger.valueOf(999L), 1L));
        assertEquals(BigInteger.valueOf(225_000L), CompoundEconomics.expectedFee(ESCROW_45_ADA, 5L));
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
     * itself no longer does that — it prices the fee slice and decides on the number. (A live
     * candidate still never reaches here with a non-ada principal today: see
     * {@code CompoundCandidateScanner}, which keeps its own separate, structural
     * {@code PRINCIPAL_NOT_ADA} gate for reasons that have nothing to do with pricing.)
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
        assertEquals(CompoundExclusion.PRICE_UNAVAILABLE, a.exclusion());
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
        assertEquals(CompoundExclusion.PRICE_UNAVAILABLE, a.exclusion());
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
        assertEquals(CompoundExclusion.PRICE_UNAVAILABLE, a.exclusion());
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
