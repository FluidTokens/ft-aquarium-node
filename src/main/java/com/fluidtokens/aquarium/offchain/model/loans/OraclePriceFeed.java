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

    /** {@code constants.max_oracle_validity_range} — a feed may not claim a window wider than this. */
    public static final long MAX_VALIDITY_RANGE_MILLIS = 3_600_000L;

    /**
     * The 1:1 ada feed is exempt from every validity rule, and this is not a shortcut:
     * {@code retrieve_oracle_data} returns it from the {@code expectedTokenPolicyId == ""} branch
     * <em>before</em> reaching the window checks, with {@code valid_from} and {@code valid_to}
     * both zero. Treating it like a real feed would mark ada permanently expired and blank the
     * health of every loan with an ada leg.
     */
    public boolean isSynthesisedUnitFeed() {
        return token.isAda();
    }

    /**
     * The on-chain rule is containment of the transaction's validity interval, not "not expired":
     * <pre>
     *   valid_to   >= transactionValidTo
     *   valid_from <= transactionValidFrom
     * </pre>
     */
    public boolean covers(long fromMillis, long toMillis) {
        return validFrom <= fromMillis && validTo >= toMillis;
    }

    /**
     * Whether a transaction valid over {@code [fromMillis, toMillis]} would be accepted with this
     * feed — both the containment rule and the maximum-window rule, exactly as
     * {@code lib/fluidtokens/oracle.ak} applies them.
     */
    public boolean usableOver(long fromMillis, long toMillis) {
        if (isSynthesisedUnitFeed()) {
            return true;
        }
        return validTo - validFrom <= MAX_VALIDITY_RANGE_MILLIS && covers(fromMillis, toMillis);
    }

    /** The instantaneous case, for reporting health rather than building a transaction. */
    public boolean usableAt(long timeMillis) {
        return usableOver(timeMillis, timeMillis);
    }
}
