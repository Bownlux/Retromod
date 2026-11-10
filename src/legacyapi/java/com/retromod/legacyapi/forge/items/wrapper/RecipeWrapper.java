/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.items.wrapper;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/** Forge's recipe wrapper: an item handler read and written as a vanilla container, as a 1.20.1 recipe takes it. */
public class RecipeWrapper implements Container {

    protected final IItemHandlerModifiable inv;

    public RecipeWrapper(IItemHandlerModifiable inv) {
        this.inv = inv;
    }

    @Override
    public int getContainerSize() {
        return inv.getSlots();
    }

    @Override
    public ItemStack getItem(int slot) {
        return inv.getStackInSlot(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int count) {
        ItemStack stack = inv.getStackInSlot(slot);
        return stack.isEmpty() ? ItemStack.EMPTY : stack.split(count);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        inv.setStackInSlot(slot, stack);
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack stack = getItem(slot);
        if (stack.isEmpty()) return ItemStack.EMPTY;
        setItem(slot, ItemStack.EMPTY);
        return stack;
    }

    @Override
    public boolean isEmpty() {
        for (int slot = 0; slot < inv.getSlots(); slot++) {
            if (!inv.getStackInSlot(slot).isEmpty()) return false;
        }
        return true;
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return inv.isItemValid(slot, stack);
    }

    /** Empties every slot. */
    public void clearContent() {
        for (int slot = 0; slot < inv.getSlots(); slot++) inv.setStackInSlot(slot, ItemStack.EMPTY);
    }

    @Override
    public void setChanged() {
        // The handler saves itself; the wrapper only reads and writes through it.
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }
}
