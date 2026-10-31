/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.items.wrapper;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/** Inserts into and takes from one slot of a vanilla container, as the container wrappers share. */
final class ContainerSlots {

    private ContainerSlots() {}

    /** Puts as much of {@code stack} in {@code slot} as fits under {@code limit}; returns the rest. */
    static ItemStack insert(Container container, int slot, ItemStack stack, int limit, boolean simulate) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        ItemStack existing = container.getItem(slot);
        int room = Math.min(limit, stack.getMaxStackSize());
        if (!existing.isEmpty()) {
            if (!ItemStack.isSameItemSameComponents(existing, stack)) return stack;
            room -= existing.getCount();
        }
        if (room <= 0) return stack;
        int moved = Math.min(room, stack.getCount());
        if (!simulate) {
            container.setItem(slot, stack.copyWithCount(existing.getCount() + moved));
            container.setChanged();
        }
        return moved == stack.getCount() ? ItemStack.EMPTY : stack.copyWithCount(stack.getCount() - moved);
    }

    /** Takes up to {@code amount} from {@code slot}. */
    static ItemStack extract(Container container, int slot, int amount, boolean simulate) {
        if (amount <= 0) return ItemStack.EMPTY;
        ItemStack existing = container.getItem(slot);
        if (existing.isEmpty()) return ItemStack.EMPTY;
        int taken = Math.min(amount, existing.getCount());
        if (simulate) return existing.copyWithCount(taken);
        ItemStack removed = container.removeItem(slot, taken);
        container.setChanged();
        return removed;
    }
}
