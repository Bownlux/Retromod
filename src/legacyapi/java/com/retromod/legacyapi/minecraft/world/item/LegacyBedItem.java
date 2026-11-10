/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.item;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;

/**
 * BedItem, which 26.3 removed: 26.3 places its own beds with a plain block item named after the block, and the bed
 * block adds the head.
 */
public class LegacyBedItem extends BlockItem {

    public LegacyBedItem(Block block, Item.Properties properties) {
        super(block, properties.useBlockDescriptionPrefix());
    }

    /** {@code instanceof BedItem}: any item that places a bed, vanilla's and every mod's. */
    public static boolean isBedItem(Object value) {
        return value instanceof BlockItem item && item.getBlock() instanceof BedBlock;
    }
}
