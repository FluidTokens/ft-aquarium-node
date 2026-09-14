package com.fluidtokens.aquarium.offchain.model;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * One asset amount, ready to render: what to call it, how it scaled, and whether we actually knew.
 *
 * @param label           ticker, else the decoded asset name, else a shortened policy id
 * @param policyId        the full policy id, shown on hover so the label is always checkable
 * @param unit            policy id + asset name, hex
 * @param amountText      the amount as it should appear
 * @param metadataUnknown true when no registry knew this asset — the page must mark it
 * @param rawAmount       the base-unit figure, unscaled, always available for reconciliation
 */
public record AssetDisplay(String label, String policyId, String unit, String amountText,
                           boolean metadataUnknown, BigInteger rawAmount) {

    /**
     * ⛔ <b>THE SCALING DECISION, and the one with money in it.</b>
     *
     * <p>When the registry published a scale, the amount is divided by {@code 10^decimals} and shown
     * as a person would read it. When nothing knew the asset, the <b>raw base-unit figure</b> is shown
     * and {@link #metadataUnknown} is set so the page can mark it.
     *
     * <p><b>Zero decimals is never assumed.</b> Assuming it silently mis-scales by up to a million and
     * produces a number that looks entirely plausible — which is precisely why this is not a fallback
     * anyone can afford. A raw amount beside a "no metadata" marker looks like what it is.
     */
    public static AssetDisplay of(BigInteger amount, TokenMetadata metadata) {
        BigInteger safeAmount = amount == null ? BigInteger.ZERO : amount;
        String text;
        if (metadata.hasDecimals()) {
            BigDecimal scaled = new BigDecimal(safeAmount).movePointLeft(metadata.decimals());
            text = scaled.stripTrailingZeros().toPlainString();
        } else {
            text = safeAmount.toString();
        }
        return new AssetDisplay(metadata.displayLabel(), metadata.policyId(), metadata.unit(), text,
                !metadata.hasDecimals(), safeAmount);
    }
}
