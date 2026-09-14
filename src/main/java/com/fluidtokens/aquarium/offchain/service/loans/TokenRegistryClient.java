package com.fluidtokens.aquarium.offchain.service.loans;

import java.util.Optional;

import com.fluidtokens.aquarium.offchain.model.TokenMetadata;

/**
 * Resolves one asset's display metadata from outside this node.
 *
 * <p>An interface rather than a class so the lookup policy in {@link TokenMetadataService} — the
 * cache, the TTLs, the not-found path — can be proven cold, with no network and no credential. The
 * HTTP implementation is the only part that cannot be, and it is deliberately the thinnest part.
 */
public interface TokenRegistryClient {

    /**
     * @return the asset's metadata, or empty when the registry answers "no such subject". Implementations
     *         throw on a transport failure rather than returning empty: <b>"not found" and "could not
     *         ask" are different answers</b>, and caching the second as the first would pin a wrong
     *         label for as long as the TTL lasts.
     */
    Optional<TokenMetadata> fetch(String unit);
}
