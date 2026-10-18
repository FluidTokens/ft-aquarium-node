package com.fluidtokens.aquarium.offchain.config;

import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class BlockfrostConfig {

    @Value("${blockfrost.url}")
    private String blockfrostUrl;

    /** ⛔ The only read of {@code blockfrost.key} in the node (FAB-141): every consumer is handed this bean. */
    @Bean
    public BlockfrostKey blockfrostKey(@Value("${blockfrost.key}") String raw) {
        return BlockfrostKey.of(raw);
    }

    @Bean
    public BFBackendService bfBackendService(BlockfrostKey blockfrostKey) {
        log.info("INIT - Using BF url: {}", blockfrostUrl);
        return new BFBackendService(blockfrostUrl, blockfrostKey.value());
    }

}
