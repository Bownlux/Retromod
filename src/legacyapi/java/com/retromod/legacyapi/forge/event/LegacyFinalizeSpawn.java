/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.event;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.level.BaseSpawner;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraftforge.event.entity.living.MobSpawnEvent;

/** Forge 1.20.1's spawn finalize event constructor, which took a spawn tag, on Forge 26.2, which reads the tag as a value input. */
public final class LegacyFinalizeSpawn {

    private LegacyFinalizeSpawn() {}

    /** The event Forge 26.2 builds for the same spawn, with the tag read through the level's registries. */
    public static MobSpawnEvent.FinalizeSpawn create(Mob mob, ServerLevelAccessor level, double x, double y, double z,
            DifficultyInstance difficulty, EntitySpawnReason reason, SpawnGroupData data, CompoundTag tag, BaseSpawner spawner) {
        return new MobSpawnEvent.FinalizeSpawn(mob, level, x, y, z, difficulty, reason, data,
                tag == null ? null : TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag), spawner);
    }
}
