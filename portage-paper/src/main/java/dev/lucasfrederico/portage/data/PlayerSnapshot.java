package dev.lucasfrederico.portage.data;

import java.util.List;
import java.util.UUID;

/**
 * Everything Portage carries for one player, frozen at one instant. The
 * format is versioned so a newer server can still read what an older one
 * wrote; item bytes stay in the game's own serialization, which upgrades
 * itself across game versions.
 *
 * @param format     the snapshot format version this instance follows
 * @param player     the player's id
 * @param server     the server that produced the snapshot
 * @param takenAt    epoch milliseconds when the snapshot was taken
 * @param inventory  the main inventory, armor and off-hand as item bytes
 * @param enderChest the ender chest contents as item bytes
 * @param heldSlot   the selected hotbar slot
 * @param health     current health
 * @param food       food level
 * @param saturation saturation level
 * @param level      experience level
 * @param exp        progress towards the next level, 0 to 1
 * @param gameMode   the game mode name
 * @param effects    the active potion effects
 */
public record PlayerSnapshot(int format, UUID player, String server, long takenAt,
                             byte[] inventory, byte[] enderChest, int heldSlot,
                             double health, int food, float saturation,
                             int level, float exp, String gameMode,
                             List<Effect> effects) {

    /** The current format version written by this build. */
    public static final int FORMAT = 1;

    /**
     * One active potion effect.
     *
     * @param type      the effect key, such as {@code minecraft:speed}
     * @param duration  remaining ticks
     * @param amplifier the effect level minus one
     * @param ambient   whether the effect came from a beacon
     * @param particles whether the effect shows particles
     * @param icon      whether the effect shows its icon
     */
    public record Effect(String type, int duration, int amplifier,
                         boolean ambient, boolean particles, boolean icon) {
    }
}
