/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world;

import com.retromod.legacyapi.minecraft.world.item.ItemStackMembers;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/** The {@code ContainerHelper} forms that read and wrote a {@code CompoundTag}, which 1.21.6 replaced with value input and output. */
public final class ContainerHelperMembers {

    private static final String ITEMS = "Items";
    private static final String SLOT = "Slot";

    private ContainerHelperMembers() {}

    /** {@code saveAllItems(tag, items, registries)}: always writes the {@code Items} list, even when it is empty. */
    public static CompoundTag saveAllItems(CompoundTag tag, NonNullList<ItemStack> items, HolderLookup.Provider registries) {
        return saveAllItems(tag, items, true, registries);
    }

    /** {@code saveAllItems(tag, items, alwaysPutTag, registries)}: each filled slot as an item with its byte {@code Slot}. */
    public static CompoundTag saveAllItems(CompoundTag tag, NonNullList<ItemStack> items, boolean alwaysPutTag,
            HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (int slot = 0; slot < items.size(); slot++) {
            ItemStack stack = items.get(slot);
            if (stack.isEmpty()) continue;
            CompoundTag entry = new CompoundTag();
            // The slot is a byte, so a container past 255 slots wraps, as it did before.
            entry.putByte(SLOT, (byte) slot);
            list.add(ItemStackMembers.save(stack, registries, entry));
        }
        if (!list.isEmpty() || alwaysPutTag) tag.put(ITEMS, list);
        return tag;
    }

    /** {@code loadAllItems(tag, items, registries)}: fills each saved slot that fits and leaves the others as they are. */
    public static void loadAllItems(CompoundTag tag, NonNullList<ItemStack> items, HolderLookup.Provider registries) {
        for (Tag element : tag.getListOrEmpty(ITEMS)) {
            // The old read took only a list of compounds, so any other entry is skipped.
            if (!(element instanceof CompoundTag entry)) continue;
            int slot = entry.getByteOr(SLOT, (byte) 0) & 255;
            if (slot < items.size()) items.set(slot, ItemStackMembers.parse(registries, entry).orElse(ItemStack.EMPTY));
        }
    }
}
