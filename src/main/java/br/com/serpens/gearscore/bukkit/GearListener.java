package br.com.serpens.gearscore.bukkit;

import br.com.serpens.gearscore.GearScorePlugin;
import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * Equipment change detection, registered only on official source servers. Handlers do a
 * constant amount of work: decide whether the change can matter and, if so, mark the player
 * for a debounced evaluation. No movement events, no scans, nothing per tick, and no message
 * is ever sent to the player from here.
 */
public final class GearListener implements Listener {

    private final GearScorePlugin plugin;

    public GearListener(GearScorePlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Any slot of the player's own inventory: clicks, shift-clicks, drags, pickups, drops,
     * breaks, hand swaps, death and changes made by other plugins all end up here.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSlotChange(PlayerInventorySlotChangeEvent event) {
        int slot = event.getSlot();
        if (slot < 0 || slot >= SnapshotFactory.SLOT_COUNT) {
            return;
        }
        if (!plugin.snapshots().changesGear(event.getOldItemStack(), event.getNewItemStack())) {
            plugin.metrics().filteredEvents.increment();
            return;
        }
        plugin.requestEvaluation(event.getPlayer().getUniqueId());
    }

    /** Armor is tracked by the entity itself, so this also fires while another container is open. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onArmorChange(PlayerArmorChangeEvent event) {
        if (!plugin.snapshots().changesGear(event.getOldItem(), event.getNewItem())) {
            plugin.metrics().filteredEvents.increment();
            return;
        }
        plugin.requestEvaluation(event.getPlayer().getUniqueId());
    }

    /** The off hand slot is not part of other container views, so its swap key needs its own hook. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (event.getClick() == ClickType.SWAP_OFFHAND && event.getWhoClicked() instanceof Player player) {
            plugin.requestEvaluation(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        plugin.requestEvaluation(event.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        plugin.requestEvaluation(event.getPlayer().getUniqueId());
    }

    /** Leaving an ignored world must refresh the score. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        plugin.requestEvaluation(event.getPlayer().getUniqueId());
    }
}
