package com.fluidtokens.aquarium.offchain.config;

import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.TransactionEvaluator;
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

    /**
     * The production builder, with a real script-cost evaluator.
     * <p>
     * Without one, cardano-client-lib leaves every redeemer holding placeholder ex-units — 10000 mem
     * against a measured 2.26M for one ada/ada liquidation — and a transaction that under-declares is
     * not rejected by the mempool: it lands and then fails on chain, forfeiting the collateral. So the
     * armed path has to be given an evaluator, and Blockfrost's {@code /utils/txs/evaluate} is the one
     * to give it: it evaluates against the chain's own protocol parameters and cost models, so the
     * question "is our pinned cost model still the chain's?" cannot arise, and it resolves the
     * transaction's inputs itself because in production they are real on-chain UTxOs.
     * <p>
     * The lambda is the narrowing, exactly as {@code LiquidationExecutor}'s {@code TransactionSubmitter}
     * is: {@link TransactionEvaluator} declares one operation and no submit method, so what the builder
     * holds can price a transaction and nothing else. Handing it the {@code BFBackendService}, or the
     * {@code DefaultTransactionProcessor} that also implements this interface, would hand it a
     * submission path through the back door.
     */
    @Bean
    @ConditionalOnProperty(prefix = "loans", name = "enabled", havingValue = "true")
    public LiquidateTransactionBuilder liquidateTransactionBuilder(LoansContractRegistry registry,
                                                                   AppConfig.Network network,
                                                                   CardanoConverters cardanoConverters,
                                                                   UtxoSupplier utxoSupplier,
                                                                   ProtocolParamsSupplier protocolParamsSupplier,
                                                                   BFBackendService bfBackendService) {
        TransactionEvaluator scriptCostEvaluator =
                (cbor, inputUtxos) -> bfBackendService.getTransactionService().evaluateTx(cbor);
        // The whole BackendService, as the library documents: QuickTxBuilder wires its utxo
        // supplier, protocol params, script supplier and transaction processor from it in one
        // constructor. The script supplier is the part that matters now — a validator travelling
        // as a reference script has to be fetchable by hash for the transaction to be priced.
        return new LiquidateTransactionBuilder(registry, network.getCardanoNetwork(), cardanoConverters,
                bfBackendService, scriptCostEvaluator);
    }

}
