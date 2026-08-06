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

        // Genesis one-shot UTxO that parameterises the `parameters` validator.
        // Together with the staking token (= FLDT policy/asset) this derives every
        // Aquarium script hash at startup (see ContractRegistry).
        @Value("${aquarium.genesis.tx-hash}")
        private String genesisTxHash;

        @Value("${aquarium.genesis.output-index}")
        private Integer genesisOutputIndex;

        @Value("${aquarium.tank.ref-input.txHash}")
        private String tankRefInputTxHash;

        @Value("${aquarium.tank.ref-input.outputIndex}")
        private Integer tankRefInputOutputIndex;

        public TransactionInput getTankRefInput() {
            return TransactionInput.builder().transactionId(tankRefInputTxHash).index(tankRefInputOutputIndex).build();
        }

    }


    /**
     * FluidTokens Lending v4 ("loans") derivation inputs. The whole v4 contract tree
     * hangs off two one-shot config NFT policy ids, so these three values are enough
     * to derive every script hash we need (see LoansContractRegistry).
     */
    @Component
    @Getter
    public static class LoansConfiguration {

        @Value("${loans.enabled:false}")
        private boolean enabled;

        /** Main config NFT policy id = hash of the applied {@code config(tx0, index0)} validator. */
        @Value("${loans.config.policy-id:}")
        private String configPolicyId;

        /** LenderManager config NFT policy id = hash of the applied {@code lm_config(tx0, index0)}. */
        @Value("${loans.lm-config.policy-id:}")
        private String lmConfigPolicyId;

        /**
         * Hex of "parameters". Hardcoded in lib/fluidtokens/constants.ak, so this is
         * effectively a constant — exposed only so a future contract revision does not
         * force a code change.
         */
        @Value("${loans.config.asset-name:706172616d6574657273}")
        private String configAssetName;

        /**
         * Not derivable from the blueprint (no smart_tokens validator is bundled); it is
         * published in the on-chain ConfigDatum. Optional: without it the pool-manager
         * branch of the derivation is skipped, which the liquidation path does not need.
         */
        @Value("${loans.smart-tokens-spend-script-hash:}")
        private String smartTokensSpendScriptHash;

        /** The tx that minted both config NFTs; the point history has to be indexed from. */
        @Value("${loans.config.ref-utxo-tx-hash:}")
        private String configRefUtxoTxHash;

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
