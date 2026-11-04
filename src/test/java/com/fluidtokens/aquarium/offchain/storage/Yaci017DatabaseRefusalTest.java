package com.fluidtokens.aquarium.offchain.storage;

import java.io.InputStream;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.ConfigurableEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ A database written by Yaci Store 0.1.7 is REFUSED at boot, and nothing is applied to it.
 *
 * <h2>Why this exists</h2>
 * Yaci Store 2.0.7 edited its {@code V0_200_1__init.sql} in place, so the checksum every 0.1.7 node
 * recorded for it no longer matches what this release resolves. Flyway's default validation turns that
 * into a startup failure — and that failure is the upgrade's guard: it stops the node before it writes
 * a 2.0.7 row into a 0.1.7 schema. Nothing in the node configures it, so nothing in the node would
 * notice if a property switched it off. This pins it, through the shipped {@code application.yaml}.
 *
 * <p>A 0.1.7 database is reproduced from a frozen copy of 0.1.7's H2 migrations
 * ({@code src/test/resources/yaci-store-0.1.7}, provenance in its README), and the fixture is first
 * shown to record the very checksums the published 0.1.7 jars carry — otherwise the refusal would be
 * measured against a database no operator has.
 *
 * <h2>Spring, not a hand-copied Flyway</h2>
 * The refusal runs through Boot's {@code FlywayAutoConfiguration} over the real {@code application.yaml}
 * (via {@link ConfigDataApplicationContextInitializer}); only the connection properties are overridden,
 * and test properties take precedence over the file. The test also asserts that the environment
 * resolved the shipped locations and {@code out-of-order}, so a runner that silently read no YAML cannot
 * pass. No {@code SpringApplication} is started, so no {@code ApplicationReadyEvent} fires.
 */
class Yaci017DatabaseRefusalTest {

    private static final String FIXTURE = "classpath:yaci-store-0.1.7/db/h2";
    private static final String NODE_MIGRATIONS = "classpath:db/migration/h2";

    /** sha256 of each fixture file, equal to the {@code v0.1.7} tag's copy. */
    private static final Map<String, String> FIXTURE_SHA256 = Map.of(
            "V0_000_1__init.sql", "cdd9b958d4b0863d598c398e66b2508f2f202425ae6408b5ca6e480d143eb380",
            "V0_000_2__error_table.sql", "946ba946a40556e0cff72e05523927629bf92676af93baddc98fd151a8b60e08",
            "V0_200_1__init.sql", "45f71779a029d93933b1b7811d0048f239fd3ffd20427089b580c1b1ef2e765b");

    /** The checksums the published 0.1.7 jars resolve, i.e. what every 0.1.7 node recorded. */
    private static final int CHECKSUM_017_V0_000_1 = 15733750;
    private static final int CHECKSUM_017_V0_000_2 = 180837475;
    private static final int CHECKSUM_017_V0_200_1 = 694847724;
    /** 2.0.7's edited H2 {@code V0_200_1__init.sql}. */
    private static final int CHECKSUM_207_V0_200_1 = -27599824;

    private static String url() {
        return "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
    }

    /** A database as a 0.1.7 node left it: Yaci's 0.1.7 migrations plus the node's own V1000. */
    private static String yaci017Database() {
        String url = url();
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations(FIXTURE, NODE_MIGRATIONS)
                .outOfOrder(true)
                .target("1000")
                .load()
                .migrate();
        return url;
    }

    /** One row of {@code flyway_schema_history}: version, checksum, success. */
    private record HistoryRow(String version, Integer checksum, boolean success) { }

    private static List<HistoryRow> history(String url) throws SQLException {
        List<HistoryRow> rows = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT version, checksum, success FROM flyway_schema_history ORDER BY installed_rank")) {
            while (rs.next()) {
                int checksum = rs.getInt("checksum");
                rows.add(new HistoryRow(rs.getString("version"), rs.wasNull() ? null : checksum,
                        rs.getBoolean("success")));
            }
        }
        return rows;
    }

    private static Map<String, Integer> checksumsByVersion(List<HistoryRow> rows) {
        Map<String, Integer> byVersion = new LinkedHashMap<>();
        for (HistoryRow row : rows) {
            if (row.version() != null) {
                byVersion.put(row.version(), row.checksum());
            }
        }
        return byVersion;
    }

    private static boolean tableExists(String url, String table) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM information_schema.tables "
                     + "WHERE LOWER(table_name) = '" + table + "'")) {
            rs.next();
            return rs.getInt(1) > 0;
        }
    }

    /**
     * The shipped configuration over the database at {@code url}: the real {@code application.yaml},
     * Boot's own DataSource and Flyway auto-configuration, and nothing overridden but where to connect.
     */
    private static ApplicationContextRunner shippedFlywayOver(String url) {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                        FlywayAutoConfiguration.class))
                .withPropertyValues(
                        "spring.datasource.url=" + url,
                        "spring.datasource.username=sa",
                        "spring.datasource.password=",
                        "spring.flyway.url=" + url,
                        "spring.flyway.user=sa",
                        "spring.flyway.password=");
    }

    private static FlywayValidateException validateFailureIn(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof FlywayValidateException validate) {
                return validate;
            }
        }
        return null;
    }

    private static byte[] resource(String path) throws Exception {
        try (InputStream in = Yaci017DatabaseRefusalTest.class.getResourceAsStream(path)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    /**
     * The fixture IS a 0.1.7 database: its files are the tag's bytes, and Flyway records for them the
     * checksums the published 0.1.7 jars carry.
     */
    @Test
    void theFixtureRecordsThePublished017Checksums() throws Exception {
        for (Map.Entry<String, String> file : FIXTURE_SHA256.entrySet()) {
            byte[] bytes = resource("/yaci-store-0.1.7/db/h2/" + file.getKey());
            assertNotNull(bytes, "fixture file missing from the classpath: " + file.getKey());
            String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            assertEquals(file.getValue(), sha256, file.getKey() + " is no longer the v0.1.7 tag's copy");
        }

        Map<String, Integer> checksums = checksumsByVersion(history(yaci017Database()));
        assertEquals(CHECKSUM_017_V0_000_1, checksums.get("0.000.1"));
        assertEquals(CHECKSUM_017_V0_000_2, checksums.get("0.000.2"));
        assertEquals(CHECKSUM_017_V0_200_1, checksums.get("0.200.1"));
    }

    /**
     * ⛔ THE REFUSAL. Over a 0.1.7 database the shipped configuration does not start: Flyway's default
     * validation fails on the edited {@code 0.200.1}. The message is printed once — the operator docs
     * quote it.
     */
    @Test
    void theShippedConfigurationRefusesA017Database() {
        String url = yaci017Database();

        // A context that failed to start refuses every call but getStartupFailure(), so the environment
        // it refreshed with is captured as it is initialised.
        AtomicReference<ConfigurableEnvironment> environment = new AtomicReference<>();
        shippedFlywayOver(url).withInitializer(context -> environment.set(context.getEnvironment())).run(context -> {
            Binder binder = Binder.get(environment.get());
            assertEquals(List.of("classpath:db/store/{vendor}", "classpath:db/migration/{vendor}"),
                    binder.bind("spring.flyway.locations", Bindable.listOf(String.class)).orElse(null),
                    "the runner must have read the shipped application.yaml");
            assertEquals(Boolean.TRUE, environment.get().getProperty("spring.flyway.out-of-order", Boolean.class),
                    "the runner must have read the shipped application.yaml");

            Throwable failure = context.getStartupFailure();
            assertNotNull(failure, "the node must refuse to start on a 0.1.7 database");
            FlywayValidateException refusal = validateFailureIn(failure);
            assertNotNull(refusal, "the refusal must be Flyway's validation, not some other failure: " + failure);
            assertTrue(refusal.getMessage().contains("Migration checksum mismatch for migration version 0.200.1"),
                    refusal.getMessage());
            assertTrue(refusal.getMessage().contains("-> Applied to database : " + CHECKSUM_017_V0_200_1),
                    refusal.getMessage());

            System.out.println("FAB-203-4 refusal of a Yaci Store 0.1.7 database:\n" + refusal.getMessage());
        });
    }

    /**
     * ⛔ Refused means UNTOUCHED: the schema history is exactly what it was, V1001 was not applied, and
     * none of 2.0.7's schema (its {@code ptr_address} table) was created.
     */
    @Test
    void theRefusalAppliesNothing() throws Exception {
        String url = yaci017Database();
        List<HistoryRow> before = history(url);

        shippedFlywayOver(url).run(context ->
                assertNotNull(context.getStartupFailure(), "the node must refuse to start on a 0.1.7 database"));

        List<HistoryRow> after = history(url);
        assertEquals(before, after, "the refusal must leave the schema history as it was");
        assertFalse(checksumsByVersion(after).containsKey("1001"), "V1001 must not have been applied");
        assertFalse(tableExists(url, "ptr_address"), "2.0.7's ptr_address must not have been created");
    }

    /** Control: the same runner over a fresh database starts, and applies 2.0.7's schema and V1001. */
    @Test
    void theShippedConfigurationMigratesAFreshDatabase() throws Exception {
        String url = url();

        shippedFlywayOver(url).run(context -> {
            assertNull(context.getStartupFailure(), "a fresh database must migrate");
            assertTrue(context.containsBean("flywayInitializer"), "Flyway must actually have run");
        });

        Map<String, Integer> checksums = checksumsByVersion(history(url));
        assertTrue(checksums.containsKey("1001"), "V1001 must be applied to a fresh database: " + checksums);
        assertEquals(CHECKSUM_207_V0_200_1, checksums.get("0.200.1"));
    }

    /**
     * The reverse direction: an image built before this upgrade, pointed at a database this release
     * wrote, refuses it the same way — which is why rolling back means restoring the old database, not
     * just the old image.
     */
    @Test
    void a017ImageRefusesADatabaseThisReleaseWrote() {
        String url = url();
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/store/h2", NODE_MIGRATIONS)
                .outOfOrder(true)
                .load()
                .migrate();

        Flyway yaci017 = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations(FIXTURE, NODE_MIGRATIONS)
                .outOfOrder(true)
                .load();
        FlywayValidateException refusal = assertThrows(FlywayValidateException.class, yaci017::validate);
        assertTrue(refusal.getMessage().contains("Migration checksum mismatch for migration version 0.200.1"),
                refusal.getMessage());
    }
}
