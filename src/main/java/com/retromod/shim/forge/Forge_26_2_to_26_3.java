/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;
import com.retromod.shim.common.Mc26_2To26_3CoreMoves;

/**
 * Forge 26.2 to 26.3 shim. Both versions are Mojang named, so this is class moves only
 * ({@link Mc26_2To26_3CoreMoves}), dominated by {@code com/mojang/blaze3d} becoming
 * {@code com/mojang/renderpearl}, plus the Forge 66 API renames that keep their meaning. Forge
 * 66 also removed things with no same-meaning replacement, among them the axe, shovel and hoe
 * {@code ToolActions}, the brewing recipe API, and {@code ForgeRegistries.FEATURES}; those need a
 * port.
 */
public class Forge_26_2_to_26_3 implements VersionShim {

    @Override
    public String getShimName() {
        return "Forge 26.2 to 26.3";
    }

    @Override
    public String getSourceVersion() {
        return "26.2";
    }

    @Override
    public String getTargetVersion() {
        return "26.3";
    }

    @Override
    public String getModLoaderType() {
        return "forge";
    }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        Mc26_2To26_3CoreMoves.register(transformer);

        // Forge API renames from 65 to 66. On a NeoForge runtime these Forge names belong to the
        // Forge to NeoForge migration instead.
        if (com.retromod.util.McReflect.isNeoForge()) {
            return;
        }

        // 66 removed the axe, hoe, pickaxe, shovel, sword and shield ToolActions with 26.3's
        // data-driven tools; NeoForge 26.3 did the same to ItemAbilities.
        com.retromod.shim.neoforge.LegacyItemAbilitiesBridge.registerForge(transformer);

        // The ender teleport event was generalised; constructor, BUS and getEntityLiving match.
        transformer.registerClassRedirect(
                "net/minecraftforge/event/entity/EntityTeleportEvent$EnderEntity",
                "net/minecraftforge/event/entity/EntityTeleportEvent$EntityRandom");

        // BiomeInfo.Builder became a record: the getters lost their "get", descriptors unchanged.
        String biomeBuilder = "net/minecraftforge/common/world/ModifiableBiomeInfo$BiomeInfo$Builder";
        String[][] biomeGetters = {
            {"getClimateSettings", "climateSettings",
                "()Lnet/minecraftforge/common/world/ClimateSettingsBuilder;"},
            {"getGenerationSettings", "generationSettings",
                "()Lnet/minecraft/world/level/biome/BiomeGenerationSettings$PlainBuilder;"},
            {"getSpecialEffects", "effects",
                "()Lnet/minecraftforge/common/world/BiomeSpecialEffectsBuilder;"},
        };
        for (String[] getter : biomeGetters) {
            transformer.registerMethodRedirect(biomeBuilder, getter[0], getter[2],
                    biomeBuilder, getter[1], getter[2]);
        }

        // Not carried: RenderBlockScreenEffectEvent renamed getBlockState to getBlockSate, but it
        // also became abstract with a BUS per overlay kind, so an old listener fails on BUS before
        // any getter runs. BiomeInfo.Builder.getMobSpawnSettings has no Forge 66 counterpart.
    }
}
