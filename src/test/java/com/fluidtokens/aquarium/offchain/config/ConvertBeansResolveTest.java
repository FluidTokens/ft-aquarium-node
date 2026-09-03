package com.fluidtokens.aquarium.offchain.config;

import com.fluidtokens.aquarium.offchain.service.loans.ConvertEconomics;
import com.fluidtokens.aquarium.offchain.service.loans.ConvertLiquidationRouter;
import com.fluidtokens.aquarium.offchain.service.loans.ConvertTransactionBuilder;
import com.fluidtokens.aquarium.offchain.service.loans.MinswapPoolResolver;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Bean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>Every collaborator the convert path needs must be something the CONTAINER can produce.</b>
 *
 * <h2>The gap this closes, found while sizing the remaining work</h2>
 * The convert path was reported "complete on our side" while three of its four classes —
 * {@link ConvertTransactionBuilder}, {@link MinswapPoolResolver} and {@link ConvertLiquidationRouter}
 * — had <b>no bean definition at all</b>: no stereotype annotation, no {@code @Bean} factory. Every
 * test constructed them by hand, so the whole suite was green and a deployed node would have found
 * {@code convertRouter == null} and refused every convert-eligible candidate.
 *
 * <p>⚑ <b>That failure was the good kind only by luck of an unrelated decision.</b>
 * {@code LiquidationExecutor} records {@code CONVERT_UNAVAILABLE} rather than falling back to
 * pay-in-advance — written for a different reason (a node whose Minswap coordinates belong to another
 * network). Without it the missing bean would have silently routed those loans down the path that
 * <b>fronts the operator's own capital</b>. <b>A safety property that holds by coincidence is worth
 * exactly one test.</b>
 *
 * <p>And the sibling precedent is not hypothetical: image {@code lending-v4-588d318} crash-looped in
 * fourteen seconds because a collaborator Spring could not construct was found at startup rather than
 * by a test ({@code ExecutorContextResolutionTest}).
 *
 * <h2>⚠ Why this asserts the FACTORY rather than starting a context</h2>
 * These beans take {@code BFBackendService}, which reaches the network on construction in a way a
 * stub cannot fully stand in for. A context test would be testing the stub. So this asserts the thing
 * that was actually missing — <b>that a definition exists, is conditioned like its siblings, and
 * returns the type</b> — and does it by reflection over {@code YaciConfig}, which cannot pass while
 * the factory is absent.
 */
class ConvertBeansResolveTest {

    /** The classes a convert needs the container to produce, and how each is meant to be defined. */
    private static final List<Class<?>> BY_FACTORY = List.of(
            MinswapPoolResolver.class, ConvertTransactionBuilder.class, ConvertLiquidationRouter.class);

    private static Set<Class<?>> beanTypesDeclaredBy(Class<?> configuration) {
        return Arrays.stream(configuration.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Bean.class))
                .map(Method::getReturnType)
                .collect(Collectors.toSet());
    }

    /**
     * ⛔ THE ASSERTION THAT WOULD HAVE CAUGHT IT. Each convert collaborator has a {@code @Bean}
     * factory on {@code YaciConfig}, beside the builders it mirrors.
     */
    @Test
    void everyConvertCollaboratorHasABeanDefinition() {
        Set<Class<?>> declared = beanTypesDeclaredBy(YaciConfig.class);

        for (Class<?> type : BY_FACTORY) {
            assertTrue(declared.contains(type),
                    type.getSimpleName() + " has NO @Bean factory on YaciConfig and no stereotype "
                            + "annotation, so the container cannot produce it. Every test builds it by "
                            + "hand, which is why the suite stays green while a deployed node finds "
                            + "the collaborator missing.");
        }

        assertTrue(ConvertEconomics.class.isAnnotationPresent(org.springframework.stereotype.Service.class),
                "ConvertEconomics is the one convert class defined by stereotype rather than factory; "
                        + "if that changed, this test is describing a shape that no longer exists");
    }

    /**
     * ⚠ Conditioned like its siblings, or the node fails to START on a configuration where lending is
     * off — which is the shipped mainnet default, and therefore the deployment most likely to meet it.
     */
    @Test
    void everyConvertBeanIsGatedOnLoansEnabledExactlyAsItsSiblingsAre() {
        for (Method m : YaciConfig.class.getDeclaredMethods()) {
            if (!m.isAnnotationPresent(Bean.class) || !BY_FACTORY.contains(m.getReturnType())) {
                continue;
            }
            var condition = m.getAnnotation(
                    org.springframework.boot.autoconfigure.condition.ConditionalOnProperty.class);
            assertTrue(condition != null, m.getName() + " is not @ConditionalOnProperty; on a node with "
                    + "loans disabled its dependencies do not exist and the context fails to start");
            assertEquals("loans", condition.prefix(), m.getName() + " is gated on the wrong prefix");
            assertEquals("true", condition.havingValue(), m.getName() + " is gated on the wrong value");
            assertTrue(Arrays.asList(condition.name()).contains("enabled"),
                    m.getName() + " is gated on the wrong property: " + Arrays.toString(condition.name()));
        }
    }

    /**
     * ⛔ AND THE INVARIANT THAT MAKES THE ABSENCE SAFE RATHER THAN SILENT. The executor must refuse a
     * convert-eligible candidate BY NAME when the router is missing — never route it to
     * pay-in-advance, which spends the operator's own capital on a loan they configured to convert.
     *
     * <p>Asserted on the source because the branch is reached only with a null router on an armed,
     * live, convert-market node — a state this suite cannot assemble, and one whose absence of a test
     * is exactly how the property came to hold by coincidence in the first place.
     */
    @Test
    void aMissingConvertRouterRefusesByNameRatherThanFallingBackToPayInAdvance() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/fluidtokens/aquarium/offchain/service/loans/LiquidationExecutor.java"));

        int guard = source.indexOf("if (convertRouter == null)");
        assertTrue(guard > 0, "the null-router guard is gone; a missing bean would now route "
                + "convert-eligible loans to the path that FRONTS THE OPERATOR'S CAPITAL");

        String branch = source.substring(guard, Math.min(source.length(), guard + 1200));
        assertTrue(branch.contains("CONVERT_UNAVAILABLE"),
                "the guard no longer records a named reason, so the refusal is indistinguishable from "
                        + "a market that simply had no candidates");
        assertTrue(branch.contains("return;"),
                "the guard no longer returns, so control falls through to the pay-in-advance branch");
    }
}
