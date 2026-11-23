/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.item;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** The instance placement hook {@code BlockItem.updateCustomBlockEntityTag(pos, level, player, stack, state)}, which 26.3 removed. */
public final class LegacyBlockItemTags {

    private LegacyBlockItemTags() {}

    /** Copies the item's block entity data onto the placed block, as the old hook's default body did. */
    public static boolean updateCustomBlockEntityTag(BlockItem item, BlockPos pos, Level level, Player player, ItemStack stack,
            BlockState state) {
        return BlockItem.updateCustomBlockEntityTag(level, player, pos, stack);
    }
}
