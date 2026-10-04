package com.fluidtokens.aquarium.offchain.config;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.ScriptSupplier;
import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.api.UtxoService;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.AddressUtxoEntity;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fluidtokens.aquarium.offchain.service.AppUtxoService;
import com.fluidtokens.aquarium.offchain.service.BlockEventListener;
import com.fluidtokens.aquarium.offchain.service.ParametersService;
import com.fluidtokens.aquarium.offchain.service.ScheduledTransactionService;
import com.fluidtokens.aquarium.offchain.service.StakerService;
import com.fluidtokens.aquarium.offchain.service.TankContractService;
import com.fluidtokens.aquarium.offchain.service.loans.CompoundTransactionBuilder;
import com.fluidtokens.aquarium.offchain.service.loans.ConvertTransactionBuilder;
import com.fluidtokens.aquarium.offchain.service.loans.LiquidatePayInAdvanceTransactionBuilder;
import com.fluidtokens.aquarium.offchain.service.loans.LiquidateTransactionBuilder;
import com.fluidtokens.aquarium.offchain.service.loans.LoanFixtures;
import com.fluidtokens.aquarium.offchain.storage.IndexFirstUtxoSupplier;
import com.fluidtokens.aquarium.offchain.storage.TankUtxoStorage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The production wiring, asserted on the production wiring.
 *
 * <h2>Why this test exists</h2>
 * Every other test of the script-cost evaluator supplies its own. That leaves the one thing that
 * actually protects the armed bot — the argument {@link YaciConfig#liquidateTransactionBuilder} passes
 * — asserted nowhere: drop it, and the whole suite stays green while the running bot goes back to
 * declaring 10000-mem redeemers, landing transactions that exhaust their budget on chain and forfeit
 * the borrower's collateral. A defect whose removal no test notices is a defect waiting to be
 * reintroduced by the next refactor.
 * <p>
 * So this calls the {@code @Bean} method directly and reads the field it wrote. No Spring context, no
 * {@code @SpringBootTest}: the bean method is an ordinary method and is treated as one.
 *
 * <h2>Why it needs no network</h2>
 * {@link BFBackendService}'s constructor only builds Retrofit clients — it performs no request — so an
 * unreachable base URL and a dummy project id are enough to construct one. Nothing here calls it: the
 * assertion is about which object the builder was handed, not about what that object answers. The URL
 * is deliberately unresolvable so that a future change which does make this path talk to a backend
 * fails loudly here instead of quietly reaching the real Blockfrost.
 */
class YaciConfigWiringTest {

    /** Unresolvable on purpose: constructing a backend service must not be constructing a connection. */
    private static final String OFFLINE_BLOCKFROST = "https://example.invalid/api/v0/";

    @Test
    void theProductionBuilderBeanIsGivenAScriptCostEvaluator() throws Exception {
        LiquidateTransactionBuilder builder = new YaciConfig().liquidateTransactionBuilder(
                LoanFixtures.registry(),
                previewNetwork(),
                LoanFixtures.converters(),
                LoanFixtures.utxoSupplier(List.of()),
                LoanFixtures.protocolParams(),
                hash -> Optional.empty(),
                new BFBackendService(OFFLINE_BLOCKFROST, "dummy"));

        Field field = LiquidateTransactionBuilder.class.getDeclaredField("scriptCostEvaluator");
        field.setAccessible(true);
        Object evaluator = field.get(builder);

        assertNotNull(evaluator,
                "YaciConfig built the liquidation transaction builder WITHOUT a script-cost evaluator. "
                        + "Its redeemers would carry cardano-client-lib's 10000-mem placeholders against "
                        + "a measured 2.26M, the mempool would accept the transaction anyway, and it "
                        + "would fail on chain in phase 2 — forfeiting collateral. Pass the evaluator.");
        assertTrue(evaluator instanceof TransactionEvaluator,
                "the builder was given a " + evaluator.getClass().getName()
                        + " where a TransactionEvaluator was asked for");
    }

    /**
     * ⛔ The COMPOUND builder's production bean, asserted the same way and for a sharper reason.
     *
     * <p>The operator's stated case for arming this path is that the exposure is the transaction fee
     * per execution — nothing advanced, nothing acquired. <b>That sentence is true only while the
     * ex-units are measured.</b> Placeholder ex-units move the exposure to the collateral, which is
     * the one way the risk analysis becomes false, so this assertion is the thing keeping it true.
     */
    @Test
    void theProductionCompoundBuilderBeanIsGivenAScriptCostEvaluator() throws Exception {
        CompoundTransactionBuilder builder = new YaciConfig().compoundTransactionBuilder(
                LoanFixtures.shippedPreviewRegistry(),
                previewNetwork(),
                LoanFixtures.utxoSupplier(List.of()),
                LoanFixtures.protocolParams(),
                hash -> Optional.empty(),
                new BFBackendService(OFFLINE_BLOCKFROST, "dummy"));

        Field field = CompoundTransactionBuilder.class.getDeclaredField("scriptCostEvaluator");
        field.setAccessible(true);
        Object evaluator = field.get(builder);

        assertNotNull(evaluator,
                "YaciConfig built the compound transaction builder WITHOUT a script-cost evaluator. "
                        + "Its redeemers would carry placeholder ex-units against a measured 2.58M mem / "
                        + "941M steps, the mempool would accept it, and it would fail in phase 2 — "
                        + "forfeiting collateral, and falsifying the risk case the path was armed on.");
        assertTrue(evaluator instanceof TransactionEvaluator,
                "the builder was given a " + evaluator.getClass().getName()
                        + " where a TransactionEvaluator was asked for");
    }

    /**
     * ⛔ FAB-134 B3b-4: the compound bean holds EXACTLY what the container injected — the index-first
     * {@link UtxoSupplier}, the per-epoch {@link ProtocolParamsSupplier} and the byte-serving
     * {@link ScriptSupplier} — and no {@code BackendService} field at all. A fresh supplier here would send
     * every compound's coin selection, collateral and indexed reference inputs back to Blockfrost; a
     * backend field would hand the builder a submission path through the back door. This replaces the
     * pre-B3b assertion that the builder HELD a BackendService (then its only route to script bytes).
     */
    @Test
    void theProductionCompoundBuilderHoldsTheInjectedSuppliersAndNoBackend() throws Exception {
        UtxoSupplier utxoSupplier = LoanFixtures.utxoSupplier(List.of());
        ProtocolParamsSupplier protocolParamsSupplier = LoanFixtures.protocolParams();
        ScriptSupplier scriptSupplier = hash -> Optional.empty();

        CompoundTransactionBuilder builder = new YaciConfig().compoundTransactionBuilder(
                LoanFixtures.shippedPreviewRegistry(), previewNetwork(),
                utxoSupplier, protocolParamsSupplier, scriptSupplier,
                new BFBackendService(OFFLINE_BLOCKFROST, "dummy"));

        assertSame(utxoSupplier, fieldOf(builder, "utxoSupplier"),
                "the compound builder does not hold the injected (index-first) UtxoSupplier");
        assertSame(protocolParamsSupplier, fieldOf(builder, "protocolParamsSupplier"),
                "the compound builder does not hold the injected (per-epoch) ProtocolParamsSupplier");
        assertSame(scriptSupplier, fieldOf(builder, "scriptSupplier"),
                "the compound builder does not hold the injected ScriptSupplier, so QuickTxBuilder cannot be "
                        + "handed the bytes of a referenced script (CCL trap 9)");

        Object evaluator = fieldOf(builder, "scriptCostEvaluator");
        assertNotNull(evaluator, "YaciConfig built the compound builder WITHOUT a script-cost evaluator");
        for (java.lang.reflect.Method method : evaluator.getClass().getMethods()) {
            assertFalse(method.getName().toLowerCase().contains("submit"),
                    "the evaluator the compound builder holds exposes " + method.getName()
                            + ": a submission path through the back door");
        }
        for (Field field : CompoundTransactionBuilder.class.getDeclaredFields()) {
            assertFalse(BackendService.class.isAssignableFrom(field.getType()),
                    "the compound builder declares a BackendService field (" + field.getName()
                            + "): a submission path, and a route back to Blockfrost for every read");
        }
    }

    /**
     * And the same wiring must not have smuggled in a submission path. The evaluator the bean builds is
     * a lambda over {@code BFBackendService.getTransactionService().evaluateTx}, and a lambda's own type
     * implements exactly the functional interface — so if this ever became "pass the backend service
     * itself", or "pass the DefaultTransactionProcessor", this assertion is what notices.
     */
    @Test
    void theEvaluatorTheBeanBuildsCannotSubmit() throws Exception {
        LiquidateTransactionBuilder builder = new YaciConfig().liquidateTransactionBuilder(
                LoanFixtures.registry(),
                previewNetwork(),
                LoanFixtures.converters(),
                LoanFixtures.utxoSupplier(List.of()),
                LoanFixtures.protocolParams(),
                hash -> Optional.empty(),
                new BFBackendService(OFFLINE_BLOCKFROST, "dummy"));

        Field field = LiquidateTransactionBuilder.class.getDeclaredField("scriptCostEvaluator");
        field.setAccessible(true);
        Object evaluator = field.get(builder);
        assertNotNull(evaluator, "no evaluator was wired at all — see the sibling test");
        Class<?> evaluatorType = evaluator.getClass();

        assertTrue(java.util.Arrays.stream(evaluatorType.getMethods())
                        .noneMatch(method -> method.getName().toLowerCase().contains("submit")),
                "the object wired as the script-cost evaluator exposes a submit method: " + evaluatorType);
    }

    /**
     * ⛔ FAB-134 B3b-5: the tank processor's {@code QuickTxBuilder} bean is built from the three injected
     * suppliers — the index-first {@link UtxoSupplier}, the per-epoch {@link ProtocolParamsSupplier} and
     * the byte-serving {@link ScriptSupplier} — and a REAL {@code DefaultTransactionProcessor}.
     * <p>
     * The processor is not optional here, unlike in the loans builders: the tank path SUBMITS
     * ({@code context.complete()}), and the same slot is cardano-client-lib's default script-cost
     * evaluator (CCL trap 8). A null there would build every tank transaction on placeholder ex-units —
     * accepted by the mempool, failed in phase 2, collateral forfeit — and then fail to submit it.
     * Reverting the bean to {@code new QuickTxBuilder(bfBackendService)} turns the first three
     * assertions red: CCL builds its own Blockfrost suppliers from the backend.
     */
    @Test
    void theTankQuickTxBuilderHoldsTheInjectedSuppliersAndARealProcessor() throws Exception {
        UtxoSupplier utxoSupplier = LoanFixtures.utxoSupplier(List.of());
        ProtocolParamsSupplier protocolParamsSupplier = LoanFixtures.protocolParams();
        ScriptSupplier scriptSupplier = hash -> Optional.empty();

        QuickTxBuilder builder = new YaciConfig().quickTxBuilder(utxoSupplier, protocolParamsSupplier,
                scriptSupplier, new BFBackendService(OFFLINE_BLOCKFROST, "dummy"));

        assertSame(utxoSupplier, quickTxBuilderField(builder, "utxoSupplier"),
                "the tank QuickTxBuilder does not hold the injected (index-first) UtxoSupplier: its coin "
                        + "selection and every indexed reference input go back to Blockfrost");
        assertSame(protocolParamsSupplier, quickTxBuilderField(builder, "protocolParamsSupplier"),
                "the tank QuickTxBuilder does not hold the injected (per-epoch) ProtocolParamsSupplier");
        assertSame(scriptSupplier, quickTxBuilderField(builder, "backendScriptSupplier"),
                "the tank QuickTxBuilder does not hold the injected (hash-checked) ScriptSupplier");
        assertInstanceOf(com.bloxbean.cardano.client.backend.api.DefaultTransactionProcessor.class,
                quickTxBuilderField(builder, "transactionProcessor"),
                "the tank QuickTxBuilder has no DefaultTransactionProcessor: it can neither submit nor — the "
                        + "same slot being the default evaluator — measure ex-units (CCL trap 8)");
    }

    private static Object quickTxBuilderField(QuickTxBuilder builder, String name) throws Exception {
        Field field = QuickTxBuilder.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(builder);
    }

    /**
     * The protocol-params bean is the per-epoch cache, not the bare Blockfrost supplier that makes one
     * HTTP call per {@code getProtocolParams()}. Every builder and the tank processor share this bean,
     * so if it reverted to the raw supplier every one of them would go back to a provider call per use.
     * <p>
     * Constructing it attempts the eager load against the unresolvable URL; that load is soft by
     * contract, so construction succeeding here is itself part of the assertion.
     */
    @Test
    void theProtocolParamsBeanIsTheEpochCache() {
        ProtocolParamsSupplier bean = new YaciConfig().protocolParamsSupplier(
                new BFBackendService(OFFLINE_BLOCKFROST, "dummy"), LoanFixtures.converters());

        assertInstanceOf(EpochProtocolParamsSupplier.class, bean,
                "YaciConfig's ProtocolParamsSupplier bean is a " + bean.getClass().getName()
                        + ": every injection point would fetch protocol parameters from Blockfrost on "
                        + "every call instead of once per epoch");
    }

    /**
     * The tank processor reads protocol parameters through the injected supplier bean, not through a
     * Blockfrost supplier it builds for itself. Driven through {@code processPayments()} with an empty
     * tank set and an empty wallet, which reaches the params read and then returns before anything is
     * built: the counting supplier must be asked exactly once, and the backend's epoch service never.
     */
    @Test
    void theTankProcessorReadsProtocolParamsThroughTheInjectedSupplier() {
        var input = TransactionInput.builder()
                .transactionId("0000000000000000000000000000000000000000000000000000000000000000")
                .index(0).build();

        BFBackendService backend = mock(BFBackendService.class);
        BlockEventListener blockEventListener = mock(BlockEventListener.class);
        when(blockEventListener.getIsSyncing()).thenReturn(new AtomicBoolean(false));
        StakerService stakerService = mock(StakerService.class);
        when(stakerService.findStakerRefInput()).thenReturn(List.of(input));
        ParametersService parametersService = mock(ParametersService.class);
        when(parametersService.loadParametersRefInput()).thenReturn(input);
        AppConfig.AquariumConfiguration aquarium = mock(AppConfig.AquariumConfiguration.class);
        when(aquarium.getTankRefInput()).thenReturn(input);
        AppUtxoService appUtxoService = mock(AppUtxoService.class);
        when(appUtxoService.listWalletUtxo()).thenReturn(List.of());

        AtomicInteger calls = new AtomicInteger();
        ProtocolParamsSupplier injected = () -> {
            calls.incrementAndGet();
            return new ProtocolParams();
        };

        var processor = new ScheduledTransactionService(
                new AppConfig.Network(),
                aquarium,
                mock(Account.class),
                mock(QuickTxBuilder.class),
                backend,
                injected,
                mock(UtxoSupplier.class),
                mock(UtxoRepository.class),
                stakerService,
                LoanFixtures.converters(),
                parametersService,
                mock(TankContractService.class),
                appUtxoService,
                blockEventListener);

        processor.processPayments();

        assertEquals(1, calls.get(),
                "the tank processor did not read protocol parameters through the injected supplier");
        verify(backend, never()).getEpochService();
    }

    /**
     * ⛔ FAB-134 B3a: the {@code UtxoSupplier} bean is the INDEX-FIRST supplier, built from the
     * production collaborators. Coin selection ({@code getAll}) must never reach Blockfrost's
     * {@code UtxoService}; an out-ref the index does not hold ({@code getTxOutput} miss) must reach it,
     * on every call. Reverting the bean to {@code new DefaultUtxoSupplier(...)} turns this red.
     * <p>FAB-134 B5b: and a miss that is a STATIC reference input (the {@code StaticReferenceInputs} bean)
     * reaches it once and is then held by out-ref — a bean wired without the static set would read it
     * three times.
     */
    @Test
    void theUtxoSupplierBeanIsIndexFirstAndOnlyAMissReachesBlockfrost() throws Exception {
        var wallet = new Account(com.bloxbean.cardano.client.common.model.Networks.testnet());
        String walletPkh = wallet.getBaseAddress().getPaymentCredentialHash().map(HexUtil::encodeHexString).get();
        String missTx = "cd".repeat(32);

        UtxoService utxoService = mock(UtxoService.class);
        Utxo fromBlockfrost = Utxo.builder().txHash(missTx).outputIndex(4).address("blockfrost").build();
        when(utxoService.getTxOutput(missTx, 4))
                .thenReturn(Result.<Utxo>success("ok").withValue(fromBlockfrost));
        BFBackendService bf = mock(BFBackendService.class);
        when(bf.getUtxoService()).thenReturn(utxoService);
        TankUtxoStorage storage = mock(TankUtxoStorage.class);
        when(storage.indexedPaymentCredentials()).thenReturn(Set.of(walletPkh));
        UtxoRepository repo = (UtxoRepository) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{UtxoRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findUnspentByOwnerAddr" -> Optional.of(List.<AddressUtxoEntity>of());
                    case "findById" -> Optional.empty();
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        // FAB-134 B5b: the static-reference-input source, read on each miss.
        String staticTx = "ef".repeat(32);
        Utxo staticFromBlockfrost = Utxo.builder().txHash(staticTx).outputIndex(1).address("blockfrost").build();
        when(utxoService.getTxOutput(staticTx, 1))
                .thenReturn(Result.<Utxo>success("ok").withValue(staticFromBlockfrost));
        com.fluidtokens.aquarium.offchain.service.StaticReferenceInputs staticReferenceInputs =
                mock(com.fluidtokens.aquarium.offchain.service.StaticReferenceInputs.class);
        when(staticReferenceInputs.current()).thenReturn(Set.of(
                com.bloxbean.cardano.client.transaction.spec.TransactionInput.builder()
                        .transactionId(staticTx).index(1).build()));

        UtxoSupplier bean = new YaciConfig().utxoSupplier(repo, storage, bf, staticReferenceInputs);

        assertInstanceOf(IndexFirstUtxoSupplier.class, bean,
                "YaciConfig's UtxoSupplier bean is a " + bean.getClass().getName()
                        + ": every coin selection would be a Blockfrost read again");
        assertTrue(bean.getAll(wallet.baseAddress()).isEmpty());
        verifyNoInteractions(utxoService);

        assertEquals("blockfrost", bean.getTxOutput(missTx, 4).orElseThrow().getAddress());
        assertEquals("blockfrost", bean.getTxOutput(missTx, 4).orElseThrow().getAddress());
        verify(utxoService, times(2)).getTxOutput(missTx, 4);

        // ⛔ The bean is wired with the static set: a static reference input reaches Blockfrost ONCE.
        assertEquals("blockfrost", bean.getTxOutput(staticTx, 1).orElseThrow().getAddress());
        assertEquals("blockfrost", bean.getTxOutput(staticTx, 1).orElseThrow().getAddress());
        assertEquals("blockfrost", bean.getTxOutput(staticTx, 1).orElseThrow().getAddress());
        verify(utxoService, times(1)).getTxOutput(staticTx, 1);
    }

    /**
     * ⛔ FAB-134 B3b: the liquidation builder bean holds the SAME supplier instances the container
     * injected — the index-first {@code UtxoSupplier}, the per-epoch {@code ProtocolParamsSupplier} and
     * the byte-serving {@code ScriptSupplier} — and no {@code BackendService} at all. A builder that
     * built its own Blockfrost suppliers would put every coin selection and every params read back on
     * Blockfrost; one that held a backend would hold a submission path.
     */
    @Test
    void theLiquidateBuilderBeanHoldsTheInjectedSuppliersAndNoBackend() throws Exception {
        UtxoSupplier utxoSupplier = LoanFixtures.utxoSupplier(List.of());
        ProtocolParamsSupplier protocolParamsSupplier = LoanFixtures.protocolParams();
        ScriptSupplier scriptSupplier = hash -> Optional.empty();

        LiquidateTransactionBuilder builder = new YaciConfig().liquidateTransactionBuilder(
                LoanFixtures.registry(), previewNetwork(), LoanFixtures.converters(),
                utxoSupplier, protocolParamsSupplier, scriptSupplier,
                new BFBackendService(OFFLINE_BLOCKFROST, "dummy"));

        assertSame(utxoSupplier, fieldOf(builder, "utxoSupplier"),
                "the builder does not hold the injected (index-first) UtxoSupplier");
        assertSame(protocolParamsSupplier, fieldOf(builder, "protocolParamsSupplier"),
                "the builder does not hold the injected (per-epoch) ProtocolParamsSupplier");
        assertSame(scriptSupplier, fieldOf(builder, "scriptSupplier"),
                "the builder does not hold the injected ScriptSupplier");
        for (Field field : LiquidateTransactionBuilder.class.getDeclaredFields()) {
            assertFalse(BackendService.class.isAssignableFrom(field.getType()),
                    "the liquidation builder declares a BackendService field (" + field.getName()
                            + "): a submission path, and a route back to Blockfrost for every read");
        }
    }

    /**
     * ⛔ FAB-134 B3b-2: the PAY-IN-ADVANCE builder bean, asserted the same way as its sibling above
     * (T-043 found fixes landing in one sibling only). It holds the SAME three supplier instances the
     * container injected, a non-null evaluator that is a {@link TransactionEvaluator} and exposes no
     * {@code submit} method, and no {@code BackendService} field at all — the pay-in-advance path fronts
     * the operator's own ada, so a submission path or a per-build Blockfrost read here costs the most.
     */
    @Test
    void thePayInAdvanceBuilderBeanHoldsTheInjectedSuppliersAnEvaluatorAndNoBackend() throws Exception {
        UtxoSupplier utxoSupplier = LoanFixtures.utxoSupplier(List.of());
        ProtocolParamsSupplier protocolParamsSupplier = LoanFixtures.protocolParams();
        ScriptSupplier scriptSupplier = hash -> Optional.empty();

        LiquidatePayInAdvanceTransactionBuilder builder = new YaciConfig().liquidatePayInAdvanceTransactionBuilder(
                LoanFixtures.registry(), previewNetwork(),
                utxoSupplier, protocolParamsSupplier, scriptSupplier,
                new BFBackendService(OFFLINE_BLOCKFROST, "dummy"));

        assertSame(utxoSupplier, fieldOf(builder, "utxoSupplier"),
                "the pay-in-advance builder does not hold the injected (index-first) UtxoSupplier");
        assertSame(protocolParamsSupplier, fieldOf(builder, "protocolParamsSupplier"),
                "the pay-in-advance builder does not hold the injected (per-epoch) ProtocolParamsSupplier");
        assertSame(scriptSupplier, fieldOf(builder, "scriptSupplier"),
                "the pay-in-advance builder does not hold the injected ScriptSupplier");

        Object evaluator = fieldOf(builder, "scriptCostEvaluator");
        assertNotNull(evaluator, "YaciConfig built the pay-in-advance builder WITHOUT a script-cost evaluator: "
                + "placeholder ex-units, a phase-2 failure, forfeited collateral (CCL trap 8)");
        assertInstanceOf(TransactionEvaluator.class, evaluator);
        for (java.lang.reflect.Method method : evaluator.getClass().getMethods()) {
            assertFalse(method.getName().toLowerCase().contains("submit"),
                    "the evaluator the pay-in-advance builder holds exposes " + method.getName()
                            + ": a submission path through the back door");
        }
        for (Field field : LiquidatePayInAdvanceTransactionBuilder.class.getDeclaredFields()) {
            assertFalse(BackendService.class.isAssignableFrom(field.getType()),
                    "the pay-in-advance builder declares a BackendService field (" + field.getName()
                            + "): a submission path, and a route back to Blockfrost for every read");
        }
    }

    /**
     * ⛔ FAB-134 B3b-3: the CONVERT builder bean, asserted the same way as its two liquidation siblings
     * above (T-043 found fixes landing in one sibling only). It holds the SAME three supplier instances
     * the container injected — a fresh supplier here would read every convert's coin selection, collateral
     * and indexed reference inputs from Blockfrost again, twice per convert (layout probe and real pass) —
     * a non-null evaluator that is a {@link TransactionEvaluator} with no {@code submit} method, and no
     * {@code BackendService} field at all.
     */
    @Test
    void theConvertBuilderBeanHoldsTheInjectedSuppliersAnEvaluatorAndNoBackend() throws Exception {
        UtxoSupplier utxoSupplier = LoanFixtures.utxoSupplier(List.of());
        ProtocolParamsSupplier protocolParamsSupplier = LoanFixtures.protocolParams();
        ScriptSupplier scriptSupplier = hash -> Optional.empty();

        ConvertTransactionBuilder builder = new YaciConfig().convertTransactionBuilder(
                LoanFixtures.registry(), previewNetwork(),
                utxoSupplier, protocolParamsSupplier, scriptSupplier,
                new BFBackendService(OFFLINE_BLOCKFROST, "dummy"));

        assertSame(utxoSupplier, fieldOf(builder, "utxoSupplier"),
                "the convert builder does not hold the injected (index-first) UtxoSupplier");
        assertSame(protocolParamsSupplier, fieldOf(builder, "protocolParamsSupplier"),
                "the convert builder does not hold the injected (per-epoch) ProtocolParamsSupplier");
        assertSame(scriptSupplier, fieldOf(builder, "scriptSupplier"),
                "the convert builder does not hold the injected ScriptSupplier");

        Object evaluator = fieldOf(builder, "scriptCostEvaluator");
        assertNotNull(evaluator, "YaciConfig built the convert builder WITHOUT a script-cost evaluator: "
                + "placeholder ex-units, a phase-2 failure, forfeited collateral (CCL trap 8)");
        assertInstanceOf(TransactionEvaluator.class, evaluator);
        for (java.lang.reflect.Method method : evaluator.getClass().getMethods()) {
            assertFalse(method.getName().toLowerCase().contains("submit"),
                    "the evaluator the convert builder holds exposes " + method.getName()
                            + ": a submission path through the back door");
        }
        for (Field field : ConvertTransactionBuilder.class.getDeclaredFields()) {
            assertFalse(BackendService.class.isAssignableFrom(field.getType()),
                    "the convert builder declares a BackendService field (" + field.getName()
                            + "): a submission path, and a route back to Blockfrost for every read");
        }
    }

    /** The script supplier bean is the hash-checked, fail-closed one — not the raw Blockfrost supplier. */
    @Test
    void theScriptSupplierBeanIsHashChecked() {
        BFBackendService bf = mock(BFBackendService.class);
        ScriptSupplier bean = new YaciConfig().scriptSupplier(bf);

        assertInstanceOf(HashCheckedScriptSupplier.class, bean,
                "YaciConfig's ScriptSupplier bean is a " + bean.getClass().getName()
                        + ": an empty answer would price a reference script at zero (CCL trap 9), and "
                        + "unverified bytes would be served");
    }

    // ===== the Minswap pool resolver reads the index, and its two coordinates must agree (FAB-137 T2b) =====

    private static final String SHIPPED_POOL_ADDRESS =
            "addr1z84q0denmyep98ph3tmzwsmw0j7zau9ljmsqx6a4rvaau66j2c79gy9l76sdg0xwhd7r0c0kna0tycz4y5s6mlenh8pq777e2a";
    private static final String SHIPPED_POOL_SPEND_HASH = "ea07b733d932129c378af627436e7cbc2ef0bf96e0036bb51b3bde6b";

    private static final String SHIPPED_POOL_POLICY = "f5808c2c990d86da54bfc97d89cee6efa20cd8461616359478d96b4c";

    private static AppConfig.LoansConfiguration minswap(String poolAddress, String spendHash) {
        return minswap(poolAddress, spendHash, SHIPPED_POOL_POLICY);
    }

    private static AppConfig.LoansConfiguration minswap(String poolAddress, String spendHash, String policyId) {
        return new AppConfig.LoansConfiguration() {
            @Override
            public String getMinswapPoolAddress() {
                return poolAddress;
            }

            @Override
            public String getMinswapPoolSpendScriptHash() {
                return spendHash;
            }

            @Override
            public String getMinswapPoolPolicyId() {
                return policyId;
            }
        };
    }

    @Test
    void aPoolAddressThatDoesNotCarryThePoolSpendHashFailsStartupNamingBoth() {
        String wrongHash = "11".repeat(28);
        var e = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new YaciConfig().minswapPoolResolver(minswap(SHIPPED_POOL_ADDRESS, wrongHash),
                        mock(UtxoRepository.class)));

        assertTrue(e.getMessage().contains("loans.minswap.pool-address"), e.getMessage());
        assertTrue(e.getMessage().contains("loans.minswap.pool-spend-script-hash"), e.getMessage());
        assertTrue(e.getMessage().contains(SHIPPED_POOL_ADDRESS), e.getMessage());
        assertTrue(e.getMessage().contains(wrongHash), e.getMessage());
        assertTrue(e.getMessage().contains(SHIPPED_POOL_SPEND_HASH), "and the credential the address does carry: "
                + e.getMessage());
    }

    @Test
    void aMatchingPoolAddressBuildsAResolverOverTheInjectedIndex() throws Exception {
        UtxoRepository repository = mock(UtxoRepository.class);
        var resolver = new YaciConfig().minswapPoolResolver(
                minswap(SHIPPED_POOL_ADDRESS, SHIPPED_POOL_SPEND_HASH), repository);

        assertSame(repository, fieldOf(resolver, "utxoRepository"));
        assertEquals(SHIPPED_POOL_SPEND_HASH, fieldOf(resolver, "poolSpendScriptHash"));
    }

    /** ⚑ The real ada/ASCEND pool datum, read off {@code 4c805499…#1} on 2026-09-18 (as in MinswapPoolResolverTest). */
    private static final String ASCEND_POOL_DATUM = "d8799fd8799fd87a9f581c1eae96baf29e27682ea3f815aba361a0c6059d45e4bfbe95bbd2f44affffd8799f4040ffd8799f581ceb7a93ebc321647673490810f618b548d7c24aa64d30ae342dba70764a0014df10415343454e44ff1b000000ba6be20bdc1b000000ce9358c8a91b000000ed5e5802cf18641864d8799f190682ffd87980ff";
    private static final com.fluidtokens.aquarium.offchain.model.AssetType ASCEND =
            com.fluidtokens.aquarium.offchain.model.AssetType.fromUnit(
                    "eb7a93ebc321647673490810f618b548d7c24aa64d30ae342dba70760014df10415343454e44");

    private static com.bloxbean.cardano.yaci.store.common.domain.Amt amt(String unit, long quantity) {
        var amount = new com.bloxbean.cardano.yaci.store.common.domain.Amt();
        amount.setUnit(unit);
        amount.setQuantity(java.math.BigInteger.valueOf(quantity));
        return amount;
    }

    /**
     * ⛔ FAB-135 T2c (amendment 2): Yaci stores payment credentials and asset units LOWERCASE. A pool
     * hash or policy id configured in uppercase -- which the boot check accepts, comparing
     * case-insensitively -- would query a credential no row carries and match no MSP or LP unit, so
     * every pair would read "not indexed" while the pool sat in the index. The bean lowercases both.
     */
    @Test
    void anUppercaseConfiguredPoolResolverStillFindsTheIndexedPool() {
        String lp = SHIPPED_POOL_POLICY + com.fluidtokens.aquarium.offchain.service.loans.ConvertTxEncoder
                .computeLpAssetName(com.fluidtokens.aquarium.offchain.model.AssetType.ada(), ASCEND);
        AddressUtxoEntity row = new AddressUtxoEntity();
        row.setTxHash("4c805499".repeat(8));
        row.setOutputIndex(1);
        row.setOwnerAddr(SHIPPED_POOL_ADDRESS);
        row.setOwnerPaymentCredential(SHIPPED_POOL_SPEND_HASH);
        row.setInlineDatum(ASCEND_POOL_DATUM);
        row.setAmounts(new java.util.ArrayList<>(List.of(amt("lovelace", 5_000_000L),
                amt(SHIPPED_POOL_POLICY + com.fluidtokens.aquarium.offchain.service.loans.ConvertTxEncoder
                        .POOL_NFT_ASSET_NAME, 1L),
                amt(lp, 9_000_000_000L))));
        UtxoRepository repository = mock(UtxoRepository.class);
        // Answers ONLY for the lowercase credential, exactly as the database would.
        when(repository.findUnspentByOwnerPaymentCredential(
                org.mockito.ArgumentMatchers.eq(SHIPPED_POOL_SPEND_HASH),
                org.mockito.ArgumentMatchers.any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(Optional.of(List.of(row)));

        var resolver = new YaciConfig().minswapPoolResolver(minswap(SHIPPED_POOL_ADDRESS,
                SHIPPED_POOL_SPEND_HASH.toUpperCase(), SHIPPED_POOL_POLICY.toUpperCase()), repository);

        var pool = resolver.snapshot().resolveEitherOrder(com.fluidtokens.aquarium.offchain.model.AssetType.ada(), ASCEND);
        assertTrue(pool.isPresent(), "an uppercase-configured resolver must still find the indexed pool");
        assertEquals("4c805499".repeat(8), pool.get().utxo().getTxHash());
    }

    @Test
    void aBlankPoolAddressIsNotChecked() {
        assertNotNull(new YaciConfig().minswapPoolResolver(minswap("", ""), mock(UtxoRepository.class)));
    }

    @Test
    void thePoolResolverBeanIsHandedNoProvider() {
        java.lang.reflect.Method bean = java.util.Arrays.stream(YaciConfig.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("minswapPoolResolver"))
                .findFirst().orElseThrow();
        for (Class<?> parameter : bean.getParameterTypes()) {
            assertFalse(BackendService.class.isAssignableFrom(parameter)
                            || UtxoService.class.isAssignableFrom(parameter),
                    "minswapPoolResolver takes a " + parameter.getName() + ": a route back to Blockfrost");
        }
    }

    private static Object fieldOf(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    /**
     * {@link AppConfig.Network} reads its network name from an {@code @Value}-injected private field, so
     * outside a Spring context it has to be set the way Spring would set it.
     */
    private static AppConfig.Network previewNetwork() throws Exception {
        AppConfig.Network network = new AppConfig.Network();
        Field field = AppConfig.Network.class.getDeclaredField("network");
        field.setAccessible(true);
        field.set(network, "preview");
        return network;
    }
}
