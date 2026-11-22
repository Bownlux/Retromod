/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level.block;

import net.minecraft.data.worldgen.features.CaveFeatures;
import net.minecraft.world.level.block.BonemealableFeaturePlacerBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;

/** MossBlock, which 1.21.2 replaced with a block that places a feature when bonemealed: it spreads moss, as vanilla's does. */
public class LegacyMossBlock extends BonemealableFeaturePlacerBlock {

    // Each translated mod carries its own copy under its own package, so the check goes by name.
    private static final String NAME = "com.retromod.legacyapi.minecraft.world.level.block.LegacyMossBlock";

    public LegacyMossBlock(BlockBehaviour.Properties properties) {
        super(CaveFeatures.MOSS_PATCH_BONEMEAL, properties);
    }

    /** {@code instanceof MossBlock}: vanilla's moss block and every mod's. */
    public static boolean isMossBlock(Object value) {
        if (value == Blocks.MOSS_BLOCK) return true;
        for (Class<?> type = value == null ? null : value.getClass(); type != null; type = type.getSuperclass()) {
            String name = type.getName();
            if (name.equals(NAME) || name.endsWith("." + NAME)) return true;
        }
        return false;
    }
}
