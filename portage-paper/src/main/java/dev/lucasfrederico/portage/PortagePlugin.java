package dev.lucasfrederico.portage;

import dev.lucasfrederico.portage.data.SnapshotCause;
import dev.lucasfrederico.portage.store.MysqlStore;
import dev.lucasfrederico.portage.store.RedisStore;
import dev.lucasfrederico.portage.sync.Events;
import dev.lucasfrederico.portage.sync.Handoff;
import dev.lucasfrederico.portage.sync.HandoffListener;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import com.google.gson.JsonParser;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Plugin entry point: opens both stores, recovers checkouts from a previous
 * run of this server, and snapshots everyone on shutdown so a stop is never
 * a data loss.
 */
public final class PortagePlugin extends JavaPlugin {

    private RedisStore redis;
    private MysqlStore database;
    private Handoff handoff;

    /** Created by the server's plugin loader. */
    public PortagePlugin() {
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        var config = getConfig();
        var server = config.getString("server-id", "server-1");

        try {
            redis = new RedisStore(config.getString("redis.host", "127.0.0.1"),
                    config.getInt("redis.port", 6379), config.getString("redis.password", ""),
                    Duration.ofMillis(config.getLong("handoff.checkout-ttl-ms", 30000)),
                    Duration.ofMillis(config.getLong("handoff.data-ttl-ms", 300000)));
            redis.ping();
            database = new MysqlStore(config.getString("database.jdbc-url"),
                    config.getString("database.user", "root"),
                    config.getString("database.password", ""),
                    config.getInt("archive.keep-per-player", 20));
        } catch (SQLException | RuntimeException e) {
            getLogger().log(Level.SEVERE, "Portage cannot start without Redis and the database", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        var events = new Events(this, redis, server);
        handoff = new Handoff(this, server, redis, database, events,
                config.getLong("handoff.wait-ms", 3000), config.getLong("handoff.poll-ms", 50));
        var recovered = handoff.recoverStaleCheckouts();
        if (recovered > 0) {
            getLogger().info(() -> "Released " + recovered + " checkout(s) left by the previous run");
        }
        getServer().getPluginManager().registerEvents(new HandoffListener(handoff), this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, PortageCommand.PROXY_CHANNEL);
        Objects.requireNonNull(getCommand("portage"))
                .setExecutor(new PortageCommand(this, handoff));
        startHeartbeat(server);
        listenForRollbacks();
        getLogger().info(() -> "Portage ready as \"" + server + "\"");
    }

    private void startHeartbeat(String server) {
        getServer().getAsyncScheduler().runAtFixedRate(this, task -> redis.heartbeat(server,
                "{\"players\":" + handoff.onlineCount()
                        + ",\"at\":" + System.currentTimeMillis() + "}"),
                1, 5, TimeUnit.SECONDS);
    }

    private void listenForRollbacks() {
        redis.subscribe("portage:apply", message -> {
            try {
                var command = JsonParser.parseString(message).getAsJsonObject();
                handoff.applyArchived(UUID.fromString(command.get("player").getAsString()),
                        command.get("snapshotId").getAsLong());
            } catch (RuntimeException e) {
                getLogger().warning(() -> "ignoring a malformed apply command: " + message);
            }
        });
    }

    @Override
    public void onDisable() {
        if (handoff != null) {
            for (Player player : getServer().getOnlinePlayers()) {
                handoff.onQuit(player, SnapshotCause.STOP);
            }
        }
        if (database != null) {
            database.close();
        }
        if (redis != null) {
            redis.close();
        }
    }
}
