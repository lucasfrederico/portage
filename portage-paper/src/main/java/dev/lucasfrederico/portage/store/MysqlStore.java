package dev.lucasfrederico.portage.store;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

/**
 * The durable lane: every handoff and every server stop leaves a snapshot
 * row behind, so a player whose Redis payload expired still gets their
 * latest state, and staff can roll back to any earlier one.
 */
public final class MysqlStore implements SnapshotArchive, AutoCloseable {

    private final HikariDataSource pool;

    /**
     * Opens the pool and makes sure the table exists.
     *
     * @param jdbcUrl  the JDBC URL
     * @param user     the database user
     * @param password the database password
     * @throws SQLException when the database cannot be reached or prepared
     */
    public MysqlStore(String jdbcUrl, String user, String password) throws SQLException {
        var config = new HikariConfig();
        // Named so the driver resolves through the plugin's own class loader;
        // DriverManager cannot see libraries a plugin brings along.
        config.setDriverClassName(jdbcUrl.startsWith("jdbc:mariadb")
                ? "org.mariadb.jdbc.Driver" : "com.mysql.cj.jdbc.Driver");
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(4);
        config.setPoolName("portage");
        this.pool = new HikariDataSource(config);
        try (var connection = pool.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS portage_snapshots (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        player CHAR(36) NOT NULL,
                        server VARCHAR(64) NOT NULL,
                        cause VARCHAR(16) NOT NULL,
                        format INT NOT NULL,
                        taken_at TIMESTAMP(3) NOT NULL,
                        payload MEDIUMBLOB NOT NULL,
                        INDEX by_player (player, id)
                    )""");
        }
    }

    /**
     * Stores one snapshot.
     *
     * @param player  the player
     * @param server  the server that took it
     * @param cause   why it was taken: {@code quit}, {@code stop}, {@code manual}
     * @param format  the snapshot format version
     * @param takenAt epoch milliseconds when it was taken
     * @param payload the encoded snapshot
     * @throws SQLException when the write fails
     */
    @Override
    public void save(UUID player, String server, String cause, int format, long takenAt,
                     byte[] payload) throws SQLException {
        try (var connection = pool.getConnection(); var statement = connection.prepareStatement(
                "INSERT INTO portage_snapshots (player, server, cause, format, taken_at, payload)"
                        + " VALUES (?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, player.toString());
            statement.setString(2, server);
            statement.setString(3, cause);
            statement.setInt(4, format);
            statement.setTimestamp(5, new Timestamp(takenAt));
            statement.setBytes(6, payload);
            statement.executeUpdate();
        }
    }

    /**
     * The most recent snapshot of a player.
     *
     * @param player the player
     * @return the encoded snapshot, if the player has ever been stored
     * @throws SQLException when the read fails
     */
    @Override
    public Optional<byte[]> latest(UUID player) throws SQLException {
        try (var connection = pool.getConnection(); var statement = connection.prepareStatement(
                "SELECT payload FROM portage_snapshots WHERE player = ? ORDER BY id DESC LIMIT 1")) {
            statement.setString(1, player.toString());
            try (var result = statement.executeQuery()) {
                return result.next() ? Optional.of(result.getBytes(1)) : Optional.empty();
            }
        }
    }

    @Override
    public void close() {
        pool.close();
    }
}
