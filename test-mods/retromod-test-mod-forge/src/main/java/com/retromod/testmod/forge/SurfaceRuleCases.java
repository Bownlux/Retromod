/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.VerticalAnchor;

/** Old surface rules as Biomes O' Plenty builds them and TerraBlender wraps the world's; 26.3 replaced them with material rules. */
// A subclass, because the old context and evaluated rule types are protected, as TerraBlender's access transformer works around.
final class SurfaceRuleCases extends SurfaceRules {

    private SurfaceRuleCases() {}

    /** The factories, the constants and isBiome while the mod constructs, before any server exists. */
    static boolean buildsWithoutServer() {
        return tree() != null;
    }

    static SurfaceRules.RuleSource tree() {
        ResourceKey<Biome> nowhere = ResourceKey.create(Registries.BIOME, new ResourceLocation("retromod_test", "nowhere"));
        return SurfaceRules.sequence(
                SurfaceRules.ifTrue(SurfaceRules.isBiome(nowhere),
                        SurfaceRules.ifTrue(SurfaceRules.ON_FLOOR, SurfaceRules.state(Blocks.RED_SAND.defaultBlockState()))),
                SurfaceRules.ifTrue(SurfaceRules.noiseCondition(Noises.SURFACE, 0.0),
                        SurfaceRules.ifTrue(SurfaceRules.isBiome(nowhere), SurfaceRules.state(Blocks.GRAVEL.defaultBlockState()))),
                SurfaceRules.ifTrue(SurfaceRules.not(SurfaceRules.yBlockCheck(VerticalAnchor.absolute(0), 0)),
                        SurfaceRules.ifTrue(SurfaceRules.UNDER_CEILING, SurfaceRules.state(Blocks.BASALT.defaultBlockState()))));
    }

    /** The overworld's rule read through the old accessor and wrapped in a rule source of the mod's own. */
    static String wrapsTheWorldRule(MinecraftServer server) {
        if (!(server.overworld().getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator)) {
            return "fail: the overworld has no noise generator";
        }
        SurfaceRules.RuleSource world = generator.generatorSettings().value().surfaceRule();
        if (world == null) return "fail: the old accessor returned no rule";
        SurfaceRules.RuleSource wrapped = SurfaceRules.sequence(tree(), new OwnRule(world));
        return wrapped != null ? "success" : "fail: the wrapped rule was not built";
    }

    /** Applies the rule it holds through the old interface, as TerraBlender's namespaced rule does. */
    record OwnRule(SurfaceRules.RuleSource inner) implements SurfaceRules.RuleSource {
        @Override
        public SurfaceRules.SurfaceRule apply(SurfaceRules.Context context) {
            return inner.apply(context);
        }

        @Override
        public KeyDispatchDataCodec<? extends SurfaceRules.RuleSource> codec() {
            return null;
        }
    }
}
