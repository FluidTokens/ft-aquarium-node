package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.model.loans.OracleSignature;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Turns a FluidTokens oracle registry array into {@link OracleEntry} values — the
 * {@code /get-oracle-tokens} response, or the {@code tokens} array of a WebSocket {@code oracle-tokens}
 * push, which carry the same entry shape.
 *
 * <p>⛔ <b>Pure: no logger, no I/O, no state.</b> Every condition the parse used to log is returned as a
 * {@link Diagnostic} carrying the same format string and arguments, and the caller decides what to do
 * with it. {@link FluidOracleClient} logs them exactly as it did before this class existed (FAB-174),
 * latching the two conditions that are latched; a caller that only wants to compare two payloads can
 * ignore them. Parsing the same payload twice gives equal results.
 *
 * <p>Registry-level checks are NOT here: two entries naming one oracle NFT, and two live versions of a
 * token disagreeing on price, are judged by the caller across a whole load.
 */
public final class OracleRegistryParser {

    private OracleRegistryParser() {
    }

    /**
     * One condition the parse met, as the log line it used to be: {@code format} and {@code args} are
     * SLF4J's. {@code latchKey} is the oracle NFT a repeat is latched on and {@code latchState} the state
     * that must change before it is warned about again (see {@code FluidOracleClient.warnOnce}); a null
     * {@code latchKey} means unlatched — warned every time.
     */
    public record Diagnostic(Kind kind, AssetType latchKey, String latchState, String format, List<Object> args) {
    }

    public enum Kind {
        PARSE_FAILURE(null),
        UNKNOWN_PROVIDER(null),
        NO_PUBLIC_KEYS("no-public-keys"),
        UNRESOLVED_KEYS("unresolved-keys");

        private final String latchKind;

        Kind(String latchKind) {
            this.latchKind = latchKind;
        }

        /** The latch's kind string for a latched diagnostic, unchanged from before FAB-174; null otherwise. */
        public String latchKind() {
            return latchKind;
        }
    }

    /** One registry entry: the entry if it parsed into a usable one, and whatever the parse met on the way. */
    public record EntryResult(Optional<OracleEntry> entry, List<Diagnostic> diagnostics) {
    }

    /**
     * A whole registry array. {@code entries} are in registry order; {@code byOracleToken} keys them by
     * oracle NFT with the LAST of two entries naming one NFT winning, the same as
     * {@code FluidOracleClient.entries()}.
     */
    public record Parsed(List<OracleEntry> entries, Map<AssetType, OracleEntry> byOracleToken,
                         List<Diagnostic> diagnostics) {
    }

    /** Parses a registry array. Anything that is not an array parses to nothing, and never throws. */
    public static Parsed parse(JsonNode array) {
        if (array == null || !array.isArray()) {
            return new Parsed(List.of(), Collections.unmodifiableMap(new LinkedHashMap<>()), List.of());
        }
        var entries = new ArrayList<OracleEntry>();
        var byNft = new LinkedHashMap<AssetType, OracleEntry>();
        var diagnostics = new ArrayList<Diagnostic>();
        for (JsonNode node : array) {
            EntryResult result = parseEntry(node);
            diagnostics.addAll(result.diagnostics());
            result.entry().ifPresent(entry -> {
                entries.add(entry);
                byNft.put(entry.oracleToken(), entry);
            });
        }
        return new Parsed(List.copyOf(entries), Collections.unmodifiableMap(new LinkedHashMap<>(byNft)),
                List.copyOf(diagnostics));
    }

    /** Parses one registry entry. Never throws: a failure is a {@link Kind#PARSE_FAILURE} diagnostic. */
    public static EntryResult parseEntry(JsonNode node) {
        var diagnostics = new ArrayList<Diagnostic>();
        Optional<OracleEntry> entry = parse(node, diagnostics);
        return new EntryResult(entry, List.copyOf(diagnostics));
    }

    private static Diagnostic diagnostic(Kind kind, AssetType latchKey, String latchState, String format,
                                         Object... args) {
        return new Diagnostic(kind, latchKey, latchState, format, Collections.unmodifiableList(Arrays.asList(args)));
    }

    private static Optional<OracleEntry> parse(JsonNode entry, List<Diagnostic> diagnostics) {
        try {
            if (!entry.path("active").asBoolean(false)) {
                return Optional.empty();
            }
            var preferred = entry.path("preferredOracle").asText("");
            var supported = entry.path("supportedOracle").path(preferred);
            if (preferred.isBlank() || supported.isMissingNode() || supported.isNull()) {
                return Optional.empty();
            }
            var denominator = supported.path("tokenPriceDenominator").bigIntegerValue();
            if (denominator.signum() == 0) {
                // rational.new(_, 0) is None on chain, so this asset is simply unpriceable
                return Optional.empty();
            }
            var token = entry.path("token");
            var asset = AssetType.fromUnit(token.path("policyId").asText("") + token.path("assetName").asText(""));
            // The token travels inside the signed feed and is checked by is_feed_token_correct,
            // so it is carried verbatim rather than reconstructed later. The provider decides the
            // on-chain variant: "multisig" feeds are signed and verified as Aggregated, "c3" feeds
            // are verified structurally against a Charli3 reference input instead.
            var price = supported.path("tokenPriceInLovelaces").bigIntegerValue();
            long validFrom = supported.path("validFrom").asLong();
            long validTo = supported.path("validTo").asLong();
            OraclePriceFeed feed = switch (preferred) {
                case "multisig" -> OraclePriceFeed.aggregated(asset, price, denominator, validFrom, validTo);
                case "c3" -> OraclePriceFeed.priceDataCharlie(asset, price, denominator, validFrom, validTo);
                default -> null;
            };
            if (feed == null) {
                diagnostics.add(diagnostic(Kind.UNKNOWN_PROVIDER, null, null,
                        "oracle {} uses unknown provider '{}'; skipping it rather than guessing a variant",
                        asset.toUnit(), preferred));
                return Optional.empty();
            }

            var oracle = entry.path("fluidOracle");
            var oracleToken = new AssetType(oracle.path("policyId").asText(""),
                    oracle.path("assetName").asText(""));
            var rewardAddress = oracle.path("rewardAddress").asText("");
            var keys = textList(entry.path("multisigOracle").path("publicKeys"));

            var charlieProviderReferenceInput = "c3".equals(preferred)
                    ? utxoRef(supported.path("referenceInput").asText("")) : null;
            // FAB-138: the provider UTxO's NFT is the c3 policy + "OracleFeed" (4f7261636c6546656564;
            // OracleEntry's javadoc says why the name is a constant). A blank policy is no NFT to probe
            // for, never a policy-less unit.
            var c3Policy = supported.path("policyId").asText("");
            var charlieProviderNft = "c3".equals(preferred) && !c3Policy.isBlank()
                    ? new AssetType(c3Policy, "4f7261636c6546656564") : null;

            return Optional.of(new OracleEntry(
                    asset,
                    oracleToken,
                    rewardAddress,
                    withdrawCredentialHash(rewardAddress),
                    utxoRef(oracle.path("referenceInput").asText("")),
                    utxoRef(oracle.path("referenceScript").asText("")),
                    keys,
                    entry.path("multisigOracle").path("requiredSignatures").asInt(0),
                    feed,
                    signatures(supported, keys, asset, oracleToken, diagnostics),
                    charlieProviderReferenceInput,
                    // ⚠ FAB-112: 1 (Lending v3) or 2 (Lending v4) since 2026-09-30. ABSENT means
                    // unknown, never an error — a registry without the field is the one we parsed
                    // for months, and refusing it would blind every price over a label.
                    entry.path("oracleVersion").isInt() ? entry.path("oracleVersion").asInt() : null,
                    charlieProviderNft));
        } catch (Exception e) {
            diagnostics.add(diagnostic(Kind.PARSE_FAILURE, null, null,
                    "could not parse a FluidTokens oracle entry: {}", e.toString()));
            return Optional.empty();
        }
    }

    /**
     * Resolves each published signature to the {@code key_position} the validator expects.
     * <p>
     * A signature whose key is not in the list is dropped rather than guessed at: the validator
     * {@code expect}s every supplied signature to verify against the key at its position, so one
     * wrong position fails the entire transaction instead of merely being ignored.
     */
    private static List<OracleSignature> signatures(JsonNode supported, List<String> keys, AssetType asset,
                                                    AssetType oracleToken, List<Diagnostic> diagnostics) {
        JsonNode published = supported.path("multisigOracle").path("signatures");
        if (!published.isArray() || published.isEmpty()) {
            return List.of();
        }
        if (keys.isEmpty()) {
            // The preview registry omits multisigOracle.publicKeys on some entries. Without it there
            // is no way to say which position a key occupies, and the array order is not a safe
            // substitute — a partial signer set would shift every index. Refuse rather than guess:
            // the validator expects each supplied signature to verify at its stated position.
            //
            // ⚠ LATCHED, because this fires on every refresh and the condition is STATIC. Measured on
            // preview 2026-09-04: 109 identical WARNs in 56 minutes, one per 30-second refresh, for a
            // registry FluidTokens are not about to change. A log that repeats twice a minute forever
            // trains an operator to ignore WARN, and the next one may be the real one.
            diagnostics.add(diagnostic(Kind.NO_PUBLIC_KEYS, oracleToken, String.valueOf(published.size()),
                    "oracle {} publishes {} signature(s) but no publicKeys; cannot resolve key "
                            + "positions, so it stays priceable but not liquidatable",
                    asset.toUnit(), published.size()));
            return List.of();
        }
        var out = new ArrayList<OracleSignature>();
        var unresolved = new ArrayList<String>();
        for (JsonNode signature : published) {
            var publicKey = signature.path("publicKey").asText("");
            int position = indexOfIgnoringCase(keys, publicKey);
            if (position < 0) {
                unresolved.add(publicKey);
                continue;
            }
            out.add(new OracleSignature(position, signature.path("signature").asText("")));
        }
        if (!unresolved.isEmpty()) {
            // Latched on the SET of offending keys — same reasoning as above, and the set is stable
            // across re-publications where the signatures themselves are not.
            diagnostics.add(diagnostic(Kind.UNRESOLVED_KEYS, oracleToken, unresolved.toString(),
                    "oracle {} published signatures from keys outside its publicKeys {}; dropping them",
                    asset.toUnit(), unresolved));
        }
        return out;
    }

    private static int indexOfIgnoringCase(List<String> keys, String key) {
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i).equalsIgnoreCase(key)) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> textList(JsonNode array) {
        var out = new ArrayList<String>();
        array.forEach(node -> out.add(node.asText()));
        return out;
    }

    private static final Pattern TX_HASH = Pattern.compile("[0-9a-fA-F]{64}");

    /**
     * {@code "txhash#index"} as the registry publishes reference UTxOs.
     * <p>
     * Null on anything that is not a well-formed reference — a missing {@code #}, a transaction id
     * that is not exactly 64 hex chars (32 bytes), or a non-numeric index — rather than throwing.
     * {@code parse}'s single {@code catch} around a whole registry entry means a thrown exception
     * here would discard every field of that entry, not just the malformed one, so callers rely on
     * this returning null instead.
     */
    static TransactionInput utxoRef(String ref) {
        int hash = ref.indexOf('#');
        if (hash < 0) {
            return null;
        }
        String transactionId = ref.substring(0, hash);
        if (!TX_HASH.matcher(transactionId).matches()) {
            return null;
        }
        try {
            int index = Integer.parseInt(ref.substring(hash + 1));
            return TransactionInput.builder()
                    .transactionId(transactionId)
                    .index(index)
                    .build();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * The oracle's withdrawal credential, which is what {@code Withdraw(credential)} in the
     * redeemer map is keyed by. These are always script stake addresses.
     */
    static String withdrawCredentialHash(String rewardAddress) {
        if (rewardAddress == null || rewardAddress.isBlank()) {
            return null;
        }
        var address = new Address(rewardAddress);
        return address.getDelegationCredentialHash()
                .or(address::getPaymentCredentialHash)
                .map(HexUtil::encodeHexString)
                .orElse(null);
    }
}
