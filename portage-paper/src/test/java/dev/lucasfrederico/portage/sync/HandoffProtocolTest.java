package dev.lucasfrederico.portage.sync;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.lucasfrederico.portage.data.PlayerSnapshot;
import dev.lucasfrederico.portage.sync.HandoffProtocol.Acquired;
import dev.lucasfrederico.portage.sync.HandoffProtocol.Retry;
import dev.lucasfrederico.portage.sync.HandoffProtocol.TakeOver;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HandoffProtocolTest {

    private static final long WAIT_MS = 3000;
    private static final byte[] FROM_LANE = bytes("from-lane");
    private static final byte[] FROM_ARCHIVE = bytes("from-archive");

    private final UUID player = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final InMemoryLane lane = new InMemoryLane();
    private final InMemoryArchive archive = new InMemoryArchive();
    private HandoffProtocol protocol;

    @BeforeEach
    void setUp() {
        var quiet = Logger.getLogger("portage-test");
        quiet.setLevel(Level.OFF);
        protocol = new HandoffProtocol(quiet, "b", lane, archive, WAIT_MS);
    }

    @Test
    void acquiresAFreePlayerAndConsumesTheHandedOffSnapshot() {
        lane.snapshots.put(player, FROM_LANE);

        var step = protocol.tryAcquire(player, 0, 0);

        var acquired = assertInstanceOf(Acquired.class, step);
        assertArrayEquals(FROM_LANE, acquired.payload().orElseThrow());
        assertEquals(Optional.of("b"), lane.checkoutOwner(player));
        assertTrue(lane.snapshots.isEmpty(), "the lane copy is consumed once claimed");
    }

    @Test
    void fallsBackToTheArchiveWhenNothingWasHandedOff() throws Exception {
        archive.save(player, "a", "quit", 1, 10, FROM_ARCHIVE);

        var acquired = assertInstanceOf(Acquired.class, protocol.tryAcquire(player, 0, 0));

        assertArrayEquals(FROM_ARCHIVE, acquired.payload().orElseThrow());
    }

    @Test
    void aBrandNewPlayerHasNothingToApply() {
        var acquired = assertInstanceOf(Acquired.class, protocol.tryAcquire(player, 0, 0));

        assertTrue(acquired.payload().isEmpty());
    }

    @Test
    void prefersTheLaneOverTheArchive() throws Exception {
        archive.save(player, "a", "quit", 1, 10, FROM_ARCHIVE);
        lane.snapshots.put(player, FROM_LANE);

        var acquired = assertInstanceOf(Acquired.class, protocol.tryAcquire(player, 0, 0));

        assertArrayEquals(FROM_LANE, acquired.payload().orElseThrow());
    }

    @Test
    void retriesWhileThePreviousServerStillOwnsThePlayer() {
        lane.checkouts.put(player, "a");

        assertInstanceOf(Retry.class, protocol.tryAcquire(player, 0, WAIT_MS - 1));
        assertEquals(Optional.of("a"), lane.checkoutOwner(player), "the other checkout is untouched");
    }

    @Test
    void takesOverOnceTheWaitRunsOut() throws Exception {
        lane.checkouts.put(player, "a");
        archive.save(player, "a", "manual", 1, 10, FROM_ARCHIVE);

        var step = protocol.tryAcquire(player, 0, WAIT_MS);

        var takeOver = assertInstanceOf(TakeOver.class, step);
        assertEquals("a", takeOver.holder());
        assertTrue(lane.checkouts.isEmpty(), "the stale checkout is dropped");

        var next = assertInstanceOf(Acquired.class, protocol.tryAcquire(player, 0, WAIT_MS));
        assertArrayEquals(FROM_ARCHIVE, next.payload().orElseThrow());
        assertEquals(Optional.of("b"), lane.checkoutOwner(player));
    }

    @Test
    void handOffLeavesTheSnapshotInTheLaneAndTheArchiveThenReleases() {
        lane.checkouts.put(player, "b");
        var snapshot = snapshot();
        var payload = bytes("state");

        protocol.handOff(snapshot, payload, "quit");

        assertArrayEquals(payload, lane.snapshots.get(player));
        assertTrue(lane.checkouts.isEmpty(), "the checkout is released for the next server");
        assertEquals(1, archive.rows.size());
        var row = archive.rows.getFirst();
        assertEquals("b", row.server());
        assertEquals("quit", row.cause());
        assertEquals(snapshot.takenAt(), row.takenAt());
        assertArrayEquals(payload, row.payload());
    }

    @Test
    void handOffStillReleasesWhenTheArchiveIsDown() {
        lane.checkouts.put(player, "b");
        archive.failing = true;

        protocol.handOff(snapshot(), bytes("state"), "quit");

        assertTrue(lane.checkouts.isEmpty());
        assertTrue(lane.snapshots.containsKey(player), "the next server can still take over");
    }

    @Test
    void abandoningAWaitingPlayerReleasesWithoutWritingAnything() {
        protocol.lock(player);
        lane.checkouts.put(player, "b");

        assertTrue(protocol.abandon(player));

        assertFalse(protocol.isLocked(player));
        assertTrue(lane.checkouts.isEmpty());
        assertTrue(lane.snapshots.isEmpty(), "an empty player never overwrites a real snapshot");
        assertTrue(archive.rows.isEmpty());
    }

    @Test
    void abandoningAPlayerWhoIsNotWaitingDoesNothing() {
        lane.checkouts.put(player, "b");

        assertFalse(protocol.abandon(player));

        assertEquals(Optional.of("b"), lane.checkoutOwner(player));
    }

    @Test
    void keepingAPlayerRenewsTheCheckoutAndArchivesWithoutReleasing() {
        lane.checkouts.put(player, "b");

        protocol.keep(snapshot(), bytes("state"), "manual");

        assertEquals(1, lane.renewals);
        assertEquals(Optional.of("b"), lane.checkoutOwner(player));
        assertTrue(lane.snapshots.isEmpty(), "nothing is handed off while the player stays");
        assertEquals("manual", archive.rows.getFirst().cause());
    }

    @Test
    void recoveryDropsOnlyThisServersCheckouts() {
        var other = UUID.fromString("00000000-0000-0000-0000-000000000002");
        lane.checkouts.put(player, "b");
        lane.checkouts.put(other, "a");

        assertEquals(1, protocol.recover());

        assertEquals(Optional.of("a"), lane.checkoutOwner(other));
        assertTrue(lane.checkoutOwner(player).isEmpty());
    }

    @Test
    void lockFollowsTheJoinLifecycle() {
        assertFalse(protocol.isLocked(player));
        protocol.lock(player);
        assertTrue(protocol.isLocked(player));
        assertTrue(protocol.unlock(player));
        assertFalse(protocol.unlock(player));
    }

    private PlayerSnapshot snapshot() {
        return new PlayerSnapshot(PlayerSnapshot.FORMAT, player, "b", 1234L,
                new byte[0], new byte[0], 0, 20.0, 20, 5f, 0, 0f, "SURVIVAL", List.of());
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
