package com.fluidtokens.aquarium.offchain.controller;

import com.fluidtokens.aquarium.offchain.service.loans.FluidOracleClient;
import com.fluidtokens.aquarium.offchain.service.loans.OracleClients;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GET /loans/oracle} — the endpoint that makes a stale oracle distinguishable from a healthy
 * one.
 * <p>
 * It exists because {@code /loans} cannot tell those apart: both simply turn every priced field
 * null. The field that does the work is {@code seconds_since_refresh}, which separates "we
 * refreshed seconds ago and the window really has passed" from "we have not refreshed in minutes".
 */
class OracleStatusEndpointTest {

    private static LoanController controllerWith(FluidOracleClient client) {
        return new LoanController(null, null, new ObjectProvider<>() {
            @Override
            public FluidOracleClient getObject() {
                return client;
            }

            @Override
            public FluidOracleClient getObject(Object... args) {
                return client;
            }

            @Override
            public FluidOracleClient getIfAvailable() {
                return client;
            }

            @Override
            public FluidOracleClient getIfUnique() {
                return client;
            }
        }, null);
    }

    @Test
    void reportsEveryTrackedFeed() throws Exception {
        var status = controllerWith(OracleClients.preview()).oracle();

        assertEquals(5, status.trackedAssets());
        assertEquals(5, status.feeds().size());
        assertNotNull(status.lastRefresh());
        assertTrue(status.secondsSinceRefresh() >= 0);
    }

    /**
     * ⛔ FAB-112. Since 2026-09-30 most tokens have TWO oracles (v1 for Lending v3, v2 for v4). The
     * endpoint must list every oracle with its version, and say tokens and oracles as two numbers —
     * one entry per token showed only v2, the version no live loan used.
     */
    @Test
    void listsEveryOracleWithItsVersionAndCountsTokensAndOraclesSeparately() throws Exception {
        var status = controllerWith(OracleClients.mainnetTwoVersions()).oracle();

        assertEquals(19, status.trackedAssets(), "priced tokens");
        assertEquals(35, status.trackedOracles(), "registry oracles");
        assertEquals(35, status.feeds().size(), "every oracle is listed, not one per token");

        var night = status.feeds().stream()
                .filter(f -> f.token().equals("0691b2fecca1ac4f53cb6dfb00b7013e561d1f34403b957cbb5af1fa4e49474854"))
                .toList();
        assertEquals(2, night.size(), "NIGHT has a v1 and a v2 oracle");
        assertEquals(java.util.Set.of(1, 2), night.stream()
                .map(LoanController.OracleFeedView::oracleVersion).collect(java.util.stream.Collectors.toSet()));
        assertTrue(night.stream().anyMatch(f -> f.oracleToken().startsWith("93794f9b"))
                        && night.stream().anyMatch(f -> f.oracleToken().startsWith("26e60b20")),
                "each version names its own oracle NFT");
    }

    /** A registry without {@code oracleVersion} (every payload before 2026-09-30) reads as unknown. */
    @Test
    void anEntryWithoutAVersionIsUnknownNotAnError() throws Exception {
        var status = controllerWith(OracleClients.preview()).oracle();

        assertEquals(5, status.feeds().size(), "nothing may be dropped for lacking a version");
        assertTrue(status.feeds().stream().allMatch(f -> f.oracleVersion() == null));
    }

    /**
     * The captured payload is old, so every window in it has passed. That is the useful assertion:
     * a feed we hold is not the same as a feed we can use, and the endpoint must say so.
     */
    @Test
    void aHeldFeedIsNotAutomaticallyAUsableFeed() throws Exception {
        var status = controllerWith(OracleClients.preview()).oracle();

        assertEquals(0, status.usableNow(), "the fixture is old, so nothing in it is still valid");
        assertTrue(status.feeds().stream().noneMatch(LoanController.OracleFeedView::usableNow));
        assertTrue(status.feeds().stream().allMatch(f -> f.validFrom() != null && f.validTo() != null),
                "windows must be reported even when they have passed — that is the diagnosis");
    }

    /**
     * Signature counts and liquidation-readiness travel with each feed, not just the price.
     * <p>
     * On preview that is now the FLDTmultisig {@code AGGREGATED} feed (a resolvable signature) plus
     * the three {@code PRICE_DATA_CHARLIE} feeds (tFLDT, NIGHT, OADA — each carrying a Charli3
     * reference input); fGold stays unusable because its signatures cannot be resolved to key
     * positions.
     */
    @Test
    void reportsWhichFeedsAreUsableForLiquidation() throws Exception {
        var status = controllerWith(OracleClients.preview()).oracle();

        var signable = status.feeds().stream().filter(LoanController.OracleFeedView::usableForLiquidation).toList();
        assertEquals(4, signable.size(),
                "FLDTmultisig (AGGREGATED) plus the three c3 feeds carrying a reference input");

        var aggregated = signable.stream().filter(f -> "AGGREGATED".equals(f.variant())).toList();
        assertEquals(1, aggregated.size(), "only the FLDTmultisig test token publishes a resolvable key");
        assertEquals(1, aggregated.getFirst().signatures());

        var charlie = signable.stream().filter(f -> "PRICE_DATA_CHARLIE".equals(f.variant())).toList();
        assertEquals(3, charlie.size(), "tFLDT, NIGHT and OADA are all Charli3-backed on preview");
        assertTrue(charlie.stream().allMatch(f -> f.signatures() == 0),
                "c3 feeds are usable for liquidation with zero signatures");

        var fgold = status.feeds().stream()
                .filter(f -> f.token().startsWith("4f4e7bb17c0e7201cc82f0177ab22695fbcee2d99735d1c3fdc44eac")
                        && f.token().endsWith("66476f6c64"))
                .findFirst().orElseThrow();
        assertFalse(fgold.usableForLiquidation(),
                "fGold publishes signatures with no publicKeys to resolve them against");
    }

    /** With the oracle disabled the endpoint must degrade, not throw. */
    @Test
    void survivesTheOracleBeingDisabled() {
        var status = controllerWith(null).oracle();

        assertNull(status.lastRefresh());
        assertEquals(0, status.trackedAssets());
        assertTrue(status.feeds().isEmpty());
        assertFalse(status.secondsSinceRefresh() > 0);
    }
}
