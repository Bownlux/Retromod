/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.items;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** Saves item slots in the layout NeoForge's handlers used: an {@code Items} list whose entries carry their {@code Slot}, and the {@code Size}. */
public final class SlotStorage {

    private SlotStorage() {}

    /** Writes every filled slot; the slot index is an int, so handlers past 255 slots keep theirs. */
    public static void write(ValueOutput output, NonNullList<ItemStack> stacks) {
        ValueOutput.ValueOutputList items = output.childrenList("Items");
        for (int slot = 0; slot < stacks.size(); slot++) {
            ItemStack stack = stacks.get(slot);
            if (stack.isEmpty()) continue;
            ValueOutput entry = items.addChild();
            entry.putInt("Slot", slot);
            entry.store(ItemStack.MAP_CODEC, stack);
        }
        output.putInt("Size", stacks.size());
    }

    /** The saved size, or {@code fallback} when none was saved. */
    public static int size(ValueInput input, int fallback) {
        return input.getIntOr("Size", fallback);
    }

    /** Fills {@code stacks} from the saved entries, skipping any outside it. */
    public static void read(ValueInput input, NonNullList<ItemStack> stacks) {
        for (ValueInput entry : input.childrenListOrEmpty("Items")) {
            int slot = entry.getIntOr("Slot", -1);
            if (slot < 0 || slot >= stacks.size()) continue;
            entry.read(ItemStack.MAP_CODEC).ifPresent(stack -> stacks.set(slot, stack));
        }
    }

    /** The 1.21 NBT form of the same layout. */
    public static CompoundTag toTag(HolderLookup.Provider provider, NonNullList<ItemStack> stacks) {
        RegistryOps<Tag> ops = provider.createSerializationContext(NbtOps.INSTANCE);
        ListTag items = new ListTag();
        for (int slot = 0; slot < stacks.size(); slot++) {
            ItemStack stack = stacks.get(slot);
            if (stack.isEmpty()) continue;
            Tag encoded = ItemStack.CODEC.encodeStart(ops, stack).getOrThrow();
            if (encoded instanceof CompoundTag entry) {
                entry.putInt("Slot", slot);
                items.add(entry);
            }
        }
        CompoundTag tag = new CompoundTag();
        tag.put("Items", items);
        tag.putInt("Size", stacks.size());
        return tag;
    }

    /** The saved size in a tag, or {@code fallback}. */
    public static int size(CompoundTag tag, int fallback) {
        return tag.contains("Size") ? tag.getIntOr("Size", fallback) : fallback;
    }

    /** Fills {@code stacks} from a tag written by {@link #toTag} or by NeoForge 1.21. */
    public static void fromTag(HolderLookup.Provider provider, CompoundTag tag, NonNullList<ItemStack> stacks) {
        RegistryOps<Tag> ops = provider.createSerializationContext(NbtOps.INSTANCE);
        for (Tag element : tag.getListOrEmpty("Items")) {
            if (!(element instanceof CompoundTag entry)) continue;
            int slot = entry.getIntOr("Slot", -1);
            if (slot < 0 || slot >= stacks.size()) continue;
            ItemStack.CODEC.parse(ops, (Tag) entry).result().ifPresent(stack -> stacks.set(slot, stack));
        }
    }
}
