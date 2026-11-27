/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.event;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.level.BaseSpawner;
import net.minecraft.world.level.ServerLevelAccessor;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;

/** The old spawn finalize event constructor, which took a spawn tag and the spawner logic, on NeoForge 26.x. */
public final class LegacyFinalizeSpawn {

    private LegacyFinalizeSpawn() {}

    /** The event NeoForge builds for the same spawn: the spawner's owner stands for the spawner, and the tag is dropped, as NeoForge carries none. */
    public static FinalizeSpawnEvent create(Mob mob, ServerLevelAccessor level, double x, double y, double z,
            DifficultyInstance difficulty, EntitySpawnReason reason, SpawnGroupData data, CompoundTag tag, BaseSpawner spawner) {
        return new FinalizeSpawnEvent(mob, level, x, y, z, difficulty, reason, data, spawner == null ? null : spawner.getOwner());
    }
}
