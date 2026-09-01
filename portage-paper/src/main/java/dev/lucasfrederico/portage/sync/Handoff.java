package dev.lucasfrederico.portage.sync;

import dev.lucasfrederico.portage.data.PlayerSnapshot;
import dev.lucasfrederico.portage.data.SnapshotCodec;
import dev.lucasfrederico.portage.data.Snapshots;
import dev.lucasfrederico.portage.store.CheckoutLane;
import dev.lucasfrederico.portage.store.SnapshotArchive;
import dev.lucasfrederico.portage.sync.HandoffProtocol.Acquired;
import dev.lucasfrederico.portage.sync.HandoffProtocol.Retry;
import dev.lucasfrederico.portage.sync.HandoffProtocol.TakeOver;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Runs the {@link HandoffProtocol} against live players: capture and apply
 * happen on the player's own thread, everything that talks to a store runs
 * on the async scheduler, and a server stop does its work inline because
 * the scheduler is already gone by then.
 */
public final class Handoff {

    private final Plugin plugin;
    private final Logger logger;
    private final String server;
    private final HandoffProtocol protocol;
    private final SnapshotCodec codec = new SnapshotCodec();
    private final long pollMs;

    /**
     * Creates the runner.
     *
     * @param plugin  the owning plugin, for scheduling
     * @param server  this server's id
     * @param lane    the fast lane
     * @param archive the durable lane
     * @param waitMs  how long a join waits for the previous server
     * @param pollMs  how often the join polls the checkout
     */
    public Handoff(Plugin plugin, String server, CheckoutLane lane, SnapshotArchive archive,
                   long waitMs, long pollMs) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.server = server;
        this.protocol = new HandoffProtocol(logger, server, lane, archive, waitMs);
        this.pollMs = pollMs;
    }

    /**
     * Whether the player is still waiting for their data.
     *
     * @param player the player
     * @return {@code true} while interaction must be blocked
     */
    public boolean isLocked(UUID player) {
        return protocol.isLocked(player);
    }

    /**
     * Starts the join side: freeze, take the checkout, load, apply, thaw.
     *
     * @param player the player who just joined, on their own thread
     */
    public void onJoin(Player player) {
        protocol.lock(player.getUniqueId());
        var startedAt = System.currentTimeMillis();
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> acquire(player, startedAt));
    }

    private void acquire(Player player, long startedAt) {
        var id = player.getUniqueId();
        if (!player.isOnline()) {
            protocol.abandon(id);
            return;
        }
        switch (protocol.tryAcquire(id, startedAt, System.currentTimeMillis())) {
            case Acquired acquired -> player.getScheduler().run(plugin,
                    task -> applyAndThaw(player, acquired.payload()), () -> protocol.abandon(id));
            case TakeOver ignored -> plugin.getServer().getAsyncScheduler()
                    .runNow(plugin, task -> acquire(player, startedAt));
            case Retry ignored -> plugin.getServer().getAsyncScheduler().runDelayed(plugin,
                    task -> acquire(player, startedAt), pollMs, TimeUnit.MILLISECONDS);
        }
    }

    private void applyAndThaw(Player player, Optional<byte[]> payload) {
        try {
            payload.ifPresent(bytes -> {
                Snapshots.apply(player, codec.decode(bytes));
                player.sendActionBar(Component.text("Data synchronized", NamedTextColor.GREEN));
            });
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "could not apply the snapshot of " + player.getName(), e);
            player.sendMessage(Component.text(
                    "Your data could not be loaded; tell a staff member before playing.",
                    NamedTextColor.RED));
        } finally {
            protocol.unlock(player.getUniqueId());
        }
    }

    /**
     * Runs the quit side: capture, hand off, archive, release.
     *
     * @param player the player who is leaving, on their own thread
     * @param cause  why the snapshot is taken, for the archive row
     */
    public void onQuit(Player player, String cause) {
        if (protocol.abandon(player.getUniqueId())) {
            return;
        }
        var snapshot = Snapshots.capture(player, server);
        var payload = codec.encode(snapshot);
        if ("stop".equals(cause)) {
            protocol.handOff(snapshot, payload, cause);
            return;
        }
        plugin.getServer().getAsyncScheduler().runNow(plugin,
                task -> protocol.handOff(snapshot, payload, cause));
    }

    /**
     * Archives a player in place without releasing the checkout, for
     * periodic saves and manual requests.
     *
     * @param player the player, on their own thread
     * @param cause  the archive row's cause
     */
    public void saveInPlace(Player player, String cause) {
        var snapshot = Snapshots.capture(player, server);
        var payload = codec.encode(snapshot);
        plugin.getServer().getAsyncScheduler().runNow(plugin,
                task -> protocol.keep(snapshot, payload, cause));
    }

    /**
     * Drops checkouts left behind by a previous run of this server.
     *
     * @return how many stale checkouts were dropped
     */
    public int recoverStaleCheckouts() {
        return protocol.recover();
    }

    /**
     * Exposes the snapshot type for diagnostics.
     *
     * @param payload an encoded snapshot
     * @return the decoded snapshot
     */
    public PlayerSnapshot decode(byte[] payload) {
        return codec.decode(payload);
    }
}
