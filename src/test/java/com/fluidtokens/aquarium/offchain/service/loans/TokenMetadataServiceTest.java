package com.fluidtokens.aquarium.offchain.service.loans;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.fluidtokens.aquarium.offchain.model.TokenMetadata;
import com.fluidtokens.aquarium.offchain.storage.TokenMetadataStore;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cache policy, driven cold: no network, no database, no credential.
 *
 * <p>Everything here is a rule about <b>when this node is allowed to believe something</b>, and each
 * one has a failure that is invisible at a glance — a ticker that never refreshes, an outage recorded
 * as a fact, or a missing scale rendered as if it were zero.
 */
class TokenMetadataServiceTest {

    private static final String FLDT = "11".repeat(28) + "464c4454";

    /** An in-memory stand-in for the token_metadata table. */
    private static final class MapStore implements TokenMetadataStore {
        private final Map<String, Stored> rows = new HashMap<>();
        private int writes;

        @Override
        public Optional<Stored> find(String unit) {
            return Optional.ofNullable(rows.get(unit));
        }

        @Override
        public void put(TokenMetadata metadata, Instant fetchedAt) {
            writes++;
            rows.put(metadata.unit(), new Stored(metadata, fetchedAt));
        }
    }

    private static final class CountingClient implements TokenRegistryClient {
        private int calls;
        private final Optional<TokenMetadata> answer;
        private final RuntimeException failure;

        private CountingClient(Optional<TokenMetadata> answer, RuntimeException failure) {
            this.answer = answer;
            this.failure = failure;
        }

        static CountingClient answering(TokenMetadata metadata) {
            return new CountingClient(Optional.of(metadata), null);
        }

        static CountingClient notFound() {
            return new CountingClient(Optional.empty(), null);
        }

        static CountingClient failing() {
            return new CountingClient(null, new IllegalStateException("registry unreachable"));
        }

        @Override
        public Optional<TokenMetadata> fetch(String unit) {
            calls++;
            if (failure != null) {
                throw failure;
            }
            return answer;
        }
    }

    private static TokenMetadata fldt() {
        return new TokenMetadata(FLDT, "FLDT", "FluidTokens", 6, TokenMetadata.Source.REGISTRY);
    }

    private static Clock at(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    private static final Instant T0 = Instant.parse("2026-09-14T00:00:00Z");

    /** Ada is settled by definition; asking a registry could only produce a worse answer. */
    @Test
    void adaIsAnsweredWithoutTouchingTheRegistryOrTheStore() {
        CountingClient client = CountingClient.notFound();
        MapStore store = new MapStore();

        TokenMetadata ada = new TokenMetadataService(store, client, at(T0)).lookup("lovelace");

        assertEquals(6, ada.decimals());
        assertEquals("ADA", ada.ticker());
        assertEquals(0, client.calls, "ada must never cost a network call");
        assertEquals(0, store.writes, "ada must never occupy a cache row");
    }

    /** A hit is fetched once and then served from the cache for 24 hours. */
    @Test
    void aHitIsFetchedOnceAndServedFromTheCacheWithinTwentyFourHours() {
        CountingClient client = CountingClient.answering(fldt());
        MapStore store = new MapStore();

        assertEquals("FLDT", new TokenMetadataService(store, client, at(T0)).lookup(FLDT).ticker());
        assertEquals("FLDT", new TokenMetadataService(store, client,
                at(T0.plus(Duration.ofHours(23)))).lookup(FLDT).ticker());

        assertEquals(1, client.calls, "the second read, 23h later, must come from the cache");
    }

    /** ...and re-asked once that expires. A ticker cached forever is a ticker nothing can correct. */
    @Test
    void aHitIsReFetchedAfterTwentyFourHours() {
        CountingClient client = CountingClient.answering(fldt());
        MapStore store = new MapStore();

        new TokenMetadataService(store, client, at(T0)).lookup(FLDT);
        new TokenMetadataService(store, client, at(T0.plus(Duration.ofHours(25)))).lookup(FLDT);

        assertEquals(2, client.calls, "past the hit TTL the registry must be asked again");
    }

    /**
     * ⛔ A miss is cached, and with NULL decimals. The negative TTL is the deliberate part: without it
     * every render re-asks about every unknown asset; with it, a newly listed token still appears the
     * same day.
     */
    @Test
    void aMissIsCachedAsUnknownWithNoDecimalsAndReAskedAfterOneHour() {
        CountingClient client = CountingClient.notFound();
        MapStore store = new MapStore();

        TokenMetadata first = new TokenMetadataService(store, client, at(T0)).lookup(FLDT);
        assertEquals(TokenMetadata.Source.UNKNOWN, first.source());
        assertNull(first.decimals(), "an unknown asset must carry NO scale, never zero");

        new TokenMetadataService(store, client, at(T0.plus(Duration.ofMinutes(30)))).lookup(FLDT);
        assertEquals(1, client.calls, "within the negative TTL the miss is served from the cache");

        new TokenMetadataService(store, client, at(T0.plus(Duration.ofMinutes(61)))).lookup(FLDT);
        assertEquals(2, client.calls, "past the negative TTL the registry is asked again");
    }

    /**
     * ⛔ <b>THE CONTROL THAT MATTERS MOST.</b> "Could not ask" is not "does not exist". Recording an
     * outage as a miss would pin a wrong label — and a missing scale — for the whole negative TTL,
     * across every render, for an asset the registry knows perfectly well.
     */
    @Test
    void anOutageIsNeverRecordedAsANotFound() {
        MapStore store = new MapStore();

        TokenMetadata result = new TokenMetadataService(store, CountingClient.failing(), at(T0)).lookup(FLDT);

        assertEquals(TokenMetadata.Source.UNKNOWN, result.source(), "the page still needs an answer");
        assertEquals(0, store.writes, "but an outage must NOT be written to the cache");
        assertTrue(store.find(FLDT).isEmpty(), "nothing may be pinned by a failure to ask");
    }

    /** During an outage a previously good answer is better than none, even past its TTL. */
    @Test
    void anOutageServesTheStaleAnswerRatherThanForgettingIt() {
        MapStore store = new MapStore();
        new TokenMetadataService(store, CountingClient.answering(fldt()), at(T0)).lookup(FLDT);

        TokenMetadata stale = new TokenMetadataService(store, CountingClient.failing(),
                at(T0.plus(Duration.ofDays(30)))).lookup(FLDT);

        assertEquals("FLDT", stale.ticker(), "a month-old ticker beats no ticker when nobody can be asked");
        assertEquals(6, stale.decimals());
    }
}
