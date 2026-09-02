package dev.lucasfrederico.portage.store;

import dev.lucasfrederico.portage.data.PlayerSnapshot;
import dev.lucasfrederico.portage.data.SnapshotCause;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * The durable lane: a bounded history of snapshots per player.
 */
public interface SnapshotArchive {

    /**
     * Stores one snapshot, pruning the player's oldest rows past the
     * configured retention.
     *
     * @param snapshot the snapshot, for the row's metadata
     * @param cause    why it was taken
     * @param payload  the encoded snapshot
     * @throws SQLException when the write fails
     */
    void save(PlayerSnapshot snapshot, SnapshotCause cause, byte[] payload) throws SQLException;

    /**
     * One specific archived snapshot of a player.
     *
     * @param player     the player the row must belong to
     * @param snapshotId the row id
     * @return the encoded snapshot, if that row exists for that player
     * @throws SQLException when the read fails
     */
    Optional<byte[]> payload(UUID player, long snapshotId) throws SQLException;

    /**
     * The most recent snapshot of a player.
     *
     * @param player the player
     * @return the encoded snapshot, if the player has ever been stored
     * @throws SQLException when the read fails
     */
    Optional<byte[]> latest(UUID player) throws SQLException;
}
