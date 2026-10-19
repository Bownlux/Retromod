/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * The common half of the multiloader layout: the block is built when this class initializes, and
 * only {@link CrossClassRegistration} names its id.
 */
public final class CrossClassBlocks {

    public static final Block CROSS_CLASS_BLOCK = new Block(BlockBehaviour.Properties.of().strength(1.0f));

    private CrossClassBlocks() {}
}
