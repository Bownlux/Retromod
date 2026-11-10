/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.item;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * SignItem, which 26.3 removed: a standing and wall block item whose properties carry the sign text and the block's
 * name, as 26.3's own signs do. The sign block opens the text editor when it is placed.
 */
public class LegacySignItem extends StandingAndWallBlockItem {

    /** 1.20 to 1.21.1: properties first. */
    public LegacySignItem(Item.Properties properties, Block standingBlock, Block wallBlock) {
        this(properties, standingBlock, wallBlock, Direction.DOWN);
    }

    /** Every version up to 26.2, with the side the wall block hangs from. */
    public LegacySignItem(Item.Properties properties, Block standingBlock, Block wallBlock, Direction attachment) {
        super(standingBlock, wallBlock, attachment, properties.signText().useBlockDescriptionPrefix());
    }

    /** 1.21.2 to 26.2: blocks first. */
    public LegacySignItem(Block standingBlock, Block wallBlock, Item.Properties properties) {
        this(properties, standingBlock, wallBlock, Direction.DOWN);
    }

    /** {@code instanceof SignItem}: any item that places a sign, vanilla's and every mod's, as the old class covered hanging signs too. */
    public static boolean isSignItem(Object value) {
        return value instanceof BlockItem item && item.getBlock() instanceof SignBlock;
    }

    /** The old placement hook a subclass may still call: stores the item's block entity data, as BlockItem does now. */
    protected boolean updateCustomBlockEntityTag(BlockPos pos, Level level, Player player, ItemStack stack, BlockState state) {
        return BlockItem.updateCustomBlockEntityTag(level, player, pos, stack);
    }
}
