/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.items.wrapper;

import com.retromod.legacyapi.neoforge.items.IItemHandlerModifiable;
import net.minecraft.world.item.ItemStack;

/** Several handlers shown as one, their slots numbered one after another. */
public class CombinedInvWrapper implements IItemHandlerModifiable {

    protected final IItemHandlerModifiable[] itemHandler;
    // baseIndex[i] is the first combined slot of handler i; the last entry is the total.
    protected final int[] baseIndex;
    protected final int slotCount;

    public CombinedInvWrapper(IItemHandlerModifiable... itemHandler) {
        this.itemHandler = itemHandler;
        this.baseIndex = new int[itemHandler.length];
        int total = 0;
        for (int i = 0; i < itemHandler.length; i++) {
            total += itemHandler[i].getSlots();
            baseIndex[i] = total;
        }
        this.slotCount = total;
    }

    /** The handler that owns combined slot {@code slot}, or -1 when it is out of range. */
    protected int getIndexForSlot(int slot) {
        if (slot < 0) return -1;
        for (int i = 0; i < baseIndex.length; i++) {
            if (slot < baseIndex[i]) return i;
        }
        return -1;
    }

    protected IItemHandlerModifiable getHandlerFromIndex(int index) {
        if (index < 0 || index >= itemHandler.length) return (IItemHandlerModifiable) EmptyItemHandler.INSTANCE;
        return itemHandler[index];
    }

    /** The handler's own slot number for combined slot {@code slot}. */
    protected int getSlotFromIndex(int slot, int index) {
        if (index <= 0 || index >= baseIndex.length) return slot;
        return slot - baseIndex[index - 1];
    }

    @Override
    public void setStackInSlot(int slot, ItemStack stack) {
        int index = getIndexForSlot(slot);
        getHandlerFromIndex(index).setStackInSlot(getSlotFromIndex(slot, index), stack);
    }

    @Override
    public int getSlots() {
        return slotCount;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        int index = getIndexForSlot(slot);
        return getHandlerFromIndex(index).getStackInSlot(getSlotFromIndex(slot, index));
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        int index = getIndexForSlot(slot);
        return getHandlerFromIndex(index).insertItem(getSlotFromIndex(slot, index), stack, simulate);
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        int index = getIndexForSlot(slot);
        return getHandlerFromIndex(index).extractItem(getSlotFromIndex(slot, index), amount, simulate);
    }

    @Override
    public int getSlotLimit(int slot) {
        int index = getIndexForSlot(slot);
        return getHandlerFromIndex(index).getSlotLimit(getSlotFromIndex(slot, index));
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        int index = getIndexForSlot(slot);
        return getHandlerFromIndex(index).isItemValid(getSlotFromIndex(slot, index), stack);
    }
}
