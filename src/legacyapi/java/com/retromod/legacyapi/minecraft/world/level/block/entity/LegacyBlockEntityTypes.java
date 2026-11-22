/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level.block.entity;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BlockEntityType;

/** {@code BlockEntityType.getKey(type)}, which 26.1 removed in favor of the registry. */
public final class LegacyBlockEntityTypes {

    private LegacyBlockEntityTypes() {}

    /** The type's registry id, or null when it is not registered, as before. */
    public static Identifier getKey(BlockEntityType<?> type) {
        return BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type);
    }
}
