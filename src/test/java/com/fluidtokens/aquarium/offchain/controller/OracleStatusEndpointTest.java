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
        });
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

    /** Signature counts and liquidation-readiness travel with each feed, not just the price. */
    @Test
    void reportsWhichFeedsCouldActuallySignALiquidation() throws Exception {
        var status = controllerWith(OracleClients.preview()).oracle();

        var signable = status.feeds().stream().filter(LoanController.OracleFeedView::usableForLiquidation).toList();
        assertEquals(1, signable.size(), "only the FLDTmultisig test token publishes a resolvable key");
        assertEquals(1, signable.getFirst().signatures());
        assertEquals("AGGREGATED", signable.getFirst().variant());

        assertTrue(status.feeds().stream()
                        .filter(f -> "PRICE_DATA_CHARLIE".equals(f.variant()))
                        .noneMatch(LoanController.OracleFeedView::usableForLiquidation),
                "Charli3-backed feeds cannot be built into a redeemer yet");
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
