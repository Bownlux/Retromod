/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.items.wrapper;

import com.retromod.legacyapi.neoforge.items.IItemHandler;
import java.util.function.Supplier;
import net.minecraft.world.item.ItemStack;

/** Passes every call to a handler it looks up each time. */
public class ForwardingItemHandler implements IItemHandler {

    protected final Supplier<IItemHandler> delegate;

    public ForwardingItemHandler(IItemHandler delegate) {
        this(() -> delegate);
    }

    public ForwardingItemHandler(Supplier<IItemHandler> delegate) {
        this.delegate = delegate;
    }

    @Override public int getSlots() { return delegate.get().getSlots(); }
    @Override public ItemStack getStackInSlot(int slot) { return delegate.get().getStackInSlot(slot); }
    @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return delegate.get().insertItem(slot, stack, simulate); }
    @Override public ItemStack extractItem(int slot, int amount, boolean simulate) { return delegate.get().extractItem(slot, amount, simulate); }
    @Override public int getSlotLimit(int slot) { return delegate.get().getSlotLimit(slot); }
    @Override public boolean isItemValid(int slot, ItemStack stack) { return delegate.get().isItemValid(slot, stack); }
}
