package dev.lucasfrederico.portage.data;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SnapshotCodecTest {

    private final SnapshotCodec codec = new SnapshotCodec();

    @Test
    void roundTripsEveryField() {
        var original = new PlayerSnapshot(PlayerSnapshot.FORMAT,
                UUID.fromString("edc09bca-dfe8-3b5c-a512-adbfc7b28c20"), "lobby-1", 1756743114432L,
                new byte[] {1, 2, 3, (byte) 0xFF}, new byte[] {9}, 4, 17.5, 18, 3.25f, 21, 0.4f,
                "ADVENTURE",
                List.of(new PlayerSnapshot.Effect("minecraft:night_vision", 600, 0, false, true, true),
                        new PlayerSnapshot.Effect("minecraft:jump_boost", 900, 1, true, false, false)));

        var back = codec.decode(codec.encode(original));

        assertEquals(original.format(), back.format());
        assertEquals(original.player(), back.player());
        assertEquals(original.server(), back.server());
        assertEquals(original.takenAt(), back.takenAt());
        assertArrayEquals(original.inventory(), back.inventory());
        assertArrayEquals(original.enderChest(), back.enderChest());
        assertEquals(original.heldSlot(), back.heldSlot());
        assertEquals(original.health(), back.health());
        assertEquals(original.food(), back.food());
        assertEquals(original.saturation(), back.saturation());
        assertEquals(original.level(), back.level());
        assertEquals(original.exp(), back.exp());
        assertEquals(original.gameMode(), back.gameMode());
        assertEquals(original.effects(), back.effects());
    }

    @Test
    void roundTripsAnEmptyPlayer() {
        var original = new PlayerSnapshot(PlayerSnapshot.FORMAT, UUID.randomUUID(), "a", 0L,
                new byte[0], new byte[0], 0, 20.0, 20, 5f, 0, 0f, "SURVIVAL", List.of());

        var back = codec.decode(codec.encode(original));

        assertEquals(0, back.inventory().length);
        assertTrue(back.effects().isEmpty());
    }

    @Test
    void payloadStaysReadableJson() {
        var snapshot = new PlayerSnapshot(PlayerSnapshot.FORMAT, UUID.randomUUID(), "a", 0L,
                new byte[0], new byte[0], 0, 20.0, 20, 5f, 0, 0f, "SURVIVAL", List.of());

        var json = new String(codec.encode(snapshot), StandardCharsets.UTF_8);

        assertTrue(json.startsWith("{\"format\":1,"));
        assertTrue(json.contains("\"gameMode\":\"SURVIVAL\""));
    }

    @Test
    void rejectsBytesThatAreNotJson() {
        assertThrows(IllegalArgumentException.class,
                () -> codec.decode("not json".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsJsonWithoutAFormat() {
        assertThrows(IllegalArgumentException.class,
                () -> codec.decode("{\"player\":\"x\"}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsAJsonArray() {
        assertThrows(IllegalArgumentException.class,
                () -> codec.decode("[1,2]".getBytes(StandardCharsets.UTF_8)));
    }
}
