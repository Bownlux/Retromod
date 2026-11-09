/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.block.Blocks;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.VerticalSurfaceType;
import net.minecraft.world.gen.YOffset;
import net.minecraft.world.gen.noise.NoiseParametersKeys;
import net.minecraft.world.gen.surfacebuilder.MaterialRules;

/** Old surface rules built while the mod initializes, before any server, as Biomes O' Plenty builds them; 26.3 removed them. */
public final class Test65OldSurfaceRules implements Test {

    // The world generator applies the wrapper the settings mixin hands it, which counts what it was asked.
    public static final AtomicInteger APPLIED = new AtomicInteger();
    public static final AtomicInteger TRIED = new AtomicInteger();
    public static volatile MaterialRules.MaterialRule tree;

    @Override
    public String description() {
        return "SurfaceRules factories, constants and isBiome with no server, and a rule source of the mod's own";
    }

    @Override
    public TestResult run() {
        // The biome no world has, so the old tree is applied and tested at each block but never changes one.
        RegistryKey<net.minecraft.world.biome.Biome> nowhere =
                RegistryKey.of(RegistryKeys.BIOME, new Identifier("retromod_test", "nowhere"));
        tree = MaterialRules.sequence(
                MaterialRules.condition(MaterialRules.biome(nowhere),
                        MaterialRules.condition(MaterialRules.STONE_DEPTH_FLOOR, MaterialRules.block(Blocks.RED_SAND.getDefaultState()))),
                MaterialRules.condition(MaterialRules.biome(nowhere),
                        MaterialRules.condition(MaterialRules.noiseThreshold(NoiseParametersKeys.SURFACE, 0.0),
                                MaterialRules.block(Blocks.GRAVEL.getDefaultState()))),
                MaterialRules.condition(MaterialRules.biome(nowhere),
                        MaterialRules.condition(MaterialRules.not(MaterialRules.aboveY(YOffset.fixed(0), 0)),
                                MaterialRules.condition(MaterialRules.stoneDepth(0, true, VerticalSurfaceType.CEILING),
                                        MaterialRules.block(Blocks.BASALT.getDefaultState())))));
        return tree != null ? TestResult.success() : TestResult.fail("the old rule tree was not built");
    }
}
