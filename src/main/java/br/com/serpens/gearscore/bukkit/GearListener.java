package br.com.serpens.gearscore.bukkit;

import br.com.serpens.gearscore.GearScorePlugin;
import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Equipment change detection, registered only on official source servers. Handlers do a
 * constant amount of work: decide whether the change can matter and, if so, mark the player
 * for a debounced evaluation. No movement events, no scans, nothing per tick.
 */
public final class GearListener implements Listener {

    private final GearScorePlugin plugin;

    public GearListener(GearScorePlugin plugin) {
        this.plugin = plugin;
    }

    /** Covers clicks, drags, pickups, drops, breaks, swaps and changes made by other plugins. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSlotChange(PlayerInventorySlotChangeEvent event) {
        int slot = event.getSlot();
        if (slot > 8 && (slot < SnapshotFactory.SLOT_BOOTS || slot > SnapshotFactory.SLOT_OFFHAND)) {
            return;
        }
        if (irrelevant(event.getOldItemStack(), event.getNewItemStack())) {
            plugin.metrics().filteredEvents.increment();
            return;
        }
        plugin.requestEvaluation(event.getPlayer().getUniqueId());
    }

    /** Armor is tracked by the entity itself, so this also fires while another container is open. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onArmorChange(PlayerArmorChangeEvent event) {
        if (irrelevant(event.getOldItem(), event.getNewItem())) {
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
    public void onRespawn(PlayerRespawnEvent event) {
        plugin.requestEvaluation(event.getPlayer().getUniqueId());
    }

    /** Leaving an ignored world must refresh the score. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        plugin.requestEvaluation(event.getPlayer().getUniqueId());
    }

    /**
     * True when the change cannot affect the score: neither item is gear, or only the
     * amount or durability changed. With custom item rules the PDC may differ, so the
     * decision is left to the snapshot comparison.
     */
    private boolean irrelevant(ItemStack before, ItemStack after) {
        SnapshotFactory snapshots = plugin.snapshots();
        if (before == null || after == null || snapshots.hasCustomRules()) {
            return false;
        }
        boolean gearBefore = snapshots.isGear(before.getType());
        boolean gearAfter = snapshots.isGear(after.getType());
        if (!gearBefore && !gearAfter) {
            return true;
        }
        return before.getType() == after.getType() && before.getEnchantments().equals(after.getEnchantments());
    }
}
