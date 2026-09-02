package dev.lucasfrederico.portage.sync;

import com.google.gson.JsonObject;
import dev.lucasfrederico.portage.data.PlayerSnapshot;
import dev.lucasfrederico.portage.data.SnapshotCause;
import dev.lucasfrederico.portage.store.RedisStore;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.plugin.Plugin;

/**
 * Publishes what the handoff is doing onto {@code portage:events}, one JSON
 * message per step, so the console can watch the network live. Publishing
 * always happens on the async scheduler; a slow Redis never touches a player
 * thread.
 */
public final class Events {

    /** The pub/sub channel every event goes to. */
    public static final String CHANNEL = "portage:events";

    private final Plugin plugin;
    private final RedisStore redis;
    private final String server;

    /**
     * Creates the publisher for one server.
     *
     * @param plugin the owning plugin, for scheduling
     * @param redis  the store to publish through
     * @param server this server's id
     */
    public Events(Plugin plugin, RedisStore redis, String server) {
        this.plugin = plugin;
        this.redis = redis;
        this.server = server;
    }

    /**
     * A player joined and is now locked.
     *
     * @param player the player
     * @param name   the player's name
     */
    public void join(UUID player, String name) {
        var event = base("join", player, name);
        publish(event);
    }

    /**
     * A player's snapshot was applied and the lock lifted.
     *
     * @param player   the player
     * @param name     the player's name
     * @param source   where the snapshot came from
     * @param waitedMs milliseconds until this server owned the checkout
     * @param totalMs  milliseconds from join to applied
     */
    public void applied(UUID player, String name, HandoffProtocol.Source source,
                        long waitedMs, long totalMs) {
        var event = base("applied", player, name);
        event.addProperty("source", source.name().toLowerCase(Locale.ROOT));
        event.addProperty("waitedMs", waitedMs);
        event.addProperty("totalMs", totalMs);
        publish(event);
    }

    /**
     * The previous server never handed off and was overruled.
     *
     * @param player the player
     * @param name   the player's name
     * @param holder the server that was holding the checkout
     */
    public void takeover(UUID player, String name, String holder) {
        var event = base("takeover", player, name);
        event.addProperty("holder", holder);
        publish(event);
    }

    /**
     * A snapshot left this server for the next one.
     *
     * @param snapshot the captured state
     * @param cause    why it was taken
     * @param bytes    the payload size
     */
    public void handoff(PlayerSnapshot snapshot, SnapshotCause cause, int bytes) {
        var event = base("handoff", snapshot.player(), snapshot.name());
        event.addProperty("cause", cause.name().toLowerCase(Locale.ROOT));
        event.addProperty("bytes", bytes);
        publish(event);
    }

    /**
     * A player left before their data arrived; nothing was written.
     *
     * @param player the player
     * @param name   the player's name
     */
    public void abandon(UUID player, String name) {
        publish(base("abandon", player, name));
    }

    /**
     * An archived snapshot was restored over the player's live state.
     *
     * @param player     the player
     * @param name       the player's name
     * @param snapshotId the archive row that was applied
     */
    public void rollback(UUID player, String name, long snapshotId) {
        var event = base("rollback", player, name);
        event.addProperty("snapshotId", snapshotId);
        publish(event);
    }

    private JsonObject base(String type, UUID player, String name) {
        var event = new JsonObject();
        event.addProperty("type", type);
        event.addProperty("player", player.toString());
        event.addProperty("name", name);
        event.addProperty("server", server);
        event.addProperty("at", System.currentTimeMillis());
        return event;
    }

    private void publish(JsonObject event) {
        plugin.getServer().getAsyncScheduler().runNow(plugin,
                task -> redis.publish(CHANNEL, event.toString()));
    }
}
