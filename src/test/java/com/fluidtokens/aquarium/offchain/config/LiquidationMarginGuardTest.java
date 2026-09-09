package com.fluidtokens.aquarium.offchain.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>{@link AppConfig.LiquidationConfiguration#init()}'s {@code profitMarginLovelace == null}
 * refusal, proven both REMOVED and DISCONNECTED.</b>
 *
 * <p>Nothing in this codebase previously called {@code init()} directly with a null margin, and
 * nothing booted a real Spring context to check the {@code @PostConstruct} wiring either — replacing
 * the check with {@code if (false)} left all 988 tests green (empty-context audit, this round).
 *
 * <h2>The two tests, and the mutant each kills</h2>
 * {@link #aNullMarginAbortsInitWithTheNamedReason()} is the REMOVED-guard case: it drives
 * {@code init()} directly on a hand-built instance whose margin is left at its Java default (no field
 * initialiser — {@code null}), with every other input legal, so the ONLY way {@code init()} can throw
 * is the margin check. Weaken or delete that check and {@code init()} returns normally instead of
 * throwing, and the test goes red.
 *
 * <p>{@link #blankMarginReachesInitAndFailsWithTheNamedReason()} is the DISCONNECTED-guard case: it
 * boots a REAL {@link ApplicationContextRunner} with the margin property present but BLANK — the
 * {@code docker/.env} shape {@code AQUARIUM_LIQUIDATION_PROFIT_MARGIN_LOVELACE=} produces. Spring's
 * String→BigInteger conversion returns no value for a blank string, the {@code @Value} field
 * injection is silently skipped, and the field is left {@code null} with NO exception raised by the
 * framework — {@link AppConfig.LiquidationConfiguration#profitMarginLovelace} carries no field
 * default (unlike {@link AppConfig.ConvertConfiguration#minswapOrderCostLovelace}), so nothing but
 * {@code init()}'s own check stands between that silent null and a bean the rest of the app treats as
 * fully configured. Drop {@code @PostConstruct} from {@code init()} and this context boots clean with
 * a null margin, and the test goes red.
 *
 * <p>{@link #omittedMarginFailsEarlierAtValueConversionRatherThanAtInit()} records the THIRD shape —
 * the key entirely absent from the environment — which fails before {@code init()} is ever reached,
 * at {@code @Value} placeholder resolution, and is unaffected by whether {@code @PostConstruct} is
 * present. It exists to keep the blank/omitted distinction honest, not to kill a mutant.
 */
class LiquidationMarginGuardTest {

    // ---- REMOVED guard: init() itself, no Spring involved ---------------------------------------

    @Test
    void aNullMarginAbortsInitWithTheNamedReason() {
        AppConfig.LiquidationConfiguration configuration = new AppConfig.LiquidationConfiguration();
        configuration.setModeName("disabled");
        // profitMarginLovelace is left at its Java default -- null, since the field carries no
        // initialiser on purpose (§26.2: a @Value default is not a default of the class). Mode is
        // legal and markets/reference-scripts are all at their blank defaults, so if the margin check
        // is weakened or removed, init() has nothing else to throw on and returns normally.

        IllegalStateException thrown = assertThrows(IllegalStateException.class, configuration::init,
                "init() must refuse a null margin before parseMode()/validateMarkets() can run over it");
        assertTrue(thrown.getMessage().contains("loans.liquidation.profit-margin-lovelace"),
                "the refusal must name the key: " + thrown.getMessage());
    }

    // ---- DISCONNECTED guard: a real Spring context, the docker/.env blank shape -----------------

    @Configuration
    @Import(AppConfig.LiquidationConfiguration.class)
    static class Ctx {
    }

    private static ApplicationContextRunner runnerWithMargin(String... marginProperty) {
        ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Ctx.class)
                .withPropertyValues("loans.liquidation.mode=disabled");
        return marginProperty.length == 0 ? runner : runner.withPropertyValues(marginProperty);
    }

    @Test
    void blankMarginReachesInitAndFailsWithTheNamedReason() {
        runnerWithMargin("loans.liquidation.profit-margin-lovelace=").run(ctx -> {
            assertNotNull(ctx.getStartupFailure(),
                    "a BLANK margin (docker/.env's `KEY=` shape) must abort context startup -- the "
                            + "field injection silently no-ops on empty text and only init()'s own "
                            + "check catches the resulting null");
            String all = rootMessage(ctx.getStartupFailure());
            assertTrue(all.contains("loans.liquidation.profit-margin-lovelace is not set"),
                    "the failure must be the NAMED init() reason, not some other exception: " + all);
        });
    }

    /**
     * The key entirely absent from the environment fails EARLIER, at {@code @Value} placeholder
     * resolution -- before {@code init()} runs at all -- so it is not a probe of the
     * {@code @PostConstruct} wiring. Recorded here so the blank/omitted distinction this class's
     * javadoc documents is itself pinned, per the correction this round makes to every place that
     * previously conflated the two.
     */
    @Test
    void omittedMarginFailsEarlierAtValueConversionRatherThanAtInit() {
        runnerWithMargin().run(ctx -> {
            assertNotNull(ctx.getStartupFailure(), "an omitted margin key must also abort startup");
            String all = rootMessage(ctx.getStartupFailure());
            assertFalse(all.contains("loans.liquidation.profit-margin-lovelace is not set"),
                    "an OMITTED key must fail at @Value resolution, never reaching init()'s named "
                            + "message -- if it does, the two failure modes have collapsed into one "
                            + "and the distinction this test pins no longer holds: " + all);
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
