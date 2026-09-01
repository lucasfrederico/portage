package dev.lucasfrederico.portage.sync;

import dev.lucasfrederico.portage.store.SnapshotArchive;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** An archive that keeps rows in a list and can be told to fail. */
final class InMemoryArchive implements SnapshotArchive {

    record Row(UUID player, String server, String cause, int format, long takenAt, byte[] payload) {
    }

    final List<Row> rows = new ArrayList<>();
    boolean failing;

    @Override
    public void save(UUID player, String server, String cause, int format, long takenAt,
                     byte[] payload) throws SQLException {
        if (failing) {
            throw new SQLException("archive down");
        }
        rows.add(new Row(player, server, cause, format, takenAt, payload));
    }

    @Override
    public Optional<byte[]> latest(UUID player) throws SQLException {
        if (failing) {
            throw new SQLException("archive down");
        }
        for (var i = rows.size() - 1; i >= 0; i--) {
            if (rows.get(i).player().equals(player)) {
                return Optional.of(rows.get(i).payload());
            }
        }
        return Optional.empty();
    }
}
