package dev.lucasfrederico.portage.sync;

import dev.lucasfrederico.portage.data.PlayerSnapshot;
import dev.lucasfrederico.portage.data.SnapshotCause;
import dev.lucasfrederico.portage.data.SnapshotCodec;
import dev.lucasfrederico.portage.data.Snapshots;
import dev.lucasfrederico.portage.store.CheckoutLane;
import dev.lucasfrederico.portage.store.SnapshotArchive;
import dev.lucasfrederico.portage.sync.HandoffProtocol.Acquired;
import dev.lucasfrederico.portage.sync.HandoffProtocol.Retry;
import dev.lucasfrederico.portage.sync.HandoffProtocol.TakeOver;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
    private final SnapshotArchive archive;
    private final Events events;
    private final AtomicInteger online = new AtomicInteger();
    private final long pollMs;

    /**
     * Creates the runner.
     *
     * @param plugin  the owning plugin, for scheduling
     * @param server  this server's id
     * @param lane    the fast lane
     * @param archive the durable lane
     * @param events  where handoff steps are announced
     * @param waitMs  how long a join waits for the previous server
     * @param pollMs  how often the join polls the checkout
     */
    public Handoff(Plugin plugin, String server, CheckoutLane lane, SnapshotArchive archive,
                   Events events, long waitMs, long pollMs) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.server = server;
        this.protocol = new HandoffProtocol(logger, server, lane, archive, waitMs);
        this.archive = archive;
        this.events = events;
        this.pollMs = pollMs;
    }

    /**
     * How many players this server currently counts, for the heartbeat.
     *
     * @return joined minus quit
     */
    public int onlineCount() {
        return online.get();
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
        online.incrementAndGet();
        events.join(player.getUniqueId(), player.getName());
        var startedAt = System.currentTimeMillis();
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> acquire(player, startedAt));
    }

    private void acquire(Player player, long startedAt) {
        var id = player.getUniqueId();
        if (!player.isOnline()) {
            abandon(player);
            return;
        }
        switch (protocol.tryAcquire(id, startedAt, System.currentTimeMillis())) {
            case Acquired acquired -> {
                var waitedMs = System.currentTimeMillis() - startedAt;
                player.getScheduler().run(plugin,
                        task -> applyAndThaw(player, acquired, startedAt, waitedMs),
                        () -> abandon(player));
            }
            case TakeOver takeOver -> {
                events.takeover(id, player.getName(), takeOver.holder());
                plugin.getServer().getAsyncScheduler()
                        .runNow(plugin, task -> acquire(player, startedAt));
            }
            case Retry ignored -> plugin.getServer().getAsyncScheduler().runDelayed(plugin,
                    task -> acquire(player, startedAt), pollMs, TimeUnit.MILLISECONDS);
        }
    }

    private void abandon(Player player) {
        if (protocol.abandon(player.getUniqueId())) {
            events.abandon(player.getUniqueId(), player.getName());
        }
    }

    private void applyAndThaw(Player player, Acquired acquired, long startedAt, long waitedMs) {
        try {
            acquired.payload().ifPresent(bytes -> {
                Snapshots.apply(player, codec.decode(bytes));
                player.sendActionBar(Component.text("Data synchronized", NamedTextColor.GREEN));
            });
            events.applied(player.getUniqueId(), player.getName(), acquired.source(),
                    waitedMs, System.currentTimeMillis() - startedAt);
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
     * Restores one archived snapshot over the player's live state, when the
     * player is on this server. Called for console-requested rollbacks.
     *
     * @param player     the player to restore
     * @param snapshotId the archive row to apply
     */
    public void applyArchived(UUID player, long snapshotId) {
        var target = plugin.getServer().getPlayer(player);
        if (target == null) {
            return;
        }
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            byte[] payload;
            try {
                var found = archive.payload(player, snapshotId);
                if (found.isEmpty()) {
                    logger.warning(() -> "rollback asked for row " + snapshotId
                            + " of " + target.getName() + ", which does not exist");
                    return;
                }
                payload = found.get();
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "could not read rollback row " + snapshotId, e);
                return;
            }
            target.getScheduler().run(plugin, applied -> {
                Snapshots.apply(target, codec.decode(payload));
                target.sendActionBar(Component.text("State restored", NamedTextColor.GREEN));
                events.rollback(player, target.getName(), snapshotId);
            }, null);
        });
    }

    /**
     * Runs the quit side: capture, hand off, archive, release.
     *
     * @param player the player who is leaving, on their own thread
     * @param cause  why the snapshot is taken, for the archive row
     */
    public void onQuit(Player player, SnapshotCause cause) {
        online.decrementAndGet();
        if (protocol.abandon(player.getUniqueId())) {
            events.abandon(player.getUniqueId(), player.getName());
            return;
        }
        var snapshot = Snapshots.capture(player, server);
        var payload = codec.encode(snapshot);
        if (cause == SnapshotCause.STOP) {
            protocol.handOff(snapshot, payload, cause);
            return;
        }
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            protocol.handOff(snapshot, payload, cause);
            events.handoff(snapshot, cause, payload.length);
        });
    }

    /**
     * Archives a player in place without releasing the checkout, for
     * periodic saves and manual requests.
     *
     * @param player the player, on their own thread
     * @param cause  the archive row's cause
     */
    public void saveInPlace(Player player, SnapshotCause cause) {
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
