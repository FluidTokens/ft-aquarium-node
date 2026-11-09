package com.fluidtokens.aquarium.offchain.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Keeps Yaci Store's two health indicators out of {@code /actuator/health} (FAB-203, D6), so that endpoint answers
 * 200 UP with a stalled tip, with no reachable relay and mid-sync — exactly what it answered on Yaci Store 0.1.7.
 *
 * <h2>Why</h2>
 * Yaci Store 2.x registers {@code com.bloxbean.cardano.yaci.store.core.health.NodeHealthIndicator} (bean
 * {@code nodeHealth}) and {@code com.bloxbean.cardano.yaci.store.core.health.NodeSyncIndicator} (bean
 * {@code nodeSync}), and Spring Boot folds every health indicator into the root aggregate. {@code nodeHealth}
 * answers DOWN without a relay connection and OUT_OF_SERVICE once no block has arrived for
 * {@code store.block-receive-delay-seconds} (120 s by default, a gap preview's chain routinely has);
 * {@code nodeSync} answers OUT_OF_SERVICE for the whole of a sync. Either one turns the root status into 503.
 * <p>
 * The Helm chart ({@code charts/ft-aquarium-node/values.yaml}) points its {@code startupProbe} (60 × 10 s) and its
 * {@code livenessProbe} at {@code /actuator/health}. With these indicators in the aggregate, a node on a fresh sync
 * outlasts the startup budget and is restarted, which restarts the sync, forever; a synced node fails liveness
 * whenever the chain pauses past the threshold above. A liveness probe must answer "is the process alive", never "is
 * the indexer caught up". The compose healthcheck ({@code /healthcheck}) is a different endpoint and is not affected.
 *
 * <h2>How</h2>
 * The two bean definitions are removed before any bean is instantiated, so the indicators are never constructed and
 * no health contributor is ever registered under either name. A name that is not defined is skipped: read-only mode
 * (where Yaci's {@code @ConditionalOnBean(HealthService.class)} already leaves both out) and a context without Yaci
 * start exactly as before. No property switches this off.
 */
@Slf4j
@Component
public class YaciHealthIndicatorExclusion implements BeanDefinitionRegistryPostProcessor {

    /** The bean names Yaci Store 2.x registers its two health indicators under. */
    static final List<String> EXCLUDED_HEALTH_INDICATORS = List.of("nodeSync", "nodeHealth");

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        for (String name : EXCLUDED_HEALTH_INDICATORS) {
            if (registry.containsBeanDefinition(name)) {
                registry.removeBeanDefinition(name);
                log.info("Yaci Store health indicator '{}' kept out of /actuator/health (FAB-203)", name);
            }
        }
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        // Nothing: the work is done on the registry, before any bean exists.
    }
}
