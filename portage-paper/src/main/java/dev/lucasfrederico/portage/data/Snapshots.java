package dev.lucasfrederico.portage.data;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

/**
 * Reads a snapshot off a live player and writes one back. Both directions
 * must run on the player's thread; callers schedule accordingly.
 */
public final class Snapshots {

    private Snapshots() {
    }

    /**
     * Captures the player's current state.
     *
     * @param player the player, on their own thread
     * @param server the id of this server
     * @return the snapshot
     */
    public static PlayerSnapshot capture(Player player, String server) {
        var effects = new ArrayList<PlayerSnapshot.Effect>();
        for (PotionEffect effect : player.getActivePotionEffects()) {
            effects.add(new PlayerSnapshot.Effect(effect.getType().getKey().toString(),
                    effect.getDuration(), effect.getAmplifier(), effect.isAmbient(),
                    effect.hasParticles(), effect.hasIcon()));
        }
        return new PlayerSnapshot(PlayerSnapshot.FORMAT, player.getUniqueId(), server,
                System.currentTimeMillis(),
                ItemStack.serializeItemsAsBytes(player.getInventory().getContents()),
                ItemStack.serializeItemsAsBytes(player.getEnderChest().getContents()),
                player.getInventory().getHeldItemSlot(),
                player.getHealth(), player.getFoodLevel(), player.getSaturation(),
                player.getLevel(), player.getExp(), player.getGameMode().name(), effects);
    }

    /**
     * Applies a snapshot to the player, replacing what they have.
     *
     * @param player   the player, on their own thread
     * @param snapshot the snapshot to apply
     */
    public static void apply(Player player, PlayerSnapshot snapshot) {
        player.getInventory().setContents(ItemStack.deserializeItemsFromBytes(snapshot.inventory()));
        player.getEnderChest().setContents(ItemStack.deserializeItemsFromBytes(snapshot.enderChest()));
        player.getInventory().setHeldItemSlot(snapshot.heldSlot());
        var maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        var ceiling = maxHealth != null ? maxHealth.getValue() : 20.0;
        player.setHealth(Math.max(0.0, Math.min(ceiling, snapshot.health())));
        player.setFoodLevel(snapshot.food());
        player.setSaturation(snapshot.saturation());
        player.setLevel(snapshot.level());
        player.setExp(snapshot.exp());
        try {
            player.setGameMode(GameMode.valueOf(snapshot.gameMode()));
        } catch (IllegalArgumentException ignored) {
            // A game mode this build does not know is left as the server set it.
        }
        for (PotionEffect active : List.copyOf(player.getActivePotionEffects())) {
            player.removePotionEffect(active.getType());
        }
        for (PlayerSnapshot.Effect effect : snapshot.effects()) {
            var key = NamespacedKey.fromString(effect.type());
            var type = key == null ? null : Registry.POTION_EFFECT_TYPE.get(key);
            if (type != null) {
                player.addPotionEffect(new PotionEffect(type, effect.duration(),
                        effect.amplifier(), effect.ambient(), effect.particles(), effect.icon()));
            }
        }
    }
}
