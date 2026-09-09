package com.fluidtokens.aquarium.offchain.service.loans;

import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.model.loans.Rational;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.Objects;
import java.util.Optional;

/**
 * The one place a token quantity becomes lovelace for the liquidation economics, and it fails
 * closed.
 * <p>
 * <b>No decimals scaling, anywhere in this class, and there must never be one.</b> The oracle
 * feed's price is lovelace <em>per base unit</em> ({@code feed.price()} in {@link LoanFinance}), so
 * {@code baseUnits * price = lovelace} exactly — the registry's {@code decimals} field is for
 * DISPLAY only, and applying it here would introduce a 10^n error while reading like correctness.
 * Verified against live mainnet data 2026-09-09: FLDT's registry price is
 * {@code 21785609/100000000} lovelace per base unit; 23,000,000,000 base units (23,000 FLDT at 6
 * decimals) times that is 5,010,690,070 lovelace = 5,010.69 ADA — no scaling anywhere.
 * <p>
 * <b>Fails closed.</b> A missing, stale or unpriceable feed is a refusal, never a fallback price:
 * there is no 1:1 assumption and no last-known price anywhere below.
 */
@Service
public class PricingService {

    private final FluidOracleClient oracleClient;

    public PricingService(FluidOracleClient oracleClient) {
        this.oracleClient = oracleClient;
    }

    /** Which of the three refusal cases a {@link Priced} carries. */
    public enum RefusalReason {
        /** No oracle entry exists for this asset at all. */
        NO_FEED,
        /** A feed exists, but is not usable at the instant asked — expired, or not yet valid. */
        NOT_USABLE_AT_INSTANT,
        /**
         * A feed exists (and, if its window is checked, may even cover the instant), but is a
         * {@code POOLED} variant. {@code get_token_amount_in_lovelace} is an outright {@code fail}
         * on that variant on chain (hazard: {@link OraclePriceFeed#price()} throws for it) — a
         * known, permanent property of the asset, never a transient machinery fault.
         */
        POOLED
    }

    /**
     * Why a {@link Priced} carries no lovelace figure — enough for an operator to act on: the asset
     * unit, the instant that was asked for, the feed's own window when a feed exists at all, its
     * age at that instant, and which of the three cases applies.
     *
     * @param asset          the asset that could not be priced
     * @param atMillis       the instant pricing was requested for
     * @param reason         which of the three refusal cases this is
     * @param feedValidFrom  the held feed's {@code validFrom}, or {@code null} when {@link #reason}
     *                       is {@link RefusalReason#NO_FEED}
     * @param feedValidTo    the held feed's {@code validTo}, or {@code null} under the same condition
     * @param feedAgeMillis  {@code atMillis - feedValidFrom} — how old the feed is at the instant
     *                       asked, negative when the feed has not started yet; {@code null} under
     *                       the same condition as the two above
     */
    public record PriceRefusal(AssetType asset, long atMillis, RefusalReason reason,
                               Long feedValidFrom, Long feedValidTo, Long feedAgeMillis) {
    }

    /** Either the priced amount in lovelace, or why not — never both. */
    public record Priced(BigInteger lovelace, PriceRefusal refusal) {

        public boolean isPriced() {
            return refusal == null;
        }

        static Priced priced(BigInteger lovelace) {
            return new Priced(lovelace, null);
        }

        static Priced refused(PriceRefusal refusal) {
            return new Priced(null, refusal);
        }
    }

    /**
     * {@code amount}, in {@code asset}'s own base units, expressed in lovelace at {@code atMillis}.
     * <p>
     * <b>ADA is the identity.</b> {@link AssetType#isAda()} returns {@code amount} unchanged without
     * consulting the oracle at all — no feed lookup, no validity check, byte-for-byte the input.
     * <p>
     * For every other asset, pricing goes through {@link FluidOracleClient#findFeed}, the
     * validity-enforcing accessor — never {@link FluidOracleClient#findFeedIgnoringValidity}, whose
     * own javadoc forbids using it to price. That second accessor is consulted only when
     * {@code findFeed} came back empty, and only to classify WHY: telling "no feed for this asset at
     * all" apart from "a feed exists but is not usable here" — a different sentence for an operator
     * reading the refusal, never a step towards pricing anyway.
     * <p>
     * A feed that is live at {@code atMillis} but a {@code POOLED} variant is refused by name rather
     * than let {@link OraclePriceFeed#price()} throw — {@code findFeed} filters on TIME only, never
     * on variant, so a live-and-pooled feed reaches this method same as any other.
     * <p>
     * Rounded DOWN ({@link Rational#floor()}). This method is used to price amounts about to be
     * treated as EARNED — a fee slice, compared against a cost — so rounding up would state more
     * lovelace than the feed actually supports and flatter whatever profitability check reads the
     * result. Rounding down is the direction that does not flatter the bot.
     */
    public Priced toLovelace(AssetType asset, BigInteger amount, long atMillis) {
        Objects.requireNonNull(asset, "asset");
        Objects.requireNonNull(amount, "amount");

        if (asset.isAda()) {
            return Priced.priced(amount);
        }

        Optional<OraclePriceFeed> usable = oracleClient.findFeed(asset, atMillis);
        if (usable.isPresent()) {
            OraclePriceFeed feed = usable.get();
            if (feed.variant() == OraclePriceFeed.Variant.POOLED) {
                return Priced.refused(refusalFor(asset, atMillis, RefusalReason.POOLED, feed));
            }
            Rational lovelace = LoanFinance.toLovelace(Rational.fromInt(amount), feed);
            return Priced.priced(lovelace.floor());
        }

        // Not usable at atMillis through findFeed. findFeedIgnoringValidity is used ONLY to
        // classify why — its result is never passed to LoanFinance.toLovelace.
        Optional<OraclePriceFeed> any = oracleClient.findFeedIgnoringValidity(asset);
        if (any.isEmpty()) {
            return Priced.refused(new PriceRefusal(asset, atMillis, RefusalReason.NO_FEED,
                    null, null, null));
        }
        OraclePriceFeed feed = any.get();
        // POOLED is a permanent property of the asset and the more informative fact, so it takes
        // precedence over "not usable right now" even when the held feed also happens to be stale.
        RefusalReason reason = feed.variant() == OraclePriceFeed.Variant.POOLED
                ? RefusalReason.POOLED : RefusalReason.NOT_USABLE_AT_INSTANT;
        return Priced.refused(refusalFor(asset, atMillis, reason, feed));
    }

    private static PriceRefusal refusalFor(AssetType asset, long atMillis, RefusalReason reason,
                                           OraclePriceFeed feed) {
        return new PriceRefusal(asset, atMillis, reason, feed.validFrom(), feed.validTo(),
                atMillis - feed.validFrom());
    }
}
