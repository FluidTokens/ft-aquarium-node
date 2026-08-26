package com.fluidtokens.aquarium.offchain.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.stereotype.Service;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-058 — <b>a bean that only exists when {@code loans.enabled=true} must not be reachable from a
 * component that exists when it is false.</b>
 *
 * <h2>The failure this prevents, which has nearly happened twice</h2>
 * Lending is <b>disabled on mainnet</b>, so every {@code @ConditionalOnProperty(loans.enabled=true)}
 * bean is <b>absent on the one path operators actually run</b>. A component that always exists and
 * injects one of them does not fail to compile and does not fail a test — <b>it fails at STARTUP, on
 * mainnet.</b> The bot does not come up.
 * <ul>
 *   <li><b>T-045</b> — nearly injected {@code UtxoSupplier}.</li>
 *   <li><b>T-053</b> — nearly injected {@code ProtocolParamsSupplier}, in a ticket whose entire
 *       purpose was to improve the mainnet path.</li>
 * </ul>
 * <b>Both were caught by asking what could satisfy the injection before writing it.</b> That is a
 * habit, and habits do not survive contact with a deadline. This does.
 *
 * <h2>⚠ Why this and not a stubbed application context</h2>
 * The literal acceptance is <i>"the context starts with {@code loans.enabled=false}"</i>, and an
 * {@code ApplicationContextRunner} would say so directly — but only after stubbing every leaf
 * ({@code Account}, {@code UtxoRepository}, {@code BFBackendService}, the converters, the config
 * records). <b>A rig that supplies what production must earn is the fixture trap this very review
 * documented</b>, and a green stubbed context would prove the stubs satisfied the constructors, not
 * that Spring can.
 *
 * <p>So this asserts the <b>rule</b> instead, off the real annotations: <b>no always-present component
 * may take a loans-conditional type in its constructor.</b> Nothing is stubbed, so nothing can be
 * stubbed wrongly.
 */
class MainnetWiringTest {

    private static final String PACKAGE = "com.fluidtokens.aquarium.offchain";

    /**
     * The components Spring would register with {@code loans.enabled} set as given.
     *
     * <p>⚑ {@link ClassPathScanningCandidateComponentProvider} <b>evaluates {@code @Conditional}
     * itself</b>. So rather than re-implementing the condition — and risking a hand-rolled predicate
     * that disagrees with the framework — this asks the framework twice and takes the difference.
     * <b>Spring's own answer, not our model of it.</b>
     */
    private static List<Class<?>> componentsWithLoans(boolean enabled) {
        var environment = new MockEnvironment().withProperty("loans.enabled", String.valueOf(enabled));
        var scanner = new ClassPathScanningCandidateComponentProvider(false, environment);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Service.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        List<Class<?>> found = new ArrayList<>();
        for (var candidate : scanner.findCandidateComponents(PACKAGE)) {
            try {
                found.add(Class.forName(candidate.getBeanClassName()));
            } catch (Throwable ignored) {
                // a class we cannot load is not a wiring risk we can assess
            }
        }
        return found;
    }

    /** True when a {@code @Bean} method only exists while lending is on. */
    private static boolean loansConditional(ConditionalOnProperty conditional) {
        if (conditional == null) {
            return false;
        }
        boolean namesEnabled = List.of(conditional.name()).contains("enabled")
                || List.of(conditional.value()).contains("loans.enabled");
        return "loans".equals(conditional.prefix()) && namesEnabled
                && "true".equals(conditional.havingValue());
    }

    /** Every type that exists ONLY while lending is on: conditional components and conditional beans. */
    private static Set<Class<?>> loansOnlyTypes() {
        Set<Class<?>> types = new LinkedHashSet<>(componentsWithLoans(true));
        componentsWithLoans(false).forEach(types::remove);      // ⇐ the difference IS the answer
        for (Class<?> type : componentsWithLoans(true)) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Bean.class)
                        && loansConditional(method.getAnnotation(ConditionalOnProperty.class))) {
                    types.add(method.getReturnType());
                }
            }
        }
        return types;
    }

    @Test
    void noAlwaysPresentComponentDependsOnALoansOnlyType() {
        Set<Class<?>> loansOnly = loansOnlyTypes();
        assertFalse(loansOnly.isEmpty(),
                "the scan found no loans-conditional types at all — it is not looking where it thinks "
                        + "it is, and a green result would mean nothing");

        List<String> violations = new ArrayList<>();
        for (Class<?> component : componentsWithLoans(false)) {   // ⇐ the MAINNET set
            for (Constructor<?> constructor : component.getDeclaredConstructors()) {
                for (Class<?> parameter : constructor.getParameterTypes()) {
                    if (loansOnly.contains(parameter)) {
                        violations.add(component.getSimpleName() + " ← " + parameter.getSimpleName());
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(),
                ("these ALWAYS-PRESENT components inject a bean that only exists when "
                        + "loans.enabled=true. Lending is DISABLED ON MAINNET, so this is a STARTUP "
                        + "FAILURE on the one path operators run — not a compile error and not a test "
                        + "failure: %s").formatted(violations));
    }

    /**
     * Proof the scan reaches the real annotations. Without this a refactor that moves or renames the
     * conditional could leave the test green by finding nothing — the shape this review has met
     * repeatedly, where an instrument that cannot see its subject returns a clean reading.
     */
    @Test
    void theScanActuallyFindsBothKINDSOfLoansOnlyType() {
        Set<Class<?>> loansOnly = loansOnlyTypes();

        assertTrue(loansOnly.stream().anyMatch(t -> t.getSimpleName().equals("LiquidationExecutor")),
                "a conditional @Service must be found; got " + loansOnly);
        assertTrue(loansOnly.stream().anyMatch(t -> t.getSimpleName().equals("ProtocolParamsSupplier")),
                "a conditional @Bean's RETURN TYPE must be found — that is the one T-053 nearly "
                        + "injected; got " + loansOnly);
    }
}
