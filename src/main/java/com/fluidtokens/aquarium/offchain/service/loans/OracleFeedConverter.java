package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;

import java.math.BigInteger;

/**
 * Encodes an {@link OraclePriceFeed} as the {@code PlutusData} the oracle validator hashes.
 * <p>
 * This is the one piece of Lending v4 off-chain code where being <em>close</em> is worthless:
 * {@code validators/oracle.ak} does
 * <pre>
 *   let serialise_data = builtin.serialise_data(redeemer.data)
 *   verify_ed25519_signature(verification_key, serialise_data, redem.signature)
 * </pre>
 * so a single wrong byte — a field reordered, a definite-length list where Plutus emits an
 * indefinite one — makes every signature fail and the transaction is rejected with nothing to
 * debug. The encoding is therefore not asserted from reading the source: {@code
 * OracleFeedSignatureTest} feeds it the signatures FluidTokens actually publishes and checks
 * they verify, which can only happen if these bytes are exactly right.
 * <p>
 * Encode only. The bot never reads a feed off chain — it receives prices as JSON from the
 * registry and must <em>produce</em> this structure for its own redeemer.
 */
public final class OracleFeedConverter {

    private OracleFeedConverter() {
    }

    /**
     * {@code types/oracle.ak}. Constructor index is declaration order in the {@code
     * OraclePriceFeed} type, and only the two variants whose fields are
     * {@code (common, price, denominator)} can be built from what we model.
     */
    public static PlutusData toPlutusData(OraclePriceFeed feed) {
        int alternative = switch (feed.variant()) {
            case AGGREGATED -> 0;
            case DEDICATED -> 2;
            // Pooled carries pool reserves and fees, Charlie/Orcfax carry reference-input indices
            // that only make sense against a specific transaction. None can be built from a price.
            // PriceDataCharlie has its own overload below that takes the missing index; the others
            // still have no way to be built from what we model.
            case POOLED, PRICE_DATA_CHARLIE, PRICE_DATA_ORCFAX -> throw new UnsupportedOperationException(
                    "cannot encode a " + feed.variant() + " feed: its fields are not a plain price"
                            + (feed.variant() == OraclePriceFeed.Variant.PRICE_DATA_CHARLIE
                                    ? " — use toPlutusData(feed, providerRefInputIndex)" : ""));
        };
        return constr(alternative,
                commonFeedData(feed),
                BigIntPlutusData.of(feed.priceInLovelaces()),
                BigIntPlutusData.of(feed.priceDenominator()));
    }

    /**
     * {@code PriceDataCharlie { provider_ref_input_index: Int, common: CommonFeedData,
     * price_in_lovelaces: Int, price_denominator: Int }} — constructor index 3.
     * <p>
     * Unlike the {@link #toPlutusData(OraclePriceFeed) signed variants}, a Charli3-backed feed
     * carries no signature over its own bytes: {@code validators/oracle.ak} checks it structurally
     * against the Charli3 provider UTxO named by {@code provider_ref_input_index}, so there is
     * nothing here to sign and nothing this method validates about that index. It only encodes what
     * it is given.
     * <p>
     * {@code provider_ref_input_index} is therefore the caller's responsibility, not this feed's:
     * it only means something relative to a specific transaction's reference inputs, which this
     * class has no view of. Resolving the real index is T-008's job.
     * <p>
     * Because there is no signature, there is also no oracle-imposed validity window: the caller
     * (T-008's transaction builder) picks {@code validFrom}/{@code validTo} itself by constructing
     * the feed with {@link OraclePriceFeed#priceDataCharlie}; nothing extra is needed here.
     */
    public static PlutusData toPlutusData(OraclePriceFeed feed, long providerRefInputIndex) {
        if (feed.variant() != OraclePriceFeed.Variant.PRICE_DATA_CHARLIE) {
            throw new UnsupportedOperationException(
                    "toPlutusData(feed, providerRefInputIndex) only encodes PRICE_DATA_CHARLIE, not "
                            + feed.variant());
        }
        return constr(3,
                BigIntPlutusData.of(BigInteger.valueOf(providerRefInputIndex)),
                commonFeedData(feed),
                BigIntPlutusData.of(feed.priceInLovelaces()),
                BigIntPlutusData.of(feed.priceDenominator()));
    }

    /** {@code CommonFeedData { valid_from: Int, valid_to: Int, token: Asset }} */
    private static PlutusData commonFeedData(OraclePriceFeed feed) {
        return constr(0,
                BigIntPlutusData.of(BigInteger.valueOf(feed.validFrom())),
                BigIntPlutusData.of(BigInteger.valueOf(feed.validTo())),
                asset(feed.token()));
    }

    /**
     * {@code Asset { policyId: ByteArray, assetName: ByteArray }} — policy id first. Ada is the
     * empty pair, which is what {@link AssetType#getPlutusDataPolicyId()} already yields.
     */
    private static PlutusData asset(AssetType token) {
        return constr(0,
                BytesPlutusData.of(token.getPlutusDataPolicyId()),
                BytesPlutusData.of(token.getPlutusDataAssetName()));
    }

    private static ConstrPlutusData constr(int alternative, PlutusData... fields) {
        return ConstrPlutusData.builder()
                .alternative(alternative)
                .data(ListPlutusData.of(fields))
                .build();
    }
}
