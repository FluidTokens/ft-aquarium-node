package com.fluidtokens.aquarium.offchain.model;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * One asset amount, ready to render: what to call it, how it scaled, and whether we actually knew.
 *
 * @param label           ticker, else the decoded asset name, else a shortened policy id
 * @param policyId        the full policy id, shown on hover so the label is always checkable
 * @param unit            policy id + asset name, hex
 * @param amountText      the amount as it should appear, rounded for reading
 * @param exactText       the same amount at FULL precision — what hover shows, and what anyone
 *                        reconciling against the chain needs. {@link #amountText} is allowed to
 *                        round only because this is always beside it
 * @param metadataUnknown true when no registry knew this asset — the page must mark it
 * @param rawAmount       the base-unit figure, unscaled, always available for reconciliation
 */
public record AssetDisplay(String label, String policyId, String unit, String amountText,
                           String exactText, boolean metadataUnknown, BigInteger rawAmount) {

    /**
     * ⛔ <b>ONE ROUNDING RULE FOR THE WHOLE PAGE, banded by magnitude.</b>
     *
     * <pre>
     *   |v| &gt;= 10,000   0 decimals, grouped     123,456
     *   1 &lt;= |v| &lt; 10,000   2 decimals          35.46   339.80
     *   |v| &lt; 1        full precision, trimmed   0.123346
     * </pre>
     *
     * <p>⚠ <b>The band below 1 is not a preference — it is what stops a non-zero amount rendering
     * as {@code 0.00}.</b> On a page an operator commits capital from, a real balance displayed as
     * zero is the worst rounding there is, and a flat "two decimals everywhere" produces exactly
     * that for any token priced in fractions. Keeping full precision under 1 makes the rule
     * self-protecting: it cannot round anything to zero that is not zero.
     *
     * <p>⚠ And rounding is only admissible because {@link #exactText} rides along. A display rule
     * must never be the reason someone cannot reconcile a figure against the chain.
     */
    private static String readable(BigDecimal scaled) {
        BigDecimal magnitude = scaled.abs();
        if (magnitude.compareTo(BigDecimal.valueOf(10_000L)) >= 0) {
            return grouped("#,##0").format(scaled);
        }
        if (magnitude.compareTo(BigDecimal.ONE) >= 0) {
            return grouped("#,##0.00").format(scaled.setScale(2, RoundingMode.HALF_UP));
        }
        // Below one: every digit the asset actually carries, trailing zeros trimmed. Never rounded.
        return scaled.stripTrailingZeros().toPlainString();
    }

    /** ⚠ Locale-independent on purpose: a page read in two countries must not show two numbers. */
    private static DecimalFormat grouped(String pattern) {
        return new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.UK));
    }

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
        String exact;
        if (metadata.hasDecimals()) {
            BigDecimal scaled = new BigDecimal(safeAmount).movePointLeft(metadata.decimals());
            exact = scaled.stripTrailingZeros().toPlainString();
            text = readable(scaled);
        } else {
            // ⚠ No scale known, so the figure is RAW base units. It is not rounded: rounding a number
            // already marked as unscaled would compound one uncertainty with another.
            exact = safeAmount.toString();
            text = exact;
        }
        return new AssetDisplay(metadata.displayLabel(), metadata.policyId(), metadata.unit(), text,
                exact, !metadata.hasDecimals(), safeAmount);
    }
}
