package com.fluidtokens.aquarium.offchain.service.loans;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.common.model.Networks;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.config.BuildInfo;
import com.fluidtokens.aquarium.offchain.config.BuildInfoAutoConfiguration;
import com.fluidtokens.aquarium.offchain.service.StakerService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.classreading.SimpleMetadataReaderFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static com.fluidtokens.aquarium.offchain.service.loans.OracleStreamTestServer.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FAB-213: the oracle stream handshake's build identity is one injectable {@link BuildInfo} bean.
 *
 * <p>Every context is the launcher as Spring builds it, with the shipped {@link BuildInfoAutoConfiguration} loaded
 * as an auto-configuration (an {@link ApplicationContextRunner} loads none unless asked — officina
 * {@code spring-boot-config-binding-traps} §2), against a real {@link OracleStreamTestServer}. Pinned: a user's own
 * {@code BuildInfo} replaces the default and is the only one; a {@code @Primary} one wins over another; whatever a
 * bean returns is sanitised by {@link OracleStreamLauncher#nodeVersion} before the client sees it; the default reads
 * the {@code aquarium.build.*} properties; and the auto-configuration is registered the way Boot registers one —
 * listed in the imports file and skipped by component scanning — so it is defined once.
 */
class BuildInfoHandshakeTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    private final Account account = new Account(Networks.preview());
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Logger captured;
    private Level previousLevel;
    private OracleStreamTestServer server;

    @BeforeEach
    void captureTheLog() {
        captured = (Logger) LoggerFactory.getLogger("com.fluidtokens.aquarium.offchain");
        previousLevel = captured.getLevel();
        captured.setLevel(Level.TRACE);
        appender.start();
        captured.addAppender(appender);
        server = new OracleStreamTestServer().authorize(account.stakeAddress()).start();
    }

    @AfterEach
    void tearDown() {
        try {
            if (server != null) {
                server.close();
            }
        } finally {
            captured.detachAppender(appender);
            captured.setLevel(previousLevel);
        }
    }

    // ======================================================================================
    // A user's own BuildInfo
    // ======================================================================================

    @Configuration(proxyBeanMethods = false)
    static class CleanOverride {
        @Bean
        BuildInfo overrideBuildInfo() {
            return new BuildInfo("override-1234", "false");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class DirtyOverride {
        @Bean
        BuildInfo overrideBuildInfo() {
            return new BuildInfo("override-1234", "true");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TwoWithAPrimary {
        @Bean
        BuildInfo otherBuildInfo() {
            return new BuildInfo("other-9999", "false");
        }

        @Bean
        @Primary
        BuildInfo primaryBuildInfo() {
            return new BuildInfo("primary-5678", "false");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class SpaceOverride {
        @Bean
        BuildInfo overrideBuildInfo() {
            return new BuildInfo("ac2c 009c6f40", "false");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CarriageReturnOverride {
        @Bean
        BuildInfo overrideBuildInfo() {
            return new BuildInfo("bad\rvalue", "false");
        }
    }

    /** (a) The override is the one {@code BuildInfo} — the default backed off — and it is the header. */
    @Test
    void aUsersOwnBuildInfoReplacesTheDefaultAndIsTheHeader() {
        context().withUserConfiguration(CleanOverride.class).run(context -> {
            assertNull(context.getStartupFailure(), "the context started");
            Map<String, BuildInfo> beans = context.getBeansOfType(BuildInfo.class);
            assertEquals(List.of("overrideBuildInfo"), List.copyOf(beans.keySet()), "exactly one BuildInfo bean");
            assertEquals(new BuildInfo("override-1234", "false"), context.getBean(BuildInfo.class));

            context.getBean(OracleStreamLauncher.class).start();
            server.awaitSession(0, WAIT);

            assertEquals(List.of("override-1234"), server.nodeVersions());
            assertOnLineEndsWith("node_ver=override-1234 source=stream");
        });
    }

    /** (b) The override's dirty flag is honoured exactly as the default's. */
    @Test
    void aDirtyOverrideAppendsDirty() {
        context().withUserConfiguration(DirtyOverride.class).run(context -> {
            assertNull(context.getStartupFailure(), "the context started");
            assertEquals(1, context.getBeansOfType(BuildInfo.class).size(), "exactly one BuildInfo bean");

            context.getBean(OracleStreamLauncher.class).start();
            server.awaitSession(0, WAIT);

            assertEquals(List.of("override-1234-dirty"), server.nodeVersions());
            assertOnLineEndsWith("node_ver=override-1234-dirty source=stream");
        });
    }

    /** (c) With two user {@code BuildInfo} beans, the {@code @Primary} one is injected. */
    @Test
    void aPrimaryBuildInfoWinsOverAnother() {
        context().withUserConfiguration(TwoWithAPrimary.class).run(context -> {
            assertNull(context.getStartupFailure(), "the context started");
            assertEquals(new BuildInfo("primary-5678", "false"), context.getBean(BuildInfo.class));

            context.getBean(OracleStreamLauncher.class).start();
            server.awaitSession(0, WAIT);

            assertEquals(List.of("primary-5678"), server.nodeVersions());
            assertOnLineEndsWith("node_ver=primary-5678 source=stream");
        });
    }

    /** (d) A space: the sanitiser drops it — no header, the connection still succeeds. */
    @Test
    void aReplacementBeanCannotBypassTheSanitiserWithASpace() {
        assertSanitisedAway(SpaceOverride.class, new BuildInfo("ac2c 009c6f40", "false"));
    }

    /** (d) A CR, which the JDK's handshake would reject on every attempt: dropped, the connection succeeds. */
    @Test
    void aReplacementBeanCannotBypassTheSanitiserWithACarriageReturn() {
        assertSanitisedAway(CarriageReturnOverride.class, new BuildInfo("bad\rvalue", "false"));
    }

    private void assertSanitisedAway(Class<?> configuration, BuildInfo expected) {
        context().withUserConfiguration(configuration).run(context -> {
            assertNull(context.getStartupFailure(), "the context started");
            assertEquals(expected, context.getBean(BuildInfo.class), "fixture: the raw override is the bean");
            OracleStreamLauncher bean = context.getBean(OracleStreamLauncher.class);
            bean.start();
            server.awaitSession(0, WAIT);
            await(() -> bean.status().state() == OracleStreamClient.Status.State.CONNECTED, WAIT, "connected");

            assertEquals(Arrays.asList((String) null), server.nodeVersions());
            assertEquals(List.of(), server.authFailures());
            assertOnLineEndsWith("node_ver=- source=stream");
        });
    }

    // ======================================================================================
    // The default
    // ======================================================================================

    /** (e) The default reads {@code aquarium.build.commitShort} and {@code aquarium.build.dirty}. */
    @Test
    void theDefaultIsTheBuildProperties() {
        context("aquarium.build.commitShort=ac2c009c6f40", "aquarium.build.dirty=true").run(context -> {
            assertNull(context.getStartupFailure(), "the context started");
            assertEquals(new BuildInfo("ac2c009c6f40", "true"), context.getBean(BuildInfo.class));

            context.getBean(OracleStreamLauncher.class).start();
            server.awaitSession(0, WAIT);

            assertEquals(List.of("ac2c009c6f40-dirty"), server.nodeVersions());
            assertOnLineEndsWith("node_ver=ac2c009c6f40-dirty source=stream");
        });
    }

    /** (e) Without either property the default is {@code unknown}/{@code unknown}, which sends no header. */
    @Test
    void withoutTheBuildPropertiesTheDefaultIsUnknownAndNoHeaderIsSent() {
        context().run(context -> {
            assertNull(context.getStartupFailure(), "the context started");
            assertEquals(new BuildInfo("unknown", "unknown"), context.getBean(BuildInfo.class));
            OracleStreamLauncher bean = context.getBean(OracleStreamLauncher.class);
            bean.start();
            server.awaitSession(0, WAIT);
            await(() -> bean.status().state() == OracleStreamClient.Status.State.CONNECTED, WAIT, "connected");

            assertEquals(Arrays.asList((String) null), server.nodeVersions());
            assertOnLineEndsWith("node_ver=- source=stream");
        });
    }

    // ======================================================================================
    // Registration
    // ======================================================================================

    /**
     * (f) Listed in {@code AutoConfiguration.imports}, and matched by Boot's {@link AutoConfigurationExcludeFilter} —
     * so {@code @SpringBootApplication}'s component scan skips it and it is defined once, as an auto-configuration,
     * after the user's beans, where {@code @ConditionalOnMissingBean} can see them.
     */
    @Test
    void theAutoConfigurationIsListedAndKeptOutOfComponentScanning() throws IOException {
        ClassLoader classLoader = getClass().getClassLoader();
        List<String> candidates = StreamSupport.stream(
                ImportCandidates.load(AutoConfiguration.class, classLoader).spliterator(), false).toList();
        assertTrue(candidates.contains(BuildInfoAutoConfiguration.class.getName()), candidates.toString());

        // As Boot's component scan sets it up: the filter is BeanClassLoaderAware, and reads the imports through
        // that class loader.
        AutoConfigurationExcludeFilter filter = new AutoConfigurationExcludeFilter();
        filter.setBeanClassLoader(classLoader);
        MetadataReaderFactory readers = new SimpleMetadataReaderFactory(classLoader);
        MetadataReader reader = readers.getMetadataReader(BuildInfoAutoConfiguration.class.getName());
        assertTrue(filter.match(reader, readers), "component scanning must skip BuildInfoAutoConfiguration");
    }

    // ======================================================================================
    // Plumbing (as OracleStreamLauncherTest.nodeVersionContext builds it)
    // ======================================================================================

    /** The launcher as Spring builds it, against {@link #server}, with the shipped auto-configuration. */
    private ApplicationContextRunner context(String... properties) {
        return new ApplicationContextRunner()
                .withPropertyValues("network=preview", "loans.oracle.stream.url=" + server.uri())
                .withPropertyValues(properties)
                .withBean(FluidOracleClient.class, () -> new FluidOracleClient("http://unused.invalid"))
                .withBean(Account.class, () -> account)
                .withBean(StakerService.class, BuildInfoHandshakeTest::staker)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(AppConfig.Network.class, () -> network("preview"))
                .withConfiguration(AutoConfigurations.of(BuildInfoAutoConfiguration.class))
                .withBean(OracleStreamLauncher.class);
    }

    private void assertOnLineEndsWith(String tail) {
        List<String> on = onLines();
        assertEquals(1, on.size(), on.toString());
        assertTrue(on.getFirst().endsWith(" network=preview host=127.0.0.1 " + tail), on.getFirst());
    }

    private List<String> onLines() {
        return List.copyOf(appender.list).stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(l -> l.startsWith("[oracle-stream] on "))
                .toList();
    }

    /** A stake check that reads "no stake"; the launcher only ever reads it during a 403 hold. */
    private static StakerService staker() {
        return org.mockito.Mockito.mock(StakerService.class);
    }

    private static AppConfig.Network network(String name) {
        AppConfig.Network network = new AppConfig.Network();
        network.setNetworkForTest(name);
        return network;
    }
}
