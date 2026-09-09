package com.fluidtokens.aquarium.offchain.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>Does the REAL {@code application.yaml} actually bind to the config beans?</b>
 *
 * <h2>The gap this closes, and it cost a shipped image</h2>
 * On 2026-09-04 the image built, pulled and then <b>failed to start</b>:
 *
 * <pre>Failed to bind properties under 'loans.liquidation' … Property: loans.liquidation.mode
 * Reason: No setter found for property: mode</pre>
 *
 * <b>A 911-test suite was green.</b> Every config test in this package drives the binder with
 * hand-written properties — {@code withPropertyValues("loans.liquidation.markets[0].unit=…")} — and a
 * test that supplies {@code markets} has no reason to also supply {@code mode}. <b>The real file
 * supplies both, and that combination existed nowhere in the suite.</b>
 *
 * <p>⚠ It was not a one-field slip either: {@code reference-scripts} is also a real key in the file and
 * also a getter-only computed field, so it would have failed the instant {@code mode} was patched.
 * <b>A fixture assembled by hand cannot fail on a key the author did not think to write down</b> —
 * which is the same family as a fixture produced by the mechanism under test, one step earlier.
 *
 * <h2>⇒ So this test supplies NOTHING. It loads the shipped file.</h2>
 * The property source here is {@code application.yaml} itself, parsed by Spring's own loader, for the
 * default document and for the {@code preview} profile. If a key in that file cannot reach the bean it
 * names, this goes red — in seconds, on a laptop, before an image exists.
 *
 * <p>⚠ <b>What it does NOT prove:</b> that the whole container starts. Bean wiring, missing
 * collaborators and {@code @PostConstruct} failures that need other beans are a different seam — the
 * 14-second preview crash from the unwired convert beans was one of those. This is the configuration
 * half, and it is the half that has now broken twice.
 */
class ApplicationYamlBindsTest {

    /** The shipped file, parsed by Spring's own YAML loader — not a hand-built approximation. */
    private static List<PropertySource<?>> shippedYaml() throws IOException {
        return new YamlPropertySourceLoader().load("application.yaml",
                new ClassPathResource("application.yaml"));
    }

    /**
     * ⚠ Document 0 is the defaults (mainnet), document 1 the {@code preview} profile. Spring
     * normally activates one by profile; here both are asserted, because a key that binds under one
     * document and not the other is exactly the shape that ships and then fails on one network only.
     */
    private static ApplicationContextRunner withDocument(int index) throws IOException {
        List<PropertySource<?>> docs = shippedYaml();
        assertTrue(docs.size() > index,
                "application.yaml has fewer than " + (index + 1) + " documents — the profile split moved");
        PropertySource<?> doc = docs.get(index);
        return new ApplicationContextRunner()
                .withUserConfiguration(Ctx.class)
                // ⚠ The ONE property supplied by hand, and it is not one of the values under test:
                // the lending beans are @ConditionalOnProperty(loans.enabled), which ships false so
                // that an operator who wants nothing gets nothing. Without it there are no beans to
                // bind to and the test would pass by being vacuous — the exact shape it exists to
                // catch. Everything else comes from the file.
                .withPropertyValues("loans.enabled=true")
                // ⚠ addLast, not addFirst: the file itself carries loans.enabled=${LOANS_ENABLED:false},
                // so a yaml source added FIRST wins over the arming flag above and the beans vanish —
                // measured, and it made the whole test vacuous while reporting green on the binding.
                .withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addLast(doc));
    }

    /**
     * ⛔ {@code @EnableConfigurationProperties} is load-bearing and was MISSING on the first attempt.
     *
     * <p>Without it {@code ApplicationContextRunner} never registers Boot's
     * {@code ConfigurationPropertiesBindingPostProcessor}, so <b>the binder does not run at all</b> —
     * only {@code @Value} resolution does. The test passed, and it passed identically with the defect
     * deliberately put back. <b>It was measuring nothing.</b> Caught by reintroducing the bug and
     * watching for red; it stayed green, which is the only reason this line exists.
     */
    @Configuration
    @EnableConfigurationProperties
    @Import({AppConfig.LiquidationConfiguration.class, AppConfig.MarketProperties.class,
            AppConfig.CompoundConfiguration.class, AppConfig.Network.class})
    static class Ctx {
    }

    /**
     * ⛔ THE ASSERTION. Every document of the shipped file must bind cleanly to the beans it
     * configures. A failure here names the exact property, which is the whole point.
     */
    @ParameterizedTest(name = "application.yaml document {0} binds")
    @ValueSource(ints = {0, 1})
    void everyDocumentOfTheShippedYamlBinds(int document) throws IOException {
        withDocument(document).run(ctx -> {
            if (ctx.getStartupFailure() != null) {
                throw new AssertionError("application.yaml document " + document + " does not bind to "
                        + "the config beans. This is the failure that ships a container which cannot "
                        + "start, and no hand-written property fixture can catch it:\n"
                        + describe(ctx.getStartupFailure()), ctx.getStartupFailure());
            }
        });
    }

    /** ⚠ The values an operator actually reads back must survive the round trip, not merely parse. */
    @ParameterizedTest(name = "document {0} yields a usable liquidation configuration")
    @ValueSource(ints = {0, 1})
    void theBoundConfigurationIsUsableAndNotJustParseable(int document) throws IOException {
        withDocument(document).run(ctx -> {
            if (ctx.getStartupFailure() != null) {
                return; // the test above owns that failure; do not report it twice
            }
            var cfg = ctx.getBean(AppConfig.LiquidationConfiguration.class);
            assertNotNull(cfg, "the liquidation configuration bean is absent — loans.enabled did not "
                    + "take, and every assertion below would have passed vacuously");
            assertNotNull(cfg.getMode(), "mode parsed to null — parseMode() did not run or was bypassed");
            assertNotNull(cfg.getMarkets(), "the market list must never be null, only empty");
            assertNotNull(cfg.getReferenceScripts(), "reference scripts must never be null");
            assertNotNull(cfg.getProfitMarginLovelace());
        });
    }

    /**
     * ⛔ The regression pinned by name. {@code loans.liquidation.mode} is a real key in the shipped
     * file AND a computed field on the bean; binding the two together is what failed.
     */
    @Test
    void theModeKeyInTheShippedFileReachesTheBean() throws IOException {
        withDocument(0).run(ctx -> {
            assertTrue(ctx.getStartupFailure() == null,
                    "loans.liquidation.mode still does not bind: " + describe(ctx.getStartupFailure()));
            assertEquals(AppConfig.LiquidationConfiguration.Mode.DISABLED,
                    ctx.getBean(AppConfig.LiquidationConfiguration.class).getMode(),
                    "the shipped default is `disabled`; if this moved, the defaults changed");
        });
    }

    private static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null && sb.length() < 2000; c = c.getCause()) {
            sb.append("  ").append(c.getClass().getSimpleName()).append(": ")
                    .append(String.valueOf(c.getMessage()).replace('\n', ' ')).append('\n');
        }
        return sb.toString();
    }
}
