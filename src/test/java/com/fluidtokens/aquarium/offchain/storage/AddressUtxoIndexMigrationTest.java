package com.fluidtokens.aquarium.offchain.storage;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ The node's own {@code address_utxo} indexes ({@code V1001}), run through the migration order that
 * ships.
 *
 * <h2>Why this exists</h2>
 * Yaci Store 0.1.7's library migration ({@code V0_200_1__init.sql}) created the indexes every
 * credential and address read of this node walks. 2.0.7 edited that migration in place and dropped
 * them; they now exist only in Yaci's dbutils {@code index.yml}, applied by {@code DBIndexProcessor},
 * which the node switches off. Nothing fails without them — every read just scans the table — so the
 * only place their absence can show is a test that asks the schema, and the query planner, directly.
 *
 * <p>Flyway is driven against an in-memory H2 with production's locations in production's order
 * ({@code db/store} first, then the node's {@code db/migration}) and production's {@code out-of-order},
 * so the files that ship are the files that run.
 *
 * <h2>⚠ What it does not prove</h2>
 * Production is PostgreSQL. The vendor copies are asserted byte-identical, and the statement is one
 * both vendors accept, which keeps that gap small; a PostgreSQL run is left to the first dev-image
 * deploy.
 */
class AddressUtxoIndexMigrationTest {

    private static final String[] PRODUCTION_LOCATIONS = {"classpath:db/store/h2", "classpath:db/migration/h2"};

    private static String url() {
        return "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
    }

    private static Flyway flyway(String url, String target) {
        var config = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations(PRODUCTION_LOCATIONS)
                .outOfOrder(true);
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    /** Index name → its columns, for every index on {@code address_utxo}. */
    private static Map<String, Set<String>> addressUtxoIndexes(Connection c) throws SQLException {
        Map<String, Set<String>> indexes = new HashMap<>();
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT index_name, column_name FROM information_schema.index_columns "
                     + "WHERE LOWER(table_name) = 'address_utxo'")) {
            while (rs.next()) {
                indexes.computeIfAbsent(rs.getString("index_name").toLowerCase(), k -> new TreeSet<>())
                        .add(rs.getString("column_name").toLowerCase());
            }
        }
        return indexes;
    }

    private static Map<String, Set<String>> withoutPrimaryKey(Map<String, Set<String>> indexes) {
        Map<String, Set<String>> rest = new HashMap<>(indexes);
        rest.keySet().removeIf(name -> name.startsWith("primary_key"));
        return rest;
    }

    /**
     * After a full migration {@code address_utxo} carries exactly Yaci 2.0.7's own two indexes plus the
     * node's two — no fewer (a read would scan), and no more (V1001 must not grow indexes no read of
     * this node uses).
     */
    @Test
    void aFullMigrationLeavesAddressUtxoWithExactlyTheIndexesTheNodeReads() throws Exception {
        String url = url();
        flyway(url, null).migrate();

        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            Map<String, Set<String>> indexes = addressUtxoIndexes(c);

            Set<String> primaryKey = indexes.entrySet().stream()
                    .filter(e -> e.getKey().startsWith("primary_key"))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
            assertNotNull(primaryKey, "address_utxo must keep its primary key: " + indexes);
            assertEquals(Set.of("output_index", "tx_hash"), primaryKey);

            assertEquals(Map.of(
                            "idx_address_utxo_slot", Set.of("slot"),
                            "idx_reference_script_hash", Set.of("reference_script_hash"),
                            "idx_address_utxo_owner_paykey_hash", Set.of("owner_payment_credential"),
                            "idx_address_utxo_owner_addr", Set.of("owner_addr")),
                    withoutPrimaryKey(indexes),
                    "the non-primary-key indexes on address_utxo");

            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT success FROM flyway_schema_history WHERE version = '1001'")) {
                assertTrue(rs.next(), "V1001 must be recorded in the schema history");
                assertTrue(rs.getBoolean("success"), "V1001 must be recorded as successful");
            }
        }
    }

    private static String explain(Connection c, String sql) throws SQLException {
        StringBuilder plan = new StringBuilder();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("EXPLAIN " + sql)) {
            while (rs.next()) {
                plan.append(rs.getString(1)).append('\n');
            }
        }
        return plan.toString();
    }

    /**
     * The two read shapes of {@code UtxoRepository} — {@code address_utxo LEFT JOIN tx_input}, unspent
     * only — are planned through the new indexes rather than a table scan.
     */
    @Test
    void theNodesTwoReadShapesAreServedByTheNewIndexes() throws Exception {
        String url = url();
        flyway(url, null).migrate();

        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            String byCredential = explain(c, "SELECT a.* FROM address_utxo a LEFT JOIN tx_input s "
                    + "ON a.tx_hash = s.tx_hash AND a.output_index = s.output_index "
                    + "WHERE a.owner_payment_credential = 'ab' AND s.tx_hash IS NULL");
            assertTrue(byCredential.contains(
                            "/* public.idx_address_utxo_owner_paykey_hash: owner_payment_credential = 'ab' */"),
                    "the credential read must use idx_address_utxo_owner_paykey_hash:\n" + byCredential);

            String byAddress = explain(c, "SELECT a.* FROM address_utxo a LEFT JOIN tx_input s "
                    + "ON a.tx_hash = s.tx_hash AND a.output_index = s.output_index "
                    + "WHERE a.owner_addr = 'ab' AND s.tx_hash IS NULL");
            assertTrue(byAddress.contains("/* public.idx_address_utxo_owner_addr: owner_addr = 'ab' */"),
                    "the address read must use idx_address_utxo_owner_addr:\n" + byAddress);
        }
    }

    /**
     * ⛔ A database that already has the index under Yaci's name — from {@code DBIndexProcessor}, or
     * created by an operator who noticed the scans — must migrate cleanly. Without {@code IF NOT EXISTS}
     * this is a boot failure on exactly the operators who did the right thing early.
     */
    @Test
    void aDatabaseThatAlreadyHasTheIndexStillMigrates() throws Exception {
        String url = url();
        flyway(url, "1000").migrate();
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement()) {
            s.execute("CREATE INDEX idx_address_utxo_owner_paykey_hash ON address_utxo(owner_payment_credential)");
        }

        flyway(url, null).migrate();

        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            Map<String, Set<String>> indexes = addressUtxoIndexes(c);
            assertEquals(Set.of("owner_payment_credential"), indexes.get("idx_address_utxo_owner_paykey_hash"),
                    "the pre-existing index must survive: " + indexes);
            assertEquals(Set.of("owner_addr"), indexes.get("idx_address_utxo_owner_addr"),
                    "the other index must still be created: " + indexes);
        }
    }

    /**
     * The two vendor copies must stay byte-identical: they are separate files only because Flyway
     * resolves {@code {vendor}} per datasource, and if they diverge the H2 runs above stop saying
     * anything about what PostgreSQL runs.
     */
    @Test
    void bothVendorCopiesAreByteIdentical() throws Exception {
        byte[] h2 = resource("/db/migration/h2/V1001__address_utxo_indexes.sql");
        byte[] postgres = resource("/db/migration/postgresql/V1001__address_utxo_indexes.sql");

        assertNotNull(h2, "the h2 migration must be on the classpath");
        assertNotNull(postgres, "the postgresql migration must be on the classpath");
        assertArrayEquals(h2, postgres,
                "the vendor copies have diverged — the H2 test no longer covers what PostgreSQL runs");
    }

    private static byte[] resource(String path) throws Exception {
        try (InputStream in = AddressUtxoIndexMigrationTest.class.getResourceAsStream(path)) {
            return in == null ? null : in.readAllBytes();
        }
    }
}
