/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.random.WeightedEntry;
import net.minecraft.util.random.WeightedRandom;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.level.ChunkPos;

/** Members TerraBlender and Farmer's Delight use while a world generates and villagers work, which 1.21.5 and 26.1 reshaped. */
final class WorldLoadMembersCase {

    private WorldLoadMembersCase() {}

    /** TerraBlender's region cache key: 26.1 named the packing helpers pack. */
    static boolean chunkKeys() {
        long expected = 3L & 0xFFFFFFFFL | ((long) -4 & 0xFFFFFFFFL) << 32;
        return ChunkPos.asLong(3, -4) == expected && ChunkPos.asLong(new BlockPos(48, 70, -64)) == expected;
    }

    /** TerraBlender's weighted region pick: 1.21.5 removed WeightedEntry and the weightless sums. */
    static boolean weightedPick() {
        List<WeightedEntry.Wrapper<String>> regions = List.of(WeightedEntry.wrap("overworld", 2), WeightedEntry.wrap("mod", 3));
        String picked = WeightedRandom.getWeightedItem(regions, 2).map(WeightedEntry.Wrapper::data).orElse(null);
        return WeightedRandom.getTotalWeight(regions) == 5 && "mod".equals(picked) && regions.get(1).weight().asInt() == 3;
    }

    /** Farmer's Delight's farmer check: 1.21.5 holds the profession in a holder behind profession(). */
    static boolean profession() {
        JsonObject json = new JsonObject();
        json.addProperty("type", "minecraft:plains");
        json.addProperty("profession", "minecraft:farmer");
        json.addProperty("level", 1);
        VillagerData data = VillagerData.CODEC.parse(JsonOps.INSTANCE, json).result().orElse(null);
        return data != null && "minecraft:farmer".equals(BuiltInRegistries.VILLAGER_PROFESSION.getKey(data.getProfession()).toString());
    }
}
