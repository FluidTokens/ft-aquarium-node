package com.fluidtokens.aquarium.offchain.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * FAB-183: the liquidation and compound delay settings no longer exist, and an operator who still passes one is
 * told so once at boot instead of getting silence that reads like the setting working.
 */
class ObsoleteSettingsWarningTest {

    private static final String TAIL = " is set but no longer exists: liquidation, compound and wallet housekeeping "
            + "run on committed blocks and oracle price changes, never on a timer (FAB-183) — remove it; "
            + "see docs/upgrading.md §7";

    private final Logger logger = (Logger) LoggerFactory.getLogger(ObsoleteSettingsWarning.class);

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attach() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    private List<String> warnings() {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /** Each obsolete name, set on its own, says exactly one WARN, and that WARN names it. */
    @ParameterizedTest
    @ValueSource(strings = {
            "loans.liquidation.delay-seconds",
            "AQUARIUM_LIQUIDATION_DELAY_SECONDS",
            "loans.compound.delay-seconds",
            "AQUARIUM_COMPOUND_DELAY_SECONDS"})
    void eachObsoleteNameThatIsSetSaysExactlyOneWarnNamingIt(String name) {
        MockEnvironment environment = new MockEnvironment().withProperty(name, "60");

        new ObsoleteSettingsWarning(environment).warnAboutObsoleteSettings();

        assertEquals(List.of(name + TAIL), warnings());
    }

    /** All four set: one WARN each, in the documented order, and a second ready event says nothing more. */
    @Test
    void allFourSetSayOneWarnEachInOrderAndNeverTwice() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("AQUARIUM_COMPOUND_DELAY_SECONDS", "30")
                .withProperty("loans.compound.delay-seconds", "30")
                .withProperty("AQUARIUM_LIQUIDATION_DELAY_SECONDS", "30")
                .withProperty("loans.liquidation.delay-seconds", "30");
        ObsoleteSettingsWarning warning = new ObsoleteSettingsWarning(environment);

        warning.warnAboutObsoleteSettings();
        warning.warnAboutObsoleteSettings();

        assertEquals(List.of(
                "loans.liquidation.delay-seconds" + TAIL,
                "AQUARIUM_LIQUIDATION_DELAY_SECONDS" + TAIL,
                "loans.compound.delay-seconds" + TAIL,
                "AQUARIUM_COMPOUND_DELAY_SECONDS" + TAIL), warnings());
    }

    /** None set: no line at all, at any level. */
    @Test
    void noneSetSaysNothing() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("loans.liquidation.mode", "live")
                .withProperty("AQUARIUM_LIQUIDATION_MODE", "live");

        new ObsoleteSettingsWarning(environment).warnAboutObsoleteSettings();

        assertEquals(List.of(), appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }

    /**
     * ⛔ The shipped configuration logs no obsolete-setting WARN: every document of {@code application.yaml}, read
     * from the classpath, as the environment's only source.
     */
    @Test
    void theShippedYamlSaysNothing() throws IOException {
        List<PropertySource<?>> documents = new YamlPropertySourceLoader()
                .load("application.yaml", new ClassPathResource("application.yaml"));
        assertFalse(documents.isEmpty(), "application.yaml is not on the classpath — this test would be vacuous");
        MockEnvironment environment = new MockEnvironment();
        for (PropertySource<?> document : documents) {
            environment.getPropertySources().addLast(document);
        }

        new ObsoleteSettingsWarning(environment).warnAboutObsoleteSettings();

        assertEquals(List.of(), warnings());
    }

    /**
     * The relaxed spelling: {@code LOANS_LIQUIDATION_DELAY_SECONDS} in a real {@link SystemEnvironmentPropertySource}
     * (dots and dashes both become underscores) is the property {@code loans.liquidation.delay-seconds}, and the
     * WARN names the property.
     */
    @Test
    void theRelaxedEnvironmentVariableSpellingWarnsForTheProperty() {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addLast(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                Map.of("LOANS_LIQUIDATION_DELAY_SECONDS", "60")));

        new ObsoleteSettingsWarning(environment).warnAboutObsoleteSettings();

        assertEquals(List.of("loans.liquidation.delay-seconds" + TAIL), warnings());
    }

    /** A failing environment is never thrown out of the listener. */
    @Test
    void aFailingEnvironmentNeverThrows() {
        MockEnvironment environment = new MockEnvironment() {
            @Override
            public boolean containsProperty(String key) {
                throw new IllegalStateException("boom");
            }
        };

        assertDoesNotThrow(() -> new ObsoleteSettingsWarning(environment).warnAboutObsoleteSettings());
        assertEquals(List.of(), warnings());
    }

    /** It runs once the application is ready, not at construction. */
    @Test
    void theListenerRunsOnApplicationReady() throws NoSuchMethodException {
        Method listener = ObsoleteSettingsWarning.class.getMethod("warnAboutObsoleteSettings");
        EventListener annotation = listener.getAnnotation(EventListener.class);
        assertNotNull(annotation, "warnAboutObsoleteSettings must be an @EventListener");
        assertArrayEquals(new Class<?>[]{ApplicationReadyEvent.class}, annotation.value());
    }
}
