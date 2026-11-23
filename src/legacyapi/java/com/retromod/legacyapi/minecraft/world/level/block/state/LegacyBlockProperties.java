/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level.block.state;

import net.minecraft.world.level.block.state.BlockBehaviour;

/** The block property setter 26.1 removed; the ones 26.2 and 26.3 changed are in classes that link only there. */
public final class LegacyBlockProperties {

    private LegacyBlockProperties() {}

    /** {@code properties.hasPostProcess(predicate)}: 26.1 removed the setting, so the properties are kept as they are. */
    public static BlockBehaviour.Properties hasPostProcess(BlockBehaviour.Properties properties, BlockBehaviour.StatePredicate predicate) {
        return properties;
    }
}
