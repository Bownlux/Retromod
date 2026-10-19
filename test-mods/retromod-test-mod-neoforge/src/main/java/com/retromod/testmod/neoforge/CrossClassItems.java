/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;

/**
 * The item reads {@link CrossClassBlocks} while it is being built, so the block class initializes
 * in the middle of the item's statement. The item has to get its own id back afterwards.
 */
public final class CrossClassItems {

    public static final Item CROSS_CLASS_ITEM = new BlockItem(CrossClassBlocks.CROSS_CLASS_BLOCK, new Item.Properties());

    private CrossClassItems() {}
}
