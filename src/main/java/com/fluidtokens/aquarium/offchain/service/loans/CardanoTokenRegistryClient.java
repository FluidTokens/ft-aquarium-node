package com.fluidtokens.aquarium.offchain.service.loans;

import java.time.Duration;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fluidtokens.aquarium.offchain.model.TokenMetadata;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * The Cardano Token Registry ({@code tokens.cardano.org}), read over HTTP.
 *
 * <h2>Why this registry, and why first</h2>
 * It is the curated source wallets and explorers already display, so a ticker shown here matches what
 * the operator sees everywhere else. <b>A ticker that disagrees with their wallet is worse than no
 * ticker.</b> CIP-68 on-chain metadata is authoritative where it exists but adoption is partial, so
 * it belongs behind this rather than in front of it.
 *
 * <h2>⛔ A 404 is an answer; everything else is an outage</h2>
 * The registry says "no such subject" with a 404. That is a real, cacheable answer and comes back as
 * {@link Optional#empty()}. Every other failure — 5xx, a timeout, a transport error — <b>throws</b>,
 * because {@link TokenMetadataService} must not record "could not ask" as "does not exist" and pin a
 * wrong label for an hour. The same rule the config verifier and the pool resolver already follow.
 */
@Slf4j
public class CardanoTokenRegistryClient implements TokenRegistryClient {

    private final WebClient webClient;
    private final Duration timeout;

    public CardanoTokenRegistryClient(String baseUrl, Duration timeout) {
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
        this.timeout = timeout;
    }

    @Override
    public Optional<TokenMetadata> fetch(String unit) {
        try {
            JsonNode body = webClient.get()
                    .uri("/metadata/{subject}", unit)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(timeout)
                    .block();
            if (body == null || body.isNull()) {
                return Optional.empty();
            }
            return Optional.of(parse(unit, body));
        } catch (WebClientResponseException.NotFound e) {
            // The registry's way of saying the asset is not listed. An answer, not a failure.
            return Optional.empty();
        }
    }

    /**
     * The registry wraps each field as {@code {"value": ...}} with a signature alongside. A field that
     * is absent stays null rather than becoming a default — particularly {@code decimals}, where a
     * default of zero would mis-scale every amount of that asset.
     */
    static TokenMetadata parse(String unit, JsonNode body) {
        return new TokenMetadata(unit,
                text(body, "ticker"),
                text(body, "name"),
                integer(body, "decimals"),
                TokenMetadata.Source.REGISTRY);
    }

    private static String text(JsonNode body, String field) {
        JsonNode value = body.path(field).path("value");
        return value.isTextual() ? value.asText() : null;
    }

    private static Integer integer(JsonNode body, String field) {
        JsonNode value = body.path(field).path("value");
        return value.isIntegralNumber() ? value.asInt() : null;
    }
}
