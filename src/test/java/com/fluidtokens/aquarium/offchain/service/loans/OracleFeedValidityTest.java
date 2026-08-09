package com.fluidtokens.aquarium.offchain.service.loans;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The validity rules from {@code lib/fluidtokens/oracle.ak}, and the registry parsing that feeds
 * them.
 * <p>
 * On chain the rule is not "the feed has not expired" but that the feed's window <em>contains</em>
 * the transaction's validity interval, and is itself no wider than
 * {@code constants.max_oracle_validity_range}. Reporting a loan as liquidatable on a price that
 * fails either test would produce a transaction the validator rejects.
 */
class OracleFeedValidityTest {

    private static final String MAINNET_FLDT =
            "577f0b1342f8f8f4aed3388b80a8535812950c7a892495c0ecdf0f1e";

    private static OraclePriceFeed feed(long validFrom, long validTo) {
        return OraclePriceFeed.aggregated(new AssetType(MAINNET_FLDT, "0014df10464c4454"),
                BigInteger.valueOf(22_930_613), BigInteger.valueOf(100_000_000), validFrom, validTo);
    }

    private static FluidOracleClient clientFromFixture() throws Exception {
        var client = new FluidOracleClient("http://unused.invalid");
        try (InputStream in = OracleFeedValidityTest.class
                .getResourceAsStream("/loans-v4/oracle-registry.json")) {
            JsonNode payload = new ObjectMapper().readTree(in);
            client.load(payload);
        }
        return client;
    }

    @Test
    void aFeedCoversOnlyIntervalsInsideItsWindow() {
        var f = feed(1_000, 2_000);
        assertTrue(f.covers(1_000, 2_000), "exactly the window is covered");
        assertTrue(f.covers(1_500, 1_600));
        assertFalse(f.covers(999, 1_500), "starts before valid_from");
        assertFalse(f.covers(1_500, 2_001), "ends after valid_to");
    }

    @Test
    void aWindowWiderThanTheProtocolMaximumIsUnusable() {
        long tooWide = OraclePriceFeed.MAX_VALIDITY_RANGE_MILLIS + 1;
        assertFalse(feed(0, tooWide).usableAt(tooWide / 2),
                "valid_to - valid_from must be <= max_oracle_validity_range");
        assertTrue(feed(0, OraclePriceFeed.MAX_VALIDITY_RANGE_MILLIS).usableAt(1),
                "exactly the maximum is allowed");
    }

    @Test
    void anExpiredFeedIsNotUsable() {
        var f = feed(1_000, 2_000);
        assertTrue(f.usableAt(1_500));
        assertFalse(f.usableAt(2_001), "expired");
        assertFalse(f.usableAt(999), "not yet started");
    }

    /**
     * The trap in this change. The synthesised ada feed is {@code 0..0}, so any naive staleness
     * check marks it permanently expired — and since ada is one leg of most loans, that would
     * blank the health of nearly every loan while looking like an oracle outage.
     * {@code retrieve_oracle_data} returns it before reaching any window check.
     */
    @Test
    void theSynthesisedAdaFeedIsAlwaysUsableDespiteItsZeroWindow() {
        var ada = OraclePriceFeed.unit();
        assertEquals(0L, ada.validFrom());
        assertEquals(0L, ada.validTo());
        assertTrue(ada.isSynthesisedUnitFeed());
        assertTrue(ada.usableAt(System.currentTimeMillis()), "ada must never expire");
        assertTrue(ada.usableOver(0, Long.MAX_VALUE));
    }

    @Test
    void adaIsPricedWithoutConsultingTheRegistry() throws Exception {
        var client = clientFromFixture();
        var feed = client.findFeed(AssetType.ada(), System.currentTimeMillis());
        assertTrue(feed.isPresent());
        assertEquals(BigInteger.ONE, feed.get().priceInLovelaces());
        assertEquals(BigInteger.ONE, feed.get().priceDenominator());
    }

    @Test
    void theRegistryPayloadParsesIntoPricedAssets() throws Exception {
        var client = clientFromFixture();
        assertEquals(19, client.trackedAssets(), "all 19 fixture entries are active and priced");

        var fldt = client.findFeedIgnoringValidity(new AssetType(MAINNET_FLDT, "0014df10464c4454"));
        assertTrue(fldt.isPresent(), "mainnet FLDT should be priced");
        assertEquals(BigInteger.valueOf(100_000_000), fldt.get().priceDenominator());
        assertEquals(new AssetType(MAINNET_FLDT, "0014df10464c4454"), fldt.get().token(),
                "the token travels inside the signed feed and must survive parsing");
    }

    /**
     * Preview collateral is not in the mainnet registry, which is why preview loans report debt
     * and lateness but never LTV. Pinned so the day a preview oracle appears, this fails and tells
     * us the assumption changed.
     */
    @Test
    void previewFldtIsAbsentFromTheMainnetRegistry() throws Exception {
        var previewFldt = new AssetType(
                "0b77d150c275bd0a600633e4be7d09f83c4b9f00981e22ac9c9d3f62", "0014df1074464c4454");
        assertTrue(clientFromFixture().findFeedIgnoringValidity(previewFldt).isEmpty());
    }

    /**
     * Fail-closed: the safely-named lookup must drop a feed the chain would reject, while the
     * diagnostic lookup still returns it so callers can say "expired" rather than "unknown asset".
     */
    @Test
    void theSafeLookupDropsStaleFeedsButTheDiagnosticOneDoesNot() throws Exception {
        var client = clientFromFixture();
        var fldt = new AssetType(MAINNET_FLDT, "0014df10464c4454");
        long longAfterTheFixtureExpired = client.findFeedIgnoringValidity(fldt).orElseThrow().validTo() + 1;

        assertTrue(client.findFeed(fldt, longAfterTheFixtureExpired).isEmpty(),
                "an expired price must not be used to decide a liquidation");
        assertTrue(client.findFeedIgnoringValidity(fldt).isPresent(),
                "but it is still available to explain why health is unavailable");
    }
}
