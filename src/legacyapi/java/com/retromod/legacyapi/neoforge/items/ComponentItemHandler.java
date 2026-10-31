/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.items;

import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.neoforged.neoforge.common.MutableDataComponentHolder;

/** An item handler kept in an item's container component, which every change writes back. */
public class ComponentItemHandler implements IItemHandlerModifiable {

    protected final MutableDataComponentHolder parent;
    protected final DataComponentType<ItemContainerContents> component;
    protected final int size;

    public ComponentItemHandler(MutableDataComponentHolder parent, DataComponentType<ItemContainerContents> component, int size) {
        this.parent = parent;
        this.component = component;
        this.size = size;
    }

    @Override
    public int getSlots() {
        return size;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        return getStackFromContents(getContents(), slot);
    }

    @Override
    public void setStackInSlot(int slot, ItemStack stack) {
        validateSlotIndex(slot);
        ItemContainerContents contents = getContents();
        if (!ItemStack.matches(getStackFromContents(contents, slot), stack)) updateContents(contents, stack, slot);
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        validateSlotIndex(slot);
        if (stack.isEmpty() || !isItemValid(slot, stack)) return stack;
        ItemContainerContents contents = getContents();
        ItemStack existing = getStackFromContents(contents, slot);
        int room = Math.min(getSlotLimit(slot), stack.getMaxStackSize());
        if (!existing.isEmpty()) {
            if (!ItemStack.isSameItemSameComponents(stack, existing)) return stack;
            room -= existing.getCount();
        }
        if (room <= 0) return stack;
        int moved = Math.min(room, stack.getCount());
        if (!simulate) updateContents(contents, stack.copyWithCount(existing.getCount() + moved), slot);
        return moved == stack.getCount() ? ItemStack.EMPTY : stack.copyWithCount(stack.getCount() - moved);
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        validateSlotIndex(slot);
        if (amount <= 0) return ItemStack.EMPTY;
        ItemContainerContents contents = getContents();
        ItemStack existing = getStackFromContents(contents, slot);
        if (existing.isEmpty()) return ItemStack.EMPTY;
        int taken = Math.min(amount, Math.min(existing.getCount(), existing.getMaxStackSize()));
        if (!simulate) {
            updateContents(contents, taken == existing.getCount() ? ItemStack.EMPTY : existing.copyWithCount(existing.getCount() - taken), slot);
        }
        return existing.copyWithCount(taken);
    }

    @Override
    public int getSlotLimit(int slot) {
        return 99;
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        // An item that holds items would nest inside itself without end.
        return stack.getItem().canFitInsideContainerItems();
    }

    protected void onContentsChanged(int slot, ItemStack oldStack, ItemStack newStack) {}

    protected ItemContainerContents getContents() {
        return parent.getOrDefault(component, ItemContainerContents.EMPTY);
    }

    protected ItemStack getStackFromContents(ItemContainerContents contents, int slot) {
        validateSlotIndex(slot);
        return slot < contents.getSlots() ? contents.getStackInSlot(slot) : ItemStack.EMPTY;
    }

    /** Writes {@code stack} into {@code slot} of a copy of the contents and stores the copy on the parent. */
    protected void updateContents(ItemContainerContents contents, ItemStack stack, int slot) {
        validateSlotIndex(slot);
        NonNullList<ItemStack> items = NonNullList.withSize(Math.max(contents.getSlots(), size), ItemStack.EMPTY);
        contents.copyInto(items);
        ItemStack oldStack = items.get(slot);
        items.set(slot, stack);
        parent.set(component, ItemContainerContents.fromItems(items));
        onContentsChanged(slot, oldStack, stack);
    }

    protected final void validateSlotIndex(int slot) {
        if (slot < 0 || slot >= size) {
            throw new RuntimeException("Slot " + slot + " not in valid range - [0," + size + ")");
        }
    }
}
