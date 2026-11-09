/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;
import com.retromod.shim.common.Mc26_2To26_3CoreMoves;

/** Updates Forge 26.2 mods for 26.3. */
public class Forge_26_2_to_26_3 implements VersionShim {

    static final String KEY_MAPPING_TRIGGERED = "net/minecraftforge/client/event/InputEvent$InteractionKeyMappingTriggered";
    static final String KEY_MAPPING_TRIGGERS = com.retromod.shim.common.RebuiltApiClasses.ROOT + "forge/event/KeyMappingTriggers";

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
        // Vanilla changes, shared with the other loaders since both versions are Mojang named.
        // Most of it is com/mojang/blaze3d becoming com/mojang/renderpearl.
        Mc26_2To26_3CoreMoves.register(transformer);
        // NeoForge 26.3 deleted ItemHandlerHelper, which Forge mods reach through the rename.
        com.retromod.shim.neoforge.LegacyItemHandlerHelperBridge.register(transformer);

        // Forge API renames from 65 to 66 that keep their meaning. On a NeoForge runtime these
        // Forge names belong to the Forge to NeoForge migration instead.
        if (com.retromod.util.McReflect.isNeoForge()) {
            return;
        }

        // 66 removed the axe, hoe, pickaxe, shovel, sword and shield ToolActions with 26.3's
        // data-driven tools; NeoForge 26.3 did the same to ItemAbilities.
        com.retromod.shim.neoforge.LegacyItemAbilitiesBridge.registerForge(transformer);

        // 66 split the key mapping event into one event per key and dropped isAttack, isUseItem and isPickBlock,
        // which old listeners call once they run on those events, so each answers from the event's class.
        if (ForgeHostMembers.lacksMethod(KEY_MAPPING_TRIGGERED, "isUseItem", "()Z", "26.3")) {
            com.retromod.shim.common.RebuiltApiClasses.registerTree(transformer, KEY_MAPPING_TRIGGERS);
            for (String key : java.util.List.of("isAttack", "isUseItem", "isPickBlock")) {
                transformer.registerMethodRedirect(KEY_MAPPING_TRIGGERED, key, "()Z",
                        KEY_MAPPING_TRIGGERS, key, "(Ljava/lang/Object;)Z", true);
            }
        }

        // 66 dropped getScanCode from its key events. The key event's layout keycode stands in, in the same position.
        LegacyForgeEventMembersBridge.registerScanCode(transformer);

        // The ender teleport event was generalised; constructor, BUS and getEntityLiving match.
        transformer.registerClassRedirect(
                "net/minecraftforge/event/entity/EntityTeleportEvent$EnderEntity",
                "net/minecraftforge/event/entity/EntityTeleportEvent$EntityRandom");

        // 66 removed the brewing recipe classes and registry with 26.3's brewing recipe files, as
        // NeoForge 26.3 did, so old recipes are recorded and written as recipe files the same way.
        LegacyBrewingBridge.registerForge(transformer);

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
        // ForgeRegistries.FEATURES was removed with no same-meaning replacement, so those mods need a port.
    }
}
