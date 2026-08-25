package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fluidtokens.aquarium.offchain.service.BlockEventListener;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import org.cardanofoundation.conversions.CardanoConverters;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The probe as Spring builds it, not as a test builds it.
 *
 * <h2>Why this exists at the wiring level</h2>
 * {@code DeploymentLivenessProbeTest} constructs the probe directly and passes the threshold as an
 * argument, so it proves the logic and nothing about the bean. Everything that could stop this class
 * from ever running in production lives outside that logic: the {@link ConditionalOnProperty} gate,
 * the {@code @Value} placeholder for the threshold, and the fact that a probe which silently fails to
 * be created is <b>indistinguishable from a probe that is running and finding nothing wrong</b>.
 * <p>
 * That last point is the whole reason to spend a test here. This class exists to break a silence, so
 * a wiring fault in it produces exactly the symptom it was built to detect — one more permanently
 * quiet signal, on top of the one it is watching for. CLAUDE.md records the 2026-08-21 incident where
 * a component was proven only through a rig that supplied what production has to earn; this is the
 * cheap inoculation against the same shape.
 */
class DeploymentLivenessProbeWiringTest {

    @Configuration
    static class StubDependencies {

        @Bean
        UtxoRepository utxoRepository() {
            return (UtxoRepository) Proxy.newProxyInstance(
                    DeploymentLivenessProbeWiringTest.class.getClassLoader(),
                    new Class<?>[]{UtxoRepository.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "findUnspentByOwnerPaymentCredential" -> Optional.of(List.of());
                        case "toString" -> "stub UtxoRepository";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        @Bean
        LoansContractRegistry registry() {
            return LoanFixtures.registry();
        }

        @Bean
        BlockEventListener blockEventListener() {
            return new BlockEventListener(null);
        }

        @Bean
        CardanoConverters cardanoConverters() {
            return LoanFixtures.converters();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(StubDependencies.class)
            .withBean(DeploymentLivenessProbe.class);

    /**
     * The shipped default has to arrive through the placeholder, not through a constant. A typo in
     * {@code ${loans.deployment-liveness.max-quiet-slots:172800}} does not fail to compile.
     */
    @Test
    void theShippedThresholdArrivesThroughThePlaceholder() {
        runner.withPropertyValues("loans.enabled=true").run(context -> {
            DeploymentLivenessProbe probe = context.getBean(DeploymentLivenessProbe.class);
            assertEquals(172_800L, threshold(probe),
                    "the 48h default must reach the bean; if this is 0 the placeholder is not binding");
        });
    }

    @Test
    void anOperatorCanOverrideTheThreshold() {
        runner.withPropertyValues("loans.enabled=true",
                        "loans.deployment-liveness.max-quiet-slots=3600")
                .run(context -> assertEquals(3_600L,
                        threshold(context.getBean(DeploymentLivenessProbe.class)),
                        "the override must win, or the knob documented in application.yaml is a lie"));
    }

    private static long threshold(DeploymentLivenessProbe probe) throws Exception {
        Field field = DeploymentLivenessProbe.class.getDeclaredField("maxQuietSlots");
        field.setAccessible(true);
        return (long) field.get(probe);
    }
}
