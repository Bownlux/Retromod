/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.items;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** A menu slot that shows and changes one slot of an item handler. */
public class SlotItemHandler extends Slot {

    // Vanilla slots need a container; this one stays empty, and every read and write goes to the handler.
    private static final Container EMPTY_CONTAINER = new SimpleContainer(0);

    private final IItemHandler itemHandler;
    protected final int index;

    public SlotItemHandler(IItemHandler itemHandler, int index, int x, int y) {
        super(EMPTY_CONTAINER, index, x, y);
        this.itemHandler = itemHandler;
        this.index = index;
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        return !stack.isEmpty() && itemHandler.isItemValid(index, stack);
    }

    @Override
    public ItemStack getItem() {
        return itemHandler.getStackInSlot(index);
    }

    @Override
    public void set(ItemStack stack) {
        ((IItemHandlerModifiable) itemHandler).setStackInSlot(index, stack);
        setChanged();
    }

    /** Sets the slot without the change hooks a player's click runs; the old menus called it on sync. */
    public void initialize(ItemStack stack) {
        ((IItemHandlerModifiable) itemHandler).setStackInSlot(index, stack);
        setChanged();
    }

    @Override
    public void onQuickCraft(ItemStack picked, ItemStack original) {}

    @Override
    public int getMaxStackSize() {
        return itemHandler.getSlotLimit(index);
    }

    @Override
    public int getMaxStackSize(ItemStack stack) {
        // A stack handler knows its per-item limit; for any other handler the slot and item limits bound it.
        if (itemHandler instanceof ItemStackHandler stacks) return stacks.getStackLimit(index, stack);
        return Math.min(itemHandler.getSlotLimit(index), stack.getMaxStackSize());
    }

    @Override
    public boolean mayPickup(Player player) {
        return !itemHandler.extractItem(index, 1, true).isEmpty();
    }

    @Override
    public ItemStack remove(int amount) {
        return itemHandler.extractItem(index, amount, false);
    }

    public IItemHandler getItemHandler() {
        return itemHandler;
    }

    @Override
    public boolean isSameInventory(Slot other) {
        return other instanceof SlotItemHandler slot && slot.getItemHandler() == itemHandler;
    }
}
