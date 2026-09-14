package com.fluidtokens.aquarium.offchain.model;

/**
 * What this node knows about one native asset: how to name it and, critically, how to scale it.
 *
 * <h2>⛔ {@code decimals} is the field with money in it</h2>
 * An amount rendered at the wrong scale is wrong by a power of ten, and it looks like a working
 * number. So {@link Source} is not decoration — it is what lets the page tell a decimals value the
 * registry actually published from the absence of one. {@link #hasDecimals()} is false for
 * {@link Source#UNKNOWN}, and callers must render the raw base-unit amount in that case rather than
 * assume any scale. <b>Assuming zero silently mis-scales by up to a million.</b>
 *
 * @param unit      policy id + asset name, hex, as the chain expresses it; {@code "lovelace"} for ada
 * @param ticker    short display symbol, or null when unknown
 * @param name      human-readable name, or null when unknown
 * @param decimals  scale, or null when unknown — never defaulted to zero
 * @param source    where the above came from
 */
public record TokenMetadata(String unit, String ticker, String name, Integer decimals, Source source) {

    public enum Source {
        /** The off-chain token registry: the curated source wallets and explorers display. */
        REGISTRY,
        /** On-chain CIP-68 metadata, used where the registry has nothing. */
        CIP68,
        /** Built in, never fetched: ada is 6 decimals by definition. */
        NATIVE,
        /** Asked and not found. Carries no decimals, deliberately. */
        UNKNOWN
    }

    /** Ada, which needs no registry and can never be unknown. */
    public static TokenMetadata ada() {
        return new TokenMetadata("lovelace", "ADA", "Cardano", 6, Source.NATIVE);
    }

    /** The honest answer for an asset nothing knows about. */
    public static TokenMetadata unknown(String unit) {
        return new TokenMetadata(unit, null, null, null, Source.UNKNOWN);
    }

    /** True only when a real scale was published. Never true for {@link Source#UNKNOWN}. */
    public boolean hasDecimals() {
        return decimals != null && source != Source.UNKNOWN;
    }

    /** The first 8 hex characters of the policy id, for display beside an unidentified asset. */
    public String shortPolicyId() {
        String policy = policyId();
        return policy.length() <= 8 ? policy : policy.substring(0, 8) + "…";
    }

    public String policyId() {
        if ("lovelace".equals(unit)) {
            return "";
        }
        return unit.length() >= 56 ? unit.substring(0, 56) : unit;
    }

    /** The asset-name half of the unit, hex. Empty for ada and for a policy-only unit. */
    public String assetNameHex() {
        if ("lovelace".equals(unit) || unit.length() <= 56) {
            return "";
        }
        return unit.substring(56);
    }

    /**
     * The asset name decoded as UTF-8 where it is printable, else null. Giovanni's ruling for an
     * unknown token is to "still show the asset name if you can" — the chain carries it even when no
     * registry does, so this is usually recoverable without asking anyone.
     */
    public String assetNameText() {
        String hex = assetNameHex();
        if (hex.isEmpty() || hex.length() % 2 != 0) {
            return null;
        }
        StringBuilder out = new StringBuilder(hex.length() / 2);
        for (int i = 0; i < hex.length(); i += 2) {
            int c;
            try {
                c = Integer.parseInt(hex.substring(i, i + 2), 16);
            } catch (NumberFormatException e) {
                return null;
            }
            if (c < 0x20 || c > 0x7e) {
                return null;
            }
            out.append((char) c);
        }
        return out.isEmpty() ? null : out.toString();
    }

    /** What to show as the asset's label: ticker, else decoded name, else the shortened policy id. */
    public String displayLabel() {
        if (ticker != null && !ticker.isBlank()) {
            return ticker;
        }
        String text = assetNameText();
        if (text != null) {
            return text;
        }
        return shortPolicyId();
    }
}
