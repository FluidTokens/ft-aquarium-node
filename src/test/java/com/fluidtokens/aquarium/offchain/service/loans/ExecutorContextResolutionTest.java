package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.service.AppUtxoService;
import com.fluidtokens.aquarium.offchain.service.BlockEventListener;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.lang.reflect.Constructor;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>Spring must be able to INSTANTIATE every executor — not merely to be handed one.</b>
 *
 * <h2>The outage this exists to prevent</h2>
 * On 2026-09-02 image {@code lending-v4-588d318} crash-looped in fourteen seconds and took the
 * preview bot down:
 * <pre>
 *   BeanCreationException: Error creating bean 'compoundExecutor'
 *     Failed to instantiate CompoundExecutor: No default constructor found
 *   Caused by: NoSuchMethodException: CompoundExecutor.&lt;init&gt;()
 * </pre>
 * {@code CompoundExecutor} has two constructors and neither carried {@code @Autowired}, so Spring
 * selected none and fell back to looking for a no-arg one. <b>Nothing in that message names the
 * cause</b>, and the whole suite was green: {@code YaciConfigWiringTest} verified the collaborating
 * bean by <em>constructing</em> it, which is a different question from whether the container can
 * <em>resolve</em> it. The gap was named in the deploy handoff and shipped anyway.
 *
 * <p><b>And on this Deployment there is no such thing as a deploy that fails safely</b> — a Recreate
 * singleton means the old pod is gone before the new one is tried, so a bad image is an outage every
 * time, not a degraded rollout. That is what makes a green suite the only affordable place to catch
 * this.
 */
class ExecutorContextResolutionTest {

    private static final String OFFLINE_BLOCKFROST = "https://example.invalid/api/v0/";

    /**
     * ⛔ THE ASSERTION THAT WOULD HAVE STOPPED THE OUTAGE. A real container, resolving the real
     * {@code @Service}, by the real rules.
     */
    @Test
    void springCanInstantiateTheCompoundExecutor() {
        new ApplicationContextRunner()
                .withPropertyValues("loans.enabled=true")
                .withUserConfiguration(StubCollaborators.class)
                .withBean(com.fluidtokens.aquarium.offchain.service.LendingConfigGate.class)
                .withBean(CompoundExecutor.class)
                .run(context -> {
                    assertTrue(context.getStartupFailure() == null,
                            "the context failed to start, which on a Recreate singleton is an outage "
                                    + "rather than a failed rollout: " + context.getStartupFailure());
                    assertNotNull(context.getBean(CompoundExecutor.class));
                });
    }

    /**
     * ⛔ FAB-115, IN A REAL CONTAINER. 2026-10-01: the live ConfigDatum stopped matching and
     * {@code LoansConfigVerifier} threw out of {@code @PostConstruct}, which failed the CONTEXT — the
     * scheduled-payment half down with the lending half. Here the real verifier is handed that exact
     * datum through Blockfrost, and the container must still start, with the one shared gate closed
     * and the executor holding that same gate (setter injection is the wiring a unit test cannot see).
     */
    @Test
    void aLendingConfigMismatchStartsTheContainerAndClosesTheSharedGate() throws Exception {
        String config = "235b32040fe1177c03b1d34febc470440c6eaaa2228a9c1b0e375200";
        String lmConfig = "fb6ae2027358b4a0b62710eb95102d87fa13f66ecf55d8943699c492";
        String asset = "706172616d6574657273";
        String smart = "fca77bcce1e5e73c97a0bfa8c90f7cd2faff6fd6ed5b6fec1c04eefa";

        var utxos = org.mockito.Mockito.mock(com.bloxbean.cardano.client.backend.api.UtxoService.class);
        for (String[] served : new String[][]{
                {config, "mainnet-config-datum-2026-09-30.hex"}, {lmConfig, "mainnet-lm-config-datum.hex"}}) {
            String datum;
            try (var in = getClass().getResourceAsStream("/loans-v4/" + served[1])) {
                datum = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            }
            String address = com.bloxbean.cardano.client.address.AddressProvider.getEntAddress(
                    com.bloxbean.cardano.client.address.Credential.fromScript(
                            com.bloxbean.cardano.client.util.HexUtil.decodeHexString(served[0])),
                    Networks.mainnet()).getAddress();
            var utxo = com.bloxbean.cardano.client.api.model.Utxo.builder()
                    .txHash("00".repeat(32)).outputIndex(0)
                    .amount(List.of(com.bloxbean.cardano.client.api.model.Amount.lovelace(BigInteger.TWO),
                            com.bloxbean.cardano.client.api.model.Amount.asset(served[0] + asset, BigInteger.ONE)))
                    .inlineDatum(datum).build();
            org.mockito.Mockito.when(utxos.getUtxos(address, 100, 1)).thenReturn(
                    com.bloxbean.cardano.client.api.model.Result.<List<com.bloxbean.cardano.client.api.model.Utxo>>success("ok")
                            .withValue(List.of(utxo)));
        }
        var bf = org.mockito.Mockito.mock(BFBackendService.class);
        org.mockito.Mockito.when(bf.getUtxoService()).thenReturn(utxos);
        var mainnet = new AppConfig.Network();
        mainnet.setNetworkForTest("mainnet");

        new ApplicationContextRunner()
                .withUserConfiguration(StubCollaborators.class)
                .withBean(com.fluidtokens.aquarium.offchain.service.LendingConfigGate.class)
                .withBean(com.fluidtokens.aquarium.offchain.service.LoansConfigVerifier.class,
                        () -> new com.fluidtokens.aquarium.offchain.service.LoansConfigVerifier(
                                new LoansContractRegistry(config, lmConfig, asset, smart, null, null, null),
                                smart, mainnet, bf, false))
                .withBean(CompoundExecutor.class)
                .run(context -> {
                    assertTrue(context.getStartupFailure() == null,
                            "a Lending v4 config mismatch must not fail the context: " + context.getStartupFailure());
                    var gate = context.getBean(com.fluidtokens.aquarium.offchain.service.LendingConfigGate.class);
                    assertTrue(gate.isBlocked(), "the verifier must close the SHARED gate");
                    assertTrue(context.getBean(com.fluidtokens.aquarium.offchain.service.LoansConfigVerifier.class)
                            .gate() == gate, "the verifier must write the container's gate, not a private one");
                    assertTrue(org.springframework.test.util.ReflectionTestUtils.getField(
                                    context.getBean(CompoundExecutor.class), "lendingConfigGate") == gate,
                            "the executor must read the same gate the verifier closed");
                });
    }

    /**
     * ⛔ FAB-115 round-2 finding 3. The reference-script verifier must close the SHARED gate in a real
     * container — a verifier that kept a private gate would log the refusal while the executors carried
     * on against stale coordinates (a phase-2 failure, collateral forfeit).
     */
    @Test
    void aReferenceScriptMismatchClosesTheContainersSharedGate() {
        var claimOnly = new AppConfig.LiquidationConfiguration(
                AppConfig.LiquidationConfiguration.Mode.SHADOW, 60, 120, 30, BigInteger.ZERO, 200,
                new LiquidateTransactionBuilder.ReferenceScripts(null, null, null, null,
                        new com.bloxbean.cardano.client.transaction.spec.TransactionInput("ab".repeat(32), 0),
                        null, null));
        com.fluidtokens.aquarium.offchain.service.LoansReferenceScriptVerifier.TxOutputLookup foreign =
                (tx, ix) -> com.bloxbean.cardano.client.api.model.Result
                        .<com.bloxbean.cardano.client.api.model.Utxo>success("ok")
                        .withValue(com.bloxbean.cardano.client.api.model.Utxo.builder().txHash(tx).outputIndex(ix)
                                .referenceScriptHash("de".repeat(28)).build());

        new ApplicationContextRunner()
                .withBean(com.fluidtokens.aquarium.offchain.service.LendingConfigGate.class)
                .withBean(com.fluidtokens.aquarium.offchain.service.LoansReferenceScriptVerifier.class,
                        () -> new com.fluidtokens.aquarium.offchain.service.LoansReferenceScriptVerifier(
                                LoanFixtures.registry(), claimOnly, foreign, false))
                .run(context -> {
                    assertTrue(context.getStartupFailure() == null,
                            "a reference-script mismatch must not fail the context: " + context.getStartupFailure());
                    var gate = context.getBean(com.fluidtokens.aquarium.offchain.service.LendingConfigGate.class);
                    assertTrue(context.getBean(com.fluidtokens.aquarium.offchain.service.LoansReferenceScriptVerifier.class)
                            .gate() == gate, "the verifier must write the container's gate, not a private one");
                    assertTrue(gate.isBlocked(), "and that shared gate must be closed");
                });
    }

    /**
     * ⛔ FAB-115 audit findings 1–2. The gate only works if EVERY reader and writer is handed the SAME
     * container bean. Injection is by setter and REQUIRED, so a missing gate bean fails the boot loudly
     * instead of leaving lending ungated (it used to be optional and failed open). This pins, for each
     * class, the annotation a container test cannot cheaply reach — LiquidationExecutor and the readiness
     * controller are too expensive to stand up — and that the gate is a scanned component at all.
     */
    @Test
    void everyLendingGateReaderAndWriterHasTheGateInjectedAndTheGateIsAComponent() throws Exception {
        var gateType = com.fluidtokens.aquarium.offchain.service.LendingConfigGate.class;
        assertTrue(gateType.isAnnotationPresent(Component.class),
                "LendingConfigGate must be a @Component, or nothing in the container is handed it");
        assertTrue(gateType.getPackageName().startsWith(
                        com.fluidtokens.aquarium.offchain.AcquariumOffchainApp.class.getPackageName()),
                "and it must live under the application's component-scan base");

        for (Class<?> type : List.of(
                com.fluidtokens.aquarium.offchain.service.LoansConfigVerifier.class,
                com.fluidtokens.aquarium.offchain.service.LoansReferenceScriptVerifier.class,
                LiquidationExecutor.class,
                CompoundExecutor.class,
                com.fluidtokens.aquarium.offchain.controller.LiquidationReadinessController.class)) {
            var setter = type.getMethod("setLendingConfigGate", gateType);
            Autowired autowired = setter.getAnnotation(Autowired.class);
            assertNotNull(autowired, type.getSimpleName() + ".setLendingConfigGate is not @Autowired — the "
                    + "container would leave it holding no gate, so a closed gate would not stop it");
            assertTrue(autowired.required(), type.getSimpleName() + ".setLendingConfigGate must be "
                    + "REQUIRED: optional injection fails open when the gate bean is missing");
        }
    }

    /**
     * FAB-136: the startup wallet sweep and its readiness gate are gone. None of the former readers may
     * still declare the setter or hold the field — a leftover setter would be dead injection, and a
     * leftover field a gate nothing opens. Matched by NAME, because the type itself no longer exists.
     */
    @Test
    void noFormerReaderStillDeclaresTheWalletReadinessSetterOrField() {
        for (Class<?> type : List.of(
                com.fluidtokens.aquarium.offchain.service.ScheduledTransactionService.class,
                LiquidationExecutor.class,
                CompoundExecutor.class,
                com.fluidtokens.aquarium.offchain.controller.Healthcheck.class,
                com.fluidtokens.aquarium.offchain.controller.LiquidationReadinessController.class)) {
            for (var method : type.getDeclaredMethods()) {
                assertFalse(method.getName().equals("setWalletReadiness"),
                        type.getSimpleName() + " still declares setWalletReadiness");
            }
            for (var field : type.getDeclaredFields()) {
                assertFalse(field.getType().getSimpleName().equals("WalletReadiness"),
                        type.getSimpleName() + "." + field.getName() + " is still a WalletReadiness");
            }
        }
    }

    /**
     * The generalisation, so the next executor cannot repeat it. Spring's rule is simple and
     * unforgiving: with more than one constructor it will not guess. This encodes the rule itself
     * rather than one instance of it, and it needs no container, so it also covers classes whose
     * collaborators are too expensive to stub.
     */
    @Test
    void everyMultiConstructorExecutorNamesTheOneSpringShouldUse() {
        List<Class<?>> managed = List.of(
                CompoundExecutor.class,
                LiquidationExecutor.class,
                CompoundCandidateScanner.class,
                CompoundEconomics.class,
                LiquidationCandidateScanner.class,
                LiquidationUtxoResolver.class,
                LenderBondService.class);

        for (Class<?> type : managed) {
            assertTrue(type.isAnnotationPresent(Service.class) || type.isAnnotationPresent(Component.class),
                    type.getSimpleName() + " is listed here as container-managed but carries no "
                            + "stereotype annotation — either annotate it or drop it from this list");

            Constructor<?>[] constructors = type.getDeclaredConstructors();
            if (constructors.length <= 1) {
                continue;
            }
            long annotated = Arrays.stream(constructors)
                    .filter(c -> c.isAnnotationPresent(Autowired.class))
                    .count();
            assertEquals(1L, annotated,
                    type.getSimpleName() + " has " + constructors.length + " constructors and "
                            + annotated + " marked @Autowired. Spring will not guess: it looks for a "
                            + "no-arg constructor, finds none, and the CONTEXT FAILS TO START with "
                            + "NoSuchMethodException — which names nothing. Exactly one constructor "
                            + "must be annotated. See this class's javadoc for the outage.");
        }
    }

    /** Stubs for everything the executor is handed; none of them is exercised, only resolved. */
    @Configuration
    static class StubCollaborators {

        private static final LoansContractRegistry REGISTRY = LoanFixtures.shippedPreviewRegistry();

        @Bean
        AppConfig.CompoundConfiguration compoundConfiguration() {
            return new AppConfig.CompoundConfiguration(false, 60L, BigInteger.ZERO);
        }

        @Bean
        AppConfig.Network network() {
            var n = new AppConfig.Network();
            org.springframework.test.util.ReflectionTestUtils.setField(n, "network", "preview");
            return n;
        }

        @Bean
        BlockEventListener blockEventListener() {
            return new BlockEventListener(null);
        }

        @Bean
        AppUtxoService appUtxoService() {
            return new AppUtxoService(null, null);
        }

        @Bean
        Account account() {
            return new Account(Networks.preview());
        }

        @Bean
        LoansContractRegistry registry() {
            return REGISTRY;
        }

        @Bean
        CompoundCandidateScanner scanner() {
            return new CompoundCandidateScanner(null, REGISTRY, null, null);
        }

        @Bean
        PricingService pricingService() {
            return new PricingService(new FluidOracleClient("http://unused.invalid"));
        }

        @Bean
        CompoundEconomics economics(AppConfig.CompoundConfiguration configuration,
                                    AppConfig.Network network, PricingService pricingService) {
            return new CompoundEconomics(configuration, network, pricingService);
        }

        @Bean
        CompoundTransactionBuilder builder(BFBackendService backendService) {
            return new CompoundTransactionBuilder(REGISTRY, Networks.preview(),
                    LoanFixtures.utxoSupplier(List.of()), LoanFixtures.protocolParams(),
                    scriptHash -> java.util.Optional.empty(), (cbor, utxos) -> null);
        }

        @Bean
        LiquidationUtxoResolver utxoResolver() {
            return new LiquidationUtxoResolver(null, REGISTRY, null);
        }

        @Bean
        com.bloxbean.cardano.client.api.UtxoSupplier utxoSupplier() {
            return LoanFixtures.utxoSupplier(List.of());
        }

        @Bean
        org.cardanofoundation.conversions.CardanoConverters converters() {
            return LoanFixtures.converters();
        }

        /** FAB-134 B5a: the Spring constructor takes the supplier a params rejection refreshes. */
        @Bean
        com.bloxbean.cardano.client.api.ProtocolParamsSupplier protocolParamsSupplier() {
            return LoanFixtures.protocolParams();
        }

        @Bean
        BFBackendService backendService() {
            return new BFBackendService(OFFLINE_BLOCKFROST, "dummy");
        }
    }
}
