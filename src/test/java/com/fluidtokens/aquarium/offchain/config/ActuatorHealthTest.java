package com.fluidtokens.aquarium.offchain.config;

import com.bloxbean.cardano.yaci.store.common.domain.HealthStatus;
import com.bloxbean.cardano.yaci.store.common.domain.SyncStatus;
import com.bloxbean.cardano.yaci.store.core.service.HealthService;
import com.bloxbean.cardano.yaci.store.core.service.SyncStatusService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.actuate.autoconfigure.health.HealthContributorAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.health.HealthEndpointAutoConfiguration;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.actuate.health.NamedContributor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ⛔ FAB-203 (D6): <b>{@code /actuator/health} answers 200 UP with a stalled tip, with no relay and mid-sync</b>,
 * which is what it answered on Yaci Store 0.1.7 and what the Helm chart's startup and liveness probes need.
 *
 * <p>Every context is {@link YaciStoreContextHarness}'s — the shipped YAML through Yaci's real auto-configuration —
 * with Yaci's {@link HealthService} (and {@link SyncStatusService}) replaced by a mock answering the scenario. ⚠
 * Without that the test is vacuous: before a block fetcher exists both Yaci indicators answer UNKNOWN, which the
 * aggregate reads as UP whether or not they are excluded. {@link #withoutTheExclusionAStalledTipIs503()} is the
 * control that shows the harness reaches them.
 */
class ActuatorHealthTest {

    @Test
    void aStalledTipIsUp() {
        YaciStoreContextHarness.runner()
                .withUserConfiguration(YaciHealthIndicatorExclusion.class, StalledTip.class)
                .run(context -> assertHealth(context, 200, "UP"));
    }

    @Test
    void noReachableRelayIsUp() {
        YaciStoreContextHarness.runner()
                .withUserConfiguration(YaciHealthIndicatorExclusion.class, NoRelay.class)
                .run(context -> assertHealth(context, 200, "UP"));
    }

    @Test
    void midSyncIsUp() {
        YaciStoreContextHarness.runner()
                .withUserConfiguration(YaciHealthIndicatorExclusion.class, MidSync.class)
                .run(context -> assertHealth(context, 200, "UP"));
    }

    @Test
    void noYaciContributorIsRegisteredAndBootsOwnAreKept() {
        YaciStoreContextHarness.runner()
                .withUserConfiguration(YaciHealthIndicatorExclusion.class, StalledTip.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    Map<String, String> contributors = contributors(context.getBean(HealthContributorRegistry.class));
                    assertFalse(contributors.containsKey("nodeSync"), "nodeSync is registered: " + contributors);
                    assertFalse(contributors.containsKey("nodeHealth"), "nodeHealth is registered: " + contributors);
                    assertEquals(Map.of(), filterValues(contributors, "com.bloxbean.cardano.yaci"),
                            "a Yaci health contributor is registered");
                    for (String kept : new String[]{"db", "diskSpace", "ping"}) {
                        assertTrue(contributors.containsKey(kept), kept + " is missing: " + contributors);
                    }
                });
    }

    /** Control: the same stalled tip without the exclusion is what an unpatched 2.0.7 node answers. */
    @Test
    void withoutTheExclusionAStalledTipIs503() {
        YaciStoreContextHarness.runner()
                .withUserConfiguration(StalledTip.class)
                .run(context -> {
                    assertHealth(context, 503, "OUT_OF_SERVICE");
                    Map<String, String> contributors = contributors(context.getBean(HealthContributorRegistry.class));
                    assertTrue(contributors.containsKey("nodeSync"), "control: nodeSync missing: " + contributors);
                    assertTrue(contributors.containsKey("nodeHealth"), "control: nodeHealth missing: " + contributors);
                });
    }

    /** Production never lists the class: it exists only if the node's own component scan finds it. */
    @Test
    void productionsComponentScanFindsTheExclusion() {
        assertTrue(AnnotatedElementUtils.hasAnnotation(YaciHealthIndicatorExclusion.class, Component.class),
                "YaciHealthIndicatorExclusion carries no @Component stereotype, so the node never creates it");
        assertTrue(YaciHealthIndicatorExclusion.class.getPackageName().startsWith("com.fluidtokens.aquarium.offchain"),
                "YaciHealthIndicatorExclusion is outside AcquariumOffchainApp's scan root");
    }

    @Test
    void aContextWithoutYaciStartsCleanly() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        HealthContributorAutoConfiguration.class, HealthEndpointAutoConfiguration.class))
                .withUserConfiguration(YaciHealthIndicatorExclusion.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(YaciHealthIndicatorExclusion.class);
                    assertNotNull(context.getBean(HealthContributorRegistry.class));
                });
    }

    // ======================================================================================

    private static void assertHealth(AssertableWebApplicationContext context, int httpStatus, String healthStatus)
            throws Exception {
        assertThat(context).hasNotFailed();
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().is(httpStatus))
                .andExpect(jsonPath("$.status").value(healthStatus));
    }

    /** Contributor name to contributor class name. */
    private static Map<String, String> contributors(HealthContributorRegistry registry) {
        Map<String, String> contributors = new HashMap<>();
        for (NamedContributor<?> contributor : registry) {
            contributors.put(contributor.getName(), contributor.getContributor().getClass().getName());
        }
        return contributors;
    }

    private static Map<String, String> filterValues(Map<String, String> contributors, String classPrefix) {
        Map<String, String> matching = new HashMap<>();
        contributors.forEach((name, type) -> {
            if (type.startsWith(classPrefix)) {
                matching.put(name, type);
            }
        });
        return matching;
    }

    /** Replaces Yaci's {@link HealthService} and {@link SyncStatusService} beans with mocks answering a scenario. */
    private static BeanPostProcessor answering(HealthStatus health, SyncStatus sync) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof HealthService) {
                    HealthService healthService = mock(HealthService.class);
                    when(healthService.getHealthStatus()).thenReturn(health);
                    return healthService;
                }
                if (sync != null && bean instanceof SyncStatusService) {
                    SyncStatusService syncStatusService = mock(SyncStatusService.class);
                    when(syncStatusService.getSyncStatus()).thenReturn(sync);
                    return syncStatusService;
                }
                return bean;
            }
        };
    }

    /** Connected, but no block for longer than {@code store.block-receive-delay-seconds}. */
    @Configuration(proxyBeanMethods = false)
    static class StalledTip {
        @Bean
        static BeanPostProcessor yaciHealthScenario() {
            return answering(HealthStatus.builder().isConnectionAlive(true).isReceivingBlocks(false).build(), null);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class NoRelay {
        @Bean
        static BeanPostProcessor yaciHealthScenario() {
            return answering(HealthStatus.builder().isConnectionAlive(false).build(), null);
        }
    }

    /** Connected and receiving, but far from the tip. */
    @Configuration(proxyBeanMethods = false)
    static class MidSync {
        @Bean
        static BeanPostProcessor yaciHealthScenario() {
            return answering(HealthStatus.builder().isConnectionAlive(true).isReceivingBlocks(true).build(),
                    SyncStatus.builder().synced(false).build());
        }
    }
}
