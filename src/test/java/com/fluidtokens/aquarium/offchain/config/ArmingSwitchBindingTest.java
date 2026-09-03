package com.fluidtokens.aquarium.offchain.config;

import com.fluidtokens.aquarium.offchain.config.AppConfig.Network;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>Does the LAST arming barrier bind from the variable an operator is told to type?</b>
 *
 * <h2>Why this exists as its own class</h2>
 * {@code loans.submittable-network} is the S3 veto: a node whose network does not equal it submits
 * nothing, whatever the mode and arming flags say, and its default of {@code preview} is what keeps
 * mainnet fail-closed while mainnet is the DEFAULT PROFILE. As of 2026-09-03 both
 * {@code docker/.env.example} and runbook §14 name {@code LOANS_SUBMITTABLE_NETWORK} to operators as
 * the switch that has to be written down on purpose.
 *
 * <p>⚠ <b>Nothing was proving that variable binds.</b> Every existing test reaches the field through
 * {@code setNetworkForTest(...)} or an anonymous subclass — which exercises the RULE and skips the
 * BINDING entirely. That is the same gap {@link MarketBindingTest} was written for one level down:
 * <b>a switch that only a test seam can move is not a switch an operator has.</b> If the relaxed
 * SCREAMING_SNAKE mapping did not reach a {@code @Value} on a nested {@code @Component}, an operator
 * would set the variable, read no error, and stay silently non-submittable — <i>failing safe, and
 * therefore invisibly</i>. A gate that cannot be opened is as much a defect as one that cannot close.
 *
 * <h2>⚠ It must be a real environment property source</h2>
 * The relaxation is a property of {@link SystemEnvironmentPropertySource} alone. Asserting it with
 * {@code withPropertyValues("loans.submittable-network=…")} would pass for the wrong reason and say
 * nothing about {@code docker run -e}.
 */
class ArmingSwitchBindingTest {

    @Configuration
    @Import(Network.class)
    static class Ctx {
    }

    private static ApplicationContextRunner runner(Map<String, Object> env) {
        return new ApplicationContextRunner()
                .withUserConfiguration(Ctx.class)
                .withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env)));
    }

    /** ⛔ The default is the protection: a mainnet node that says nothing cannot submit. */
    @Test
    void aMainnetNodeThatSaysNothingIsNotSubmittable() {
        runner(Map.of("NETWORK", "mainnet")).run(ctx -> {
            Network network = ctx.getBean(Network.class);
            assertEquals("mainnet", network.getNetwork());
            assertFalse(network.isSubmittable(),
                    "the S3 veto's whole purpose: mainnet is the DEFAULT profile, so a node that "
                            + "never mentions loans.submittable-network must stay fail-closed");
        });
    }

    /**
     * ⛔ And the switch an operator is TOLD to type actually opens it. Without this the instruction in
     * docker/.env.example and runbook §14 would be advice that does nothing.
     */
    @Test
    void theEnvironmentVariableTheRunbookNamesActuallyOpensTheGate() {
        runner(Map.of("NETWORK", "mainnet", "LOANS_SUBMITTABLE_NETWORK", "mainnet")).run(ctx -> {
            Network network = ctx.getBean(Network.class);
            assertTrue(network.isSubmittable(),
                    "LOANS_SUBMITTABLE_NETWORK=mainnet must bind through relaxed environment naming, "
                            + "or the last arming barrier is one no operator can lift");
        });
    }

    /** A preview node with the default is submittable — the ordinary preview posture, unchanged. */
    @Test
    void aPreviewNodeIsSubmittableOnTheDefault() {
        runner(Map.of("NETWORK", "preview")).run(ctx ->
                assertTrue(ctx.getBean(Network.class).isSubmittable()));
    }

    /**
     * ⚠ Mismatched values do NOT open it. The comparison is equality against the node's own network,
     * not a boolean — so pointing a preview node at mainnet grants nothing, in either direction.
     */
    @Test
    void namingTheOtherNetworkGrantsNothing() {
        runner(Map.of("NETWORK", "preview", "LOANS_SUBMITTABLE_NETWORK", "mainnet")).run(ctx ->
                assertFalse(ctx.getBean(Network.class).isSubmittable()));
        runner(Map.of("NETWORK", "mainnet", "LOANS_SUBMITTABLE_NETWORK", "preview")).run(ctx ->
                assertFalse(ctx.getBean(Network.class).isSubmittable()));
    }
}
