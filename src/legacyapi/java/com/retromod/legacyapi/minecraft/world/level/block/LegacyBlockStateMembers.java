/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level.block;

import java.util.stream.Stream;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Block state members 26.1 and 26.3 removed. */
public final class LegacyBlockStateMembers {

    private LegacyBlockStateMembers() {}

    /** {@code state.getTags()}: the block's tags. */
    public static Stream<TagKey<Block>> getTags(Object state) {
        return ((BlockState) state).getBlock().builtInRegistryHolder().tags();
    }

    /** {@code state.blocksMotion()}: a solid block stops movement, except cobwebs and bamboo shoots, which slow it instead. */
    public static boolean blocksMotion(Object state) {
        BlockState blockState = (BlockState) state;
        Block block = blockState.getBlock();
        return block != Blocks.COBWEB && block != Blocks.BAMBOO_SAPLING && blockState.isSolid();
    }
}
