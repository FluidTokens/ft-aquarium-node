package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

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

    /**
     * Told what every {@link #load} produced, once all of it is held. ⛔ <b>Read-only by construction</b>
     * (FAB-175): a listener receives the immutable entries and the very {@link Instant} stored as
     * {@link #lastRefresh()}, after every map, the refresh time and the shape line are set, and each call is
     * caught on its own — so a listener can neither change what the node prices with nor break the refresh.
     */
    @FunctionalInterface
    public interface LoadListener {
        void loaded(java.util.Collection<OracleEntry> entries, Instant loadedAt);
    }

    private final List<LoadListener> loadListeners = new CopyOnWriteArrayList<>();

    public FluidOracleClient(@Value("${loans.oracle.url:https://api.fluidtokens.com/get-oracle-tokens}") String url) {
        this.webClient = WebClient.builder().baseUrl(url).build();
        log.info("FluidTokens oracle registry: {}", url);
    }

    /** Registers a {@link LoadListener}; listeners are told in registration order. */
    public void addLoadListener(LoadListener l) {
        loadListeners.add(l);
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
        array.forEach(node -> {
            // A condition the parse met is logged BEFORE this entry's own bookkeeping, so the log
            // interleaves exactly as it did when the parse logged it itself (FAB-174).
            var parsed = OracleRegistryParser.parseEntry(node);
            parsed.diagnostics().forEach(this::report);
            parsed.entry().ifPresent(entry -> {
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
            });
        });
        warnOnVersionPriceDisagreement(versionsPerToken, System.currentTimeMillis());
        byToken.set(Map.copyOf(tokens));
        byOracleToken.set(Map.copyOf(oracleTokens));
        var versions = new HashMap<AssetType, List<OracleEntry>>();
        versionsPerToken.forEach((token, list) -> versions.put(token, List.copyOf(list)));
        versionsByToken.set(Map.copyOf(versions));
        Instant now = Instant.now();
        lastRefresh.set(now);
        reportShapeOnChange(oracleTokens.size(), tokens.size(),
                (int) versionsPerToken.values().stream().filter(v -> v.size() > 1).count());
        notifyLoadListeners(now);
        return tokens.size();
    }

    /** Each listener in its own catch: one that throws is named by its exception's class and skipped. */
    private void notifyLoadListeners(Instant loadedAt) {
        Collection<OracleEntry> entries = byOracleToken.get().values();
        for (LoadListener listener : loadListeners) {
            try {
                listener.loaded(entries, loadedAt);
            } catch (RuntimeException e) {
                log.warn("oracle load listener failed ({})", e.getClass().getSimpleName());
            }
        }
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

    /**
     * Logs one condition the parser met, exactly as the parse used to log it itself: latched ones through
     * {@link #warnOnce}, the rest as a plain WARN.
     */
    private void report(OracleRegistryParser.Diagnostic d) {
        if (d.latchKey() != null) {
            warnOnce(d.latchKey(), d.kind().latchKind(), d.latchState(), d.format(), d.args().toArray());
        } else {
            log.warn(d.format(), d.args().toArray());
        }
    }

    /** {@code "txhash#index"} as the registry publishes reference UTxOs; see {@link OracleRegistryParser#utxoRef}. */
    static TransactionInput utxoRef(String ref) {
        return OracleRegistryParser.utxoRef(ref);
    }

    /** The oracle's withdrawal credential; see {@link OracleRegistryParser#withdrawCredentialHash}. */
    static String withdrawCredentialHash(String rewardAddress) {
        return OracleRegistryParser.withdrawCredentialHash(rewardAddress);
    }

    /** Convenience for callers that only have the raw numbers. */
    static OraclePriceFeed feedOf(AssetType token, BigInteger price, BigInteger denominator,
                                  long validFrom, long validTo) {
        return OraclePriceFeed.aggregated(token, price, denominator, validFrom, validTo);
    }
}
