/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** 1.21.5 and 1.21.6 removed the item stack and container saves that took a CompoundTag. */
final class LegacyItemNbtCase {

    private LegacyItemNbtCase() {}

    /** The first value that read back wrong, or success. */
    static String run(HolderLookup.Provider registries) {
        // A chest with an empty first slot saves only its filled slots, each with a byte Slot.
        NonNullList<ItemStack> items = NonNullList.withSize(3, ItemStack.EMPTY);
        items.set(1, new ItemStack(Items.DIAMOND, 5));
        items.set(2, new ItemStack(Items.APPLE, 2));
        CompoundTag saved = ContainerHelper.saveAllItems(new CompoundTag(), items, registries);
        if (saved.getList("Items", Tag.TAG_COMPOUND).size() != 2) return "fail: saved " + saved;
        NonNullList<ItemStack> loaded = NonNullList.withSize(3, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(saved, loaded, registries);
        if (!loaded.get(0).isEmpty() || !ItemStack.matches(loaded.get(1), items.get(1))
                || !ItemStack.matches(loaded.get(2), items.get(2))) {
            return "fail: loaded " + loaded;
        }
        if (ContainerHelper.saveAllItems(new CompoundTag(), NonNullList.withSize(2, ItemStack.EMPTY), false, registries)
                .contains("Items")) {
            return "fail: an empty chest wrote its list when asked not to";
        }

        // One stack on its own, and the optional forms that treat empty as an empty tag.
        Tag one = items.get(1).save(registries);
        ItemStack back = ItemStack.parse(registries, one).orElse(ItemStack.EMPTY);
        if (!ItemStack.matches(back, items.get(1))) return "fail: parse read back " + back;
        CompoundTag slot = new CompoundTag();
        slot.putByte("Slot", (byte) 4);
        CompoundTag withSlot = (CompoundTag) items.get(2).save(registries, slot);
        if (withSlot.getByte("Slot") != 4 || !ItemStack.matches(ItemStack.parseOptional(registries, withSlot), items.get(2))) {
            return "fail: save into a prefix wrote " + withSlot;
        }
        Tag empty = ItemStack.EMPTY.saveOptional(registries);
        if (!(empty instanceof CompoundTag emptyTag) || !emptyTag.isEmpty()) return "fail: saveOptional of empty wrote " + empty;
        if (!ItemStack.parseOptional(registries, new CompoundTag()).isEmpty()) return "fail: an empty tag read as a stack";
        return "success";
    }
}
