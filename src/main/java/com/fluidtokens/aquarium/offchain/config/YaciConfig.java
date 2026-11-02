package com.fluidtokens.aquarium.offchain.config;

import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.ScriptSupplier;
import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.TransactionProcessor;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.exception.ApiException;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.DefaultProtocolParamsSupplier;
import com.bloxbean.cardano.client.backend.api.DefaultScriptSupplier;
import com.bloxbean.cardano.client.backend.api.DefaultTransactionProcessor;
import com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import com.fluidtokens.aquarium.offchain.service.StaticReferenceInputs;
import com.fluidtokens.aquarium.offchain.service.loans.LiquidatePayInAdvanceTransactionBuilder;
import com.fluidtokens.aquarium.offchain.service.loans.CompoundTransactionBuilder;
import com.fluidtokens.aquarium.offchain.service.loans.ConvertEconomics;
import com.fluidtokens.aquarium.offchain.service.loans.ConvertLiquidationRouter;
import com.fluidtokens.aquarium.offchain.service.loans.ConvertTransactionBuilder;
import com.fluidtokens.aquarium.offchain.service.loans.MinswapPoolResolver;
import com.fluidtokens.aquarium.offchain.service.loans.LiquidateTransactionBuilder;
import com.fluidtokens.aquarium.offchain.service.loans.OracleOutRefIndex;
import com.fluidtokens.aquarium.offchain.service.loans.OracleReferenceInputProbe;
import com.fluidtokens.aquarium.offchain.storage.IndexFirstUtxoSupplier;
import com.fluidtokens.aquarium.offchain.storage.TankUtxoStorage;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.conversions.CardanoConverters;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Configuration
@Slf4j
public class YaciConfig {

    /**
     * ⛔ FAB-38: <b>the node's ONE script-cost evaluator</b>, and the only place in {@code src/main} that
     * asks Blockfrost's {@code /utils/txs/evaluate} for ex-units. Every path that prices a script
     * transaction holds this instance: the four loans builders (liquidate, compound, convert,
     * pay-in-advance) as their {@code scriptCostEvaluator}, the tank {@code QuickTxBuilder} through its
     * processor slot ({@link EvaluatingTransactionProcessor}), and {@code ScheduledTransactionService}'s
     * dump-mode wrapper. Replacing this bean replaces evaluation for all of them at once.
     * <p>
     * It evaluates against the chain's own protocol parameters and cost models, so "is our pinned cost
     * model still the chain's?" cannot arise, and Blockfrost resolves the transaction's inputs itself
     * (they are real on-chain UTxOs in production) — so {@code inputUtxos} is ignored, as
     * cardano-client-lib's {@link DefaultTransactionProcessor} ignores it too. It runs on the caller's
     * thread through the {@link BFBackendService} bean, so the bounded backend's evaluation-stage timeout
     * and per-pass deadline apply exactly as they did when each site made this call itself.
     * <p>
     * ⛔ <b>An evaluator, never a processor.</b> {@link TransactionEvaluator} declares one operation and
     * no submit method, so every holder of this bean can price a transaction and nothing else; the loans
     * builders keep having no path to the wire. Submission exists only inside the tank builder's
     * processor, which is not a bean.
     */
    @Bean
    public TransactionEvaluator transactionEvaluator(BFBackendService bfBackendService) {
        return (cbor, inputUtxos) -> bfBackendService.getTransactionService().evaluateTx(cbor);
    }

    /**
     * The tank processor's builder ({@code ScheduledTransactionService}, by injection — the one
     * {@code QuickTxBuilder} in this node with no {@code new} at its call site). FAB-134 B3b-5: built from
     * the three injected suppliers below, Blockfrost's transaction submission and the
     * {@link #transactionEvaluator} bean, and from nothing else.
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
     * placeholder ex-units. So the slot holds an {@link EvaluatingTransactionProcessor}: submission is
     * {@link DefaultTransactionProcessor}'s Blockfrost {@code /tx/submit}, and evaluation is the
     * {@link #transactionEvaluator} bean — the same instance every loans builder holds — never a
     * processor-local evaluation.
     * <p>
     * Never {@code new QuickTxBuilder(bfBackendService)}: that form builds its own Blockfrost UTxO,
     * protocol-params and script suppliers and bypasses all three beans ({@code BlockfrostBuildWiringGuardTest}).
     */
    @Bean
    public QuickTxBuilder quickTxBuilder(UtxoSupplier utxoSupplier,
                                         ProtocolParamsSupplier protocolParamsSupplier,
                                         ScriptSupplier scriptSupplier,
                                         BFBackendService bfBackendService,
                                         TransactionEvaluator transactionEvaluator) {
        return new QuickTxBuilder(utxoSupplier, protocolParamsSupplier, scriptSupplier,
                new EvaluatingTransactionProcessor(
                        new DefaultTransactionProcessor(bfBackendService.getTransactionService()),
                        transactionEvaluator));
    }

    /**
     * ⛔ FAB-38: the tank {@code QuickTxBuilder}'s processor — submission from one object, evaluation from
     * another. cardano-client-lib's {@link TransactionProcessor} is both (it extends
     * {@link TransactionEvaluator}), and {@code QuickTxBuilder} uses it as the evaluator whenever a build
     * sets none (CCL trap 8), so a processor that only submitted would ship placeholder ex-units.
     * {@code submitTransaction} goes to {@code submitter}; {@code evaluateTx(byte[], Set<Utxo>)} — which the
     * interface's two default overloads ({@code byte[]} alone, and {@code Transaction}) route to — goes to
     * {@code evaluator}, the {@link YaciConfig#transactionEvaluator} bean. Not a bean itself: the context
     * holds no {@code TransactionProcessor}, so nothing can inject a submission path.
     */
    static final class EvaluatingTransactionProcessor implements TransactionProcessor {

        private final TransactionProcessor submitter;
        private final TransactionEvaluator evaluator;

        EvaluatingTransactionProcessor(TransactionProcessor submitter, TransactionEvaluator evaluator) {
            this.submitter = Objects.requireNonNull(submitter, "submitter");
            this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        }

        @Override
        public Result<String> submitTransaction(byte[] cborData) throws ApiException {
            return submitter.submitTransaction(cborData);
        }

        @Override
        public Result<List<EvaluationResult>> evaluateTx(byte[] cbor, Set<Utxo> inputUtxos) throws ApiException {
            return evaluator.evaluateTx(cbor, inputUtxos);
        }
    }

    /**
     * The node's {@link UtxoSupplier}: the local Yaci index first (FAB-134 B3a), see
     * {@link IndexFirstUtxoSupplier}.
     * <ul>
     *   <li>UTxOs at an address — coin selection — come ONLY from the index, for the payment credentials
     *       {@link TankUtxoStorage#indexedPaymentCredentials()} watches; any other address is refused,
     *       and an empty answer is never topped up from Blockfrost.</li>
     *   <li>One output by out-ref comes from the index when it holds the row with a faithful
     *       reference-script hash — including, at any address, an oracle feed or Charli3 provider output that
     *       holds a token of a configured oracle NFT policy (FAB-196), once the index has seen the block that
     *       created it — and otherwise from Blockfrost's {@code UtxoService}. That covers the reference
     *       inputs this node does not index (oracle scripts, an oracle feed or provider output holding no
     *       token of a configured policy or created before the cursor, FluidTokens-published reference
     *       scripts, the tank reference input). FAB-134 B5b: a miss whose
     *       out-ref is in {@link StaticReferenceInputs#current()} is read ONCE and then held by out-ref
     *       (an output's content never changes; liveness is the ledger's to judge); any other miss is one
     *       direct read, not held.</li>
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
                                     BFBackendService bfBackendService,
                                     StaticReferenceInputs staticReferenceInputs) {
        return new IndexFirstUtxoSupplier(utxoRepository, tankUtxoStorage::indexedPaymentCredentials,
                new DefaultUtxoSupplier(bfBackendService.getUtxoService()), staticReferenceInputs::current);
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
     * ⛔ FAB-138: the oracle reference-input probe the production liquidation, pay-in-advance and convert
     * builders run inside {@code build()}, before script-cost evaluation. It reads an out-ref's ADDRESS
     * through the index-first {@link UtxoSupplier} above (the content hold, which says nothing about
     * liveness), and then asks whether the out-ref is still in the live set holding its NFT.
     * <p>
     * ⛔ FAB-196: <b>the node's own index answers that first</b>. The {@link OracleOutRefIndex} it is handed
     * is the {@link TankUtxoStorage} bean: an out-ref the index holds unspent with its NFT is LIVE, and one
     * the index recorded spent is refused naming the spending transaction — both with no Blockfrost call.
     * Only an out-ref the index cannot answer (never seen, pruned, created before the cursor, or holding no
     * token of a configured oracle NFT policy) reaches Blockfrost's {@code UtxoService}: one
     * {@code getUtxos(address, nft)} for it, exactly the check this bean made before the index existed.
     * <p>
     * It is handed the {@code UtxoService} alone, never the {@code BFBackendService}: a {@code UtxoService}
     * can read and cannot submit, and the index is read-only, so neither the probe nor the builder that
     * holds it has a path to the wire.
     */
    @Bean
    public OracleReferenceInputProbe oracleReferenceInputProbe(UtxoSupplier utxoSupplier,
                                                               BFBackendService bfBackendService,
                                                               OracleOutRefIndex oracleOutRefIndex) {
        return new OracleReferenceInputProbe(utxoSupplier, bfBackendService.getUtxoService(), oracleOutRefIndex);
    }

    /**
     * The production liquidation builder (FAB-134 B3b): built from the three injected suppliers and
     * the {@link #transactionEvaluator} bean, and holding nothing else.
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
     * armed path has to be given an evaluator, and it is given the node's one: the
     * {@link #transactionEvaluator} bean, Blockfrost's {@code /utils/txs/evaluate} (see that bean for
     * why). Besides the oracle probe's check of an out-ref the index cannot answer (below), it is the one
     * Blockfrost call a build still makes (with {@code getTxOutput} for reference inputs the index does not
     * hold).
     * <p>
     * The bean's type is the narrowing, exactly as {@code LiquidationExecutor}'s
     * {@code TransactionSubmitter} is: {@link TransactionEvaluator} declares one operation and no submit
     * method, so what the builder holds can price a transaction and nothing else. The builder is handed
     * no {@code BFBackendService} and no {@code DefaultTransactionProcessor} — either would hand it a
     * submission path through the back door.
     * <p>
     * ⛔ FAB-138: it is also handed the {@link OracleReferenceInputProbe} above, which checks — before any
     * evaluation — that every oracle out-ref the build references is still live. FAB-196: the index answers
     * first, so an out-ref it holds (live, or recorded spent) costs no Blockfrost call; each distinct out-ref
     * the index cannot answer costs one more Blockfrost read per build, on top of the evaluation. That probe
     * holds a read-only {@code UtxoService} and a read-only index and nothing that can submit, so the
     * builder still gets no submission path.
     */
    @Bean
    public LiquidateTransactionBuilder liquidateTransactionBuilder(LoansContractRegistry registry,
                                                                   AppConfig.Network network,
                                                                   CardanoConverters cardanoConverters,
                                                                   UtxoSupplier utxoSupplier,
                                                                   ProtocolParamsSupplier protocolParamsSupplier,
                                                                   ScriptSupplier scriptSupplier,
                                                                   TransactionEvaluator transactionEvaluator,
                                                                   OracleReferenceInputProbe oracleReferenceInputProbe) {
        // The three injected suppliers and the evaluator bean — never the BackendService itself. The
        // builder constructs QuickTxBuilder from the suppliers with a null processor, so it can price a
        // transaction (evaluator + script bytes) and has nothing that could submit one.
        return new LiquidateTransactionBuilder(registry, network.getCardanoNetwork(), cardanoConverters,
                utxoSupplier, protocolParamsSupplier, scriptSupplier, transactionEvaluator,
                oracleReferenceInputProbe);
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
     * <p>The evaluator is the {@link #transactionEvaluator} bean — Blockfrost's
     * {@code /utils/txs/evaluate}, typed as the one-method {@link TransactionEvaluator} so what the
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
                                                                 TransactionEvaluator transactionEvaluator) {
        // The three injected suppliers and the evaluator bean — never the BackendService itself.
        return new CompoundTransactionBuilder(registry, network.getCardanoNetwork(),
                utxoSupplier, protocolParamsSupplier, scriptSupplier, transactionEvaluator);
    }

    /**
     * The Minswap pool resolver — how a convert finds the ONE authentic pool for a loan's pair.
     *
     * <p>⛔ <b>It reads the node's own index, never the provider.</b> One unpaged
     * {@code findUnspentByOwnerPaymentCredential(pool-spend-script-hash)} per liquidation cycle, then
     * authentication by MSP, datum pair and recomputed LP name ({@link MinswapPoolResolver}). The pool
     * credential is indexed alongside the wallet's (FAB-137 T2a); no {@code BFBackendService} reaches
     * this bean.
     *
     * <p>⚠ {@link UtxoRepository} is Yaci Store's JPA repository and is registered unconditionally, so
     * this bean does not inherit a feature-flag condition from its new dependency (CCL §9b: check what a
     * new dependency is conditional on).
     *
     * <p>⛔ <b>Startup fails on a pool address whose payment credential is not the pool spend script
     * hash.</b> The resolver queries by the hash; the router and the readiness page gate on the
     * address. Configured apart, they would name two different places, and every convert would read
     * "no pool" against a pool that exists. A blank address means "no Minswap on this network" and is
     * not checked.
     */
    @Bean
    public MinswapPoolResolver minswapPoolResolver(AppConfig.LoansConfiguration loansConfiguration,
                                                   UtxoRepository utxoRepository) {
        String poolAddress = loansConfiguration.getMinswapPoolAddress();
        String spendHash = loansConfiguration.getMinswapPoolSpendScriptHash();
        if (poolAddress != null && !poolAddress.isBlank()) {
            String credential = new com.bloxbean.cardano.client.address.Address(poolAddress)
                    .getPaymentCredentialHash()
                    .map(com.bloxbean.cardano.client.util.HexUtil::encodeHexString)
                    .orElse(null);
            if (credential == null || !credential.equalsIgnoreCase(spendHash)) {
                throw new IllegalStateException("loans.minswap.pool-address (" + poolAddress
                        + ") has payment credential " + credential
                        + ", which is not loans.minswap.pool-spend-script-hash (" + spendHash
                        + "); the pool index is read by the hash, so the two must name the same "
                        + "Minswap pool script");
            }
        }
        // ⛔ Yaci stores payment credentials and asset units LOWERCASE, and the check above compares
        // case-insensitively — so an uppercase hash or policy id would pass it, then query a credential
        // no row carries and match no MSP or LP unit: every pair "not indexed" with the pool in the index.
        String policyId = loansConfiguration.getMinswapPoolPolicyId();
        return new MinswapPoolResolver(utxoRepository,
                spendHash == null ? null : spendHash.toLowerCase(java.util.Locale.ROOT),
                policyId == null ? null : policyId.toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * The production convert builder (FAB-134 B3b-3) — wired exactly as its two liquidation siblings above,
     * and for the same reason: <b>the operator's whole risk case for this path — "exposure is the
     * transaction fee per execution" — is true only while the ex-units are MEASURED.</b> Placeholder
     * ex-units move the exposure to the collateral (CCL trap 8), and this class has no constructor that
     * permits them. Built from the three injected suppliers and the {@link #transactionEvaluator} bean,
     * and holding nothing else:
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
     * The evaluator is the {@link #transactionEvaluator} bean — Blockfrost's {@code /utils/txs/evaluate},
     * typed as the one-method {@link TransactionEvaluator}: it is the one Blockfrost call a build still makes (with
     * {@code getTxOutput} for reference inputs the index does not hold). The builder is handed no
     * {@code BFBackendService} — that would hand it a submission path through the back door; arming and
     * submission stay in {@code LiquidationExecutor} behind its two independent flags.
     * <p>
     * ⛔ FAB-138: it is also handed the {@link OracleReferenceInputProbe} bean, which checks — once per
     * build, before either pass is evaluated — that the collateral oracle's out-refs (feed and, for a
     * Charli3 feed, provider) are still live. FAB-196: the index answers first, so an out-ref it holds
     * (live, or recorded spent) costs no Blockfrost call; each out-ref the index cannot answer costs one
     * more Blockfrost read, at most two per build. That probe holds a read-only {@code UtxoService} and a
     * read-only index and nothing that can submit, so the builder still gets no submission path.
     */
    @Bean
    public ConvertTransactionBuilder convertTransactionBuilder(LoansContractRegistry registry,
                                                               AppConfig.Network network,
                                                               UtxoSupplier utxoSupplier,
                                                               ProtocolParamsSupplier protocolParamsSupplier,
                                                               ScriptSupplier scriptSupplier,
                                                               TransactionEvaluator transactionEvaluator,
                                                               OracleReferenceInputProbe oracleReferenceInputProbe) {
        // The three injected suppliers and the evaluator bean — never the BackendService itself.
        return new ConvertTransactionBuilder(registry, network.getCardanoNetwork(),
                utxoSupplier, protocolParamsSupplier, scriptSupplier, transactionEvaluator,
                oracleReferenceInputProbe);
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
     * sibling only). Built from the three injected suppliers and the {@link #transactionEvaluator} bean,
     * and holding nothing else:
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
     * forfeiting the collateral. So this path is given the same {@link #transactionEvaluator} bean the
     * plain builder holds — Blockfrost's {@code /utils/txs/evaluate}, whose protocol parameters and cost
     * models are the chain's by construction — typed as the one-method {@link TransactionEvaluator} so
     * the builder can price a transaction and nothing else. It is the one Blockfrost call a build still makes (with
     * {@code getTxOutput} for reference inputs the index does not hold). The builder is handed no
     * {@code BFBackendService} and no {@code DefaultTransactionProcessor} — either would hand it a
     * submission path through the back door; arming and submission stay in {@code LiquidationExecutor}
     * behind its two independent flags.
     * <p>
     * ⛔ FAB-138: it is also handed the {@link OracleReferenceInputProbe} bean, which checks — once per
     * build, before any evaluation — that every oracle out-ref the build references is still live: the
     * collateral leg's feed and Charli3 provider, and the principal leg's too when the principal is a
     * token. FAB-196: the index answers first, so an out-ref it holds (live, or recorded spent) costs no
     * Blockfrost call; each distinct out-ref the index cannot answer costs one Blockfrost read, up to four
     * per build. That probe holds a read-only {@code UtxoService} and a read-only index and nothing that
     * can submit, so the builder still gets no submission path.
     */
    @Bean
    public LiquidatePayInAdvanceTransactionBuilder liquidatePayInAdvanceTransactionBuilder(
            LoansContractRegistry registry,
            AppConfig.Network network,
            UtxoSupplier utxoSupplier,
            ProtocolParamsSupplier protocolParamsSupplier,
            ScriptSupplier scriptSupplier,
            TransactionEvaluator transactionEvaluator,
            OracleReferenceInputProbe oracleReferenceInputProbe) {
        // The three injected suppliers and the evaluator bean — never the BackendService itself.
        return new LiquidatePayInAdvanceTransactionBuilder(registry, network.getCardanoNetwork(),
                utxoSupplier, protocolParamsSupplier, scriptSupplier, transactionEvaluator,
                oracleReferenceInputProbe);
    }

}
