package com.fluidtokens.aquarium.offchain.config;

import java.time.Clock;
import java.time.Duration;

import javax.sql.DataSource;

import com.fluidtokens.aquarium.offchain.service.loans.CardanoTokenRegistryClient;
import com.fluidtokens.aquarium.offchain.service.loans.TokenMetadataService;
import com.fluidtokens.aquarium.offchain.service.loans.TokenRegistryClient;
import com.fluidtokens.aquarium.offchain.storage.JdbcTokenMetadataStore;
import com.fluidtokens.aquarium.offchain.storage.TokenMetadataStore;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Token metadata, and <b>only when the operator UI is on</b>.
 *
 * <h2>⛔ The whole block is conditional, which is the requirement</h2>
 * Giovanni's requirement for this work was explicit: the backend may reach other APIs to enrich the
 * view, and <b>every one of those endpoints is switched off when the UI is off</b>. Gating the
 * {@code @Configuration} rather than each call means a node with the UI off constructs no client, no
 * store and no service — there is nothing present that could make an outbound request, rather than
 * something present that declines to. A node running with the UI off behaves exactly as it did
 * before this existed.
 *
 * <h2>The registry is an authorised dependency, and only this one</h2>
 * {@code tokens.cardano.org} was authorised for this purpose. The repo constitution's
 * allowed-dependencies clause still stands for everything else: the next external service is an
 * escalation, not a judgment call.
 */
@Configuration
@ConditionalOnProperty(prefix = "loans.ui", name = "enabled", havingValue = "true")
public class TokenMetadataConfig {

    @Bean
    public TokenRegistryClient tokenRegistryClient(
            @Value("${loans.ui.token-registry.url:https://tokens.cardano.org}") String url,
            @Value("${loans.ui.token-registry.timeout-seconds:5}") long timeoutSeconds) {
        return new CardanoTokenRegistryClient(url, Duration.ofSeconds(timeoutSeconds));
    }

    @Bean
    public TokenMetadataStore tokenMetadataStore(DataSource dataSource) {
        return new JdbcTokenMetadataStore(dataSource);
    }

    @Bean
    public TokenMetadataService tokenMetadataService(TokenMetadataStore store, TokenRegistryClient client) {
        return new TokenMetadataService(store, client, Clock.systemUTC());
    }
}
