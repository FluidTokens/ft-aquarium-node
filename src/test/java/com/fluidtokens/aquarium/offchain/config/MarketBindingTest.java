package com.fluidtokens.aquarium.offchain.config;

import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration;
import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration.Action;
import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration.Mode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.math.BigInteger;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>Does Spring actually BIND the market list?</b>
 *
 * <h2>Why constructing the object proves nothing here</h2>
 * {@code MarketGateTest} builds {@code Market} objects by hand and tests the rule they drive. That is a
 * different question from whether the container can fill them from configuration — the same gap that
 * let {@code CompoundExecutor} pass a whole suite and crash-loop in fourteen seconds
 * ({@code ExecutorContextResolutionTest}). <b>A configuration shape that only a test can populate is
 * not configuration.</b>
 *
 * <h2>And the specific doubt this settles</h2>
 * {@link LiquidationConfiguration} now carries {@code @ConfigurationProperties} <em>alongside</em> a
 * dozen {@code @Value} fields. Mixing the two on one class is legal but subtle: the binder fills by
 * setter, {@code @Value} fills by field, and the failure mode of getting it wrong is a silently empty
 * list — a node that reads "no markets configured" and applies every default. This asserts the mix
 * works, in both the YAML-ish and the relaxed indexed-env forms an operator will actually use.
 */
class MarketBindingTest {

    @Configuration
    // ⛔ The BINDER now owns only AppConfig.MarketProperties; LiquidationConfiguration is a plain
    // @Value component that pulls the bound list in at @PostConstruct. Splitting them is what fixed
    // the 2026-09-04 boot failure (a getter-only computed field under a bound prefix demands a setter
    // that cannot exist) — see ApplicationYamlBindsTest. The assertions below are unchanged: they
    // still read the list through LiquidationConfiguration, which is where the gate reads it.
    @EnableConfigurationProperties(AppConfig.MarketProperties.class)
    @org.springframework.context.annotation.Import(LiquidationConfiguration.class)
    static class Ctx {
    }

    private static ApplicationContextRunner runner() {
        // ⚠ loans.enabled: LiquidationConfiguration is @ConditionalOnProperty on it, so without this
        // the bean is simply absent and every assertion below would fail on a missing bean rather
        // than on the binding it is about.
        return new ApplicationContextRunner().withUserConfiguration(Ctx.class)
                .withPropertyValues("loans.enabled=true");
    }

    /** The canonical form: a list of objects with named fields. */
    @Test
    void theMarketListBindsFromNamedProperties() {
        runner().withPropertyValues(
                "loans.liquidation.markets[0].unit=lovelace",
                "loans.liquidation.markets[0].mode=SHADOW",
                "loans.liquidation.markets[0].action=CONVERT",
                "loans.liquidation.markets[1].unit=aabbccddeeff00112233445566778899aabbccddeeff001122334455",
                "loans.liquidation.markets[1].action=ANTICIPATE",
                "loans.liquidation.markets[1].cap=1000000000"
        ).run(ctx -> {
            LiquidationConfiguration cfg = ctx.getBean(LiquidationConfiguration.class);
            assertEquals(2, cfg.getMarkets().size(), "the list bound; an empty one would mean every "
                    + "market silently fell back to its default");

            var ada = cfg.getMarkets().get(0);
            assertEquals("lovelace", ada.getUnit());
            assertEquals(Mode.SHADOW, ada.getMode());
            assertEquals(Action.CONVERT, ada.getAction());

            var token = cfg.getMarkets().get(1);
            assertEquals(Action.ANTICIPATE, token.getAction());
            assertEquals(BigInteger.valueOf(1_000_000_000L), token.getCap());
            assertTrue(token.getMode() == null, "an omitted mode must stay null so it can INHERIT the "
                    + "node mode; defaulting it to a value here would break the ceiling rule");
        });
    }

    /**
     * ⚠ The form docker operators are forced into, because a YAML list is not one environment variable.
     * Relaxed binding maps {@code LOANS_LIQUIDATION_MARKETS_0_UNIT} onto {@code markets[0].unit} — and
     * if it did not, every containerised deployment would read as "no markets configured".
     */
    @Test
    void theIndexedEnvironmentVariableFormBindsToo() {
        // ⚠ It must be a SystemEnvironmentPropertySource, not an ordinary one: the SCREAMING_SNAKE
        // relaxation is a property of that source alone. Asserting this with withPropertyValues()
        // would have passed for the wrong reason and told an operator nothing about `docker run -e`.
        runner().withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addFirst(
                new SystemEnvironmentPropertySource(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        Map.of("LOANS_LIQUIDATION_MARKETS_0_UNIT", "lovelace",
                                "LOANS_LIQUIDATION_MARKETS_0_ACTION", "ANTICIPATE",
                                "LOANS_LIQUIDATION_MARKETS_0_CAP", "500000000")))
        ).run(ctx -> {
            LiquidationConfiguration cfg = ctx.getBean(LiquidationConfiguration.class);
            assertEquals(1, cfg.getMarkets().size(),
                    "indexed env names must bind, or docker operators cannot configure a market at all");
            assertEquals("lovelace", cfg.getMarkets().get(0).getUnit());
            assertEquals(Action.ANTICIPATE, cfg.getMarkets().get(0).getAction());
            assertEquals(BigInteger.valueOf(500_000_000L), cfg.getMarkets().get(0).getCap());
        });
    }

    /** No list at all is legal and means "every market at its default" — never null. */
    @Test
    void anAbsentListIsAnEmptyListAndNeverNull() {
        runner().run(ctx -> {
            LiquidationConfiguration cfg = ctx.getBean(LiquidationConfiguration.class);
            assertNotNull(cfg.getMarkets());
            assertTrue(cfg.getMarkets().isEmpty());
        });
    }

    /** ⚠ The mix must not break the {@code @Value} half of the same class. */
    @Test
    void theValueBoundFieldsStillWorkAlongsideTheBinder() {
        runner().withPropertyValues("loans.liquidation.markets[0].unit=lovelace")
                .run(ctx -> {
                    LiquidationConfiguration cfg = ctx.getBean(LiquidationConfiguration.class);
                    assertEquals(1, cfg.getMarkets().size());
                    assertEquals("disabled", cfg.getModeName(),
                            "@Value defaults must survive the class becoming @ConfigurationProperties");
                });
    }
}
