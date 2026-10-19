/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests.crossclass;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;

/**
 * The common half of the multiloader layout: the block is built when this class initializes, and
 * nothing here names its id. {@link CrossClassRegistrar} does.
 */
public final class CrossClassBlocks {

    public static final Block CROSS_CLASS_BLOCK = new Block(AbstractBlock.Settings.create().strength(1.0f));

    private CrossClassBlocks() {}
}
