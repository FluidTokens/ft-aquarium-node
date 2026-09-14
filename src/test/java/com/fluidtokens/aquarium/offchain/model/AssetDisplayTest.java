package com.fluidtokens.aquarium.offchain.model;

import java.math.BigInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ The scaling rule, which is the one place in this feature where being wrong costs money.
 *
 * <p>An amount rendered at the wrong scale is wrong by a power of ten and <b>looks like a working
 * number</b>. Nothing downstream can detect it, and an operator acting on it acts on a figure that is
 * plausible and false.
 */
class AssetDisplayTest {

    private static final String FLDT = "11".repeat(28) + "464c4454";

    /** Ada is six decimals by definition and needs no registry. */
    @Test
    void adaScalesBySixWithoutAskingAnyone() {
        AssetDisplay d = AssetDisplay.of(BigInteger.valueOf(20_887_781L), TokenMetadata.ada());

        assertEquals("20.887781", d.amountText());
        assertEquals("ADA", d.label());
        assertFalse(d.metadataUnknown(), "ada can never be unknown");
    }

    @Test
    void aRegistryDecimalsValueScalesTheAmount() {
        TokenMetadata fldt = new TokenMetadata(FLDT, "FLDT", "FluidTokens", 6, TokenMetadata.Source.REGISTRY);

        AssetDisplay d = AssetDisplay.of(new BigInteger("100000000"), fldt);

        assertEquals("100", d.amountText(), "100,000,000 base units at 6 decimals is 100 FLDT");
        assertEquals("FLDT", d.label());
        assertFalse(d.metadataUnknown());
    }

    /**
     * ⛔ <b>THE CONTROL.</b> Zero decimals is a REAL scale — a token genuinely declaring 0 must render
     * its base units unchanged — and that is exactly why zero can never double as "we do not know".
     * This test pins the two apart: same amount, one declared 0, one unknown, distinguishable only by
     * the marker.
     */
    @Test
    void aDeclaredZeroIsNotTheSameThingAsAnUnknownScale() {
        BigInteger amount = new BigInteger("100000000");
        TokenMetadata declaredZero = new TokenMetadata(FLDT, "ZRO", "Zero", 0, TokenMetadata.Source.REGISTRY);
        TokenMetadata unknown = TokenMetadata.unknown(FLDT);

        AssetDisplay zero = AssetDisplay.of(amount, declaredZero);
        AssetDisplay none = AssetDisplay.of(amount, unknown);

        assertEquals("100000000", zero.amountText(), "a declared 0 renders base units");
        assertEquals("100000000", none.amountText(), "an unknown scale renders base units too");

        assertFalse(zero.metadataUnknown(), "a declared zero is known and must not be marked");
        assertTrue(none.metadataUnknown(), "an unknown scale MUST be marked");
        assertNotEquals(zero.metadataUnknown(), none.metadataUnknown(),
                "if these ever agree, the page cannot distinguish a real 0 from a missing value");
    }

    /**
     * The unknown case keeps as much identity as the chain can give: the asset name decoded from its
     * own hex, and the shortened policy id. Giovanni's ruling — still show the asset name if you can.
     */
    @Test
    void anUnknownTokenStillShowsItsAssetNameAndShortenedPolicy() {
        AssetDisplay d = AssetDisplay.of(new BigInteger("42"), TokenMetadata.unknown(FLDT));

        assertEquals("FLDT", d.label(), "the asset name is recoverable from the unit's own hex");
        assertEquals("11".repeat(28), d.policyId(), "the full policy id travels for the hover");
        assertEquals("42", d.amountText(), "raw base units, never a guessed scale");
        assertTrue(d.metadataUnknown());
    }

    /** A non-printable asset name cannot be decoded, so the shortened policy id is the label. */
    @Test
    void anUndecodableAssetNameFallsBackToTheShortenedPolicyId() {
        String unit = "22".repeat(28) + "00ff01";
        AssetDisplay d = AssetDisplay.of(BigInteger.ONE, TokenMetadata.unknown(unit));

        assertEquals("22222222…", d.label());
        assertTrue(d.metadataUnknown());
    }

    /** The raw figure is always carried, so a reconciliation never depends on the rendered text. */
    @Test
    void theRawBaseUnitAmountSurvivesScaling() {
        TokenMetadata fldt = new TokenMetadata(FLDT, "FLDT", "FluidTokens", 6, TokenMetadata.Source.REGISTRY);

        AssetDisplay d = AssetDisplay.of(new BigInteger("100000000"), fldt);

        assertEquals(new BigInteger("100000000"), d.rawAmount());
    }
}
