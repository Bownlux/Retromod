/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.items.wrapper;

import com.retromod.legacyapi.neoforge.items.IItemHandlerModifiable;
import net.minecraft.world.item.ItemStack;

/** A run of another handler's slots, from {@code minSlot} up to but not including {@code maxSlot}. */
public class RangedWrapper implements IItemHandlerModifiable {

    private final IItemHandlerModifiable compose;
    private final int minSlot;
    private final int maxSlot;

    public RangedWrapper(IItemHandlerModifiable compose, int minSlot, int maxSlotExclusive) {
        this.compose = compose;
        this.minSlot = minSlot;
        this.maxSlot = maxSlotExclusive;
    }

    private boolean inRange(int slot) {
        return slot >= 0 && slot < maxSlot - minSlot;
    }

    @Override
    public int getSlots() {
        return maxSlot - minSlot;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        return inRange(slot) ? compose.getStackInSlot(slot + minSlot) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        return inRange(slot) ? compose.insertItem(slot + minSlot, stack, simulate) : stack;
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        return inRange(slot) ? compose.extractItem(slot + minSlot, amount, simulate) : ItemStack.EMPTY;
    }

    @Override
    public void setStackInSlot(int slot, ItemStack stack) {
        if (inRange(slot)) compose.setStackInSlot(slot + minSlot, stack);
    }

    @Override
    public int getSlotLimit(int slot) {
        return inRange(slot) ? compose.getSlotLimit(slot + minSlot) : 0;
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return inRange(slot) && compose.isItemValid(slot + minSlot, stack);
    }
}
