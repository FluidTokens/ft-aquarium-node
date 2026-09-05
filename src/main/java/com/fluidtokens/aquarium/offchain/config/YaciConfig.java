package com.fluidtokens.aquarium.offchain.config;

import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.backend.api.DefaultProtocolParamsSupplier;
import com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import com.fluidtokens.aquarium.offchain.service.loans.LiquidatePayInAdvanceTransactionBuilder;
import com.fluidtokens.aquarium.offchain.service.loans.CompoundTransactionBuilder;
import com.fluidtokens.aquarium.offchain.service.loans.ConvertEconomics;
import com.fluidtokens.aquarium.offchain.service.loans.ConvertLiquidationRouter;
import com.fluidtokens.aquarium.offchain.service.loans.ConvertTransactionBuilder;
import com.fluidtokens.aquarium.offchain.service.loans.MinswapPoolResolver;
import com.fluidtokens.aquarium.offchain.service.loans.LiquidateTransactionBuilder;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.conversions.CardanoConverters;
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
    public UtxoSupplier utxoSupplier(BFBackendService bfBackendService) {
        return new DefaultUtxoSupplier(bfBackendService.getUtxoService());
    }

    @Bean
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

    /**
     * The compound builder, wired exactly as {@code liquidateTransactionBuilder} above and for the
     * same reason (findings §20, §22).
     *
     * <p>⛔ <b>Without this bean the node does not start</b>: {@code CompoundExecutor} requires it, and
     * {@code CompoundExecutor} exists whenever {@code loans.enabled=true} — which is the preview
     * default. A builder that only ever existed in tests would have failed context startup on exactly
     * the deployment operators run, while passing every test (the third-site hazard of CCL trap 9b,
     * one layer up).
     *
     * <p>The evaluator is Blockfrost's {@code /utils/txs/evaluate}, narrowed to a lambda so what the
     * builder holds can price a transaction and nothing else. <b>The operator's whole risk case for
     * arming this path — "exposure is the transaction fee per execution" — is true only while the
     * ex-units are measured</b>: placeholder ex-units move the exposure to the collateral (CCL trap 8).
     */
    @Bean
    public CompoundTransactionBuilder compoundTransactionBuilder(LoansContractRegistry registry,
                                                                 AppConfig.Network network,
                                                                 UtxoSupplier utxoSupplier,
                                                                 ProtocolParamsSupplier protocolParamsSupplier,
                                                                 BFBackendService bfBackendService) {
        TransactionEvaluator scriptCostEvaluator =
                (cbor, inputUtxos) -> bfBackendService.getTransactionService().evaluateTx(cbor);
        return new CompoundTransactionBuilder(registry, network.getCardanoNetwork(), bfBackendService,
                utxoSupplier, protocolParamsSupplier, scriptCostEvaluator);
    }

    /**
     * The Minswap pool resolver — how a convert finds the ONE pool for a loan's pair.
     *
     * <p>⛔ <b>It queries the provider; it does NOT read the node's index, and no index is needed.</b>
     * The LP asset name is <em>computable</em> from the pair (SHA3-256, twice — findings §34), so this
     * asks for one specific asset rather than searching: {@code /addresses/{poolAddress}/utxos/{lpUnit}}
     * returns exactly one row. Indexing Minswap instead would pull every V2 pool on the network into
     * this node's storage and need a far-back {@code sync-start} (§39.2).
     */
    @Bean
    public MinswapPoolResolver minswapPoolResolver(AppConfig.LoansConfiguration loansConfiguration,
                                                   BFBackendService bfBackendService) {
        return new MinswapPoolResolver(bfBackendService.getUtxoService(),
                loansConfiguration.getMinswapPoolAddress(),
                loansConfiguration.getMinswapPoolPolicyId());
    }

    /**
     * The convert builder. Wired exactly as its siblings above, and for the same reason: <b>the
     * operator's whole risk case for this path — "exposure is the transaction fee per execution" — is
     * true only while the ex-units are MEASURED.</b> Placeholder ex-units move the exposure to the
     * collateral (CCL trap 8), and this class has no constructor that permits them.
     */
    @Bean
    public ConvertTransactionBuilder convertTransactionBuilder(LoansContractRegistry registry,
                                                               AppConfig.Network network,
                                                               UtxoSupplier utxoSupplier,
                                                               ProtocolParamsSupplier protocolParamsSupplier,
                                                               BFBackendService bfBackendService) {
        TransactionEvaluator scriptCostEvaluator =
                (cbor, inputUtxos) -> bfBackendService.getTransactionService().evaluateTx(cbor);
        return new ConvertTransactionBuilder(registry, network.getCardanoNetwork(), bfBackendService,
                utxoSupplier, protocolParamsSupplier, scriptCostEvaluator);
    }

    /**
     * The convert seam the executor routes to when a market's {@code action} is {@code CONVERT}.
     *
     * <p>⛔ <b>Its ABSENCE is a named refusal, not a fallback</b> — {@code LiquidationExecutor} records
     * {@code CONVERT_UNAVAILABLE} rather than quietly routing the candidate to pay-in-advance, which
     * would front the operator's own capital on a loan they configured to convert. So a node that
     * cannot derive the convert action (its {@code loans.minswap.*} belonging to another network) is
     * safe by construction.
     *
     * <p>⚠ <b>This bean is only conditional on {@code loans.enabled}, deliberately, and NOT on the
     * convert action being derivable.</b> Making its existence depend on the derivation would turn a
     * legible refusal into a missing bean, and this project has already learned what an unwired
     * component costs: image {@code lending-v4-588d318} crash-looped in fourteen seconds because a
     * collaborator Spring could not construct was found at startup rather than by a test
     * ({@code ExecutorContextResolutionTest}).
     */
    @Bean
    public ConvertLiquidationRouter convertLiquidationRouter(LoansContractRegistry registry,
                                                             AppConfig.LoansConfiguration loansConfiguration,
                                                             MinswapPoolResolver minswapPoolResolver,
                                                             ConvertEconomics convertEconomics,
                                                             ConvertTransactionBuilder convertTransactionBuilder,
                                                             AppConfig.LiquidationConfiguration liquidationConfiguration,
                                                             AppConfig.Network network) {
        return new ConvertLiquidationRouter(registry, loansConfiguration, liquidationConfiguration,
                minswapPoolResolver, convertEconomics, convertTransactionBuilder,
                network.getCardanoNetwork());
    }

    /**
     * The pay-in-advance liquidation builder, with a real script-cost evaluator — the convert-path
     * mirror of {@code liquidateTransactionBuilder} above (T-014).
     * <p>
     * Without an evaluator, cardano-client-lib leaves every redeemer holding placeholder ex-units, and a
     * transaction that under-declares is not rejected by the mempool: it lands and then fails on chain,
     * forfeiting the collateral. So this path is given the same Blockfrost {@code /utils/txs/evaluate}
     * evaluator the plain builder's bean uses — its protocol parameters and cost models are the chain's
     * by construction — narrowed to the one-method {@link TransactionEvaluator} so the builder can price
     * a transaction and nothing else. And, exactly as the plain builder, the builder is constructed from
     * the {@code BFBackendService}: its {@code QuickTxBuilder} needs the backend's script supplier to
     * fetch a validator travelling as a reference script (the oracle script, and on preview
     * {@code loan_claim_action}) so the transaction can be priced and feed correctly. Holding a backend
     * does not reopen submission — nothing in the builder calls {@code submit}; the routing seam
     * ({@code PayInAdvanceLiquidationRouter}) only ever invokes {@code build(Request)}, and arming and
     * submission stay in {@code LiquidationExecutor} behind its two independent flags.
     */
    @Bean
    public LiquidatePayInAdvanceTransactionBuilder liquidatePayInAdvanceTransactionBuilder(
            LoansContractRegistry registry,
            AppConfig.Network network,
            BFBackendService bfBackendService) {
        TransactionEvaluator scriptCostEvaluator =
                (cbor, inputUtxos) -> bfBackendService.getTransactionService().evaluateTx(cbor);
        return new LiquidatePayInAdvanceTransactionBuilder(registry, network.getCardanoNetwork(),
                bfBackendService, scriptCostEvaluator);
    }

}
