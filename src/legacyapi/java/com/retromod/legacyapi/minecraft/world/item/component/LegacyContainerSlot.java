/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.item.component;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** ItemContainerContents.Slot as a 1.21 mod built it: an index and a stack, which 26.1 turned into a template. */
public final class LegacyContainerSlot {

    private final int index;
    private final ItemStack item;

    public LegacyContainerSlot(int index, ItemStack item) {
        this.index = index;
        this.item = item;
    }

    public int index() {
        return index;
    }

    public ItemStack item() {
        return item;
    }

    /** {@code contents.asSlots()}: each stack that is not empty, with its slot. */
    public static List<LegacyContainerSlot> asSlots(ItemContainerContents contents) {
        List<LegacyContainerSlot> slots = new ArrayList<>();
        for (int slot = 0; slot < contents.getSlots(); slot++) {
            ItemStack stack = contents.getStackInSlot(slot);
            if (!stack.isEmpty()) slots.add(new LegacyContainerSlot(slot, stack));
        }
        return slots;
    }

    /** {@code ItemContainerContents.fromSlots(slots)}: each stack at its slot, empty ones between. */
    public static ItemContainerContents fromSlots(List<LegacyContainerSlot> slots) {
        int size = 0;
        for (LegacyContainerSlot slot : slots) size = Math.max(size, slot.index + 1);
        if (size == 0) return ItemContainerContents.EMPTY;
        List<ItemStack> stacks = new ArrayList<>(size);
        for (int slot = 0; slot < size; slot++) stacks.add(ItemStack.EMPTY);
        for (LegacyContainerSlot slot : slots) stacks.set(slot.index, slot.item);
        return ItemContainerContents.fromItems(stacks);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof LegacyContainerSlot that && index == that.index && Objects.equals(item, that.item);
    }

    @Override
    public int hashCode() {
        return 31 * index + Objects.hashCode(item);
    }

    @Override
    public String toString() {
        return "Slot[index=" + index + ", item=" + item + "]";
    }
}
