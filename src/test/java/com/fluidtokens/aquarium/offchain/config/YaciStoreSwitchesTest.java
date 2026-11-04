package com.fluidtokens.aquarium.offchain.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bloxbean.cardano.yaci.store.api.core.CoreApiConfiguration;
import com.bloxbean.cardano.yaci.store.api.core.controller.GenesisController;
import com.bloxbean.cardano.yaci.store.api.core.controller.SyncController;
import com.bloxbean.cardano.yaci.store.common.config.StoreProperties;
import com.bloxbean.cardano.yaci.store.events.BlockHeaderEvent;
import com.bloxbean.cardano.yaci.store.events.EventMetadata;
import com.bloxbean.cardano.yaci.store.plugin.aspect.PluginAspect;
import com.bloxbean.cardano.yaci.store.plugin.controller.PluginController;
import com.bloxbean.cardano.yaci.store.plugin.file.PluginFileClient;
import com.bloxbean.cardano.yaci.store.plugin.scheduler.controller.SchedulerAdminController;
import com.bloxbean.cardano.yaci.store.starter.core.YaciStoreProperties;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.web.servlet.error.BasicErrorController;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ FAB-203 (D1): <b>every Yaci Store 2.x default the node does not adopt is switched off, as Yaci itself reads
 * it.</b>
 *
 * <p>{@link ShippedDefaultsTest} pins the five keys in the file. This proves what they do once Yaci's real
 * auto-configuration reads the shipped document ({@link YaciStoreContextHarness}): no plugin framework (whose files
 * root the non-root image cannot create, and whose {@code pluginTaskScheduler} would displace Boot's 4-thread
 * scheduler the node's {@code @Scheduled} work relies on), no plugin or core admin API, no automatic index
 * management, no parallel block processing — and the exact set of Yaci routes the node still serves.
 */
class YaciStoreSwitchesTest {

    private static final String RESOURCE = "application.yaml";
    private static final String DB_INDEX_PROCESSOR = "com.bloxbean.cardano.yaci.store.dbutils.DBIndexProcessor";

    /** FAB-203-5 documents this set; a change here is a docs change. */
    private static final Set<String> SERVED_ROUTES = Set.of(
            "GET /api/v1/addresses/{address}/utxos",
            "GET /api/v1/addresses/{address}/utxos/{asset}",
            "GET /api/v1/addresses/{address}/transactions",
            "GET /api/v1/assets/utxos/unit/{unit}",
            "GET /api/v1/assets/{unit}/utxos",
            "GET /api/v1/assets/{unit}/transactions",
            "GET /api/v1/utxos/{txHash}/{index}",
            "POST /api/v1/utxos",
            "GET /v3/api-docs",
            "GET /v3/api-docs.yaml",
            "GET /v3/api-docs/swagger-config",
            "GET /swagger-ui.html");

    @Test
    void storePropertiesAndTheEnvironmentReadEveryD1SwitchAsOff() {
        YaciStoreContextHarness.runner().run(context -> {
            assertThat(context).hasNotFailed();
            StoreProperties storeProperties = context.getBean(StoreProperties.class);
            assertFalse(storeProperties.isPluginsEnabled(), "store.plugins.enabled");
            assertFalse(storeProperties.isPluginApiEnabled(), "store.plugins.api-enabled");
            assertFalse(storeProperties.isEnableParallelProcessing(), "store.executor.enable-parallel-processing");
            // Read straight from the Environment by CoreApiConfiguration and DBIndexProcessor, never bound.
            assertEquals(Boolean.FALSE, context.getEnvironment().getProperty("store.core.api-enabled", Boolean.class),
                    "store.core.api-enabled");
            assertEquals(Boolean.FALSE,
                    context.getEnvironment().getProperty("store.auto-index-management", Boolean.class),
                    "store.auto-index-management");
        });
    }

    @Test
    void noPluginOrCoreApiBeanExists() {
        YaciStoreContextHarness.runner().run(context -> {
            assertThat(context).hasNotFailed();
            for (Class<?> absent : List.of(PluginAspect.class, PluginController.class, SchedulerAdminController.class,
                    CoreApiConfiguration.class, SyncController.class, GenesisController.class)) {
                assertEquals(List.of(), List.of(context.getBeanNamesForType(absent)),
                        absent.getSimpleName() + " must not be a bean");
            }
            assertFalse(context.containsBean("pluginTaskScheduler"), "pluginTaskScheduler must not be a bean");
        });
    }

    /**
     * Boot's {@code taskScheduler} only exists while no other {@link TaskScheduler} does, so Yaci's plugin scheduler
     * would silently replace it — and with it the 4-thread pool {@code spring.task.scheduling.pool.size} sizes. The
     * probe settles which scheduler {@code @Scheduled} methods actually run on, whatever the auto-configuration order.
     */
    @Test
    void bootsFourThreadSchedulerIsTheOnlyOneAndRunsScheduledMethods() {
        YaciStoreContextHarness.runner()
                .withBean(SchedulingProbe.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    Map<String, TaskScheduler> schedulers = context.getBeansOfType(TaskScheduler.class);
                    assertEquals(Set.of("taskScheduler"), schedulers.keySet(), "exactly one TaskScheduler bean");
                    ThreadPoolTaskScheduler scheduler =
                            assertInstanceOf(ThreadPoolTaskScheduler.class, schedulers.get("taskScheduler"));
                    assertEquals(4, scheduler.getScheduledThreadPoolExecutor().getCorePoolSize(),
                            "spring.task.scheduling.pool.size ships 4");

                    SchedulingProbe probe = context.getBean(SchedulingProbe.class);
                    assertTrue(probe.ran.await(5, TimeUnit.SECONDS), "the @Scheduled probe never ran");
                    assertTrue(probe.threadName.get().startsWith("scheduling-"),
                            "@Scheduled ran on " + probe.threadName.get() + ", not on Boot's scheduler");
                });
    }

    @Test
    void theServedRoutesAreExactlyYacisUtxoApiAndSpringdoc() {
        YaciStoreContextHarness.runner().run(context -> {
            assertThat(context).hasNotFailed();
            RequestMappingHandlerMapping mapping =
                    context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
            Set<String> routes = new TreeSet<>();
            for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mapping.getHandlerMethods().entrySet()) {
                if (BasicErrorController.class.isAssignableFrom(entry.getValue().getBeanType())) {
                    continue;
                }
                Set<RequestMethod> methods = entry.getKey().getMethodsCondition().getMethods();
                for (String path : entry.getKey().getPatternValues()) {
                    if (methods.isEmpty()) {
                        routes.add("ANY " + path);
                    }
                    for (RequestMethod method : methods) {
                        routes.add(method + " " + path);
                    }
                }
            }
            assertEquals(new TreeSet<>(SERVED_ROUTES), routes);
        });
    }

    @Test
    void autoIndexManagementAppliesNoIndexOnAnEarlySyncBlock() {
        YaciStoreContextHarness.runner().run(context -> {
            assertThat(context).hasNotFailed();
            // By name: yaci-store-dbutils is a runtime-scoped dependency of yaci-store-core, absent at compile time.
            Logger logger = (Logger) LoggerFactory.getLogger(DB_INDEX_PROCESSOR);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            try {
                context.publishEvent(new BlockHeaderEvent(
                        EventMetadata.builder().syncMode(true).block(1).slot(1).build(), null));
            } finally {
                logger.detachAppender(appender);
            }
            List<String> applied = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.contains("apply optional indexes"))
                    .toList();
            assertEquals(List.of(), applied, "DBIndexProcessor applied indexes on a sync below block 50,000");
        });
    }

    /**
     * The non-root image owns nothing under its working directory, so a plugin files root Yaci tries to create there
     * fails the boot. Constructed exactly as {@code YaciStoreAutoConfiguration.storeProperties()} feeds it, from the
     * shipped base document, under a directory nobody can write.
     */
    @Test
    void pluginFileClientCreatesNothingUnderAnUnwritableWorkingDirectory() throws IOException {
        List<PropertySource<?>> documents = new YamlPropertySourceLoader()
                .load(RESOURCE, new ClassPathResource(RESOURCE));
        YaciStoreProperties bound = new Binder(ConfigurationPropertySources.from(documents.getFirst()))
                .bindOrCreate("store", YaciStoreProperties.class);

        Path workingDirectory = Files.createTempDirectory("unwritable-app");
        try {
            Files.setPosixFilePermissions(workingDirectory, PosixFilePermissions.fromString("r-xr-xr-x"));
            assertFalse(Files.isWritable(workingDirectory),
                    "fixture: " + workingDirectory + " must be unwritable (running as root?), or this proves nothing");

            StoreProperties storeProperties = new StoreProperties();
            storeProperties.setPluginsEnabled(bound.getPlugins().isEnabled());
            storeProperties.setPluginFilesEnableLocks(bound.getPlugins().getFiles().isEnableLocks());
            storeProperties.setPluginFilesRootPath(workingDirectory.resolve("plugins/files").toString());

            assertDoesNotThrow(() -> new PluginFileClient(storeProperties));
            assertFalse(Files.exists(workingDirectory.resolve("plugins")), "PluginFileClient created plugins/");
        } finally {
            Files.setPosixFilePermissions(workingDirectory, PosixFilePermissions.fromString("rwx------"));
            try (var leftovers = Files.walk(workingDirectory)) {
                leftovers.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    /** Records the thread its {@code @Scheduled} method first runs on. */
    static class SchedulingProbe {
        final CountDownLatch ran = new CountDownLatch(1);
        final AtomicReference<String> threadName = new AtomicReference<>();

        @Scheduled(fixedDelay = 50)
        void record() {
            if (threadName.compareAndSet(null, Thread.currentThread().getName())) {
                ran.countDown();
            }
        }
    }
}
