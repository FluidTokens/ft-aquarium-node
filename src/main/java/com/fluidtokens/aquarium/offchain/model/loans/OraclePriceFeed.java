package com.fluidtokens.aquarium.offchain.model.loans;

import com.fluidtokens.aquarium.offchain.model.AssetType;

import java.math.BigInteger;

/**
 * {@code OraclePriceFeed} from {@code lib/fluidtokens/types/oracle.ak}, reduced to what
 * {@code finance.ak} actually consumes: a price expressed as lovelace per smallest token unit,
 * plus the {@code CommonFeedData} the validator checks — the validity window and the token the
 * feed is about.
 * <p>
 * Every feed variant except {@code Pooled} collapses to a numerator/denominator pair, and
 * {@code Pooled} is an outright {@code fail} in {@code get_token_amount_in_lovelace} — so it is
 * modelled here as an unsupported type rather than something we might silently price.
 * <p>
 * {@code token} is not decoration: it is inside the bytes the oracle's key signs, and
 * {@code is_feed_token_correct} rejects a feed whose token does not match the loan leg being
 * priced. {@code OracleFeedConverter} reproduces that encoding exactly.
 */
public record OraclePriceFeed(Variant variant,
                              AssetType token,
                              BigInteger priceInLovelaces,
                              BigInteger priceDenominator,
                              long validFrom,
                              long validTo) {

    public enum Variant {
        AGGREGATED, POOLED, DEDICATED, PRICE_DATA_CHARLIE, PRICE_DATA_ORCFAX
    }

    /**
     * The 1:1 feed {@code retrieve_oracle_data} synthesises when the priced asset has an empty
     * policy id — i.e. ada, or any loan leg with no oracle. Validity is 0..0 on chain too, and the
     * token is the empty {@code Asset { policyId: "", assetName: "" }}.
     */
    public static OraclePriceFeed unit() {
        return new OraclePriceFeed(Variant.AGGREGATED, AssetType.ada(), BigInteger.ONE, BigInteger.ONE, 0L, 0L);
    }

    public static OraclePriceFeed aggregated(AssetType token, BigInteger priceInLovelaces,
                                             BigInteger priceDenominator, long validFrom, long validTo) {
        return new OraclePriceFeed(Variant.AGGREGATED, token, priceInLovelaces, priceDenominator,
                validFrom, validTo);
    }

    /** Lovelace per smallest unit of the token, as {@code rational.new(price, denominator)}. */
    public Rational price() {
        if (variant == Variant.POOLED) {
            throw new UnsupportedOperationException(
                    "Pooled oracle feeds are a `fail` in finance.ak — they cannot be priced off chain either");
        }
        return Rational.required(priceInLovelaces, priceDenominator);
    }

    public boolean isValidAt(long timeMillis) {
        return timeMillis >= validFrom && timeMillis <= validTo;
    }
}
