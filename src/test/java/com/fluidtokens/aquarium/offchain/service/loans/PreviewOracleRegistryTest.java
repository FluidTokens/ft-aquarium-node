package com.fluidtokens.aquarium.offchain.service.loans;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The preview registry ({@code testapi.fluidtokens.com}), which is a different shape from mainnet
 * in ways that matter.
 * <p>
 * Preview is where our indexed loans live, so this payload — not the mainnet one — decides whether
 * health is computable for them. It also omits fields mainnet always provides, which is how the
 * mainnet-shaped assumptions in the parser get caught.
 */
class PreviewOracleRegistryTest {

    /** The collateral on every loan currently indexed on preview. */
    private static final AssetType TFLDT =
            new AssetType("0b77d150c275bd0a600633e4be7d09f83c4b9f00981e22ac9c9d3f62", "0014df1074464c4454");

    private static FluidOracleClient client() throws Exception {
        var client = new FluidOracleClient("http://unused.invalid");
        try (InputStream in = PreviewOracleRegistryTest.class
                .getResourceAsStream("/loans-v4/oracle-registry-preview.json")) {
            client.load(new ObjectMapper().readTree(in));
        }
        return client;
    }

    @Test
    void thePreviewPayloadParses() throws Exception {
        assertEquals(5, client().trackedAssets());
    }

    /**
     * The whole reason the preview registry matters: mainnet does not price tFLDT, so every preview
     * loan reported LTV as unavailable. This entry is what turns health on for them.
     */
    @Test
    void previewPricesTheCollateralOurLoansActuallyUse() throws Exception {
        var entry = client().findEntry(TFLDT).orElseThrow(
                () -> new AssertionError("tFLDT must be priced, or preview health stays blank"));

        assertNotNull(entry.feed().price());
        assertEquals("9a2ec5c92daccbb269611a9eae7a40f9788d3f9c0229661b6234286f",
                entry.oracleToken().policyId(), "matches collateral.oracleTokenAsset in the loan datum");
        assertEquals("000de1406f766f3633", entry.oracleToken().assetName());
    }

    /**
     * And the sting: tFLDT is served by Charli3, so those loans are priceable but <em>not</em>
     * liquidatable by us until {@code PriceDataCharlie} is modelled. Worth failing loudly if that
     * ever changes, because it decides whether Phase 3 can be tested on preview at all.
     */
    @Test
    void theCollateralOurLoansUseIsCharli3BackedAndSoNotYetLiquidatable() throws Exception {
        var entry = client().findEntry(TFLDT).orElseThrow();

        assertEquals(OraclePriceFeed.Variant.PRICE_DATA_CHARLIE, entry.feed().variant());
        assertFalse(entry.usableForLiquidation(),
                "if this starts passing, preview end-to-end liquidation just became possible");
    }

    /**
     * Preview omits {@code multisigOracle.publicKeys} on some entries even while publishing
     * signatures. Positions cannot be recovered from the signature array order — a partial signer
     * set would shift every index — so the entry must stay priceable and refuse to claim it is
     * liquidatable, rather than guessing a {@code key_position} that fails the whole transaction.
     */
    @Test
    void anEntryWithSignaturesButNoPublicKeysIsPriceableAndNotLiquidatable() throws Exception {
        var fgold = client().findEntry(
                new AssetType("4f4e7bb17c0e7201cc82f0177ab22695fbcee2d99735d1c3fdc44eac", "66476f6c64"))
                .orElseThrow();

        assertTrue(fgold.verificationKeys().isEmpty(), "the preview payload omits publicKeys here");
        assertTrue(fgold.signatures().isEmpty(), "so no signature can be given a position");
        assertFalse(fgold.usableForLiquidation());
        assertNotNull(fgold.feed().price(), "the price is still perfectly good");
    }

    /** The one preview oracle that publishes keys, and so the one that could sign a liquidation. */
    @Test
    void theMultisigTestTokenResolvesItsSignature() throws Exception {
        var entry = client().findEntry(
                new AssetType("4f4e7bb17c0e7201cc82f0177ab22695fbcee2d99735d1c3fdc44eac",
                        "464c44546d756c7469736967")).orElseThrow();

        assertEquals(OraclePriceFeed.Variant.AGGREGATED, entry.feed().variant());
        assertEquals(1, entry.verificationKeys().size());
        assertEquals(1, entry.signatures().size());
        assertEquals(0, entry.signatures().getFirst().keyPosition());
        assertTrue(entry.usableForLiquidation());
    }

    /** An asset name may legitimately be empty (preview OADA), which must not break parsing. */
    @Test
    void anEmptyAssetNameParses() throws Exception {
        var oada = client().findEntry(
                new AssetType("02901d124a495e3fe65cc01cb746a756aafbdcbfab34b756560e6c82", ""));
        assertTrue(oada.isPresent());
    }

    /** Deployment details must be present on preview too, or Phase 3 has nothing to reference. */
    @Test
    void everyPreviewEntryCarriesItsDeployment() throws Exception {
        for (OracleEntry entry : client().entries()) {
            String where = entry.token().toUnit();
            assertNotNull(entry.referenceInput(), where);
            assertNotNull(entry.referenceScript(), where);
            assertNotNull(entry.withdrawCredentialHash(), where);
        }
    }
}
