/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import java.util.List;
import net.minecraft.registry.Registries;
import net.minecraft.util.collection.Weighted;
import net.minecraft.util.collection.Weighting;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.village.VillagerData;

/** Chunk keys, weighted picks and villager professions, which 26.1 and 1.21.5 renamed or reshaped. */
public final class Test64ChunkKeysWeightsAndProfessions implements Test {

    @Override
    public String description() {
        return "ChunkPos.toLong, Weighted entries with Weighting sums and picks, and VillagerData.getProfession";
    }

    @Override
    public TestResult run() {
        long expected = 3L & 0xFFFFFFFFL | ((long) -4 & 0xFFFFFFFFL) << 32;
        if (ChunkPos.toLong(3, -4) != expected) return TestResult.fail("ChunkPos.toLong packed " + ChunkPos.toLong(3, -4));
        // TerraBlender keeps its regions in a weighted list like this one.
        List<Weighted.Present<String>> regions = List.of(Weighted.of("overworld", 2), Weighted.of("mod", 3));
        String picked = Weighting.getAt(regions, 2).map(Weighted.Present::getData).orElse(null);
        if (Weighting.getWeightSum(regions) != 5 || !"mod".equals(picked) || regions.get(1).getWeight().getValue() != 3) {
            return TestResult.fail("the weighted pick gave " + picked);
        }
        // Farmer's Delight lets farmers till its soil after checking this profession.
        JsonObject json = new JsonObject();
        json.addProperty("type", "minecraft:plains");
        json.addProperty("profession", "minecraft:farmer");
        json.addProperty("level", 1);
        VillagerData data = VillagerData.CODEC.parse(JsonOps.INSTANCE, json).result().orElse(null);
        if (data == null || !"minecraft:farmer".equals(Registries.VILLAGER_PROFESSION.getId(data.getProfession()).toString())) {
            return TestResult.fail("the villager's profession read as " + (data == null ? null : data.getProfession()));
        }
        return TestResult.success();
    }
}
