package com.fluidtokens.aquarium.offchain.config;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.common.model.Networks;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class AccountConfig {

    @Bean
    public Account account(@Value("${wallet.mnemonic}") String mnemonic, @Value("${network}") String network) {

        var actualNetwork = switch (network) {
            case "preprod" -> Networks.preprod();
            case "preview" -> Networks.preview();
            default -> Networks.mainnet();
        };

        var account = new Account(actualNetwork, mnemonic);
        log.info("INIT - Using account with enterprise address: {}", account.enterpriseAddress());
        log.info("INIT - Using account with base address: {}", account.baseAddress());
        log.info("INIT - Using account with stake address: {}", account.stakeAddress());
        return account;

    }


}
