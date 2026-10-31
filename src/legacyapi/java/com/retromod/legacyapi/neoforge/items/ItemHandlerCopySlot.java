/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.items;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.world.inventory.StackCopySlot;

/** A handler slot for handlers that hand out copies, such as item components, through NeoForge's copy slot. */
public class ItemHandlerCopySlot extends StackCopySlot {

    private final SlotItemHandler slot;

    public ItemHandlerCopySlot(IItemHandler handler, int index, int x, int y) {
        super(index, x, y);
        this.slot = new SlotItemHandler(handler, index, x, y);
    }

    public ItemHandlerCopySlot(SlotItemHandler slot) {
        super(slot.getSlotIndex(), slot.x, slot.y);
        this.slot = slot;
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        return slot.mayPlace(stack);
    }

    @Override
    protected ItemStack getStackCopy() {
        return slot.getItem();
    }

    @Override
    protected void setStackCopy(ItemStack stack) {
        ((IItemHandlerModifiable) slot.getItemHandler()).setStackInSlot(slot.index, stack);
    }

    @Override
    public void onQuickCraft(ItemStack picked, ItemStack original) {
        slot.onQuickCraft(picked, original);
    }

    @Override
    public int getMaxStackSize() {
        return slot.getMaxStackSize();
    }

    @Override
    public int getMaxStackSize(ItemStack stack) {
        return slot.getMaxStackSize(stack);
    }

    @Override
    public boolean mayPickup(Player player) {
        return slot.mayPickup(player);
    }

    @Override
    public boolean isSameInventory(Slot other) {
        return slot.isSameInventory(other);
    }

    public IItemHandler getItemHandler() {
        return slot.getItemHandler();
    }
}
