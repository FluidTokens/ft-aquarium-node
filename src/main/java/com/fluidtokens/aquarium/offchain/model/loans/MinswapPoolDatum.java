package com.fluidtokens.aquarium.offchain.model.loans;

import com.fluidtokens.aquarium.offchain.model.AssetType;

import java.math.BigInteger;

/**
 * Minswap V2's {@code PoolDatum}, reduced to what {@code lm_liquidate_and_convert_action} reads.
 *
 * <p>⚠ <b>The field order is the chain's, not a reading of a library we vendor</b> — the
 * {@code amm_dex_v2} package is an Aiken dependency and is absent from the upstream clone, so this was
 * decoded from the live ADA/FLDT pool on mainnet and cross-checked against Minswap's own declaration
 * (findings §32.3, §34). Ten fields; the four after {@link #reserveB} are read by Minswap's own
 * validators and not by ours, so they are kept only to prove the arity.
 *
 * @param assetA   the pool's {@code asset_a} — <b>not</b> sorted by us; the pool declares the order
 * @param assetB   the pool's {@code asset_b}
 * @param reserveA current reserve of {@link #assetA}
 * @param reserveB current reserve of {@link #assetB}
 */
public record MinswapPoolDatum(AssetType assetA,
                               AssetType assetB,
                               BigInteger totalLiquidity,
                               BigInteger reserveA,
                               BigInteger reserveB) {

    /** Number of fields in the on-chain constructor; a mismatch means Minswap changed the type. */
    public static final int FIELD_COUNT = 10;
}
