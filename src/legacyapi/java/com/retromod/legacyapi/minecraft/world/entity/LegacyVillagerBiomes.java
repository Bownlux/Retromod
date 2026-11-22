/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.entity;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.level.biome.Biome;

/** {@code VillagerType.byBiome(biome)}, which returns the type's key on 1.21.11 and newer. */
public final class LegacyVillagerBiomes {

    private LegacyVillagerBiomes() {}

    /** The villager type for the biome, looked up from the key the host returns. */
    public static VillagerType byBiome(Holder<Biome> biome) {
        return BuiltInRegistries.VILLAGER_TYPE.getValueOrThrow(VillagerType.byBiome(biome));
    }
}
