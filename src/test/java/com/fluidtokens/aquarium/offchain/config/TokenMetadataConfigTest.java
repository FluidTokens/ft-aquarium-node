package com.fluidtokens.aquarium.offchain.config;

import javax.sql.DataSource;

import com.fluidtokens.aquarium.offchain.service.loans.TokenMetadataService;
import com.fluidtokens.aquarium.offchain.service.loans.TokenRegistryClient;
import com.fluidtokens.aquarium.offchain.storage.TokenMetadataStore;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ⛔ <b>Off means absent, not merely quiet.</b>
 *
 * <p>The requirement was that every endpoint added for the operator UI is switched off when the UI
 * is. The strong form of that is what these tests pin: with {@code loans.ui.enabled} unset or false,
 * <b>no bean exists</b> that could make an outbound request — not a disabled one, not one guarding
 * itself at each call. A node with the UI off is unchanged by this feature.
 *
 * <p>Testing the gate matters more than it looks: {@code @ConditionalOnProperty} failures are silent
 * in both directions. A wrong prefix leaves the beans always on, and nothing in a normal run says so.
 */
class TokenMetadataConfigTest {

    @Configuration
    static class StubDataSource {
        @Bean
        DataSource dataSource() {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:gate-test");
            return ds;
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(StubDataSource.class, TokenMetadataConfig.class);

    @Test
    void withTheUiOffNoTokenMetadataBeanExistsAtAll() {
        runner.run(context -> {
            org.assertj.core.api.Assertions.assertThat(context).doesNotHaveBean(TokenMetadataService.class);
            org.assertj.core.api.Assertions.assertThat(context).doesNotHaveBean(TokenRegistryClient.class);
            org.assertj.core.api.Assertions.assertThat(context).doesNotHaveBean(TokenMetadataStore.class);
        });
    }

    @Test
    void anExplicitFalseIsAlsoOff() {
        runner.withPropertyValues("loans.ui.enabled=false").run(context ->
                org.assertj.core.api.Assertions.assertThat(context).doesNotHaveBean(TokenMetadataService.class));
    }

    /** The control: the same wiring must actually build when the UI is on, or the gate proves nothing. */
    @Test
    void withTheUiOnTheServiceIsBuilt() {
        runner.withPropertyValues("loans.ui.enabled=true").run(context -> {
            org.assertj.core.api.Assertions.assertThat(context).hasSingleBean(TokenMetadataService.class);
            org.assertj.core.api.Assertions.assertThat(context).hasSingleBean(TokenRegistryClient.class);
            org.assertj.core.api.Assertions.assertThat(context).hasSingleBean(TokenMetadataStore.class);
        });
    }
}
