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
     * The price for an asset, or empty if there is none <em>or the one we hold is not usable at
     * {@code atMillis}</em>.
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
        return findFeedIgnoringValidity(asset).filter(feed -> feed.usableAt(atMillis));
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
        return Optional.ofNullable(byAsset.get().get(asset));
    }

    public Instant lastRefresh() {
        return lastRefresh.get();
    }

    public int trackedAssets() {
        return byAsset.get().size();
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

    /** Replaces the held prices with whatever parses out of a registry payload. */
    int load(JsonNode array) {
        var next = new HashMap<AssetType, OraclePriceFeed>();
        array.forEach(entry -> parse(entry).ifPresent(p -> next.put(p.asset(), p.feed())));
        byAsset.set(Map.copyOf(next));
        lastRefresh.set(Instant.now());
        return next.size();
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
            // The token travels inside the signed feed and is checked by is_feed_token_correct,
            // so it is carried verbatim rather than reconstructed later.
            var feed = OraclePriceFeed.aggregated(
                    asset,
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
    static OraclePriceFeed feedOf(AssetType token, BigInteger price, BigInteger denominator,
                                  long validFrom, long validTo) {
        return OraclePriceFeed.aggregated(token, price, denominator, validFrom, validTo);
    }
}
