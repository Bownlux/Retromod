/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import net.minecraft.world.item.Item;

/** An item drawn by an old BlockEntityWithoutLevelRenderer, which Minecraft 1.21.4 removed. */
public final class LegacyRendererItem {

    // First read inside the item register event, so the item is built while its id is being registered.
    public static final Item INSTANCE = new Item(new Item.Properties());

    private LegacyRendererItem() {}
}
