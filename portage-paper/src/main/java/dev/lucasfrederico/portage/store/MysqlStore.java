package dev.lucasfrederico.portage.store;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.lucasfrederico.portage.data.PlayerSnapshot;
import dev.lucasfrederico.portage.data.SnapshotCause;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The durable lane on MariaDB or MySQL. Players and servers live in their own
 * tables so a snapshot row carries a 4-byte and a 2-byte id instead of the
 * 36-character UUID and the server name; snapshots are clustered by player
 * so the latest one is a single-page read. Ids are cached in memory once
 * resolved, keeping the hot path to one INSERT and one DELETE.
 */
public final class MysqlStore implements SnapshotArchive, AutoCloseable {

    private static final int PLAYER_CACHE_SIZE = 10_000;

    private final HikariDataSource pool;
    private final int keepPerPlayer;
    private final Map<UUID, Integer> playerIds = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Integer> eldest) {
            return size() > PLAYER_CACHE_SIZE;
        }
    };
    private final Map<String, Integer> serverIds = new ConcurrentHashMap<>();

    /**
     * Opens the pool and makes sure the tables exist.
     *
     * @param jdbcUrl       the JDBC URL
     * @param user          the database user
     * @param password      the database password
     * @param keepPerPlayer how many snapshots to keep per player; 0 keeps all
     * @throws SQLException when the database cannot be reached or prepared
     */
    public MysqlStore(String jdbcUrl, String user, String password, int keepPerPlayer)
            throws SQLException {
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
        this.keepPerPlayer = keepPerPlayer;
        createTables();
    }

    private void createTables() throws SQLException {
        try (var connection = pool.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS portage_players (
                        id         INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
                        uuid       BINARY(16)   NOT NULL UNIQUE,
                        name       VARCHAR(16)  NOT NULL,
                        first_seen TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                        last_seen  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS portage_servers (
                        id   SMALLINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
                        name VARCHAR(64) NOT NULL UNIQUE
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS portage_snapshots (
                        player_id INT UNSIGNED      NOT NULL,
                        id        BIGINT UNSIGNED   NOT NULL AUTO_INCREMENT,
                        server_id SMALLINT UNSIGNED NOT NULL,
                        cause     TINYINT UNSIGNED  NOT NULL,
                        format    SMALLINT UNSIGNED NOT NULL,
                        taken_at  TIMESTAMP(3)      NOT NULL,
                        payload   MEDIUMBLOB        NOT NULL,
                        PRIMARY KEY (player_id, id),
                        KEY by_id (id)
                    )""");
        }
    }

    @Override
    public void save(PlayerSnapshot snapshot, SnapshotCause cause, byte[] payload)
            throws SQLException {
        try (var connection = pool.getConnection()) {
            var playerId = playerId(connection, snapshot.player(), snapshot.name());
            var serverId = serverId(connection, snapshot.server());
            try (var statement = connection.prepareStatement(
                    "INSERT INTO portage_snapshots"
                            + " (player_id, server_id, cause, format, taken_at, payload)"
                            + " VALUES (?, ?, ?, ?, ?, ?)")) {
                statement.setInt(1, playerId);
                statement.setInt(2, serverId);
                statement.setInt(3, cause.code());
                statement.setInt(4, snapshot.format());
                statement.setTimestamp(5, new Timestamp(snapshot.takenAt()));
                statement.setBytes(6, payload);
                statement.executeUpdate();
            }
            prune(connection, playerId);
        }
    }

    @Override
    public Optional<byte[]> payload(UUID player, long snapshotId) throws SQLException {
        try (var connection = pool.getConnection()) {
            var playerId = knownPlayerId(connection, player);
            if (playerId.isEmpty()) {
                return Optional.empty();
            }
            try (var statement = connection.prepareStatement(
                    "SELECT payload FROM portage_snapshots WHERE player_id = ? AND id = ?")) {
                statement.setInt(1, playerId.get());
                statement.setLong(2, snapshotId);
                try (var result = statement.executeQuery()) {
                    return result.next() ? Optional.of(result.getBytes(1)) : Optional.empty();
                }
            }
        }
    }

    @Override
    public Optional<byte[]> latest(UUID player) throws SQLException {
        try (var connection = pool.getConnection()) {
            var playerId = knownPlayerId(connection, player);
            if (playerId.isEmpty()) {
                return Optional.empty();
            }
            try (var statement = connection.prepareStatement(
                    "SELECT payload FROM portage_snapshots WHERE player_id = ?"
                            + " ORDER BY id DESC LIMIT 1")) {
                statement.setInt(1, playerId.get());
                try (var result = statement.executeQuery()) {
                    return result.next() ? Optional.of(result.getBytes(1)) : Optional.empty();
                }
            }
        }
    }

    private void prune(Connection connection, int playerId) throws SQLException {
        if (keepPerPlayer <= 0) {
            return;
        }
        // Deletes everything older than the player's Nth newest row; with fewer
        // than N rows the subquery is NULL and nothing matches.
        try (var statement = connection.prepareStatement(
                "DELETE FROM portage_snapshots WHERE player_id = ? AND id < ("
                        + "SELECT id FROM (SELECT id FROM portage_snapshots WHERE player_id = ?"
                        + " ORDER BY id DESC LIMIT 1 OFFSET ?) AS nth)")) {
            statement.setInt(1, playerId);
            statement.setInt(2, playerId);
            statement.setInt(3, keepPerPlayer - 1);
            statement.executeUpdate();
        }
    }

    private int playerId(Connection connection, UUID player, String name) throws SQLException {
        var cached = cachedPlayerId(player);
        if (cached.isPresent()) {
            return cached.get();
        }
        int id;
        try (var statement = connection.prepareStatement(
                "INSERT INTO portage_players (uuid, name) VALUES (?, ?)"
                        + " ON DUPLICATE KEY UPDATE name = VALUES(name),"
                        + " last_seen = CURRENT_TIMESTAMP(3), id = LAST_INSERT_ID(id)")) {
            statement.setBytes(1, bytes(player));
            statement.setString(2, name);
            statement.executeUpdate();
            id = lastInsertId(connection);
        }
        cachePlayerId(player, id);
        return id;
    }

    private Optional<Integer> knownPlayerId(Connection connection, UUID player) throws SQLException {
        var cached = cachedPlayerId(player);
        if (cached.isPresent()) {
            return cached;
        }
        try (var statement = connection.prepareStatement(
                "SELECT id FROM portage_players WHERE uuid = ?")) {
            statement.setBytes(1, bytes(player));
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                var id = result.getInt(1);
                cachePlayerId(player, id);
                return Optional.of(id);
            }
        }
    }

    private Optional<Integer> cachedPlayerId(UUID player) {
        synchronized (playerIds) {
            return Optional.ofNullable(playerIds.get(player));
        }
    }

    private void cachePlayerId(UUID player, int id) {
        synchronized (playerIds) {
            playerIds.put(player, id);
        }
    }

    private int serverId(Connection connection, String server) throws SQLException {
        var cached = serverIds.get(server);
        if (cached != null) {
            return cached;
        }
        int id;
        try (var statement = connection.prepareStatement(
                "INSERT INTO portage_servers (name) VALUES (?)"
                        + " ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)")) {
            statement.setString(1, server);
            statement.executeUpdate();
            id = lastInsertId(connection);
        }
        serverIds.put(server, id);
        return id;
    }

    private static int lastInsertId(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT LAST_INSERT_ID()")) {
            result.next();
            return result.getInt(1);
        }
    }

    private static byte[] bytes(UUID uuid) {
        return ByteBuffer.allocate(16)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
    }

    @Override
    public void close() {
        pool.close();
    }
}
