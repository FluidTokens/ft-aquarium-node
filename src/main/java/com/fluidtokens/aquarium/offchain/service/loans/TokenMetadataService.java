package com.fluidtokens.aquarium.offchain.service.loans;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import com.fluidtokens.aquarium.offchain.model.TokenMetadata;
import com.fluidtokens.aquarium.offchain.storage.TokenMetadataStore;

import lombok.extern.slf4j.Slf4j;

/**
 * Ticker, name and decimals for a native asset, fetched once and kept locally.
 *
 * <h2>Why the cache has two TTLs</h2>
 * Tickers and decimals effectively never change, so a <b>24 hour</b> hit costs nothing and survives a
 * registry outage. A <b>1 hour</b> negative TTL is the deliberate part: a newly listed token should
 * appear the same day without this node re-asking about an unknown asset on every single render.
 *
 * <h2>⛔ What must never happen</h2>
 * An amount rendered at the wrong scale is wrong by a power of ten and looks like a working number.
 * So a registry miss yields {@link TokenMetadata.Source#UNKNOWN} with <b>null decimals</b>, never
 * zero, and callers render the raw base-unit amount. <b>Assuming zero silently mis-scales by up to a
 * million.</b>
 *
 * <h2>A transport failure is not a "not found"</h2>
 * {@link TokenRegistryClient#fetch} throws when it could not ask. This class then serves whatever it
 * already had — even if stale — and failing that returns UNKNOWN <b>without writing it</b>, so an
 * outage cannot pin a wrong label for an hour.
 */
@Slf4j
public class TokenMetadataService {

    static final Duration HIT_TTL = Duration.ofHours(24);
    static final Duration MISS_TTL = Duration.ofHours(1);

    private final TokenMetadataStore store;
    private final TokenRegistryClient client;
    private final Clock clock;

    public TokenMetadataService(TokenMetadataStore store, TokenRegistryClient client, Clock clock) {
        this.store = store;
        this.client = client;
        this.clock = clock;
    }

    /**
     * @param unit policy id + asset name in hex, or {@code "lovelace"}
     */
    public TokenMetadata lookup(String unit) {
        if (unit == null || unit.isBlank()) {
            return TokenMetadata.unknown("");
        }
        // Ada needs nobody's opinion: 6 decimals by definition, and asking a registry about it would
        // be a network call that can only produce a worse answer.
        if ("lovelace".equals(unit)) {
            return TokenMetadata.ada();
        }

        Optional<TokenMetadataStore.Stored> cached = store.find(unit);
        if (cached.isPresent() && isFresh(cached.get())) {
            return cached.get().metadata();
        }

        try {
            Optional<TokenMetadata> fetched = client.fetch(unit);
            TokenMetadata resolved = fetched.orElseGet(() -> TokenMetadata.unknown(unit));
            store.put(resolved, clock.instant());
            return resolved;
        } catch (RuntimeException e) {
            // Could not ask. Serve what we have, however old, rather than record a wrong answer.
            log.debug("token metadata lookup failed for {}: {}", unit, e.toString());
            return cached.map(TokenMetadataStore.Stored::metadata).orElseGet(() -> TokenMetadata.unknown(unit));
        }
    }

    private boolean isFresh(TokenMetadataStore.Stored stored) {
        Duration ttl = stored.metadata().source() == TokenMetadata.Source.UNKNOWN ? MISS_TTL : HIT_TTL;
        return clock.instant().isBefore(stored.fetchedAt().plus(ttl));
    }
}
