package io.github.flauschinatorxd.totemlimit;

import org.bukkit.Material;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Counts and removes totems. All methods work on copies (clone) only and never mutate an
 * ItemStack that came straight out of an inventory. Changes are written back by the caller
 * via setItem/setItemOnCursor.
 */
public final class TotemCounter {

    private static final Material TOTEM = Material.TOTEM_OF_UNDYING;
    private static final int MAX_DEPTH = 8;

    /** Removal order: main inventory -> hotbar -> armor -> offhand (last). */
    private static final int[] REMOVAL_ORDER;

    static {
        List<Integer> order = new ArrayList<>();
        for (int i = 9; i <= 35; i++) order.add(i);
        for (int i = 0; i <= 8; i++) order.add(i);
        for (int i = 36; i <= 39; i++) order.add(i);
        order.add(40);
        REMOVAL_ORDER = order.stream().mapToInt(Integer::intValue).toArray();
    }

    private TotemCounter() {
    }

    public static int countPlayer(Player player, boolean containers) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            total += count(stack, containers, 0);
        }
        total += count(player.getItemOnCursor(), containers, 0);
        return total;
    }

    public static int count(ItemStack stack, boolean containers) {
        return count(stack, containers, 0);
    }

    private static int count(ItemStack stack, boolean containers, int depth) {
        if (stack == null || stack.isEmpty()) return 0;
        if (stack.getType() == TOTEM) return stack.getAmount();
        if (!containers || depth >= MAX_DEPTH || !stack.hasItemMeta()) return 0;

        ItemMeta meta = stack.getItemMeta();
        int inner = 0;
        if (meta instanceof BundleMeta bundle) {
            for (ItemStack item : bundle.getItems()) {
                inner += count(item, true, depth + 1);
            }
        } else if (meta instanceof BlockStateMeta bsm && bsm.hasBlockState()
                && bsm.getBlockState() instanceof Container container) {
            for (ItemStack item : container.getSnapshotInventory().getContents()) {
                inner += count(item, true, depth + 1);
            }
        }
        return inner * stack.getAmount();
    }

    /**
     * Removes up to {@code excess} totems from the player's possession.
     * Totems inside containers (shulker/bundle) go first, then loose totems.
     *
     * @return exactly the removed items (sum of totems == number removed)
     */
    public static List<ItemStack> removeExcess(Player player, int excess, boolean containers) {
        List<ItemStack> removed = new ArrayList<>();
        int[] budget = {excess};

        // Pass 1: nested totems only, pass 2: loose totems
        for (boolean direct : new boolean[]{false, true}) {
            if (budget[0] <= 0) break;
            if (!direct && !containers) continue;

            ItemStack cursor = player.getItemOnCursor();
            ItemStack newCursor = strip(cursor, budget, removed, direct, containers, 0);
            if (newCursor != cursor) {
                player.setItemOnCursor(newCursor);
            }

            Inventory inv = player.getInventory();
            for (int slot : REMOVAL_ORDER) {
                if (budget[0] <= 0) break;
                if (slot >= inv.getSize()) continue;
                ItemStack current = inv.getItem(slot);
                ItemStack updated = strip(current, budget, removed, direct, containers, 0);
                if (updated != current) {
                    inv.setItem(slot, updated);
                }
            }
        }
        return removed;
    }

    public static int sum(List<ItemStack> stacks) {
        int total = 0;
        for (ItemStack s : stacks) total += s.getAmount();
        return total;
    }

    /**
     * Removes totems from a copy of the stack.
     *
     * @return the same reference if nothing changed; otherwise a new stack (or null = empty)
     */
    private static ItemStack strip(ItemStack stack, int[] budget, List<ItemStack> out,
                                   boolean includeSelf, boolean containers, int depth) {
        if (stack == null || stack.isEmpty() || budget[0] <= 0) return stack;

        if (stack.getType() == TOTEM) {
            if (!includeSelf) return stack;
            int take = Math.min(stack.getAmount(), budget[0]);
            ItemStack taken = stack.clone();
            taken.setAmount(take);
            out.add(taken);
            budget[0] -= take;
            int left = stack.getAmount() - take;
            if (left <= 0) return null;
            ItemStack rest = stack.clone();
            rest.setAmount(left);
            return rest;
        }

        if (!containers || depth >= MAX_DEPTH || count(stack, true, depth) == 0) return stack;

        int copies = stack.getAmount();
        // Meta changes affect every copy in the stack -> split the budget per copy
        int[] innerBudget = {Math.ceilDiv(budget[0], copies)};
        List<ItemStack> innerOut = new ArrayList<>();

        ItemStack result = stack.clone();
        ItemMeta meta = result.getItemMeta();
        if (meta instanceof BundleMeta bundle) {
            List<ItemStack> items = new ArrayList<>();
            for (ItemStack item : bundle.getItems()) {
                ItemStack copy = item.clone();
                ItemStack updated = strip(copy, innerBudget, innerOut, true, true, depth + 1);
                if (updated != null && !updated.isEmpty()) items.add(updated);
            }
            bundle.setItems(items);
        } else if (meta instanceof BlockStateMeta bsm && bsm.hasBlockState()
                && bsm.getBlockState() instanceof Container container) {
            Inventory inv = container.getSnapshotInventory();
            for (int i = 0; i < inv.getSize(); i++) {
                ItemStack item = inv.getItem(i);
                if (item == null || item.isEmpty()) continue;
                ItemStack updated = strip(item.clone(), innerBudget, innerOut, true, true, depth + 1);
                inv.setItem(i, updated);
            }
            bsm.setBlockState(container);
        } else {
            return stack;
        }

        if (innerOut.isEmpty()) return stack;
        result.setItemMeta(meta);

        int removedPerCopy = sum(innerOut);
        for (int c = 0; c < copies; c++) {
            for (ItemStack r : innerOut) out.add(r.clone());
        }
        budget[0] = Math.max(0, budget[0] - removedPerCopy * copies);
        return result;
    }
}
