package com.fluidtokens.aquarium.offchain.service.loans;

import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PricingService} — the one place a token quantity becomes lovelace, and it fails closed.
 * <p>
 * {@link StubOracleClient} is this codebase's established shape for testing against
 * {@link FluidOracleClient} without the registry's JSON machinery (same pattern as
 * {@code LiquidationSubmitVetoTest.FakeOracleClient}): it overrides the two public accessors
 * {@code findFeed}/{@code findFeedIgnoringValidity} directly, so each test hands {@link PricingService}
 * exactly the feed situation it wants to prove.
 */
class PricingServiceTest {

    /** A test-only token — not a real mainnet asset, just something that is not ada. */
    private static final AssetType TOKEN =
            new AssetType("c0eaf6cea665d1b99647a9d24461984103de0492da93e7af29fe5d9", "5553444d");

    private static final long AT_MILLIS = 1_700_000_000_000L;

    /**
     * Overrides ONLY {@code findFeedIgnoringValidity}, so the REAL {@code findFeed} — and therefore
     * the real {@code usableAt(atMillis)} window check — runs against the instant the service passes.
     * The other stubs answer {@code findFeed} whatever the instant, which is exactly why a mutant
     * pricing at {@code 0L} instead of {@code atMillis} survived the suite (slice-2 audit, M7).
     */
    private static final class WindowedOracleClient extends FluidOracleClient {
        private final OraclePriceFeed held;

        WindowedOracleClient(OraclePriceFeed held) {
            super("http://unused.invalid");
            this.held = held;
        }

        @Override
        public Optional<OraclePriceFeed> findFeedIgnoringValidity(AssetType asset) {
            return asset.isAda() ? Optional.of(OraclePriceFeed.unit()) : Optional.of(held);
        }
    }

    /** Hands {@link PricingService} exactly the {@code findFeed}/{@code findFeedIgnoringValidity}
     *  answers a test wants, bypassing the registry's JSON parsing entirely. */
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

    /** Always throws — proves a code path never touches the oracle at all. */
    private static final class ThrowingOracleClient extends FluidOracleClient {
        ThrowingOracleClient() {
            super("http://unused.invalid");
        }

        @Override
        public Optional<OraclePriceFeed> findFeed(AssetType asset, long atMillis) {
            throw new AssertionError("must not consult the oracle for ada");
        }

        @Override
        public Optional<OraclePriceFeed> findFeedIgnoringValidity(AssetType asset) {
            throw new AssertionError("must not consult the oracle for ada");
        }
    }

    // ---- ADA is the identity ------------------------------------------------------------------

    @Test
    void adaIsTheIdentityAndConsultsNoOracle() {
        var service = new PricingService(new ThrowingOracleClient());

        var priced = service.toLovelace(AssetType.ada(), BigInteger.valueOf(123_456_789L), AT_MILLIS);

        assertTrue(priced.isPriced());
        assertEquals(BigInteger.valueOf(123_456_789L), priced.lovelace(),
                "ada must come back byte-for-byte the input amount");
    }

    // ---- correct pricing, arithmetic stated -----------------------------------------------------

    /**
     * 21,785,609 / 100,000,000 lovelace per base unit (live FLDT registry price, 2026-09-09) times
     * 23,000,000,000 base units = 5,010,690,070 lovelace exactly (230 * 21,785,609, no remainder) —
     * matching the value verified against live mainnet data.
     */
    @Test
    void pricesAKnownTokenExactlyAgainstItsFeed() {
        var client = new StubOracleClient();
        client.usable = OraclePriceFeed.aggregated(TOKEN, BigInteger.valueOf(21_785_609L),
                BigInteger.valueOf(100_000_000L), AT_MILLIS - 1_000, AT_MILLIS + 1_000);
        var service = new PricingService(client);

        var priced = service.toLovelace(TOKEN, BigInteger.valueOf(23_000_000_000L), AT_MILLIS);

        assertTrue(priced.isPriced());
        assertEquals(BigInteger.valueOf(5_010_690_070L), priced.lovelace());
    }

    /**
     * ⛔ NO DECIMALS SCALING. 2 lovelace per base unit, 3 base units — the correct answer is 6, not
     * 6,000,000 (a stray x1_000_000) and not 0 (a stray /1_000_000). A mutant introducing either
     * multiplier dies here, cheaply and obviously.
     */
    @Test
    void appliesNoDecimalsScaling() {
        var client = new StubOracleClient();
        client.usable = OraclePriceFeed.aggregated(TOKEN, BigInteger.TWO, BigInteger.ONE,
                AT_MILLIS - 1_000, AT_MILLIS + 1_000);
        var service = new PricingService(client);

        var priced = service.toLovelace(TOKEN, BigInteger.valueOf(3L), AT_MILLIS);

        assertTrue(priced.isPriced());
        assertEquals(BigInteger.valueOf(6L), priced.lovelace());
        assertNotEquals(BigInteger.valueOf(6_000_000L), priced.lovelace());
        assertNotEquals(BigInteger.ZERO, priced.lovelace());
    }

    /**
     * Rounds DOWN — the direction that does not flatter whatever profitability check reads the
     * result. 10 base units at 1/3 lovelace each is 10/3 = 3.33…, floored to 3, never ceil'd to 4.
     */
    @Test
    void roundsDown() {
        var client = new StubOracleClient();
        client.usable = OraclePriceFeed.aggregated(TOKEN, BigInteger.ONE, BigInteger.valueOf(3L),
                AT_MILLIS - 1_000, AT_MILLIS + 1_000);
        var service = new PricingService(client);

        var priced = service.toLovelace(TOKEN, BigInteger.TEN, AT_MILLIS);

        assertTrue(priced.isPriced());
        assertEquals(BigInteger.valueOf(3L), priced.lovelace(), "10/3 must floor to 3, not ceil to 4");
    }

    // ---- fail-closed refusals, distinguishable from one another ---------------------------------

    @Test
    void aMissingFeedRefusesAsNoFeed() {
        var client = new StubOracleClient();   // usable and anyHeld both left null
        var service = new PricingService(client);

        var priced = service.toLovelace(TOKEN, BigInteger.TEN, AT_MILLIS);

        assertFalse(priced.isPriced());
        assertNull(priced.lovelace(), "a refusal must never also carry a number");
        assertEquals(PricingService.RefusalReason.NO_FEED, priced.refusal().reason());
        assertEquals(TOKEN, priced.refusal().asset());
        assertEquals(AT_MILLIS, priced.refusal().atMillis());
        assertNull(priced.refusal().feedValidFrom(), "no feed means no window to report");
        assertNull(priced.refusal().feedValidTo());
        assertNull(priced.refusal().feedAgeMillis());
    }

    @Test
    void aStaleFeedRefusesAsNotUsableAndCarriesItsWindowAndAge() {
        var client = new StubOracleClient();
        // usable left null: findFeed itself has already filtered this feed out as unusable.
        client.anyHeld = OraclePriceFeed.aggregated(TOKEN, BigInteger.ONE, BigInteger.ONE,
                1_000L, 2_000L);   // expired long before AT_MILLIS
        var service = new PricingService(client);

        var priced = service.toLovelace(TOKEN, BigInteger.TEN, AT_MILLIS);

        assertFalse(priced.isPriced());
        assertEquals(PricingService.RefusalReason.NOT_USABLE_AT_INSTANT, priced.refusal().reason());
        assertEquals(1_000L, priced.refusal().feedValidFrom());
        assertEquals(2_000L, priced.refusal().feedValidTo());
        assertEquals(AT_MILLIS - 1_000L, priced.refusal().feedAgeMillis());
    }

    /** The missing and stale cases must be told apart — an operator's remedy differs for each. */
    @Test
    void aMissingFeedAndAStaleFeedAreDistinguishable() {
        var missing = new PricingService(new StubOracleClient())
                .toLovelace(TOKEN, BigInteger.TEN, AT_MILLIS);

        var staleClient = new StubOracleClient();
        staleClient.anyHeld = OraclePriceFeed.aggregated(TOKEN, BigInteger.ONE, BigInteger.ONE, 1_000L, 2_000L);
        var stale = new PricingService(staleClient).toLovelace(TOKEN, BigInteger.TEN, AT_MILLIS);

        assertNotEquals(missing.refusal().reason(), stale.refusal().reason());
    }

    /**
     * ⛔ A POOLED feed must refuse by NAME rather than let {@code OraclePriceFeed.price()} escape as
     * a generic exception — asserted by construction: {@code toLovelace} must not throw.
     */
    @Test
    void aPooledFeedRefusesWithoutThrowing() {
        var client = new StubOracleClient();
        client.usable = new OraclePriceFeed(OraclePriceFeed.Variant.POOLED, TOKEN,
                BigInteger.ONE, BigInteger.ONE, AT_MILLIS - 1_000, AT_MILLIS + 1_000);
        var service = new PricingService(client);

        var priced = assertDoesNotThrow(() -> service.toLovelace(TOKEN, BigInteger.TEN, AT_MILLIS));

        assertFalse(priced.isPriced());
        assertEquals(PricingService.RefusalReason.POOLED, priced.refusal().reason());
    }

    /** A pooled feed that is ALSO expired still reports POOLED — the more permanent, useful fact. */
    @Test
    void aPooledAndExpiredFeedStillReportsPooled() {
        var client = new StubOracleClient();
        client.anyHeld = new OraclePriceFeed(OraclePriceFeed.Variant.POOLED, TOKEN,
                BigInteger.ONE, BigInteger.ONE, 1_000L, 2_000L);
        var service = new PricingService(client);

        var priced = assertDoesNotThrow(() -> service.toLovelace(TOKEN, BigInteger.TEN, AT_MILLIS));

        assertFalse(priced.isPriced());
        assertEquals(PricingService.RefusalReason.POOLED, priced.refusal().reason());
    }

    // ---- the fail-closed property itself, as its own mutant-killing assertion -------------------

    /**
     * ⛔ THE MUTANT THIS KILLS: a refusal path that falls through to returning the amount unchanged
     * (a 1:1 assumption). Every refusal above already asserts {@code isPriced() == false}, but this
     * test states the property on its own, once, unmissably: a refused {@link PricingService.Priced}
     * NEVER carries a lovelace figure, whatever the amount asked for.
     */
    @Test
    void aRefusalNeverCarriesAnAmountEvenWhenOneWouldLookPlausible() {
        var client = new StubOracleClient();   // no feed at all
        var service = new PricingService(client);

        var priced = service.toLovelace(TOKEN, BigInteger.valueOf(999_999L), AT_MILLIS);

        assertFalse(priced.isPriced());
        assertNull(priced.lovelace(),
                "a 1:1 fallback would return 999_999 here — fail-closed means it returns nothing");
    }

    /**
     * ⛔ The INSTANT is part of the question. A feed valid only over a window must price inside it and
     * refuse outside it — through the service's own call, with the real {@code findFeed} in play.
     * Mutant: {@code oracleClient.findFeed(asset, 0L)} — pricing at a fixed instant instead of the
     * one asked for — survived every other test here, because they stub {@code findFeed} to ignore
     * its arguments. This one does not, so that mutant now refuses at 1_300_000 and dies.
     */
    @Test
    void pricesAtTheInstantAskedNotAtSomeOtherInstant() {
        OraclePriceFeed windowed = OraclePriceFeed.aggregated(TOKEN, BigInteger.TWO, BigInteger.ONE,
                1_000_000L, 1_600_000L);
        PricingService service = new PricingService(new WindowedOracleClient(windowed));

        PricingService.Priced inside = service.toLovelace(TOKEN, BigInteger.valueOf(5L), 1_300_000L);
        assertTrue(inside.isPriced(), "inside the feed's window the price must be available: " + inside);
        assertEquals(BigInteger.TEN, inside.lovelace());

        PricingService.Priced before = service.toLovelace(TOKEN, BigInteger.valueOf(5L), 0L);
        assertFalse(before.isPriced(), "outside the window the same feed must refuse: " + before);
        assertEquals(PricingService.RefusalReason.NOT_USABLE_AT_INSTANT, before.refusal().reason());
    }
}
