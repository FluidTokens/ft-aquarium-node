package com.fluidtokens.aquarium.offchain.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import javax.sql.DataSource;

import com.fluidtokens.aquarium.offchain.model.TokenMetadata;

import lombok.extern.slf4j.Slf4j;

/**
 * {@link TokenMetadataStore} over the {@code token_metadata} table, in plain JDBC.
 *
 * <h2>⚠ Why JDBC and not JPA, deliberately</h2>
 * This node's database is <b>yaci-store's</b>. Its repositories are enabled by yaci-store's own
 * configuration, and this application declares no {@code @EnableJpaRepositories} of its own — adding
 * one to register a single entity would switch off Spring Boot's repository auto-configuration for
 * everything, and <b>no test in this repo boots a context with a datasource</b>, so that breakage
 * would first appear on an operator's node at startup. One table read by two statements is not worth
 * that risk. JDBC touches nothing yaci-store owns.
 *
 * <h2>Never fatal</h2>
 * This is a display cache. A failure here degrades a label; it must never take down a page, let alone
 * a node. Every fault is logged and swallowed, and the caller falls back to "unknown", which renders
 * the raw amount and a marker.
 */
@Slf4j
public class JdbcTokenMetadataStore implements TokenMetadataStore {

    private static final String SELECT =
            "SELECT ticker, name, decimals, source, fetched_at FROM token_metadata WHERE unit = ?";
    private static final String DELETE = "DELETE FROM token_metadata WHERE unit = ?";
    private static final String INSERT =
            "INSERT INTO token_metadata (unit, ticker, name, decimals, source, fetched_at) VALUES (?, ?, ?, ?, ?, ?)";

    private final DataSource dataSource;

    public JdbcTokenMetadataStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Optional<Stored> find(String unit) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(SELECT)) {
            ps.setString(1, unit);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                int decimals = rs.getInt("decimals");
                // ⛔ wasNull() is the whole point: 0 is a real scale and NULL means "not known".
                Integer scale = rs.wasNull() ? null : decimals;
                TokenMetadata metadata = new TokenMetadata(unit, rs.getString("ticker"), rs.getString("name"),
                        scale, TokenMetadata.Source.valueOf(rs.getString("source")));
                return Optional.of(new Stored(metadata, rs.getTimestamp("fetched_at").toInstant()));
            }
        } catch (SQLException | IllegalArgumentException e) {
            log.debug("token metadata read failed for {}: {}", unit, e.toString());
            return Optional.empty();
        }
    }

    @Override
    public void put(TokenMetadata metadata, Instant fetchedAt) {
        // Delete-then-insert rather than a vendor-specific upsert: the two migrations must stay
        // byte-identical across vendors, and ON CONFLICT / MERGE are not spelled the same way.
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement delete = c.prepareStatement(DELETE)) {
                delete.setString(1, metadata.unit());
                delete.executeUpdate();
            }
            try (PreparedStatement insert = c.prepareStatement(INSERT)) {
                insert.setString(1, metadata.unit());
                insert.setString(2, metadata.ticker());
                insert.setString(3, metadata.name());
                if (metadata.decimals() == null) {
                    insert.setNull(4, java.sql.Types.INTEGER);
                } else {
                    insert.setInt(4, metadata.decimals());
                }
                insert.setString(5, metadata.source().name());
                insert.setTimestamp(6, Timestamp.from(fetchedAt));
                insert.executeUpdate();
            }
        } catch (SQLException e) {
            log.debug("token metadata write failed for {}: {}", metadata.unit(), e.toString());
        }
    }
}
