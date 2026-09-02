package dev.lucasfrederico.portage.console;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Locale;
import java.util.List;

/**
 * Read-mostly access to the archive tables. The only write is the rollback
 * copy, which re-appends an old row as the newest one.
 */
final class Db {

    /** One row of the players table. */
    record PlayerRow(int id, String uuid, String name, String firstSeen, String lastSeen) {
    }

    /** One row of the snapshots table, with the payload's scalar fields. */
    record SnapshotRow(long id, String server, int cause, int format, String takenAt,
                       int bytes, int level, double health, int food, String gameMode,
                       int effects) {
    }

    private final HikariDataSource pool;

    Db(String jdbcUrl, String user, String password) {
        var config = new HikariConfig();
        config.setDriverClassName("org.mariadb.jdbc.Driver");
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(2);
        config.setPoolName("portage-console");
        this.pool = new HikariDataSource(config);
    }

    List<PlayerRow> players(String query) {
        var out = new ArrayList<PlayerRow>();
        var like = "%" + (query == null ? "" : query.trim()) + "%";
        try (var connection = pool.getConnection(); var statement = connection.prepareStatement(
                "SELECT id, HEX(uuid), name, first_seen, last_seen FROM portage_players"
                        + " WHERE name LIKE ? OR HEX(uuid) LIKE ?"
                        + " ORDER BY last_seen DESC LIMIT 50")) {
            statement.setString(1, like);
            statement.setString(2, like.replace("-", ""));
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    out.add(new PlayerRow(result.getInt(1), dashed(result.getString(2)),
                            result.getString(3), seconds(result.getString(4)),
                            seconds(result.getString(5))));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("player search failed", e);
        }
        return out;
    }

    List<SnapshotRow> timeline(String uuid) {
        var out = new ArrayList<SnapshotRow>();
        try (var connection = pool.getConnection(); var statement = connection.prepareStatement(
                "SELECT s.id, v.name, s.cause, s.format, s.taken_at, s.payload"
                        + " FROM portage_snapshots s"
                        + " JOIN portage_players p ON p.id = s.player_id"
                        + " JOIN portage_servers v ON v.id = s.server_id"
                        + " WHERE p.uuid = ? ORDER BY s.id DESC LIMIT 50")) {
            statement.setBytes(1, undashed(uuid));
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    var payload = result.getBytes(6);
                    var scalars = scalars(payload);
                    out.add(new SnapshotRow(result.getLong(1), result.getString(2),
                            result.getInt(3), result.getInt(4), seconds(result.getString(5)),
                            payload.length,
                            scalars.get("level").getAsInt(),
                            scalars.get("health").getAsDouble(),
                            scalars.get("food").getAsInt(),
                            scalars.get("gameMode").getAsString(),
                            scalars.getAsJsonArray("effects").size()));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("timeline read failed", e);
        }
        return out;
    }

    void copyAsRollback(String uuid, long snapshotId) {
        try (var connection = pool.getConnection(); var statement = connection.prepareStatement(
                "INSERT INTO portage_snapshots"
                        + " (player_id, server_id, cause, format, taken_at, payload)"
                        + " SELECT player_id, server_id, 4, format, NOW(3), payload"
                        + " FROM portage_snapshots WHERE id = ? AND player_id ="
                        + " (SELECT id FROM portage_players WHERE uuid = ?)")) {
            statement.setLong(1, snapshotId);
            statement.setBytes(2, undashed(uuid));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("rollback copy failed", e);
        }
    }

    private static String seconds(String timestamp) {
        var dot = timestamp.indexOf('.');
        return dot < 0 ? timestamp : timestamp.substring(0, dot);
    }

    private static JsonObject scalars(byte[] payload) {
        return JsonParser.parseString(new String(payload, StandardCharsets.UTF_8))
                .getAsJsonObject();
    }

    private static String dashed(String hex) {
        var flat = hex.toLowerCase(Locale.ROOT);
        return flat.substring(0, 8) + "-" + flat.substring(8, 12) + "-" + flat.substring(12, 16)
                + "-" + flat.substring(16, 20) + "-" + flat.substring(20);
    }

    private static byte[] undashed(String uuid) {
        return HexFormat.of().parseHex(uuid.replace("-", ""));
    }
}
