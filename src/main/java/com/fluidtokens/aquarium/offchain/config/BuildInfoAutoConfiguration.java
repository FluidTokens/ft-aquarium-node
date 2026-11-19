package com.fluidtokens.aquarium.offchain.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * The default {@link BuildInfo} (FAB-213).
 *
 * <p>⛔ <b>An auto-configuration, not a component-scanned {@code @Configuration}</b>, and listed in
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}. Boot's
 * {@code AutoConfigurationExcludeFilter} keeps such a class out of component scanning, so it is registered once,
 * after the user's beans — which is what lets {@code @ConditionalOnMissingBean} see a user's own {@link BuildInfo}
 * and back off, instead of a second definition of the same bean name overriding or colliding with it.
 */
@AutoConfiguration
public class BuildInfoAutoConfiguration {

    /**
     * The two placeholders the oracle stream launcher has always read, with the same {@code unknown} fallback (the
     * banner's), so the header is unchanged.
     */
    @Bean
    @ConditionalOnMissingBean
    public BuildInfo buildInfo(@Value("${aquarium.build.commitShort:unknown}") String commitShort,
                               @Value("${aquarium.build.dirty:unknown}") String dirty) {
        return new BuildInfo(commitShort, dirty);
    }
}
