/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.items.wrapper;

import com.retromod.legacyapi.neoforge.items.IItemHandler;
import com.retromod.legacyapi.neoforge.items.IItemHandlerModifiable;
import net.minecraft.world.item.ItemStack;

/** A handler with no slots, which takes and gives nothing. */
public class EmptyItemHandler implements IItemHandlerModifiable {

    public static final IItemHandler INSTANCE = new EmptyItemHandler();

    @Override public int getSlots() { return 0; }
    @Override public ItemStack getStackInSlot(int slot) { return ItemStack.EMPTY; }
    @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return stack; }
    @Override public ItemStack extractItem(int slot, int amount, boolean simulate) { return ItemStack.EMPTY; }
    @Override public void setStackInSlot(int slot, ItemStack stack) {}
    @Override public int getSlotLimit(int slot) { return 0; }
    @Override public boolean isItemValid(int slot, ItemStack stack) { return false; }
}
