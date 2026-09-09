package com.fluidtokens.aquarium.offchain;

import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.service.loans.OracleFeedConverter;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Checks {@link OracleFeedConverter} against the contract's own schema, the same way
 * {@code LoanDatumSchemaTest} pins {@code LoanDatumConverter}.
 * <p>
 * {@code fluidtokens/types/oracle/OraclePriceFeed} is absent from the deployed blueprint for the
 * same reason {@code LoanDatum} is — the oracle validator's redeemer is typed {@code Data} — so
 * {@code aiken build --include-all-types} is again the only source of truth for constructor
 * indices and field order. See {@code LoanDatumSchemaTest} for how to regenerate
 * {@code loans-v4-alltypes.plutus.json} from a checkout of {@code FluidTokens/ft-cardano-loans-v4};
 * this test reads the exact same fixture rather than a copy of it.
 * <p>
 * The byte pin below is the stronger check: it is not derived from this schema or from
 * {@code OracleFeedConverter} itself, but transcribed once from a known-good encoding and never
 * regenerated from the code under test — so a change to the encoder that happens to still satisfy
 * the schema pins would still be caught here.
 */
class OracleFeedSchemaTest {

    private static JsonNode definitions() {
        try (InputStream is = OracleFeedSchemaTest.class
                .getResourceAsStream("/loans-v4/loans-v4-alltypes.plutus.json")) {
            return new ObjectMapper().readTree(Objects.requireNonNull(is)).get("definitions");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static JsonNode constructor(String definition, int index) {
        for (JsonNode c : definitions().get(definition).get("anyOf")) {
            if (c.get("index").asInt() == index) {
                return c;
            }
        }
        throw new AssertionError("no constructor " + index + " on " + definition);
    }

    private static List<String> fieldTitles(JsonNode constructor) {
        var titles = new ArrayList<String>();
        constructor.get("fields").forEach(f -> titles.add(f.path("title").asText(null)));
        return titles;
    }

    /**
     * Pins the constructor index every variant of {@code OraclePriceFeed} declares — including the
     * two the converter never builds — because {@code OracleFeedConverter} hard-codes these as
     * literal {@code alternative} numbers and a reordering upstream would silently mis-encode.
     */
    @Test
    void oraclePriceFeedConstructorIndicesMatchTheContract() {
        assertEquals("Aggregated",
                constructor("fluidtokens/types/oracle/OraclePriceFeed", 0).get("title").asText());
        assertEquals("Pooled",
                constructor("fluidtokens/types/oracle/OraclePriceFeed", 1).get("title").asText());
        assertEquals("Dedicated",
                constructor("fluidtokens/types/oracle/OraclePriceFeed", 2).get("title").asText());
        assertEquals("PriceDataCharlie",
                constructor("fluidtokens/types/oracle/OraclePriceFeed", 3).get("title").asText());
        assertEquals("PriceDataOrcfax",
                constructor("fluidtokens/types/oracle/OraclePriceFeed", 4).get("title").asText());
    }

    /**
     * The whole risk of the two-arg {@code toPlutusData}: it indexes fields positionally, so a
     * reordering upstream would encode a plausible but wrong redeemer rather than throwing.
     */
    @Test
    void priceDataCharlieFieldOrderMatchesTheContract() {
        assertEquals(List.of("provider_ref_input_index", "common", "price_in_lovelaces", "price_denominator"),
                fieldTitles(constructor("fluidtokens/types/oracle/OraclePriceFeed", 3)));
    }

    @Test
    void commonFeedDataFieldOrderMatchesTheContract() {
        assertEquals(List.of("valid_from", "valid_to", "token"),
                fieldTitles(constructor("fluidtokens/types/oracle/CommonFeedData", 0)));
    }

    /**
     * The byte pin. Transcribed once from a known-good {@code PriceDataCharlie} encoding — not
     * regenerated from {@link OracleFeedConverter} — so if the encoder ever produces something
     * different, the encoder is wrong, not this literal.
     */
    @Test
    void priceDataCharlieEncodesToTheExactPinnedBytes() {
        var feed = OraclePriceFeed.priceDataCharlie(
                new AssetType("0b77d150c275bd0a600633e4be7d09f83c4b9f00981e22ac9c9d3f62", "0014df1074464c4454"),
                BigInteger.valueOf(338163), BigInteger.valueOf(1000000),
                1786351764415L, 1786352364415L);

        String expectedHex = "d87c9f04d8799f1b0000019feadcc3bf1b0000019feae5eb7fd8799f581c0b77d150c275bd0a"
                + "600633e4be7d09f83c4b9f00981e22ac9c9d3f62490014df1074464c4454ffff1a000528f31a000f4240ff";

        byte[] actual = OracleFeedConverter.toPlutusData(feed, 4).serializeToBytes();
        assertEquals(expectedHex, HexUtil.encodeHexString(actual));
    }

    /**
     * Proof that {@code provider_ref_input_index} actually reaches the encoding rather than being
     * hard-coded: the test above only ever exercises index 4, so an encoder that ignored its second
     * argument and always emitted {@code 04} would still pass it. These two literals — transcribed,
     * not derived by editing the string above at runtime — pin two other indices against the same
     * feed and require every byte after the leading CBOR small-uint to stay identical.
     */
    @Test
    void providerRefInputIndexReachesTheEncoding() {
        var feed = OraclePriceFeed.priceDataCharlie(
                new AssetType("0b77d150c275bd0a600633e4be7d09f83c4b9f00981e22ac9c9d3f62", "0014df1074464c4454"),
                BigInteger.valueOf(338163), BigInteger.valueOf(1000000),
                1786351764415L, 1786352364415L);

        String expectedForIndex0 = "d87c9f00d8799f1b0000019feadcc3bf1b0000019feae5eb7fd8799f581c0b77d150c275bd0a"
                + "600633e4be7d09f83c4b9f00981e22ac9c9d3f62490014df1074464c4454ffff1a000528f31a000f4240ff";
        String expectedForIndex7 = "d87c9f07d8799f1b0000019feadcc3bf1b0000019feae5eb7fd8799f581c0b77d150c275bd0a"
                + "600633e4be7d09f83c4b9f00981e22ac9c9d3f62490014df1074464c4454ffff1a000528f31a000f4240ff";

        assertEquals(expectedForIndex0,
                HexUtil.encodeHexString(OracleFeedConverter.toPlutusData(feed, 0).serializeToBytes()));
        assertEquals(expectedForIndex7,
                HexUtil.encodeHexString(OracleFeedConverter.toPlutusData(feed, 7).serializeToBytes()));
    }

    /** The one-arg overload keeps refusing a variant it has no way to encode. */
    @Test
    void oneArgConverterStillThrowsForPriceDataCharlie() {
        var feed = OraclePriceFeed.priceDataCharlie(AssetType.ada(), BigInteger.ONE, BigInteger.ONE, 0L, 0L);
        assertThrows(UnsupportedOperationException.class, () -> OracleFeedConverter.toPlutusData(feed));
    }

    /** And the two-arg overload only ever encodes the one variant it was built for. */
    @Test
    void twoArgConverterThrowsForEveryOtherVariant() {
        var feed = OraclePriceFeed.aggregated(AssetType.ada(), BigInteger.ONE, BigInteger.ONE, 0L, 0L);
        assertThrows(UnsupportedOperationException.class, () -> OracleFeedConverter.toPlutusData(feed, 0));
    }
}
