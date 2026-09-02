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
                .withBean(CompoundExecutor.class)
                .run(context -> {
                    assertTrue(context.getStartupFailure() == null,
                            "the context failed to start, which on a Recreate singleton is an outage "
                                    + "rather than a failed rollout: " + context.getStartupFailure());
                    assertNotNull(context.getBean(CompoundExecutor.class));
                });
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
            return new AppUtxoService(null, null, null);
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
        CompoundEconomics economics(AppConfig.CompoundConfiguration configuration,
                                    AppConfig.Network network) {
            return new CompoundEconomics(configuration, network);
        }

        @Bean
        CompoundTransactionBuilder builder(BFBackendService backendService) {
            return new CompoundTransactionBuilder(REGISTRY, Networks.preview(), backendService,
                    LoanFixtures.utxoSupplier(List.of()), LoanFixtures.protocolParams(),
                    (cbor, utxos) -> null);
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

        @Bean
        BFBackendService backendService() {
            return new BFBackendService(OFFLINE_BLOCKFROST, "dummy");
        }
    }
}
