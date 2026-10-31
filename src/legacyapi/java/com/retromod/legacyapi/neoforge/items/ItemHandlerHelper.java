/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.items;

import net.minecraft.util.Prediction;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** The old static helpers for moving stacks into handlers and players. */
public class ItemHandlerHelper {

    /** Offers the stack to each slot in turn and returns what no slot took. */
    public static ItemStack insertItem(IItemHandler handler, ItemStack stack, boolean simulate) {
        if (handler == null || stack.isEmpty()) return stack;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            stack = handler.insertItem(slot, stack, simulate);
            if (stack.isEmpty()) return ItemStack.EMPTY;
        }
        return stack;
    }

    /** Like {@link #insertItem}, but tops up slots holding the same item before it fills empty ones. */
    public static ItemStack insertItemStacked(IItemHandler handler, ItemStack stack, boolean simulate) {
        if (handler == null || stack.isEmpty()) return stack;
        if (!stack.isStackable()) return insertItem(handler, stack, simulate);
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack held = handler.getStackInSlot(slot);
            if (held.isEmpty() || !ItemStack.isSameItemSameComponents(held, stack)) continue;
            stack = handler.insertItem(slot, stack, simulate);
            if (stack.isEmpty()) return ItemStack.EMPTY;
        }
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            if (!handler.getStackInSlot(slot).isEmpty()) continue;
            stack = handler.insertItem(slot, stack, simulate);
            if (stack.isEmpty()) return ItemStack.EMPTY;
        }
        return stack;
    }

    public static void giveItemToPlayer(Player player, ItemStack stack) {
        giveItemToPlayer(player, stack, -1);
    }

    /** Puts the stack in the preferred slot when it fits, then anywhere in the inventory, and drops the rest. */
    public static void giveItemToPlayer(Player player, ItemStack stack, int preferredSlot) {
        if (stack.isEmpty()) return;
        Inventory inventory = player.getInventory();
        ItemStack remaining = stack.copy();
        if (preferredSlot >= 0 && preferredSlot < inventory.getNonEquipmentItems().size()) {
            ItemStack held = inventory.getItem(preferredSlot);
            if (held.isEmpty()) {
                inventory.setItem(preferredSlot, remaining);
                return;
            }
            if (ItemStack.isSameItemSameComponents(held, remaining)) {
                int moved = Math.min(remaining.getCount(), held.getMaxStackSize() - held.getCount());
                if (moved > 0) {
                    held.grow(moved);
                    remaining.shrink(moved);
                    inventory.setChanged();
                }
            }
        }
        if (!remaining.isEmpty()) inventory.placeItemBackInInventory(remaining, Prediction.SERVER_ONLY);
    }

    /** A comparator signal from 0 to 15 for how full the handler is, as vanilla containers give. */
    public static int calcRedstoneFromInventory(IItemHandler handler) {
        if (handler == null || handler.getSlots() == 0) return 0;
        int filledSlots = 0;
        float fullness = 0.0F;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (stack.isEmpty()) continue;
            fullness += (float) stack.getCount() / (float) Math.min(handler.getSlotLimit(slot), stack.getMaxStackSize());
            filledSlots++;
        }
        fullness /= handler.getSlots();
        return (int) Math.floor(fullness * 14.0F) + (filledSlots > 0 ? 1 : 0);
    }
}
