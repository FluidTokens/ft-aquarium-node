package com.fluidtokens.aquarium.offchain.config;

import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.ScriptSupplier;
import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.backend.api.DefaultProtocolParamsSupplier;
import com.bloxbean.cardano.client.backend.api.DefaultScriptSupplier;
import com.bloxbean.cardano.client.backend.api.DefaultTransactionProcessor;
import com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import com.fluidtokens.aquarium.offchain.service.loans.LiquidatePayInAdvanceTransactionBuilder;
import com.fluidtokens.aquarium.offchain.service.loans.CompoundTransactionBuilder;
import com.fluidtokens.aquarium.offchain.service.loans.ConvertEconomics;
import com.fluidtokens.aquarium.offchain.service.loans.ConvertLiquidationRouter;
import com.fluidtokens.aquarium.offchain.service.loans.ConvertTransactionBuilder;
import com.fluidtokens.aquarium.offchain.service.loans.MinswapPoolResolver;
import com.fluidtokens.aquarium.offchain.service.loans.LiquidateTransactionBuilder;
import com.fluidtokens.aquarium.offchain.storage.IndexFirstUtxoSupplier;
import com.fluidtokens.aquarium.offchain.storage.TankUtxoStorage;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.conversions.CardanoConverters;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@Slf4j
public class YaciConfig {

    /**
     * The tank processor's builder ({@code ScheduledTransactionService}, by injection — the one
     * {@code QuickTxBuilder} in this node with no {@code new} at its call site). FAB-134 B3b-5: built from
     * the three injected suppliers below and Blockfrost's transaction processor, and from nothing else.
     * <ul>
     *   <li>{@link UtxoSupplier} — the index-first bean: coin selection, the pinned collateral and the
     *       parameters and staker reference inputs come from the local index; only out-refs the index
     *       cannot hold (the tank reference script) reach Blockfrost.</li>
     *   <li>{@link ProtocolParamsSupplier} — the per-epoch bean, not a fetch per build.</li>
     *   <li>{@link ScriptSupplier} — the hash-checked bean, so the tank's reference script is priced from
     *       bytes fetched once rather than on every build.</li>
     * </ul>
     * <p>
     * ⛔ <b>The processor is there to SUBMIT AND TO EVALUATE, and is never null.</b> Unlike the loans
     * builders, the tank path submits ({@code context.complete()}), so it needs a processor; and
     * cardano-client-lib uses that same slot as the default script-cost evaluator (CCL trap 8). A null
     * here would not make the tank "safely submit-incapable": it would build every tank transaction on
     * placeholder ex-units. {@link DefaultTransactionProcessor} is both Blockfrost's
     * {@code /tx/submit} and its {@code /utils/txs/evaluate} — the two Blockfrost calls a tank build
     * still makes by design.
     * <p>
     * Never {@code new QuickTxBuilder(bfBackendService)}: that form builds its own Blockfrost UTxO,
     * protocol-params and script suppliers and bypasses all three beans ({@code BlockfrostBuildWiringGuardTest}).
     */
    @Bean
    public QuickTxBuilder quickTxBuilder(UtxoSupplier utxoSupplier,
                                         ProtocolParamsSupplier protocolParamsSupplier,
                                         ScriptSupplier scriptSupplier,
                                         BFBackendService bfBackendService) {
        return new QuickTxBuilder(utxoSupplier, protocolParamsSupplier, scriptSupplier,
                new DefaultTransactionProcessor(bfBackendService.getTransactionService()));
    }

    /**
     * The node's {@link UtxoSupplier}: the local Yaci index first (FAB-134 B3a), see
     * {@link IndexFirstUtxoSupplier}.
     * <ul>
     *   <li>UTxOs at an address — coin selection — come ONLY from the index, for the payment credentials
     *       {@link TankUtxoStorage#indexedPaymentCredentials()} watches; any other address is refused,
     *       and an empty answer is never topped up from Blockfrost.</li>
     *   <li>One output by out-ref comes from the index when it holds the row with a faithful
     *       reference-script hash, and otherwise from Blockfrost's {@code UtxoService} — one direct read
     *       per miss, nothing cached. That covers the reference inputs this node does not index (oracle
     *       feeds and scripts, FluidTokens-published reference scripts).</li>
     * </ul>
     * <p>
     * Deliberately narrower than handing a builder a {@code BackendService}: a supplier can answer
     * "what is at this address" and "what is at this out-ref", and nothing else — in particular it
     * cannot submit. That keeps a builder's "never submits" property a matter of wiring rather than of
     * discipline.
     */
    @Bean
    public UtxoSupplier utxoSupplier(UtxoRepository utxoRepository,
                                     TankUtxoStorage tankUtxoStorage,
                                     BFBackendService bfBackendService) {
        return new IndexFirstUtxoSupplier(utxoRepository, tankUtxoStorage::indexedPaymentCredentials,
                new DefaultUtxoSupplier(bfBackendService.getUtxoService()));
    }

    /**
     * Blockfrost's protocol parameters, fetched once at boot and then once per epoch rather than on
     * every call (see {@link EpochProtocolParamsSupplier}). Every injection point of this bean gets the
     * cached supplier; the values it serves are still only ever the chain's own.
     */
    @Bean
    public ProtocolParamsSupplier protocolParamsSupplier(BFBackendService bfBackendService,
                                                         CardanoConverters cardanoConverters) {
        return new EpochProtocolParamsSupplier(
                new DefaultProtocolParamsSupplier(bfBackendService.getEpochService()),
                cardanoConverters, Clock.systemUTC());
    }

    /**
     * The script bytes a reference input publishes, by hash — fetched from Blockfrost ONCE per hash,
     * checked to hash to what was asked for, and served from memory after that (see
     * {@link HashCheckedScriptSupplier} for why a by-hash memo is not a cache of chain state).
     * <p>
     * cardano-client-lib needs the bytes, not just the {@code referenceScriptHash} a UTxO carries, to
     * charge the Conway reference-script fee; without them it charges zero and the ledger rejects the
     * transaction at phase 1 (CCL trap 9). This supplier never answers empty for a real hash.
     */
    @Bean
    public ScriptSupplier scriptSupplier(BFBackendService bfBackendService) {
        return new HashCheckedScriptSupplier(new DefaultScriptSupplier(bfBackendService.getScriptService()));
    }

    /**
     * The production liquidation builder (FAB-134 B3b): built from the three injected suppliers and a
     * real script-cost evaluator, and holding nothing else.
     * <ul>
     *   <li>{@link UtxoSupplier} — the index-first bean above: coin selection, collateral and every
     *       indexed reference input come from the local index; only out-refs the index cannot hold
     *       reach Blockfrost.</li>
     *   <li>{@link ProtocolParamsSupplier} — the per-epoch bean above, not a fetch per build.</li>
     *   <li>{@link ScriptSupplier} — the bean above, so every referenced script is priced, the
     *       oracle's included.</li>
     * </ul>
     * <p>
     * Without an evaluator, cardano-client-lib leaves every redeemer holding placeholder ex-units — 10000
     * mem against a measured 2.26M for one ada/ada liquidation — and a transaction that under-declares
     * is not rejected by the mempool: it lands and then fails on chain, forfeiting the collateral. So the
     * armed path has to be given an evaluator, and Blockfrost's {@code /utils/txs/evaluate} is the one
     * to give it: it evaluates against the chain's own protocol parameters and cost models, so the
     * question "is our pinned cost model still the chain's?" cannot arise, and it resolves the
     * transaction's inputs itself because in production they are real on-chain UTxOs. It is the one
     * Blockfrost call a build still makes (with {@code getTxOutput} for reference inputs the index does
     * not hold).
     * <p>
     * The lambda is the narrowing, exactly as {@code LiquidationExecutor}'s {@code TransactionSubmitter}
     * is: {@link TransactionEvaluator} declares one operation and no submit method, so what the builder
     * holds can price a transaction and nothing else. The builder is handed no {@code BFBackendService}
     * and no {@code DefaultTransactionProcessor} — either would hand it a submission path through the
     * back door.
     */
    @Bean
    public LiquidateTransactionBuilder liquidateTransactionBuilder(LoansContractRegistry registry,
                                                                   AppConfig.Network network,
                                                                   CardanoConverters cardanoConverters,
                                                                   UtxoSupplier utxoSupplier,
                                                                   ProtocolParamsSupplier protocolParamsSupplier,
                                                                   ScriptSupplier scriptSupplier,
                                                                   BFBackendService bfBackendService) {
        TransactionEvaluator scriptCostEvaluator =
                (cbor, inputUtxos) -> bfBackendService.getTransactionService().evaluateTx(cbor);
        // The three injected suppliers and the evaluator lambda — never the BackendService itself. The
        // builder constructs QuickTxBuilder from the suppliers with a null processor, so it can price a
        // transaction (evaluator + script bytes) and has nothing that could submit one.
        return new LiquidateTransactionBuilder(registry, network.getCardanoNetwork(), cardanoConverters,
                utxoSupplier, protocolParamsSupplier, scriptSupplier, scriptCostEvaluator);
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
     *
     * <p>FAB-134 B3b-4: built from the three injected suppliers and the evaluator, and holding nothing
     * else — the {@link UtxoSupplier} (index-first: coin selection, collateral and the indexed config and
     * pool-side reference inputs), the per-epoch {@link ProtocolParamsSupplier} (not a fetch per build)
     * and the hash-checked {@link ScriptSupplier}. Per compound build Blockfrost now sees one evaluate,
     * plus {@code getTxOutput} for out-refs the index does not hold — today the FT-published
     * reference-script coordinates {@code CompoundExecutor} resolves per candidate. The builder is handed
     * no {@code BFBackendService}: that would hand it a submission path through the back door.
     */
    @Bean
    public CompoundTransactionBuilder compoundTransactionBuilder(LoansContractRegistry registry,
                                                                 AppConfig.Network network,
                                                                 UtxoSupplier utxoSupplier,
                                                                 ProtocolParamsSupplier protocolParamsSupplier,
                                                                 ScriptSupplier scriptSupplier,
                                                                 BFBackendService bfBackendService) {
        TransactionEvaluator scriptCostEvaluator =
                (cbor, inputUtxos) -> bfBackendService.getTransactionService().evaluateTx(cbor);
        // The three injected suppliers and the evaluator lambda — never the BackendService itself.
        return new CompoundTransactionBuilder(registry, network.getCardanoNetwork(),
                utxoSupplier, protocolParamsSupplier, scriptSupplier, scriptCostEvaluator);
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
     * The production convert builder (FAB-134 B3b-3) — wired exactly as its two liquidation siblings above,
     * and for the same reason: <b>the operator's whole risk case for this path — "exposure is the
     * transaction fee per execution" — is true only while the ex-units are MEASURED.</b> Placeholder
     * ex-units move the exposure to the collateral (CCL trap 8), and this class has no constructor that
     * permits them. Built from the three injected suppliers and a real script-cost evaluator, and holding
     * nothing else:
     * <ul>
     *   <li>{@link UtxoSupplier} — the index-first bean: coin selection, collateral and every indexed
     *       reference input come from the local index; only out-refs the index cannot hold reach
     *       Blockfrost.</li>
     *   <li>{@link ProtocolParamsSupplier} — the per-epoch bean, not a fetch per build (a convert builds
     *       twice: the layout probe and the real pass).</li>
     *   <li>{@link ScriptSupplier} — the hash-checked bean, so every referenced script is priced, the
     *       collateral oracle's included. The convert used to declare a partial list of its registry
     *       scripts, which cardano-client-lib prices INSTEAD of asking a supplier — leaving the oracle's
     *       bytes unpriced, the shape of the 2026-08-24 {@code FeeTooSmallUTxO}.</li>
     * </ul>
     * The evaluator is Blockfrost's {@code /utils/txs/evaluate}, narrowed to the one-method
     * {@link TransactionEvaluator}: it is the one Blockfrost call a build still makes (with
     * {@code getTxOutput} for reference inputs the index does not hold). The builder is handed no
     * {@code BFBackendService} — that would hand it a submission path through the back door; arming and
     * submission stay in {@code LiquidationExecutor} behind its two independent flags.
     */
    @Bean
    public ConvertTransactionBuilder convertTransactionBuilder(LoansContractRegistry registry,
                                                               AppConfig.Network network,
                                                               UtxoSupplier utxoSupplier,
                                                               ProtocolParamsSupplier protocolParamsSupplier,
                                                               ScriptSupplier scriptSupplier,
                                                               BFBackendService bfBackendService) {
        TransactionEvaluator scriptCostEvaluator =
                (cbor, inputUtxos) -> bfBackendService.getTransactionService().evaluateTx(cbor);
        // The three injected suppliers and the evaluator lambda — never the BackendService itself.
        return new ConvertTransactionBuilder(registry, network.getCardanoNetwork(),
                utxoSupplier, protocolParamsSupplier, scriptSupplier, scriptCostEvaluator);
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
                                                             CardanoConverters converters,
                                                             AppConfig.Network network) {
        return new ConvertLiquidationRouter(registry, loansConfiguration, liquidationConfiguration,
                minswapPoolResolver, convertEconomics, convertTransactionBuilder, converters,
                network.getCardanoNetwork());
    }

    /**
     * The production pay-in-advance liquidation builder (FAB-134 B3b-2) — the mirror of
     * {@code liquidateTransactionBuilder} above, wired identically (T-043: fixes that land in one
     * sibling only). Built from the three injected suppliers and a real script-cost evaluator, and
     * holding nothing else:
     * <ul>
     *   <li>{@link UtxoSupplier} — the index-first bean: coin selection, collateral and every indexed
     *       reference input come from the local index; only out-refs the index cannot hold reach
     *       Blockfrost.</li>
     *   <li>{@link ProtocolParamsSupplier} — the per-epoch bean, not a fetch per build.</li>
     *   <li>{@link ScriptSupplier} — the hash-checked bean, so every referenced script is priced, the
     *       oracle's included (the 2026-08-24 {@code FeeTooSmallUTxO} was the oracle's going unpriced).</li>
     * </ul>
     * <p>
     * Without an evaluator, cardano-client-lib leaves every redeemer holding placeholder ex-units, and a
     * transaction that under-declares is not rejected by the mempool: it lands and then fails on chain,
     * forfeiting the collateral. So this path is given the same Blockfrost {@code /utils/txs/evaluate}
     * evaluator the plain builder's bean uses — its protocol parameters and cost models are the chain's
     * by construction — narrowed to the one-method {@link TransactionEvaluator} so the builder can price
     * a transaction and nothing else. It is the one Blockfrost call a build still makes (with
     * {@code getTxOutput} for reference inputs the index does not hold). The builder is handed no
     * {@code BFBackendService} and no {@code DefaultTransactionProcessor} — either would hand it a
     * submission path through the back door; arming and submission stay in {@code LiquidationExecutor}
     * behind its two independent flags.
     */
    @Bean
    public LiquidatePayInAdvanceTransactionBuilder liquidatePayInAdvanceTransactionBuilder(
            LoansContractRegistry registry,
            AppConfig.Network network,
            UtxoSupplier utxoSupplier,
            ProtocolParamsSupplier protocolParamsSupplier,
            ScriptSupplier scriptSupplier,
            BFBackendService bfBackendService) {
        TransactionEvaluator scriptCostEvaluator =
                (cbor, inputUtxos) -> bfBackendService.getTransactionService().evaluateTx(cbor);
        // The three injected suppliers and the evaluator lambda — never the BackendService itself.
        return new LiquidatePayInAdvanceTransactionBuilder(registry, network.getCardanoNetwork(),
                utxoSupplier, protocolParamsSupplier, scriptSupplier, scriptCostEvaluator);
    }

}
