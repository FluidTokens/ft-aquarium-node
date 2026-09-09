package com.fluidtokens.aquarium.offchain.service.loans;

import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.CollateralAsset;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ada as collateral — the inverse of the usual loan, and a shape that silently lost its price.
 * <p>
 * Preview loan {@code bad3e0871c24…} borrows tFLDT against 40 ada. Its datum carries an empty
 * collateral policy id, which on chain is the {@code expectedTokenPolicyId == ""} branch of
 * {@code retrieve_oracle_data} — a synthesised 1:1 feed, no oracle consulted. Off chain we were
 * building {@code new AssetType("", "")}, which is not the ada this codebase spells
 * {@code ("lovelace", "")}, so the lookup missed and health reported
 * {@code "no oracle price for "} — with nothing after the "for", which is what gave it away.
 * <p>
 * The same empty policy id also made the collateral <em>quantity</em> zero, because nothing in a
 * UTxO is called {@code ""}. Zero collateral reads as a fully undercollateralised loan, and it
 * suppressed {@code bot_liquidatable} through the {@code collateralAmount > 1} check.
 */
class AdaCollateralTest {

    private static final String TFLDT_POLICY = "0b77d150c275bd0a600633e4be7d09f83c4b9f00981e22ac9c9d3f62";

    /** Exactly as {@code LoanDatumConverter} decodes an ada collateral: empty policy, empty name. */
    private static CollateralAsset adaCollateral() {
        return new CollateralAsset("", Optional.of(""), AssetType.ada());
    }

    @Test
    void adaCollateralResolvesToAdaAndNotToTheEmptyAsset() {
        var collateral = adaCollateral();

        assertTrue(collateral.isAda());
        assertEquals(AssetType.ada(), collateral.assetType());
        assertTrue(collateral.assetType().isAda(),
                "must be the ada the oracle client recognises, or the 1:1 feed is never returned");
        assertEquals("lovelace", collateral.assetType().toUnit());
    }

    @Test
    void aTokenCollateralIsUnaffected() {
        var named = new CollateralAsset(TFLDT_POLICY, Optional.of("0014df1074464c4454"), AssetType.ada());
        assertFalse(named.isAda());
        assertEquals(new AssetType(TFLDT_POLICY, "0014df1074464c4454"), named.assetType());
    }

    /** A collection keeps the empty asset name — the oracle prices the whole policy. */
    @Test
    void aCollectionResolvesToItsPolicyWithNoName() {
        var collection = new CollateralAsset(TFLDT_POLICY, Optional.empty(), AssetType.ada());
        assertFalse(collection.isAda());
        assertEquals(new AssetType(TFLDT_POLICY, ""), collection.assetType());
    }

    /**
     * The bug that made the empty-policy case invisible: {@code AssetType("", "")} is not ada, so
     * it silently fails every price lookup. Pinned so nobody reintroduces the direct construction.
     */
    @Test
    void theEmptyAssetTypeIsNotAdaWhichIsWhyItMustNotBeBuiltDirectly() {
        assertFalse(new AssetType("", "").isAda());
        assertEquals("", new AssetType("", "").toUnit());
    }
}
