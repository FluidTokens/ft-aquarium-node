package com.fluidtokens.aquarium.offchain.config;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * A servlet context built from the <b>shipped</b> {@code application.yaml} through Yaci Store's and Spring Boot's
 * real auto-configuration — the shape Yaci's switches, beans, routes and health indicators actually take in the node.
 *
 * <h2>What it proves</h2>
 * The YAML is loaded by {@link ConfigDataApplicationContextInitializer}, the same config-data mechanism the node
 * boots with (the {@code test} profile is not activated), so a key the file misspells or drops is seen exactly as
 * production sees it. Every Yaci auto-configuration on the classpath runs: its {@code @ConditionalOnProperty}
 * switches, its component scans, its Flyway migrations. It runs offline — an in-memory H2 database per runner, a
 * relay address nothing listens on — and never fires {@code ApplicationReadyEvent}, which is what starts Yaci's sync,
 * so no connection to a Cardano node is ever attempted. The plugin files root is a fresh temporary directory, so a
 * mutant that turns plugins on writes nothing into the worktree.
 *
 * <h2>What it does not prove</h2>
 * The node's own beans are absent: there is no component scan of {@code com.fluidtokens.aquarium.offchain}, so
 * nothing here says how the node's storage, scheduler users or controllers sit on top of Yaci. Tests add the beans
 * and properties they need on the returned runner.
 */
final class YaciStoreContextHarness {

    private YaciStoreContextHarness() {
    }

    /** A fresh runner: its own H2 database and its own plugin files root. */
    static WebApplicationContextRunner runner() {
        String database = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        Path pluginFilesRoot;
        try {
            pluginFilesRoot = Files.createTempDirectory("yaci-plugin-files");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        pluginFilesRoot.toFile().deleteOnExit();
        return new WebApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(AutoConfiguredYaciStore.class)
                .withPropertyValues(
                        "spring.datasource.url=" + database,
                        "spring.datasource.username=sa",
                        "spring.datasource.password=",
                        "spring.flyway.url=" + database,
                        "spring.flyway.user=sa",
                        "spring.flyway.password=",
                        "spring.jpa.hibernate.ddl-auto=none",
                        "store.cardano.host=127.0.0.1",
                        "store.cardano.port=1",
                        "store.plugins.files.root-path=" + pluginFilesRoot);
    }

    /** Auto-configuration only: no component scan, so none of the node's own beans. */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class AutoConfiguredYaciStore {
    }
}
