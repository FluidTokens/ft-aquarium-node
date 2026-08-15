package com.fluidtokens.aquarium.offchain.config;

import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.backend.api.DefaultProtocolParamsSupplier;
import com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import com.fluidtokens.aquarium.offchain.service.loans.LiquidateTransactionBuilder;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.conversions.CardanoConverters;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class YaciConfig {

    @Bean
    public QuickTxBuilder quickTxBuilder(BFBackendService bfBackendService) {
        return new QuickTxBuilder(bfBackendService);
    }

    /**
     * The two suppliers {@link LiquidateTransactionBuilder} needs, taken off the same Blockfrost
     * backend the rest of the node uses.
     * <p>
     * Deliberately narrower than handing the builder a {@code BackendService}: a supplier can answer
     * "what is at this address" and "what are the protocol params", and nothing else — in particular
     * it cannot submit. That is what keeps the builder's "never takes a BackendService, never
     * submits" property a matter of wiring rather than of discipline.
     */
    @Bean
    @ConditionalOnProperty(prefix = "loans", name = "enabled", havingValue = "true")
    public UtxoSupplier utxoSupplier(BFBackendService bfBackendService) {
        return new DefaultUtxoSupplier(bfBackendService.getUtxoService());
    }

    @Bean
    @ConditionalOnProperty(prefix = "loans", name = "enabled", havingValue = "true")
    public ProtocolParamsSupplier protocolParamsSupplier(BFBackendService bfBackendService) {
        return new DefaultProtocolParamsSupplier(bfBackendService.getEpochService());
    }

    @Bean
    @ConditionalOnProperty(prefix = "loans", name = "enabled", havingValue = "true")
    public LiquidateTransactionBuilder liquidateTransactionBuilder(LoansContractRegistry registry,
                                                                   AppConfig.Network network,
                                                                   CardanoConverters cardanoConverters,
                                                                   UtxoSupplier utxoSupplier,
                                                                   ProtocolParamsSupplier protocolParamsSupplier) {
        return new LiquidateTransactionBuilder(registry, network.getCardanoNetwork(), cardanoConverters,
                utxoSupplier, protocolParamsSupplier);
    }

}
