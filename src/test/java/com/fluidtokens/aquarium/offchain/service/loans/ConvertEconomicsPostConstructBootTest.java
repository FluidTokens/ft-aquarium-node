package com.fluidtokens.aquarium.offchain.service.loans;

import com.fluidtokens.aquarium.offchain.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>Proves {@link ConvertEconomics#announceAndGuard()} actually RUNS AT CONTAINER STARTUP —
 * not merely that it refuses when a test calls it directly.</b>
 *
 * <p>Every test in {@code ConvertEconomicsTest} hand-constructs a {@code ConvertEconomics} and calls
 * {@code announceAndGuard()} itself, which is this repo's own documented failure mode — see
 * {@code CLAUDE.md}: <i>"every test used the rig that supplies what production must earn."</i>
 * Deleting {@code @PostConstruct} from {@code announceAndGuard()} leaves all of those tests green,
 * because none of them goes through Spring at all: they prove the METHOD refuses, not that the
 * CONTAINER ever calls it. A node configured
 * {@code LOANS_LIQUIDATION_CONVERT_MINSWAP_ORDER_COST_LOVELACE=1} would boot clean and charge every
 * convert 3,999,999 lovelace less than it spends.
 *
 * <p>This class boots a REAL {@link ApplicationContextRunner} over exactly the beans the production
 * context wires for this refusal — {@code ConvertConfiguration}, {@code LiquidationConfiguration},
 * {@code Network} and {@code ConvertEconomics} itself — and checks whether the CONTEXT refuses, the
 * way an operator's node would. Four rows, matching the auditor's probe: one lovelace under
 * {@code ConvertEconomics.MINSWAP_ORDER_OVERHEAD} and a negative value must abort startup and name
 * the key; exactly the bound and comfortably above it must both start clean, with the bean carrying
 * the value actually configured — never the field default, and never silence.
 *
 * <p>⚠ <b>The mutant this file exists to kill is {@code @PostConstruct} being dropped from
 * {@code announceAndGuard()}</b> (or the bean being made lazy, or its wiring bypassed some other
 * way) — not a weakened comparison inside the method, which
 * {@code ConvertEconomicsTest#anOrderCostBelowWhatTheOrderMustCarryIsRefusedAtStartupAndAtOrAboveItStarts}
 * already kills by calling {@code announceAndGuard()} directly. The two are a deliberate pair: that
 * test is the REMOVED-guard case, this one is the DISCONNECTED-guard case.
 */
class ConvertEconomicsPostConstructBootTest {

    @Configuration
    @Import({AppConfig.ConvertConfiguration.class, AppConfig.LiquidationConfiguration.class,
            AppConfig.Network.class, ConvertEconomics.class})
    static class Ctx {
    }

    private static ApplicationContextRunner runnerWithOrderCost(String orderCost) {
        return new ApplicationContextRunner()
                .withUserConfiguration(Ctx.class)
                .withPropertyValues("network=preview",
                        "loans.liquidation.profit-margin-lovelace=5000000",
                        "loans.liquidation.convert.enabled=true",
                        "loans.liquidation.convert.dex-cost-floor-lovelace=5000000",
                        "loans.liquidation.convert.minswap-order-cost-lovelace=" + orderCost);
    }

    /** PROBE2 [one under the bound]: must fail, and the failure must name the key. */
    @Test
    void oneLovelaceUnderTheBoundFailsStartupAndNamesTheKey() {
        runnerWithOrderCost("3999999").run(ctx -> {
            assertNotNull(ctx.getStartupFailure(),
                    "a configured order cost of 3,999,999 -- one lovelace under "
                            + "MINSWAP_ORDER_OVERHEAD -- must abort context startup");
            String all = rootMessage(ctx.getStartupFailure());
            assertTrue(all.contains("loans.liquidation.convert.minswap-order-cost-lovelace"),
                    "the startup failure must name the key, not just symptom: " + all);
        });
    }

    /** PROBE2 [negative]: must fail. */
    @Test
    void aNegativeOrderCostFailsStartup() {
        runnerWithOrderCost("-1").run(ctx ->
                assertNotNull(ctx.getStartupFailure(),
                        "a negative order cost must abort context startup"));
    }

    /** PROBE2 [exactly the bound]: starts clean, bound value reaches the bean. */
    @Test
    void exactlyTheBoundStartsCleanAndBindsTheBoundValue() {
        runnerWithOrderCost("4000000").run(ctx -> {
            assertNull(ctx.getStartupFailure(), () -> "an order cost exactly at the bound must start "
                    + "the context clean: " + ctx.getStartupFailure());
            var convert = ctx.getBean(AppConfig.ConvertConfiguration.class);
            assertEquals(BigInteger.valueOf(4_000_000L), convert.getMinswapOrderCostLovelace());
        });
    }

    /** PROBE2 [above the bound]: starts clean, the STATED value reaches the bean -- not the constant. */
    @Test
    void aboveTheBoundStartsCleanAndBindsTheStatedValue() {
        runnerWithOrderCost("7000000").run(ctx -> {
            assertNull(ctx.getStartupFailure(), () -> "an order cost above the bound must start the "
                    + "context clean: " + ctx.getStartupFailure());
            var convert = ctx.getBean(AppConfig.ConvertConfiguration.class);
            assertEquals(BigInteger.valueOf(7_000_000L), convert.getMinswapOrderCostLovelace());
        });
    }

    private static String rootMessage(Throwable t) {
        StringBuilder all = new StringBuilder();
        Throwable cursor = t;
        while (cursor != null) {
            all.append(cursor.getMessage()).append(" | ");
            cursor = cursor.getCause();
        }
        return all.toString();
    }
}
