/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.items.wrapper;

import com.retromod.legacyapi.neoforge.items.IItemHandlerModifiable;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/** A vanilla container as an item handler, slot for slot. */
public class InvWrapper implements IItemHandlerModifiable {

    private final Container inv;

    public InvWrapper(Container inv) {
        this.inv = inv;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof InvWrapper wrapper && wrapper.inv == inv;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(inv);
    }

    @Override
    public int getSlots() {
        return inv.getContainerSize();
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        return inv.getItem(slot);
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        if (!inv.canPlaceItem(slot, stack)) return stack;
        return ContainerSlots.insert(inv, slot, stack, getSlotLimit(slot), simulate);
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        return ContainerSlots.extract(inv, slot, amount, simulate);
    }

    @Override
    public void setStackInSlot(int slot, ItemStack stack) {
        inv.setItem(slot, stack);
    }

    @Override
    public int getSlotLimit(int slot) {
        return inv.getMaxStackSize();
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return inv.canPlaceItem(slot, stack);
    }

    public Container getInv() {
        return inv;
    }
}
