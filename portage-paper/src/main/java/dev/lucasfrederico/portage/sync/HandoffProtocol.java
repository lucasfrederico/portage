package dev.lucasfrederico.portage.sync;

import dev.lucasfrederico.portage.data.PlayerSnapshot;
import dev.lucasfrederico.portage.data.SnapshotCause;
import dev.lucasfrederico.portage.store.CheckoutLane;
import dev.lucasfrederico.portage.store.SnapshotArchive;
import java.sql.SQLException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The handoff rules, free of any game type so they can be tested on their
 * own. A player is locked from the moment they join until this server owns
 * their checkout and has their freshest snapshot; on quit the snapshot goes
 * to the fast lane first, then the checkout is released, then the archive
 * gets its copy. That order is what lets the next server start applying
 * without ever waiting on the database.
 */
public final class HandoffProtocol {

    /** What a join attempt decided. */
    public sealed interface JoinStep permits Acquired, Retry, TakeOver {
    }

    /**
     * This server owns the player now.
     *
     * @param payload the freshest snapshot, empty for a brand new player
     */
    public record Acquired(Optional<byte[]> payload) implements JoinStep {
    }

    /** Another server still owns the player and the wait is not over. */
    public record Retry() implements JoinStep {
    }

    /**
     * The wait ran out; the stale checkout was dropped and the next attempt
     * will take the player over with the archive copy.
     *
     * @param holder the server that never handed off
     */
    public record TakeOver(String holder) implements JoinStep {
    }

    private final Logger logger;
    private final String server;
    private final CheckoutLane lane;
    private final SnapshotArchive archive;
    private final long waitMs;
    private final Set<UUID> locked = ConcurrentHashMap.newKeySet();

    /**
     * Creates the rules for one server.
     *
     * @param logger  where failures are reported
     * @param server  this server's id
     * @param lane    the fast lane
     * @param archive the durable lane
     * @param waitMs  how long a join waits for the previous server
     */
    public HandoffProtocol(Logger logger, String server, CheckoutLane lane,
                           SnapshotArchive archive, long waitMs) {
        this.logger = logger;
        this.server = server;
        this.lane = lane;
        this.archive = archive;
        this.waitMs = waitMs;
    }

    /**
     * Marks a player as waiting for their data.
     *
     * @param player the player
     */
    public void lock(UUID player) {
        locked.add(player);
    }

    /**
     * Whether the player is still waiting for their data.
     *
     * @param player the player
     * @return {@code true} while interaction must be blocked
     */
    public boolean isLocked(UUID player) {
        return locked.contains(player);
    }

    /**
     * Clears the waiting mark.
     *
     * @param player the player
     * @return {@code true} when the player was waiting
     */
    public boolean unlock(UUID player) {
        return locked.remove(player);
    }

    /**
     * Makes one attempt at owning a joining player.
     *
     * @param player    the player
     * @param startedAt epoch milliseconds when the join began
     * @param now       epoch milliseconds now
     * @return what to do next
     */
    public JoinStep tryAcquire(UUID player, long startedAt, long now) {
        if (lane.tryCheckout(player, server)) {
            return new Acquired(lane.takeSnapshot(player).or(() -> latestArchived(player)));
        }
        if (now - startedAt < waitMs) {
            return new Retry();
        }
        var holder = lane.checkoutOwner(player).orElse("nobody");
        logger.warning(() -> player + " waited " + waitMs + "ms for " + holder
                + " to hand off; taking over with the archive copy");
        lane.release(player);
        return new TakeOver(holder);
    }

    /**
     * Hands a leaving player to whichever server takes them next.
     *
     * @param snapshot the state just captured
     * @param payload  the same state, encoded
     * @param cause    why the snapshot was taken, for the archive row
     */
    public void handOff(PlayerSnapshot snapshot, byte[] payload, SnapshotCause cause) {
        lane.putSnapshot(snapshot.player(), payload);
        lane.release(snapshot.player());
        archiveQuietly(snapshot, payload, cause);
    }

    /**
     * Lets go of a player who left before their data arrived. Nothing is
     * written: their empty state must never replace a real snapshot.
     *
     * @param player the player
     * @return {@code true} when the player was still waiting
     */
    public boolean abandon(UUID player) {
        if (!unlock(player)) {
            return false;
        }
        lane.release(player);
        return true;
    }

    /**
     * Archives a player who is staying, keeping the checkout alive.
     *
     * @param snapshot the state just captured
     * @param payload  the same state, encoded
     * @param cause    why the snapshot was taken, for the archive row
     */
    public void keep(PlayerSnapshot snapshot, byte[] payload, SnapshotCause cause) {
        lane.renewCheckout(snapshot.player());
        archiveQuietly(snapshot, payload, cause);
    }

    /**
     * Drops checkouts left behind by a previous run of this server.
     *
     * @return how many stale checkouts were dropped
     */
    public int recover() {
        return lane.releaseAllOf(server);
    }

    private Optional<byte[]> latestArchived(UUID player) {
        try {
            return archive.latest(player);
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "could not read the latest snapshot of " + player, e);
            return Optional.empty();
        }
    }

    private void archiveQuietly(PlayerSnapshot snapshot, byte[] payload, SnapshotCause cause) {
        try {
            archive.save(snapshot, cause, payload);
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "could not store the snapshot of " + snapshot.player(), e);
        }
    }
}
