/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.SpawnPlacementType;
import net.minecraft.world.level.LevelReader;
import net.minecraftforge.common.util.TriPredicate;

/** Forge's {@code SpawnPlacements.Type.create(name, test)}, from when spawn placement types were an enum it extended. */
public final class SpawnPlacementTypeMembers {

    private SpawnPlacementTypeMembers() {}

    /** A placement type that asks the mod's test; 1.20.5 made the type an interface, so the name is no longer kept. */
    public static SpawnPlacementType create(String name, TriPredicate<LevelReader, BlockPos, EntityType<?>> test) {
        return (level, pos, type) -> test.test(level, pos, type);
    }
}
