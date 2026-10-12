package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.model.loans.OracleSignature;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Polls the FluidTokens oracle registry for the prices the health-factor engine needs.
 * <p>
 * Same endpoint {@code ada-watch} uses for Lending v3 alerts
 * ({@code https://api.fluidtokens.com/get-oracle-tokens}, no auth). Each entry carries a
 * {@code preferredOracle} and, under {@code supportedOracle}, that provider's price as a
 * numerator/denominator pair plus a validity window — which maps straight onto
 * {@code CommonFeedData} + {@code Aggregated} in {@code types/oracle.ak}.
 * <p>
 * <b>Prices only.</b> The same payload also carries
 * {@code multisigOracle.signatures[]}, which is what a liquidation transaction needs for its
 * {@code OracleRedeemer}, and this client deliberately ignores it: reading a price is a
 * read-only concern, submitting a signed feed is not. Phase 2 adds a separate client for that.
 * Charli3-backed ({@code c3}) entries are the exception: they carry no signature to defer, so this
 * client captures their {@code supportedOracle.c3.referenceInput} directly, as
 * {@link OracleEntry#charlieProviderReferenceInput()}.
 * <p>
 * <b>Network caveat:</b> this endpoint serves <em>mainnet</em> assets. On preview most loan
 * collaterals will simply have no feed, and callers must treat a missing feed as "health
 * unknown" rather than as a zero price.
 */
@Service
@Slf4j
@ConditionalOnProperty(prefix = "loans.oracle", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FluidOracleClient {

    private final WebClient webClient;

    private final AtomicReference<Map<AssetType, OracleEntry>> byToken = new AtomicReference<>(Map.of());

    /** Same entries, keyed by the oracle's own NFT — the way a loan datum names its oracle. */
    private final AtomicReference<Map<AssetType, OracleEntry>> byOracleToken = new AtomicReference<>(Map.of());

    /** Every entry per priced token, for LOAN-FREE lookups that must choose among versions (A3). */
    private final AtomicReference<Map<AssetType, List<OracleEntry>>> versionsByToken = new AtomicReference<>(Map.of());

    private final AtomicReference<Instant> lastRefresh = new AtomicReference<>(Instant.EPOCH);

    /** Last state warned about, per asset and warning kind — see {@link #warnOnce}. */
    private final java.util.Map<String, String> warnedStates = new java.util.concurrent.ConcurrentHashMap<>();

    public FluidOracleClient(@Value("${loans.oracle.url:https://api.fluidtokens.com/get-oracle-tokens}") String url) {
        this.webClient = WebClient.builder().baseUrl(url).build();
        log.info("FluidTokens oracle registry: {}", url);
    }

    @PostConstruct
    public void init() {
        refresh();
    }

    /**
     * ⛔ <b>LOAN-FREE pricing only</b> — a wallet balance, a pool's principal, anything with no loan
     * datum to say which oracle it means. For a LOAN, use {@link #findFeedForLeg}: since 2026-09-30 a
     * token can have two oracles (v1 for Lending v3, v2 for Lending v4) and a loan's datum names one of
     * them, so pricing a loan by its token can read the wrong one (FAB-111).
     *
     * <p>The price for an asset, or empty if there is none <em>or none we hold is usable at
     * {@code atMillis}</em>. With several versions: one usable now, preferring the HIGHEST
     * {@code oracleVersion} (ruling A3, 2026-10-01). They are one feed published twice, so this only
     * decides anything when one version has lapsed and the other has not.
     * <p>
     * Fail-closed on purpose. A liquidation decision taken on an expired price is worse than no
     * decision: the validator would reject the transaction, and in the meantime we would have
     * reported a loan as liquidatable on a price nobody stands behind. Expiry is real and routine
     * — 3 of the 19 registry entries were expired the day this was written.
     * <p>
     * Ada always prices 1:1 and needs no oracle: {@code retrieve_oracle_data} synthesises exactly
     * that feed when the asset's policy id is empty.
     */
    public Optional<OraclePriceFeed> findFeed(AssetType asset, long atMillis) {
        if (asset == null) {
            return Optional.empty();
        }
        if (asset.isAda()) {
            return Optional.of(OraclePriceFeed.unit());
        }
        List<OracleEntry> versions = versionsByToken.get().getOrDefault(asset, List.of());
        if (versions.isEmpty()) {
            // Nothing loaded for it here — and the path a subclass that overrides findEntry takes.
            return findFeedIgnoringValidity(asset).filter(feed -> feed.usableAt(atMillis));
        }
        return versions.stream()
                .filter(entry -> entry.feed().usableAt(atMillis))
                .max(BY_VERSION)
                .map(OracleEntry::feed);
    }

    /**
     * The held feed whether or not it is still valid. Only for telling "we have no feed for this
     * asset" apart from "the feed we have has expired" when explaining an unavailable health —
     * never for pricing. Use {@link #findFeed(AssetType, long)} to price.
     */
    public Optional<OraclePriceFeed> findFeedIgnoringValidity(AssetType asset) {
        if (asset == null) {
            return Optional.empty();
        }
        if (asset.isAda()) {
            return Optional.of(OraclePriceFeed.unit());
        }
        return findEntry(asset).map(OracleEntry::feed);
    }

    /**
     * The oracle deployment for a priced asset, LOAN-FREE: the highest {@code oracleVersion} when there
     * are several. A loan must use {@link #findEntryByOracleToken} with the NFT its datum names.
     */
    public Optional<OracleEntry> findEntry(AssetType token) {
        if (token == null) {
            return Optional.empty();
        }
        List<OracleEntry> versions = versionsByToken.get().getOrDefault(token, List.of());
        return versions.isEmpty() ? Optional.ofNullable(byToken.get().get(token))
                : versions.stream().max(BY_VERSION);
    }

    /**
     * ⛔ <b>The price of one LEG of a loan, from the oracle that loan's datum names</b> (FAB-111).
     * {@code oracleToken} is {@code LoanDatum.principalOracleAsset} for the principal leg and
     * {@code collateral.oracleTokenAsset} for the collateral leg. Ada needs no oracle and prices 1:1,
     * whatever the datum carries — {@code retrieve_oracle_data} short-circuits on the empty policy id.
     *
     * <p>Exactly what the validator will check, which is the point: health, readiness and the
     * economics of a loan must agree with the transaction that would liquidate it.
     */
    public Optional<OraclePriceFeed> findFeedForLeg(AssetType asset, AssetType oracleToken, long atMillis) {
        return findFeedForLegIgnoringValidity(asset, oracleToken).filter(feed -> feed.usableAt(atMillis));
    }

    /** As {@link #findFeedForLeg}, whether or not still valid — only to explain an absence. */
    public Optional<OraclePriceFeed> findFeedForLegIgnoringValidity(AssetType asset, AssetType oracleToken) {
        if (asset == null) {
            return Optional.empty();
        }
        if (asset.isAda()) {
            return Optional.of(OraclePriceFeed.unit());
        }
        // ⚠ The named oracle must price THIS leg's token (oracle audit round 1, finding 6). A datum
        // naming another token's oracle would otherwise be priced at that other token's price; the
        // validator's is_feed_token_correct refuses such a transaction, so the figures must not claim it.
        return findEntryByOracleToken(oracleToken)
                .filter(entry -> asset.equals(entry.token()))
                .map(OracleEntry::feed);
    }

    /** Higher {@code oracleVersion} wins; an entry without one ranks below any that has one. */
    private static final java.util.Comparator<OracleEntry> BY_VERSION = java.util.Comparator.comparing(
            OracleEntry::oracleVersion, java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()));

    /**
     * The oracle a loan datum actually names. {@code retrieve_oracle_data} requires the reference
     * input to hold this NFT, so for building a transaction this is the correct lookup — matching
     * on the priced asset instead would silently pick a different oracle if one asset ever gained
     * a second feed.
     */
    public Optional<OracleEntry> findEntryByOracleToken(AssetType oracleToken) {
        return oracleToken == null ? Optional.empty()
                : Optional.ofNullable(byOracleToken.get().get(oracleToken));
    }

    /**
     * Every oracle currently known — <b>every registry entry</b>, one per oracle NFT.
     *
     * <p>⛔ FAB-110. This returned {@code byToken.values()} until 2026-10-01, which is ONE entry per
     * priced token: harmless while the registry listed one oracle per token, and wrong since
     * FluidTokens split them on 2026-09-30 (v1 for Lending v3, v2 for Lending v4, same asset names,
     * both permanent). {@code byToken} keeps the last of two, so every v1 oracle vanished from here —
     * and {@code LiquidationExecutor.oracleSnapshot()}, the NFT-keyed map every liquidation builder
     * resolves a loan's oracle from, is built from THIS method. Every live v4 loan names a v1 oracle,
     * so every one of them refused {@code ORACLE_ENTRY_MISSING} at build.
     *
     * <p>Keyed by oracle NFT, so two versions of one token are two entries, which is the truth.
     */
    public Collection<OracleEntry> entries() {
        return byOracleToken.get().values();
    }

    public Instant lastRefresh() {
        return lastRefresh.get();
    }

    public int trackedAssets() {
        return byToken.get().size();
    }

    /**
     * Oracles, not tokens: since 2026-09-30 most tokens have two (v1 for Lending v3, v2 for v4), so
     * this is larger than {@link #trackedAssets()} and the two must never be read as one number.
     */
    public int trackedOracles() {
        return byOracleToken.get().size();
    }

    /**
     * Feeds carry a 50-minute window (10 for the Charli3-backed one) against a 60-minute protocol
     * maximum, so a 30s cadence is fresh enough and polite.
     */
    @Scheduled(fixedDelay = 30_000L, initialDelay = 30_000L)
    public void refresh() {
        try {
            var body = webClient.get().retrieve().bodyToMono(JsonNode.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();
            if (body == null || !body.isArray()) {
                log.warn("FluidTokens oracle response was null or not an array");
                return;
            }
            int count = load(body);
            log.debug("FluidTokens oracle refreshed: {} priced assets", count);
        } catch (Exception e) {
            // transient failures self-heal on the next tick; one line, no stack trace
            log.warn("could not refresh the FluidTokens oracle ({}); retrying in 30s", e.toString());
            log.debug("oracle refresh failure", e);
        }
    }

    /** Replaces the held entries with whatever parses out of a registry payload. */
    int load(JsonNode array) {
        var tokens = new HashMap<AssetType, OracleEntry>();
        var oracleTokens = new HashMap<AssetType, OracleEntry>();
        var versionsPerToken = new HashMap<AssetType, List<OracleEntry>>();
        array.forEach(node -> parse(node).ifPresent(entry -> {
            // ⛔ TWO ORACLES FOR ONE TOKEN IS NORMAL (FAB-113). Since 2026-09-30 FluidTokens publish
            // v1 (Lending v3) and v2 (Lending v4) of most tokens, permanently. This used to WARN on
            // every refresh — 16 tokens twice a minute, ~46,000 lines a day — about the registry's
            // normal shape, which teaches whoever reads the log that WARN means nothing here.
            // byToken keeps the last for TOKEN-keyed lookups; every entry stays reachable by its NFT.
            tokens.put(entry.token(), entry);
            versionsPerToken.computeIfAbsent(entry.token(), k -> new ArrayList<>()).add(entry);

            // ⚠ What IS a conflict: two entries naming the SAME oracle NFT. A loan names its oracle by
            // that NFT, so two of them make "the oracle this loan uses" ambiguous.
            OracleEntry displaced = oracleTokens.put(entry.oracleToken(), entry);
            if (displaced != null) {
                // Keep the per-token versions consistent with the NFT map (last wins): the displaced
                // entry must not stay selectable, nor count as a second "version" (audit finding 9).
                versionsPerToken.get(displaced.token()).remove(displaced);
                warnOnce(entry.oracleToken(), "duplicate-oracle-nft", "duplicate",
                        "oracle registry lists oracle NFT {} TWICE (token {}) — keeping the last. A loan "
                                + "names its oracle by this NFT, so which entry it gets is now arbitrary.",
                        entry.oracleToken().toUnit(), entry.token().toUnit());
            }
        }));
        warnOnVersionPriceDisagreement(versionsPerToken, System.currentTimeMillis());
        byToken.set(Map.copyOf(tokens));
        byOracleToken.set(Map.copyOf(oracleTokens));
        var versions = new HashMap<AssetType, List<OracleEntry>>();
        versionsPerToken.forEach((token, list) -> versions.put(token, List.copyOf(list)));
        versionsByToken.set(Map.copyOf(versions));
        lastRefresh.set(Instant.now());
        reportShapeOnChange(oracleTokens.size(), tokens.size(),
                (int) versionsPerToken.values().stream().filter(v -> v.size() > 1).count());
        return tokens.size();
    }

    private volatile String lastShape;

    /** One INFO line when the registry's shape moves — never one per refresh. */
    private void reportShapeOnChange(int oracles, int tokens, int multiVersionTokens) {
        String shape = "%d oracles across %d tokens, %d tokens with more than one oracle version"
                .formatted(oracles, tokens, multiVersionTokens);
        if (!shape.equals(lastShape)) {
            lastShape = shape;
            log.info("FluidTokens oracle registry: {}", shape);
        }
    }

    /**
     * ⚠ Two versions of one token are ONE feed published twice, so at the same instant they must
     * agree. If both are valid and their prices differ, something upstream broke — the one condition
     * about duplicates still worth a WARN (ruling A5, 2026-10-01). Latched on the first live entry's
     * oracle NFT and the two prices, so it re-warns only when the disagreement itself changes (with
     * three or more disagreeing versions it can re-warn per refresh — none exist today).
     */
    private void warnOnVersionPriceDisagreement(Map<AssetType, List<OracleEntry>> versionsPerToken, long now) {
        versionsPerToken.forEach((token, entries) -> {
            List<OracleEntry> live = entries.stream().filter(e -> e.feed().usableAt(now)).toList();
            if (live.size() < 2) {
                return;
            }
            OracleEntry first = live.getFirst();
            for (OracleEntry other : live.subList(1, live.size())) {
                // a/b == c/d  <=>  a*d == c*b — exact, no rounding
                boolean equal = first.feed().priceInLovelaces().multiply(other.feed().priceDenominator())
                        .equals(other.feed().priceInLovelaces().multiply(first.feed().priceDenominator()));
                if (!equal) {
                    warnOnce(first.oracleToken(), "version-price-disagreement",
                            first.feed().priceInLovelaces() + "/" + first.feed().priceDenominator() + " vs "
                                    + other.feed().priceInLovelaces() + "/" + other.feed().priceDenominator(),
                            "oracle versions for {} DISAGREE while both are valid: {} (v{}) says {}/{}, {} (v{}) "
                                    + "says {}/{}. They are meant to be one feed published twice.",
                            token.toUnit(), first.oracleToken().toUnit(), first.oracleVersion(),
                            first.feed().priceInLovelaces(), first.feed().priceDenominator(),
                            other.oracleToken().toUnit(), other.oracleVersion(),
                            other.feed().priceInLovelaces(), other.feed().priceDenominator());
                }
            }
        });
    }

    private Optional<OracleEntry> parse(JsonNode entry) {
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
                log.warn("oracle {} uses unknown provider '{}'; skipping it rather than guessing a variant",
                        asset.toUnit(), preferred);
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
                    signatures(supported, keys, asset, oracleToken),
                    charlieProviderReferenceInput,
                    // ⚠ FAB-112: 1 (Lending v3) or 2 (Lending v4) since 2026-09-30. ABSENT means
                    // unknown, never an error — a registry without the field is the one we parsed
                    // for months, and refusing it would blind every price over a label.
                    entry.path("oracleVersion").isInt() ? entry.path("oracleVersion").asInt() : null,
                    charlieProviderNft));
        } catch (Exception e) {
            log.warn("could not parse a FluidTokens oracle entry: {}", e.toString());
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
    private List<OracleSignature> signatures(JsonNode supported, List<String> keys, AssetType asset, AssetType oracleToken) {
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
            warnOnce(oracleToken, "no-public-keys", String.valueOf(published.size()),
                    "oracle {} publishes {} signature(s) but no publicKeys; cannot resolve key "
                            + "positions, so it stays priceable but not liquidatable",
                    asset.toUnit(), published.size());
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
            warnOnce(oracleToken, "unresolved-keys", unresolved.toString(),
                    "oracle {} published signatures from keys outside its publicKeys {}; dropping them",
                    asset.toUnit(), unresolved);
        }
        return out;
    }

    /**
     * ⛔ <b>Say it once, and again only when it CHANGES.</b>
     *
     * <p>The oracle registry is re-read every 30 seconds, so a warning about a condition IN that
     * registry repeats twice a minute for as long as the condition lasts — which, for a third party's
     * stale preview feed, is indefinitely. **Measured 2026-09-04: 109 identical WARNs in 56 minutes.**
     * That is not a loud signal, it is a quiet one: it teaches whoever reads these logs that WARN
     * means nothing here, and the next WARN may be the one that matters.
     *
     * <h2>⚠ What the latch keys on, and the trap in choosing it</h2>
     * On {@code state} — a caller-supplied string that must be <b>stable while the condition is
     * unchanged</b>. That rules out the obvious choice: <b>the signatures themselves are re-signed on
     * every publication</b>, so latching on their content would re-warn every refresh and the latch
     * would do nothing at all while looking like it worked. The signature COUNT and the SET OF
     * OFFENDING KEYS are stable; the signatures are not.
     *
     * <p>⇒ So it re-warns exactly when something an operator would want to know has moved — a signer
     * added or removed, a different key going unresolved — and stays silent otherwise. And when the
     * condition is fixed upstream this branch simply stops being reached, so the latch needs no
     * clearing.
     */
    private void warnOnce(AssetType oracleToken, String kind, String state, String format, Object... args) {
        // ⚠ Keyed by ORACLE NFT, not priced token (FAB-113): since 2026-09-30 one token has two
        // oracles, and a per-token latch lets their two states overwrite each other — re-warning on
        // every refresh, the exact noise this method exists to stop.
        String key = oracleToken.toUnit() + "/" + kind;
        String previous = warnedStates.put(key, state);
        if (state.equals(previous)) {
            log.debug(format, args);
            return;
        }
        log.warn(format, args);
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

    /** Convenience for callers that only have the raw numbers. */
    static OraclePriceFeed feedOf(AssetType token, BigInteger price, BigInteger denominator,
                                  long validFrom, long validTo) {
        return OraclePriceFeed.aggregated(token, price, denominator, validFrom, validTo);
    }
}
