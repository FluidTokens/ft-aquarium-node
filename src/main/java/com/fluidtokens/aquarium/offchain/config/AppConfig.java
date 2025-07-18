package com.fluidtokens.aquarium.offchain.config;

import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.conversions.CardanoConverters;
import org.cardanofoundation.conversions.ClasspathConversionsFactory;
import org.cardanofoundation.conversions.domain.NetworkType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;

@Configuration
@EnableScheduling
@Slf4j
public class AppConfig {

    @Component
    @Getter
    public static class Network {

        @Value("${network}")
        private String network;

        public com.bloxbean.cardano.client.common.model.Network getCardanoNetwork() {
            return switch (network) {
                case "preprod" -> Networks.preprod();
                case "preview" -> Networks.preview();
                default -> Networks.mainnet();
            };
        }

    }

    @Component
    @Getter
    public static class AquariumConfiguration {

        @Value("${aquarium.staking.token.policy}")
        private String stakingTokenPolicy;

        @Value("${aquarium.staking.token.name}")
        private String stakingTokenName;

        @Value("${aquarium.tank.ref-input.txHash}")
        private String tankRefInputTxHash;

        @Value("${aquarium.tank.ref-input.outputIndex}")
        private Integer tankRefInputOutputIndex;

        public TransactionInput getTankRefInput() {
            return TransactionInput.builder().transactionId(tankRefInputTxHash).index(tankRefInputOutputIndex).build();
        }

    }

    @Bean
    public CardanoConverters cardanoConverters(@Value("${network}") String network) {
        var networkType = switch (network) {
            case "preprod" -> NetworkType.PREPROD;
            case "preview" -> NetworkType.PREVIEW;
            default -> NetworkType.MAINNET;
        };

        log.info("INIT Converters network: {}, network type: {}", network, networkType);

        return ClasspathConversionsFactory.createConverters(networkType);
    }

}
