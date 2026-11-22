/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level.block.state;

import net.minecraft.world.level.block.state.BlockBehaviour;

/** The block property setter 26.3 changed from taking a {@code StatePredicate}; it links only on 26.3 and newer. */
public final class LegacyBlockProperties263 {

    private LegacyBlockProperties263() {}

    /** {@code properties.isViewBlocking(predicate)}: the old predicate answers for every box, since it never saw one. */
    public static BlockBehaviour.Properties isViewBlocking(BlockBehaviour.Properties properties, BlockBehaviour.StatePredicate predicate) {
        return properties.isViewBlocking((state, level, pos, box) -> predicate.test(state, level, pos));
    }
}
