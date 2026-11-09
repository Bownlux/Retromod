/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.color;

import net.minecraft.world.item.ItemStack;

/** Minecraft's old item color handler, which old Forge and NeoForge mods implement; 1.21.4 replaced it with item tints. */
@FunctionalInterface
public interface ItemColor {

    /** The color for one tint index of the item's model; -1 leaves the quad untinted. */
    int getColor(ItemStack stack, int tintIndex);
}
