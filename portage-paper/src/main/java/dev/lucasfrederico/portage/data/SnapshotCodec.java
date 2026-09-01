package dev.lucasfrederico.portage.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.UUID;

/**
 * Turns snapshots into bytes and back. The payload is JSON with item data
 * as base64, a shape that stays readable in Redis and in the database while
 * a debugging session is on.
 */
public final class SnapshotCodec {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** Creates a codec; it holds no state. */
    public SnapshotCodec() {
    }

    /**
     * Encodes a snapshot.
     *
     * @param snapshot the snapshot to encode
     * @return UTF-8 JSON bytes
     */
    public byte[] encode(PlayerSnapshot snapshot) {
        var root = new JsonObject();
        root.addProperty("format", snapshot.format());
        root.addProperty("player", snapshot.player().toString());
        root.addProperty("server", snapshot.server());
        root.addProperty("takenAt", snapshot.takenAt());
        root.addProperty("inventory", Base64.getEncoder().encodeToString(snapshot.inventory()));
        root.addProperty("enderChest", Base64.getEncoder().encodeToString(snapshot.enderChest()));
        root.addProperty("heldSlot", snapshot.heldSlot());
        root.addProperty("health", snapshot.health());
        root.addProperty("food", snapshot.food());
        root.addProperty("saturation", snapshot.saturation());
        root.addProperty("level", snapshot.level());
        root.addProperty("exp", snapshot.exp());
        root.addProperty("gameMode", snapshot.gameMode());
        var effects = new JsonArray();
        for (PlayerSnapshot.Effect effect : snapshot.effects()) {
            var e = new JsonObject();
            e.addProperty("type", effect.type());
            e.addProperty("duration", effect.duration());
            e.addProperty("amplifier", effect.amplifier());
            e.addProperty("ambient", effect.ambient());
            e.addProperty("particles", effect.particles());
            e.addProperty("icon", effect.icon());
            effects.add(e);
        }
        root.add("effects", effects);
        return GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Decodes a snapshot.
     *
     * @param bytes UTF-8 JSON bytes produced by {@link #encode}
     * @return the snapshot
     * @throws IllegalArgumentException when the payload is not a snapshot
     */
    public PlayerSnapshot decode(byte[] bytes) {
        JsonObject root;
        try {
            root = GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), JsonObject.class);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("not a snapshot payload", e);
        }
        if (root == null || !root.has("format")) {
            throw new IllegalArgumentException("not a snapshot payload");
        }
        var effects = new ArrayList<PlayerSnapshot.Effect>();
        for (var element : root.getAsJsonArray("effects")) {
            var e = element.getAsJsonObject();
            effects.add(new PlayerSnapshot.Effect(e.get("type").getAsString(),
                    e.get("duration").getAsInt(), e.get("amplifier").getAsInt(),
                    e.get("ambient").getAsBoolean(), e.get("particles").getAsBoolean(),
                    e.get("icon").getAsBoolean()));
        }
        return new PlayerSnapshot(root.get("format").getAsInt(),
                UUID.fromString(root.get("player").getAsString()),
                root.get("server").getAsString(), root.get("takenAt").getAsLong(),
                Base64.getDecoder().decode(root.get("inventory").getAsString()),
                Base64.getDecoder().decode(root.get("enderChest").getAsString()),
                root.get("heldSlot").getAsInt(), root.get("health").getAsDouble(),
                root.get("food").getAsInt(), root.get("saturation").getAsFloat(),
                root.get("level").getAsInt(), root.get("exp").getAsFloat(),
                root.get("gameMode").getAsString(), effects);
    }
}
