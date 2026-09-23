package io.github.flauschinatorxd.totemlimit;

import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public final class TotemListener implements Listener {

    private final TotemLimitPlugin plugin;

    public TotemListener(TotemLimitPlugin plugin) {
        this.plugin = plugin;
    }

    private int count(ItemStack stack) {
        return TotemCounter.count(stack, plugin.countContainers());
    }

    private boolean wouldExceed(Player player, int incoming) {
        return incoming > 0
                && TotemCounter.countPlayer(player, plugin.countContainers()) + incoming > plugin.limit();
    }

    // ---------------------------------------------------------------- Pickup

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player) || plugin.isExempt(player)) return;
        int incoming = count(event.getItem().getItemStack());
        if (incoming == 0) return;

        if (wouldExceed(player, incoming)) {
            event.setCancelled(true);
            plugin.sendMessage(player, "pickup-blocked", true);
            return;
        }
        plugin.scheduleCheck(player);
    }

    // ---------------------------------------------------------------- Inventory clicks

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || plugin.isExempt(player)) return;

        if (wouldExceed(player, incomingFromClick(event, player))) {
            event.setCancelled(true);
            plugin.sendMessage(player, "pickup-blocked", true);
        }
        // Cancelled or not: verify the final state on the next tick
        plugin.scheduleCheck(player);
    }

    /** How many totems this click can move from a foreign inventory into the player's possession. */
    private int incomingFromClick(InventoryClickEvent event, Player player) {
        Inventory own = player.getInventory();
        Inventory top = event.getView().getTopInventory();

        // Double-click also collects from the top inventory, no matter where the click happened
        if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            ItemStack cursor = event.getCursor();
            if (cursor.isEmpty() || top.equals(own)) return 0;
            int sum = 0;
            for (ItemStack item : top.getContents()) {
                if (item != null && item.isSimilar(cursor)) sum += count(item);
            }
            return sum;
        }

        Inventory clicked = event.getClickedInventory();
        if (clicked == null || clicked.equals(own)) return 0;

        int current = count(event.getCurrentItem());
        return switch (event.getAction()) {
            case NOTHING, CLONE_STACK, DROP_ALL_SLOT, DROP_ONE_SLOT, DROP_ALL_CURSOR, DROP_ONE_CURSOR,
                 PLACE_ALL, PLACE_ONE, PLACE_SOME -> 0;
            case SWAP_WITH_CURSOR -> current - count(event.getCursor());
            case HOTBAR_SWAP -> {
                int button = event.getHotbarButton();
                ItemStack other = button >= 0 && button <= 8 ? own.getItem(button) : player.getInventory().getItemInOffHand();
                yield current - count(other);
            }
            // PICKUP_*, MOVE_TO_OTHER_INVENTORY and any unknown action: assume the worst
            default -> current;
        };
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) plugin.scheduleCheck(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) plugin.scheduleCheck(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSlotChange(PlayerInventorySlotChangeEvent event) {
        if (count(event.getNewItemStack()) > 0) plugin.scheduleCheck(event.getPlayer());
    }

    // ---------------------------------------------------------------- Armor stands

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        Player player = event.getPlayer();
        if (plugin.isExempt(player)) return;
        int incoming = count(event.getArmorStandItem()) - count(event.getPlayerItem());
        if (wouldExceed(player, incoming)) {
            event.setCancelled(true);
            plugin.sendMessage(player, "pickup-blocked", true);
            return;
        }
        plugin.scheduleCheck(player);
    }

    // ---------------------------------------------------------------- Misc

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        plugin.scheduleCheck(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        plugin.scheduleCheck(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent event) {
        plugin.scheduleCheck(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        plugin.scheduleCheck(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        plugin.forget(event.getPlayer());
    }
}
