package com.fluidtokens.aquarium.offchain.model.loans;

import java.math.BigInteger;

/**
 * {@code OraclePriceFeed} from {@code lib/fluidtokens/types/oracle.ak}, reduced to what
 * {@code finance.ak} actually consumes: a price expressed as lovelace per smallest token unit,
 * plus the validity window the transaction has to fit inside.
 * <p>
 * Every feed variant except {@code Pooled} collapses to a numerator/denominator pair, and
 * {@code Pooled} is an outright {@code fail} in {@code get_token_amount_in_lovelace} — so it is
 * modelled here as an unsupported type rather than something we might silently price.
 */
public record OraclePriceFeed(Variant variant,
                              BigInteger priceInLovelaces,
                              BigInteger priceDenominator,
                              long validFrom,
                              long validTo) {

    public enum Variant {
        AGGREGATED, POOLED, DEDICATED, PRICE_DATA_CHARLIE, PRICE_DATA_ORCFAX
    }

    /**
     * The 1:1 feed {@code retrieve_oracle_data} synthesises when the priced asset has an empty
     * policy id — i.e. ada, or any loan leg with no oracle. Validity is 0..0 on chain too.
     */
    public static OraclePriceFeed unit() {
        return new OraclePriceFeed(Variant.AGGREGATED, BigInteger.ONE, BigInteger.ONE, 0L, 0L);
    }

    public static OraclePriceFeed aggregated(BigInteger priceInLovelaces, BigInteger priceDenominator,
                                             long validFrom, long validTo) {
        return new OraclePriceFeed(Variant.AGGREGATED, priceInLovelaces, priceDenominator, validFrom, validTo);
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
