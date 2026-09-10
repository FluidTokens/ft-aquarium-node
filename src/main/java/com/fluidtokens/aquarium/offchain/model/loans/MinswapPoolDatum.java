package com.fluidtokens.aquarium.offchain.model.loans;

import com.fluidtokens.aquarium.offchain.model.AssetType;

import java.math.BigInteger;

/**
 * Minswap V2's {@code PoolDatum}, reduced to what {@code lm_liquidate_and_convert_action} and the
 * bot's own pre-trade quote read.
 *
 * <p>⚠ <b>The field order is the chain's, not a reading of a library we vendor</b> — the
 * {@code amm_dex_v2} package is an Aiken dependency and is absent from the upstream clone, so this was
 * decoded from the live ADA/FLDT pool on mainnet and cross-checked against Minswap's own declaration
 * (findings §32.3, §34). Ten fields on chain; this record carries eight of them, and the arity check in
 * {@code MinswapPoolDatumConverter} is what keeps the positions honest.
 *
 * <h2>⛔ The two fee numerators are the pool's OWN price, and no constant substitutes for them</h2>
 * Measured live on mainnet 2026-09-10 (height 13,922,040): the ADA/FLDT pool declares
 * {@code base_fee_a = base_fee_b = 80} and the FLDT/USDM pool {@code = 70}. <b>Two pools, two different
 * fees</b> — so a hardcoded numerator is wrong for at least one of them however it is calibrated, and
 * the 24/10000 and 30/10000 figures the DEX literature quotes are wrong for both. Against the real
 * mainnet batch {@code 5f0572b2…} the datum's 80 reproduces the payout to within 272 lovelace on
 * 18,565,738 (−0.0015 %, the safe direction) where 24 and 30 over-quote by +0.56 % and +0.50 %.
 * Over-quoting is the direction that matters: it lets a futile order through to be built, submitted and
 * refunded at the operator's expense.
 *
 * @param assetA             the pool's {@code asset_a} — <b>not</b> sorted by us; the pool declares the order
 * @param assetB             the pool's {@code asset_b}
 * @param reserveA           current reserve of {@link #assetA}
 * @param reserveB           current reserve of {@link #assetB}
 * @param baseFeeANumerator  {@code base_fee_a_numerator}, parts of {@code 10000}, charged on the INPUT
 *                           when selling {@link #assetA} for {@link #assetB} ({@code aToB})
 * @param baseFeeBNumerator  {@code base_fee_b_numerator}, the same for the {@code bToA} direction. ⚠ It
 *                           is a SEPARATE field and equal to {@link #baseFeeANumerator} on both live
 *                           mainnet pools today — which is exactly why a direction mix-up here would be
 *                           invisible on the pools the tests use, and why the quote is tested against a
 *                           fixture with UNEQUAL numerators.
 * @param allowDynamicFee    {@code allow_dynamic_fee}. When true the base numerators are not the whole
 *                           fee, so a quote built from them would understate the cost; the convert plan
 *                           refuses such a pool by name rather than quoting it.
 */
public record MinswapPoolDatum(AssetType assetA,
                               AssetType assetB,
                               BigInteger totalLiquidity,
                               BigInteger reserveA,
                               BigInteger reserveB,
                               BigInteger baseFeeANumerator,
                               BigInteger baseFeeBNumerator,
                               boolean allowDynamicFee) {

    /** Number of fields in the on-chain constructor; a mismatch means Minswap changed the type. */
    public static final int FIELD_COUNT = 10;

    /** Minswap's fee denominator: both base numerators are parts of ten thousand. */
    public static final BigInteger FEE_DENOMINATOR = BigInteger.valueOf(10_000L);
}
