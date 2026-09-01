package dev.lucasfrederico.portage.store;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * The durable lane: an append-only history of snapshots per player.
 */
public interface SnapshotArchive {

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
    void save(UUID player, String server, String cause, int format, long takenAt, byte[] payload)
            throws SQLException;

    /**
     * The most recent snapshot of a player.
     *
     * @param player the player
     * @return the encoded snapshot, if the player has ever been stored
     * @throws SQLException when the read fails
     */
    Optional<byte[]> latest(UUID player) throws SQLException;
}
