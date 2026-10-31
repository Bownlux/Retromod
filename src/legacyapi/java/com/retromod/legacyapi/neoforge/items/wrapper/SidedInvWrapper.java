/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.items.wrapper;

import com.retromod.legacyapi.neoforge.items.IItemHandlerModifiable;
import net.minecraft.core.Direction;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.ItemStack;

/** The slots a sided vanilla container opens to one face, numbered from 0. */
public class SidedInvWrapper implements IItemHandlerModifiable {

    protected final WorldlyContainer inv;
    protected final Direction side;

    public SidedInvWrapper(WorldlyContainer inv, Direction side) {
        this.inv = inv;
        this.side = side;
    }

    /** The container slot behind face slot {@code slot}, or -1. */
    public static int getSlot(WorldlyContainer inv, int slot, Direction side) {
        int[] slots = inv.getSlotsForFace(side);
        return slot >= 0 && slot < slots.length ? slots[slot] : -1;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof SidedInvWrapper wrapper && wrapper.inv == inv && wrapper.side == side;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(inv) * 31 + (side == null ? 0 : side.hashCode());
    }

    @Override
    public int getSlots() {
        return inv.getSlotsForFace(side).length;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        int index = getSlot(inv, slot, side);
        return index < 0 ? ItemStack.EMPTY : inv.getItem(index);
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        int index = getSlot(inv, slot, side);
        if (index < 0 || !inv.canPlaceItem(index, stack) || !inv.canPlaceItemThroughFace(index, stack, side)) return stack;
        return ContainerSlots.insert(inv, index, stack, getSlotLimit(slot), simulate);
    }

    @Override
    public void setStackInSlot(int slot, ItemStack stack) {
        int index = getSlot(inv, slot, side);
        if (index >= 0) inv.setItem(index, stack);
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        int index = getSlot(inv, slot, side);
        if (index < 0) return ItemStack.EMPTY;
        ItemStack existing = inv.getItem(index);
        if (existing.isEmpty() || !inv.canTakeItemThroughFace(index, existing, side)) return ItemStack.EMPTY;
        return ContainerSlots.extract(inv, index, amount, simulate);
    }

    @Override
    public int getSlotLimit(int slot) {
        return inv.getMaxStackSize();
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        int index = getSlot(inv, slot, side);
        return index >= 0 && inv.canPlaceItem(index, stack) && inv.canPlaceItemThroughFace(index, stack, side);
    }
}
