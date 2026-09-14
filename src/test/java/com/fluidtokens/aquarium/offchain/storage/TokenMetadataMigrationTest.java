package com.fluidtokens.aquarium.offchain.storage;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

import javax.sql.DataSource;

import com.fluidtokens.aquarium.offchain.model.TokenMetadata;

import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ Runs the SHIPPED migration, rather than trusting that it would run.
 *
 * <h2>Why this exists at all</h2>
 * No test in this repo boots a Spring context with a datasource, so a migration added to
 * {@code spring.flyway.locations} would otherwise be exercised for the first time <b>on an operator's
 * node, at boot</b> — and a migration that fails there does not degrade a feature, it stops the node
 * starting. A schema change nothing runs before release is a boot failure waiting for a stranger.
 *
 * <p>This drives Flyway directly against an in-memory H2 using the real
 * {@code classpath:db/migration/h2} location, so the file that ships is the file that runs.
 *
 * <h2>⚠ What it does not prove</h2>
 * Production is PostgreSQL. This exercises the H2 copy, so it proves the SQL is valid and the shape is
 * right; it does not prove the PostgreSQL copy parses on PostgreSQL. The two files are asserted
 * identical below, which is what makes that gap small rather than invisible — and the DDL uses only
 * types both vendors spell the same way.
 */
class TokenMetadataMigrationTest {

    private static String url() {
        return "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    }

    private static Connection migrated() throws SQLException {
        String url = url();
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration/h2")
                .load()
                .migrate();
        return DriverManager.getConnection(url, "sa", "");
    }

    /** The table the UI's token cache needs, created by the migration that ships. */
    @Test
    void theShippedMigrationCreatesTheTokenMetadataTable() throws Exception {
        try (Connection c = migrated(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO token_metadata (unit, ticker, name, decimals, source, fetched_at) "
                    + "VALUES ('abc', 'FLDT', 'FluidTokens', 6, 'REGISTRY', CURRENT_TIMESTAMP)");
            try (ResultSet rs = s.executeQuery("SELECT ticker, decimals, source FROM token_metadata WHERE unit = 'abc'")) {
                assertTrue(rs.next(), "the row must be readable back");
                assertEquals("FLDT", rs.getString("ticker"));
                assertEquals(6, rs.getInt("decimals"));
                assertEquals("REGISTRY", rs.getString("source"));
            }
        }
    }

    /**
     * ⛔ <b>{@code decimals} MUST be nullable.</b> Zero is a real scale; if the column forbade null,
     * "we do not know" would have to be stored as zero and every amount of that asset would render
     * mis-scaled by up to a million — the exact defect the UNKNOWN source exists to prevent. A NOT NULL
     * here would make the application-level rule unenforceable.
     */
    @Test
    void decimalsIsNullableSoUnknownNeverHasToBeStoredAsZero() throws Exception {
        try (Connection c = migrated(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO token_metadata (unit, decimals, source, fetched_at) "
                    + "VALUES ('unknown-asset', NULL, 'UNKNOWN', CURRENT_TIMESTAMP)");
            try (ResultSet rs = s.executeQuery("SELECT decimals FROM token_metadata WHERE unit = 'unknown-asset'")) {
                assertTrue(rs.next());
                rs.getInt("decimals");
                assertTrue(rs.wasNull(), "an unknown scale must be stored as NULL, not as 0");
            }
        }
    }

    /** Re-running must be harmless: Flyway records the version and the DDL is itself idempotent. */
    @Test
    void theMigrationIsIdempotent() {
        String url = url();
        for (int i = 0; i < 2; i++) {
            Flyway.configure().dataSource(url, "sa", "")
                    .locations("classpath:db/migration/h2").load().migrate();
        }
    }

    /**
     * The two vendor copies must stay identical. They are only separate files because Flyway resolves
     * {@code {vendor}} per datasource; if they ever diverge, the H2 test above stops saying anything
     * about what PostgreSQL will run.
     */
    @Test
    void bothVendorCopiesAreIdentical() throws Exception {
        String h2 = resource("/db/migration/h2/V1000__token_metadata.sql");
        String postgres = resource("/db/migration/postgresql/V1000__token_metadata.sql");

        assertNotNull(h2, "the h2 migration must be on the classpath");
        assertNotNull(postgres, "the postgresql migration must be on the classpath");
        assertEquals(h2, postgres,
                "the vendor copies have diverged — the H2 test no longer covers what PostgreSQL runs");
    }

    private static String resource(String path) throws Exception {
        try (var in = TokenMetadataMigrationTest.class.getResourceAsStream(path)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static DataSource migratedDataSource() {
        String url = url();
        Flyway.configure().dataSource(url, "sa", "")
                .locations("classpath:db/migration/h2").load().migrate();
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL(url);
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }

    /** The store writes and reads back through the schema that actually ships. */
    @Test
    void theStoreRoundTripsAKnownAssetThroughTheShippedSchema() {
        JdbcTokenMetadataStore store = new JdbcTokenMetadataStore(migratedDataSource());
        Instant now = Instant.parse("2026-09-14T10:00:00Z");
        TokenMetadata fldt = new TokenMetadata("11", "FLDT", "FluidTokens", 6, TokenMetadata.Source.REGISTRY);

        store.put(fldt, now);

        TokenMetadataStore.Stored back = store.find("11").orElseThrow();
        assertEquals("FLDT", back.metadata().ticker());
        assertEquals(6, back.metadata().decimals());
        assertEquals(TokenMetadata.Source.REGISTRY, back.metadata().source());
        assertEquals(now, back.fetchedAt(), "the TTL depends on this timestamp surviving the round trip");
    }

    /**
     * ⛔ <b>THE ROUND TRIP THAT MATTERS.</b> An unknown scale must come back as null, not as 0.
     * JDBC's {@code getInt} returns 0 for SQL NULL, so without {@code wasNull()} every unknown asset
     * would read back claiming a real scale of zero — and would then render mis-scaled by up to a
     * million with no marker, because the row would look perfectly well-formed.
     */
    @Test
    void anUnknownScaleSurvivesAsNullRatherThanBecomingZero() {
        JdbcTokenMetadataStore store = new JdbcTokenMetadataStore(migratedDataSource());
        TokenMetadata unknown = TokenMetadata.unknown("deadbeef");

        store.put(unknown, Instant.parse("2026-09-14T10:00:00Z"));

        TokenMetadata back = store.find("deadbeef").orElseThrow().metadata();
        assertNull(back.decimals(), "a NULL scale must not read back as 0");
        assertFalse(back.hasDecimals(), "and must not be treated as a usable scale");
        assertEquals(TokenMetadata.Source.UNKNOWN, back.source());
    }

    /** Writing the same unit twice replaces it rather than failing the primary key. */
    @Test
    void reWritingAUnitReplacesItRatherThanFailing() {
        JdbcTokenMetadataStore store = new JdbcTokenMetadataStore(migratedDataSource());
        Instant t0 = Instant.parse("2026-09-14T10:00:00Z");
        store.put(TokenMetadata.unknown("aa"), t0);
        store.put(new TokenMetadata("aa", "NEW", "Listed later", 2, TokenMetadata.Source.REGISTRY),
                t0.plusSeconds(3600));

        TokenMetadataStore.Stored back = store.find("aa").orElseThrow();
        assertEquals("NEW", back.metadata().ticker(), "a token listed later must be able to replace its miss");
        assertEquals(2, back.metadata().decimals());
    }

    /** An absent unit is empty, not an error. */
    @Test
    void anAbsentUnitReadsAsEmpty() {
        assertTrue(new JdbcTokenMetadataStore(migratedDataSource()).find("nothing").isEmpty());
    }
}
