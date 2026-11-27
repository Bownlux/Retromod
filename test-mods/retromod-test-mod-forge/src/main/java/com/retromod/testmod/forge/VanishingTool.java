/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Vanishable;

/** #415: an item that implements Vanishable, the marker 1.20.5 removed. Only loaded, never registered. */
public class VanishingTool extends Item implements Vanishable {
    public VanishingTool(Item.Properties properties) {
        super(properties);
    }
}
