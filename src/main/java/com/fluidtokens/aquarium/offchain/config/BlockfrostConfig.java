package com.fluidtokens.aquarium.offchain.config;

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
    public BoundedBFBackendService bfBackendService(
            BlockfrostKey blockfrostKey,
            @Value("${blockfrost.timeout.connect-seconds:5}") long connectSeconds,
            @Value("${blockfrost.timeout.read-seconds:30}") long readSeconds,
            @Value("${blockfrost.timeout.write-seconds:30}") long writeSeconds,
            @Value("${blockfrost.timeout.discovery-call-seconds:12}") long discoverySeconds,
            @Value("${blockfrost.timeout.evaluation-call-seconds:30}") long evaluationSeconds,
            @Value("${blockfrost.timeout.submission-call-seconds:30}") long submissionSeconds,
            @Value("${wallet-coordinator.pass-timeout-seconds:120}") long passSeconds) {
        log.info("INIT - Using BF url: {}", blockfrostUrl);
        return new BoundedBFBackendService(blockfrostUrl, blockfrostKey,
                connectSeconds, readSeconds, writeSeconds,
                discoverySeconds, evaluationSeconds, submissionSeconds, passSeconds);
    }

}
