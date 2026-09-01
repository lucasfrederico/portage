package dev.lucasfrederico.portage.sync;

import dev.lucasfrederico.portage.data.SnapshotCause;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.entity.Player;

/**
 * Wires the handoff to the game and holds a joining player still until
 * their data has landed, so nothing they do in the gap can be lost or
 * duplicated.
 */
public final class HandoffListener implements Listener {

    private final Handoff handoff;

    /**
     * Creates the listener.
     *
     * @param handoff the protocol runner
     */
    public HandoffListener(Handoff handoff) {
        this.handoff = handoff;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    void onJoin(PlayerJoinEvent event) {
        handoff.onJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void onQuit(PlayerQuitEvent event) {
        handoff.onQuit(event.getPlayer(), SnapshotCause.QUIT);
    }

    @EventHandler
    void onMove(PlayerMoveEvent event) {
        if (!handoff.isLocked(event.getPlayer().getUniqueId())) {
            return;
        }
        if (!event.hasChangedPosition()) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler
    void onInteract(PlayerInteractEvent event) {
        cancelIfLocked(event.getPlayer(), event);
    }

    @EventHandler
    void onDrop(PlayerDropItemEvent event) {
        cancelIfLocked(event.getPlayer(), event);
    }

    @EventHandler
    void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        cancelIfLocked(player, event);
    }

    @EventHandler
    void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        cancelIfLocked(player, event);
    }

    @EventHandler
    void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        cancelIfLocked(player, event);
    }

    @EventHandler
    void onBreak(BlockBreakEvent event) {
        cancelIfLocked(event.getPlayer(), event);
    }

    @EventHandler
    void onPlace(BlockPlaceEvent event) {
        cancelIfLocked(event.getPlayer(), event);
    }

    @EventHandler
    void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        cancelIfLocked(player, event);
    }

    private void cancelIfLocked(Player player, Cancellable event) {
        if (!handoff.isLocked(player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
    }
}
