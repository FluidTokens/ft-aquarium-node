package com.fluidtokens.aquarium.offchain.config;

/**
 * The Blockfrost project id, normalised once: OkHttp throws a CR or LF in the {@code project_id} header back
 * with the whole value in its message and redacts only Authorization and Cookie, so a raw key with a trailing
 * newline leaks itself into the log of every Blockfrost call. {@link BlockfrostConfig} is the only place
 * {@code blockfrost.key} is bound; every consumer is handed {@link #value()}.
 *
 * <p>The rule is {@code BlockfrostPublishedOutputLookup}'s: surrounding whitespace is {@code strip()}ped,
 * null or blank is {@code ""} (unset), and anything else outside visible ASCII refuses — without the value.
 * A class and not a record, because a record's {@code toString} would print the key.
 */
public final class BlockfrostKey {

    /** The refusal for a key no header can carry. Names the property, never the value. */
    public static final String NOT_A_HEADER_VALUE =
            "blockfrost.key contains characters not allowed in an HTTP header (value withheld)";

    private final String value;

    private BlockfrostKey(String value) {
        this.value = value;
    }

    /**
     * The key with its surrounding whitespace stripped; {@code ""} for null or blank. Throws
     * {@link IllegalStateException} with {@link #NOT_A_HEADER_VALUE} and no cause when what is left holds a
     * character outside 0x21–0x7E.
     */
    public static BlockfrostKey of(String raw) {
        String stripped = raw == null ? "" : raw.strip();
        if (!isVisibleAscii(stripped)) {
            throw new IllegalStateException(NOT_A_HEADER_VALUE);
        }
        return new BlockfrostKey(stripped);
    }

    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return value.isEmpty() ? "BlockfrostKey[unset]" : "BlockfrostKey[set, value withheld]";
    }

    /** Every character in 0x21–0x7E: a strict subset of what an HTTP header value may hold. */
    private static boolean isVisibleAscii(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x21 || c > 0x7E) {
                return false;
            }
        }
        return true;
    }
}
