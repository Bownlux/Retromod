/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.item;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStackTemplate;

/** {@code item.getCraftingRemainder()} as the item it returned before it became a stack template in 26.1. */
public final class LegacyCraftingRemainders {

    private LegacyCraftingRemainders() {}

    /** The remainder's item, or null when crafting leaves nothing behind, as before. */
    public static Item getCraftingRemainder(Item item) {
        ItemStackTemplate remainder = item.getCraftingRemainder();
        return remainder == null ? null : remainder.typeHolder().value();
    }
}
