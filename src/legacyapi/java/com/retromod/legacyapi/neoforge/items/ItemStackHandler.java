/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.items;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.common.util.ValueIOSerializable;

/** A fixed list of item slots, the handler most old block entities and items keep their inventory in. */
public class ItemStackHandler implements IItemHandler, IItemHandlerModifiable, ValueIOSerializable {

    protected NonNullList<ItemStack> stacks;

    public ItemStackHandler() {
        this(1);
    }

    public ItemStackHandler(int size) {
        stacks = NonNullList.withSize(size, ItemStack.EMPTY);
    }

    public ItemStackHandler(NonNullList<ItemStack> stacks) {
        this.stacks = stacks;
    }

    /** Replaces the slots with {@code size} empty ones. */
    public void setSize(int size) {
        stacks = NonNullList.withSize(size, ItemStack.EMPTY);
    }

    @Override
    public void setStackInSlot(int slot, ItemStack stack) {
        validateSlotIndex(slot);
        stacks.set(slot, stack);
        onContentsChanged(slot);
    }

    @Override
    public int getSlots() {
        return stacks.size();
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        validateSlotIndex(slot);
        return stacks.get(slot);
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        if (!isItemValid(slot, stack)) return stack;
        validateSlotIndex(slot);
        ItemStack existing = stacks.get(slot);
        int room = getStackLimit(slot, stack);
        if (!existing.isEmpty()) {
            if (!ItemStack.isSameItemSameComponents(stack, existing)) return stack;
            room -= existing.getCount();
        }
        if (room <= 0) return stack;
        int moved = Math.min(room, stack.getCount());
        if (!simulate) {
            // A new slot takes a copy, so the caller's stack never becomes the handler's.
            if (existing.isEmpty()) stacks.set(slot, stack.copyWithCount(moved));
            else existing.grow(moved);
            onContentsChanged(slot);
        }
        return moved == stack.getCount() ? ItemStack.EMPTY : stack.copyWithCount(stack.getCount() - moved);
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (amount <= 0) return ItemStack.EMPTY;
        validateSlotIndex(slot);
        ItemStack existing = stacks.get(slot);
        if (existing.isEmpty()) return ItemStack.EMPTY;
        int taken = Math.min(amount, Math.min(existing.getCount(), existing.getMaxStackSize()));
        ItemStack result = existing.copyWithCount(taken);
        if (!simulate) {
            stacks.set(slot, taken == existing.getCount() ? ItemStack.EMPTY : existing.copyWithCount(existing.getCount() - taken));
            onContentsChanged(slot);
        }
        return result;
    }

    @Override
    public int getSlotLimit(int slot) {
        return 99;
    }

    /** How many of {@code stack} the slot holds: its own limit and the stack's, whichever is lower. */
    protected int getStackLimit(int slot, ItemStack stack) {
        return Math.min(getSlotLimit(slot), stack.getMaxStackSize());
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return true;
    }

    @Override
    public void serialize(ValueOutput output) {
        SlotStorage.write(output, stacks);
    }

    @Override
    public void deserialize(ValueInput input) {
        setSize(SlotStorage.size(input, stacks.size()));
        SlotStorage.read(input, stacks);
        onLoad();
    }

    /** NeoForge 1.21's NBT form, which old mods still call to save the handler themselves. */
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        return SlotStorage.toTag(provider, stacks);
    }

    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
        setSize(SlotStorage.size(tag, stacks.size()));
        SlotStorage.fromTag(provider, tag, stacks);
        onLoad();
    }

    protected void validateSlotIndex(int slot) {
        if (slot < 0 || slot >= stacks.size()) {
            throw new RuntimeException("Slot " + slot + " not in valid range - [0," + stacks.size() + ")");
        }
    }

    protected void onLoad() {}

    protected void onContentsChanged(int slot) {}
}
