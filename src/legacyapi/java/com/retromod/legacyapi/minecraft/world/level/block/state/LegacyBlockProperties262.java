/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level.block.state;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;

/** The block property setter 26.2 changed from taking a {@code StatePredicate}; it links only on 26.2 and newer. */
public final class LegacyBlockProperties262 {

    private LegacyBlockProperties262() {}

    /** {@code properties.emissiveRendering(predicate)}: 26.2 asks with the state alone, so the old one sees no level or position. */
    public static BlockBehaviour.Properties emissiveRendering(BlockBehaviour.Properties properties, BlockBehaviour.StatePredicate predicate) {
        return properties.emissiveRendering(state -> predicate.test(state, EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
    }
}
