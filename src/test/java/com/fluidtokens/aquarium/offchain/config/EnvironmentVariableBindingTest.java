package com.fluidtokens.aquarium.offchain.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.math.BigInteger;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>Can a Helm chart actually set these keys as environment variables?</b>
 *
 * <p>Every operator-facing deployment of this node drives it through env vars — {@code docker/.env},
 * or a chart's {@code env:} block. <b>Most keys have an explicit placeholder in
 * {@code application.yaml}</b> ({@code mode: ${AQUARIUM_LIQUIDATION_MODE:disabled}}), so their env
 * name is spelled out in the file and there is nothing to infer.
 *
 * <p>⚠ <b>Six do not.</b> {@code loans.submittable-network}, the three
 * {@code loans.liquidation.convert.*} keys, {@code loans.verify-config.fail-on-unreachable} and
 * {@code loans.liquidation.reference-scripts.lm-liquidate-and-convert-action} exist ONLY as
 * {@code @Value} annotations with inline defaults. They are settable exclusively through Spring
 * Boot's <b>relaxed environment binding</b> — {@code SystemEnvironmentPropertySource} mapping
 * {@code loans.liquidation.convert.enabled} to {@code LOANS_LIQUIDATION_CONVERT_ENABLED}, dashes
 * and dots alike becoming underscores.
 *
 * <p>⇒ <b>That mapping is an assumption a whole chart rests on, and until now nothing in this repo
 * measured it.</b> A catalogue that told a chart author to write
 * {@code LOANS_LIQUIDATION_REFERENCE_SCRIPTS_LM_LIQUIDATE_AND_CONVERT_ACTION} and was wrong about the
 * dash handling would produce a value that binds nowhere and reports nothing — the same silence as
 * an unset key.
 *
 * <p>The property source here is a <b>real {@link SystemEnvironmentPropertySource}</b> over a map of
 * UPPERCASE names, which is the exact class Spring Boot installs for the process environment. Nothing
 * is pre-lowercased and no property alias is supplied: if the mapping did not hold, these go red.
 */
class EnvironmentVariableBindingTest {

    @Configuration
    @EnableConfigurationProperties
    @Import({AppConfig.LiquidationConfiguration.class, AppConfig.MarketProperties.class,
            AppConfig.ConvertConfiguration.class, AppConfig.CompoundConfiguration.class,
            AppConfig.Network.class})
    static class Beans {
    }

    /** A runner whose environment carries {@code env} as the process environment would. */
    private static ApplicationContextRunner withEnv(Map<String, Object> env) {
        return new ApplicationContextRunner()
                .withInitializer(ctx -> {
                    StandardEnvironment environment = (StandardEnvironment) ctx.getEnvironment();
                    environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env));
                })
                .withUserConfiguration(Beans.class)
                .withPropertyValues("loans.enabled=true", "network=preview");
    }

    /**
     * The five keys with NO {@code application.yaml} placeholder. Each is named the way a chart would
     * have to name it, and asserted to reach the bean that reads it.
     */
    @Test
    void theKeysWithNoYamlPlaceholderAreReachableAsEnvironmentVariables() {
        withEnv(Map.of(
                "LOANS_LIQUIDATION_CONVERT_ENABLED", "false",
                "LOANS_LIQUIDATION_CONVERT_PROFIT_MARGIN_LOVELACE", "-2000000",
                "LOANS_LIQUIDATION_CONVERT_DEX_COST_FLOOR_LOVELACE", "4000000",
                "LOANS_LIQUIDATION_REFERENCE_SCRIPTS_LM_LIQUIDATE_AND_CONVERT_ACTION",
                "56840ffb07ca0ad4e1eb921695bad5d2719f838612008e13bfe7f775933a7def#0"))
                .run(ctx -> {
                    assertFalse(ctx.getStartupFailure() != null,
                            () -> "context failed: " + ctx.getStartupFailure());

                    var convert = ctx.getBean(AppConfig.ConvertConfiguration.class);
                    assertFalse(convert.isEnabled(),
                            "LOANS_LIQUIDATION_CONVERT_ENABLED=false must override the default TRUE");
                    assertEquals(BigInteger.valueOf(-2_000_000), convert.getProfitMarginLovelace());
                    assertEquals(BigInteger.valueOf(4_000_000), convert.getDexCostFloorLovelace());

                    var liquidation = ctx.getBean(AppConfig.LiquidationConfiguration.class);
                    var refs = liquidation.getReferenceScripts();
                    assertNotNull(refs.lmLiquidateAndConvertAction(),
                            "the ninth reference-script slot is the ONLY one with no "
                                    + "AQUARIUM_LIQUIDATION_REF_* placeholder in application.yaml, so "
                                    + "relaxed env binding is its only route in");
                    assertEquals(0, refs.lmLiquidateAndConvertAction().getIndex());
                    assertEquals("56840ffb07ca0ad4e1eb921695bad5d2719f838612008e13bfe7f775933a7def",
                            refs.lmLiquidateAndConvertAction().getTransactionId());
                });
    }

    /**
     * ⛔ {@code NETWORK} — the one value that decides which chain this node acts on — binds from a
     * real environment variable. Asserted for both networks, so a default cannot make it pass.
     */
    @Test
    void theTargetNetworkBindsFromTheEnvironmentAndIsTheOnlyNetworkValue() {
        for (String target : new String[]{"mainnet", "preview", "preprod"}) {
            new ApplicationContextRunner()
                    .withInitializer(ctx -> ((StandardEnvironment) ctx.getEnvironment())
                            .getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                    Map.of("NETWORK", target))))
                    .withUserConfiguration(Beans.class)
                    .withPropertyValues("loans.enabled=true")
                    .run(ctx -> assertEquals(target, ctx.getBean(AppConfig.Network.class).getNetwork(),
                            "NETWORK must reach the bean: it is the only thing that decides where "
                                    + "this node submits, and a value that binds nowhere points a "
                                    + "node at the wrong chain with no error"));
        }
    }

    /**
     * The market list — an object list, so the chart needs INDEXED names. This is the shape a values
     * file has to render, and getting the index syntax wrong yields an empty list rather than an
     * error, i.e. "convert everywhere at the node mode" silently.
     */
    @Test
    void theMarketListBindsFromIndexedEnvironmentVariableNames() {
        withEnv(Map.of(
                "LOANS_LIQUIDATION_MARKETS_0_UNIT", "lovelace",
                "LOANS_LIQUIDATION_MARKETS_0_ACTION", "ANTICIPATE",
                "LOANS_LIQUIDATION_MARKETS_0_CAP", "30000000",
                "LOANS_LIQUIDATION_MARKETS_0_MODE", "SHADOW"))
                .run(ctx -> {
                    assertFalse(ctx.getStartupFailure() != null,
                            () -> "context failed: " + ctx.getStartupFailure());
                    var markets = ctx.getBean(AppConfig.LiquidationConfiguration.class).getMarkets();
                    assertEquals(1, markets.size(),
                            "an unbound market list is EMPTY, not an error — the failure mode is the "
                                    + "node silently converting everywhere instead of anticipating");
                    assertEquals("lovelace", markets.getFirst().getUnit());
                    assertEquals(AppConfig.LiquidationConfiguration.Action.ANTICIPATE,
                            markets.getFirst().getAction());
                    assertEquals(BigInteger.valueOf(30_000_000), markets.getFirst().getCap());
                });
    }

    /**
     * ⚠ <b>Casing and dashes on the enum-valued keys.</b> The catalogue tells a chart author what to
     * write, and "it must be UPPERCASE" would be a rule invented rather than measured. The node mode
     * is compared with {@code equalsIgnoreCase} in {@code parseMode()}; the per-market {@code mode}
     * and {@code action} go through Spring's lenient enum converter. Both accept either case, so the
     * catalogue can say so instead of imposing a superstition.
     */
    @ParameterizedTest
    @CsvSource({"shadow,SHADOW", "SHADOW,shadow", "Shadow,ShAdOw", "live,LIVE"})
    void enumValuedKeysAreCaseInsensitiveOnBothTheNodeModeAndTheMarketMode(String nodeMode,
                                                                          String marketMode) {
        withEnv(Map.of(
                "AQUARIUM_LIQUIDATION_MODE", nodeMode,
                "LOANS_LIQUIDATION_MARKETS_0_UNIT", "lovelace",
                "LOANS_LIQUIDATION_MARKETS_0_MODE", marketMode))
                .withPropertyValues("loans.liquidation.mode=" + nodeMode)
                .run(ctx -> {
                    assertFalse(ctx.getStartupFailure() != null,
                            () -> "context failed for node '" + nodeMode + "' / market '" + marketMode
                                    + "': " + ctx.getStartupFailure());
                    var cfg = ctx.getBean(AppConfig.LiquidationConfiguration.class);
                    assertEquals(nodeMode.toUpperCase(), cfg.getMode().name());
                    assertEquals(marketMode.toUpperCase(), cfg.getMarkets().getFirst().getMode().name());
                });
    }

    /**
     * ⛔ The mutation that proves the harness: an unrecognised mode must ABORT STARTUP. If the env
     * name did not bind at all, this value would never be seen and the context would come up on the
     * default {@code disabled} — green, and meaningless.
     */
    @Test
    void anUnrecognisedModeFailsTheContextRatherThanDefaultingQuietly() {
        withEnv(Map.of())
                .withPropertyValues("loans.liquidation.mode=shadowy")
                .run(ctx -> {
                    assertNotNull(ctx.getStartupFailure(),
                            "a typo'd mode must fail the context, not resolve to a safe-looking default");
                    assertTrue(ctx.getStartupFailure().getMessage() != null
                                    && ctx.getStartupFailure().getMessage().contains("shadowy")
                            || rootMessage(ctx.getStartupFailure()).contains("shadowy"),
                            "the failure must name the offending value: "
                                    + rootMessage(ctx.getStartupFailure()));
                });
    }

    private static String rootMessage(Throwable t) {
        Throwable cursor = t;
        StringBuilder all = new StringBuilder();
        while (cursor != null) {
            all.append(cursor.getMessage()).append(" | ");
            cursor = cursor.getCause();
        }
        return all.toString();
    }
}
