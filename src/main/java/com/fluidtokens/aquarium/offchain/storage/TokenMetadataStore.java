package com.fluidtokens.aquarium.offchain.storage;

import java.time.Instant;
import java.util.Optional;

import com.fluidtokens.aquarium.offchain.model.TokenMetadata;

/**
 * Where resolved token metadata is kept between renders.
 *
 * <p>Narrow on purpose. The cache policy — two TTLs, the not-found path, what an outage must not
 * overwrite — is the part that can be got wrong in a way that mis-scales money, so it lives in
 * {@link com.fluidtokens.aquarium.offchain.service.loans.TokenMetadataService} and is proven against
 * an in-memory store with a controllable clock. A {@code JpaRepository} cannot be driven that way
 * without a database, and a rule that can only be exercised with one is a rule nothing checks.
 */
public interface TokenMetadataStore {

    record Stored(TokenMetadata metadata, Instant fetchedAt) {
    }

    Optional<Stored> find(String unit);

    void put(TokenMetadata metadata, Instant fetchedAt);
}
