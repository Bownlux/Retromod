/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.commands.arguments.item;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;

/**
 * Minecraft's old {@code ItemParser.ItemResult}: an item and the NBT written after its id up to 1.20.4,
 * an item and its component changes from 1.20.5. The part a version did not have is null.
 */
public record LegacyItemResult(Holder<Item> item, CompoundTag nbt, DataComponentPatch components) {

    /** The 1.20.4 result. */
    public LegacyItemResult(Holder<Item> item, CompoundTag nbt) {
        this(item, nbt, null);
    }

    /** The 1.20.5 to 1.21 result. */
    public LegacyItemResult(Holder<Item> item, DataComponentPatch components) {
        this(item, null, components);
    }
}
