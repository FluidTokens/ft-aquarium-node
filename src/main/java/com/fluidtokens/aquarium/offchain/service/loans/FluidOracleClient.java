package com.fluidtokens.aquarium.offchain.service.loans;

import com.fasterxml.jackson.databind.JsonNode;
import com.fluidtokens.aquarium.offchain.model.AssetType;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
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

    private final AtomicReference<Map<AssetType, OraclePriceFeed>> byAsset = new AtomicReference<>(Map.of());

    private final AtomicReference<Instant> lastRefresh = new AtomicReference<>(Instant.EPOCH);

    public FluidOracleClient(@Value("${loans.oracle.url:https://api.fluidtokens.com/get-oracle-tokens}") String url) {
        this.webClient = WebClient.builder().baseUrl(url).build();
        log.info("FluidTokens oracle registry: {}", url);
    }

    @PostConstruct
    public void init() {
        refresh();
    }

    /**
     * Ada always prices 1:1 and needs no oracle — {@code retrieve_oracle_data} synthesises exactly
     * that feed when the asset's policy id is empty. Any other asset needs a live entry.
     */
    public Optional<OraclePriceFeed> findFeed(AssetType asset) {
        if (asset == null) {
            return Optional.empty();
        }
        if (asset.isAda()) {
            return Optional.of(OraclePriceFeed.unit());
        }
        return Optional.ofNullable(byAsset.get().get(asset));
    }

    public Instant lastRefresh() {
        return lastRefresh.get();
    }

    public int trackedAssets() {
        return byAsset.get().size();
    }

    /** The oracle validity window is ~10 minutes, so a 30s cadence is fresh enough and polite. */
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
            var next = new HashMap<AssetType, OraclePriceFeed>();
            body.forEach(entry -> parse(entry).ifPresent(p -> next.put(p.asset(), p.feed())));
            byAsset.set(Map.copyOf(next));
            lastRefresh.set(Instant.now());
            log.debug("FluidTokens oracle refreshed: {} priced assets", next.size());
        } catch (Exception e) {
            // transient failures self-heal on the next tick; one line, no stack trace
            log.warn("could not refresh the FluidTokens oracle ({}); retrying in 30s", e.toString());
            log.debug("oracle refresh failure", e);
        }
    }

    private record Priced(AssetType asset, OraclePriceFeed feed) {
    }

    private Optional<Priced> parse(JsonNode entry) {
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
            var feed = OraclePriceFeed.aggregated(
                    supported.path("tokenPriceInLovelaces").bigIntegerValue(),
                    denominator,
                    supported.path("validFrom").asLong(),
                    supported.path("validTo").asLong());
            return Optional.of(new Priced(asset, feed));
        } catch (Exception e) {
            log.warn("could not parse a FluidTokens oracle entry: {}", e.toString());
            return Optional.empty();
        }
    }

    /** Convenience for callers that only have the raw numbers. */
    static OraclePriceFeed feedOf(BigInteger price, BigInteger denominator, long validFrom, long validTo) {
        return OraclePriceFeed.aggregated(price, denominator, validFrom, validTo);
    }
}
