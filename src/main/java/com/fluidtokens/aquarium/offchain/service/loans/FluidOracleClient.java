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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * <p>
 * <b>One merged snapshot, two writers</b> (FAB-183). What every lookup reads is ONE immutable {@link Snapshot}
 * keyed by oracle NFT, published through one reference. It is written by the API poller's {@link #load} and —
 * when {@code loans.oracle.source} is {@code stream}, the default — by every parsed oracle-stream push
 * ({@link #mergeStreamPush}) and connection change ({@link #streamConnected}), all under one lock. Per NFT the
 * entry with the larger {@code feed.validFrom} wins and an equal one keeps the held entry (Giovanni A1,
 * 2026-10-31: newest only, never a fallback to an older version). An NFT stays held only while the API's last
 * load lists it or the last push of a CONNECTED stream lists it. With no push ever merged this is exactly the
 * old replace-on-load, for any sequence of loads whose per-NFT {@code validFrom} never decreases.
 */
@Service
@Slf4j
@ConditionalOnProperty(prefix = "loans.oracle", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FluidOracleClient {

    private final WebClient webClient;

    /**
     * Everything a lookup reads, as of one write. ⛔ <b>Read it ONCE per call</b>: two reads of the reference can
     * see two different writes, and their maps would then disagree.
     */
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.EMPTY);

    /** The three writers ({@link #load}, {@link #mergeStreamPush}, {@link #streamConnected}) take turns on it. */
    private final Object writeLock = new Object();

    /** Last state warned about, per asset and warning kind — see {@link #warnOnce}. */
    private final java.util.Map<String, String> warnedStates = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Told what every {@link #load} produced, once all of it is held. ⛔ <b>Read-only by construction</b>
     * (FAB-175): a listener receives the immutable entries and the very {@link Instant} stored as
     * {@link #lastRefresh()}, after the snapshot and the shape line are set, and each call is
     * caught on its own — so a listener can neither change what the node prices with nor break the refresh.
     * ⛔ The entries are the API payload's OWN (FAB-183), never the merged ones: after a stream push made a held
     * entry newer, a listener still hears exactly what the API served.
     */
    @FunctionalInterface
    public interface LoadListener {
        void loaded(java.util.Collection<OracleEntry> entries, Instant loadedAt);
    }

    private final List<LoadListener> loadListeners = new CopyOnWriteArrayList<>();

    /** Which writer changed the merged prices. */
    public enum Source { API, STREAM }

    /**
     * Told after every write that changed the merged prices — an NFT added, removed or replaced — once, after the
     * write's lock is released, on the writer's own thread, and each listener in its own catch. A write that
     * changed nothing tells no one; a disconnect that drops stream-only NFTs reports {@link Source#STREAM}.
     *
     * <p>{@code streamConnected} is the stream's connection state as that write left it.
     */
    @FunctionalInterface
    public interface ChangeListener {
        void changed(Source source, Set<AssetType> changedOracleNfts, boolean streamConnected);
    }

    private final List<ChangeListener> changeListeners = new CopyOnWriteArrayList<>();

    /**
     * One write's whole state, immutable. Package-private only so a test may read it through {@link #snapshot()}.
     *
     * @param byOracleToken   the MERGED entries, keyed by oracle NFT — what every lookup prices from
     * @param byToken         per priced token, the entry {@link #findEntry} returns (highest {@code oracleVersion})
     * @param versionsByToken per priced token, every merged entry of it: API-payload order for the NFTs the API's
     *                        last load listed, then stream-only NFTs by unit
     * @param lastRefresh     when the API's last successful load was held; {@link Instant#EPOCH} before any
     * @param apiEntries      the API's last successful load, its OWN parsed entries keyed by NFT, in payload order
     * @param apiNfts         that load's NFT set
     * @param streamNfts      the NFT set of the last parsed stream push; emptied by a disconnect
     * @param streamConnected whether the stream is connected
     */
    record Snapshot(Map<AssetType, OracleEntry> byOracleToken,
                    Map<AssetType, OracleEntry> byToken,
                    Map<AssetType, List<OracleEntry>> versionsByToken,
                    Instant lastRefresh,
                    Map<AssetType, OracleEntry> apiEntries,
                    Set<AssetType> apiNfts,
                    Set<AssetType> streamNfts,
                    boolean streamConnected) {

        static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of(), Map.of(), Instant.EPOCH, Map.of(), Set.of(),
                Set.of(), false);
    }

    public FluidOracleClient(@Value("${loans.oracle.url:https://api.fluidtokens.com/get-oracle-tokens}") String url) {
        this.webClient = WebClient.builder().baseUrl(url).build();
        log.info("FluidTokens oracle registry: {}", url);
    }

    /** Registers a {@link LoadListener}; listeners are told in registration order. */
    public void addLoadListener(LoadListener l) {
        loadListeners.add(l);
    }

    /** Registers a {@link ChangeListener}; listeners are told in registration order. */
    public void addChangeListener(ChangeListener l) {
        changeListeners.add(l);
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
        List<OracleEntry> versions = snapshot.get().versionsByToken().getOrDefault(asset, List.of());
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
        Snapshot held = snapshot.get();
        List<OracleEntry> versions = held.versionsByToken().getOrDefault(token, List.of());
        return versions.isEmpty() ? Optional.ofNullable(held.byToken().get(token))
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
                : Optional.ofNullable(snapshot.get().byOracleToken().get(oracleToken));
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
        return snapshot.get().byOracleToken().values();
    }

    /**
     * ⛔ The API's OWN last successful load — its parsed entries, never merged values — for the stream's
     * comparison, which judges the stream against what the API served.
     */
    Collection<OracleEntry> lastApiEntries() {
        return snapshot.get().apiEntries().values();
    }

    /** For tests: the snapshot every lookup reads, as one write left it. */
    Snapshot snapshot() {
        return snapshot.get();
    }

    /** When the API's last successful load was held; {@link Instant#EPOCH} before any. */
    public Instant lastRefresh() {
        return snapshot.get().lastRefresh();
    }

    public int trackedAssets() {
        return snapshot.get().byToken().size();
    }

    /**
     * Oracles, not tokens: since 2026-09-30 most tokens have two (v1 for Lending v3, v2 for v4), so
     * this is larger than {@link #trackedAssets()} and the two must never be read as one number.
     */
    public int trackedOracles() {
        return snapshot.get().byOracleToken().size();
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

    /**
     * Merges whatever parses out of a registry payload as {@link Source#API}: it becomes the API's last load, and
     * each of its entries replaces the held one for its NFT unless that one is at least as new. The diagnostics,
     * warnings and shape line are the payload's own, and so is what the {@link LoadListener}s are told.
     *
     * @return the payload's distinct priced tokens
     */
    int load(JsonNode array) {
        var tokens = new HashMap<AssetType, OracleEntry>();
        // In payload order, a duplicate NFT moved to where its last entry stands — the order of versionsPerToken.
        var oracleTokens = new LinkedHashMap<AssetType, OracleEntry>();
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
                OracleEntry displaced = oracleTokens.remove(entry.oracleToken());
                oracleTokens.put(entry.oracleToken(), entry);
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
        Map<AssetType, OracleEntry> apiEntries = Collections.unmodifiableMap(new LinkedHashMap<>(oracleTokens));
        Instant now;
        Snapshot written;
        Set<AssetType> changed;
        synchronized (writeLock) {
            now = Instant.now();
            Snapshot previous = snapshot.get();
            written = merge(previous, apiEntries.values(), now, apiEntries, previous.streamNfts(),
                    previous.streamConnected());
            changed = changedNfts(previous, written);
            reportShapeOnChange(oracleTokens.size(), tokens.size(),
                    (int) versionsPerToken.values().stream().filter(v -> v.size() > 1).count());
        }
        // ⛔ The API's own entries, never the merged ones: the stream's comparison judges the stream against them.
        notifyLoadListeners(List.copyOf(apiEntries.values()), now);
        notifyChangeListeners(Source.API, changed, written.streamConnected());
        return tokens.size();
    }

    /**
     * Merges one parsed stream push as {@link Source#STREAM}, and records its NFT set: while the stream is
     * connected, an NFT that push lists stays held even when the API's last load does not list it.
     *
     * @param at when the push arrived (said for the caller's record; the merge reads only {@code validFrom})
     */
    void mergeStreamPush(Collection<OracleEntry> entries, Instant at) {
        var pushed = new LinkedHashMap<AssetType, OracleEntry>();
        entries.forEach(entry -> pushed.put(entry.oracleToken(), entry)); // the last of two for one NFT, as load()
        Snapshot written;
        Set<AssetType> changed;
        synchronized (writeLock) {
            Snapshot previous = snapshot.get();
            written = merge(previous, pushed.values(), previous.lastRefresh(), previous.apiEntries(),
                    Set.copyOf(pushed.keySet()), previous.streamConnected());
            changed = changedNfts(previous, written);
        }
        notifyChangeListeners(Source.STREAM, changed, written.streamConnected());
    }

    /**
     * Records the stream's connection state. ⛔ A disconnect empties the last push's NFT set, so the NFTs only
     * the stream listed are dropped at once, and a push from a connection that has ended never holds an entry
     * again.
     */
    void streamConnected(boolean connected) {
        Snapshot written;
        Set<AssetType> changed;
        synchronized (writeLock) {
            Snapshot previous = snapshot.get();
            written = merge(previous, List.of(), previous.lastRefresh(), previous.apiEntries(),
                    connected ? previous.streamNfts() : Set.of(), connected);
            changed = changedNfts(previous, written);
        }
        notifyChangeListeners(Source.STREAM, changed, written.streamConnected());
    }

    /**
     * ⛔ Under {@link #writeLock} only. Merges {@code incoming} into {@code previous} and publishes the result.
     *
     * <p><b>Merge:</b> an incoming entry replaces the held one for its NFT iff there is none or its
     * {@code feed().validFrom()} is STRICTLY greater; an equal one keeps the held entry. <b>Membership:</b> then
     * the merged map keeps exactly the API's last-load NFTs, plus the last push's NFTs while the stream is
     * connected.
     */
    private Snapshot merge(Snapshot previous, Collection<OracleEntry> incoming, Instant lastRefresh,
                           Map<AssetType, OracleEntry> apiEntries, Set<AssetType> streamNfts, boolean connected) {
        var merged = new HashMap<>(previous.byOracleToken());
        for (OracleEntry entry : incoming) {
            OracleEntry held = merged.get(entry.oracleToken());
            if (held == null || entry.feed().validFrom() > held.feed().validFrom()) {
                merged.put(entry.oracleToken(), entry);
            }
        }
        merged.keySet().removeIf(nft -> !apiEntries.containsKey(nft) && !(connected && streamNfts.contains(nft)));
        Snapshot written = derive(merged, lastRefresh, apiEntries, streamNfts, connected);
        snapshot.set(written);
        return written;
    }

    /**
     * The snapshot of a merged map: the per-token version lists (API-payload order for the NFTs the API listed,
     * then stream-only NFTs by unit) and, per token, the entry {@link #findEntry} returns. Pure.
     */
    private static Snapshot derive(Map<AssetType, OracleEntry> merged, Instant lastRefresh,
                           Map<AssetType, OracleEntry> apiEntries, Set<AssetType> streamNfts, boolean connected) {
        var order = new ArrayList<AssetType>();
        apiEntries.keySet().stream().filter(merged::containsKey).forEach(order::add);
        merged.keySet().stream().filter(nft -> !apiEntries.containsKey(nft))
                .sorted(java.util.Comparator.comparing(AssetType::toUnit))
                .forEach(order::add);
        var perToken = new LinkedHashMap<AssetType, List<OracleEntry>>();
        order.forEach(nft -> {
            OracleEntry entry = merged.get(nft);
            perToken.computeIfAbsent(entry.token(), k -> new ArrayList<>()).add(entry);
        });
        var versions = new HashMap<AssetType, List<OracleEntry>>();
        var tokens = new HashMap<AssetType, OracleEntry>();
        perToken.forEach((token, list) -> {
            versions.put(token, List.copyOf(list));
            tokens.put(token, list.stream().max(BY_VERSION).orElseThrow());
        });
        return new Snapshot(Map.copyOf(merged), Map.copyOf(tokens), Map.copyOf(versions), lastRefresh, apiEntries,
                Set.copyOf(apiEntries.keySet()), Set.copyOf(streamNfts), connected);
    }

    /** The NFTs one write added, removed or replaced. */
    private static Set<AssetType> changedNfts(Snapshot before, Snapshot after) {
        var changed = new HashSet<AssetType>();
        before.byOracleToken().forEach((nft, entry) -> {
            if (!entry.equals(after.byOracleToken().get(nft))) {
                changed.add(nft);
            }
        });
        after.byOracleToken().keySet().stream()
                .filter(nft -> !before.byOracleToken().containsKey(nft))
                .forEach(changed::add);
        return Set.copyOf(changed);
    }

    /** After the lock is released, on the writer's thread; each listener in its own catch, as the load listeners. */
    private void notifyChangeListeners(Source source, Set<AssetType> changed, boolean streamConnected) {
        if (changed.isEmpty()) {
            return;
        }
        for (ChangeListener listener : changeListeners) {
            try {
                listener.changed(source, changed, streamConnected);
            } catch (RuntimeException e) {
                log.warn("oracle change listener failed ({})", e.getClass().getSimpleName());
            }
        }
    }

    /** Each listener in its own catch: one that throws is named by its exception's class and skipped. */
    private void notifyLoadListeners(Collection<OracleEntry> entries, Instant loadedAt) {
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
