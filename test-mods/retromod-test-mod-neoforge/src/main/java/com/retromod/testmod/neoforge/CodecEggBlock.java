/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.FallingBlock;

/**
 * #336: an egg block as MCreator writes one. 26.3 removed block codecs, so filling
 * {@code CODEC} with {@code simpleCodec} threw while the class initialized, and every block the
 * mod registered after it was lost. Never registered: reading {@link #CODEC} initializes it.
 */
final class CodecEggBlock extends FallingBlock {

    static final MapCodec<CodecEggBlock> CODEC = simpleCodec(CodecEggBlock::new);

    CodecEggBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends FallingBlock> codec() {
        return CODEC;
    }
}
