/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;

/** Updates Forge 1.21.10 mods for 1.21.11. */
public class Forge_1_21_10_to_1_21_11 implements VersionShim {
    @Override public String getShimName() { return "Forge 1.21.10 to 1.21.11"; }
    @Override public String getSourceVersion() { return "1.21.10"; }
    @Override public String getTargetVersion() { return "1.21.11"; }
    @Override public String getModLoaderType() { return "forge"; }
    @Override public void registerRedirects(RetromodTransformer transformer) {
        // The RenderSystem setters this version removed.
        com.retromod.shim.common.RemovedRenderStateNeutralize.registerRemovedIn(transformer, "1.21.11");
        // WorldVersion.getPackVersion(PackType) became packVersion(PackType) with a major and minor number.
        com.retromod.shim.common.LegacyPackVersionBridge.register(transformer);
        // VertexConsumer.addVertex, Transformation, and Vec3 took JOML's read-only interfaces.
        com.retromod.shim.common.JomlParameterWidening.register1_21_11(transformer);
        // Screen.resize lost its Minecraft argument; old overrides and calls are bridged to the new one.
        com.retromod.shim.common.LegacyGuiOverrideAdapter.registerResizeCalls(transformer);
        // The int permission level in CommandSourceStack's constructor became a PermissionSet.
        com.retromod.shim.common.LegacyCommandSourceBridge.register(transformer);
        // 1.21.11 moved game rules to gamerules.GameRules under new names, which only a server
        // level holds. Fabric 1.21.11 bridges the same reads in intermediary names in its own shim.
        com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves.registerGameRuleFieldRenames(transformer);
        com.retromod.shim.common.LegacyGameRuleCallBridge.register(transformer);
        // SimpleCraftingRecipeSerializer became CustomRecipe.Serializer.
        com.retromod.shim.common.LegacyRecipeSerializerShim.registerCustomRecipeRename(transformer);
        // CompoundTag.remove(String) returns the removed tag from 1.21.11.
        com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves.registerNbtRemoveReturnsTag(transformer);
        // Mth.sin and Mth.cos take a double from 1.21.11.
        com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves.registerMthDoubleTrig(transformer);
        // LevelTimeAccess gave way to environment attributes, which now hold the moon phase and the sun's angle.
        com.retromod.shim.common.LegacyLevelTimeBridge.register(transformer);
        // The chunk's moved flag, the spawn entry codec and the villager type constants already have their 26.x shapes
        // here (checked on NeoForge 21.11.42), so a mod built before them meets all three on this host.
        com.retromod.shim.common.LegacyWorldgenMembersBridge.registerOlder(transformer);
        // Members removed after 1.21.1 (render calls, ABGR colors, GUI lighting, armor slots, owner ids), and GeckoLib 4's textures rebuilt on 1.21.11's.
        com.retromod.shim.common.LegacyRemovedMemberBridge.register(transformer);
        com.retromod.shim.api.common.LegacyGeckoLib4Bridge.register(transformer);
        // RenderType moved into the rendertype package and its static factories into RenderTypes.
        com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves.registerBlockLayerGetters(transformer);
        com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves.registerRenderTypeFactoryMoves(transformer);
        // 1.21.11 has no TextureAtlasHolder and no RegisterAdditional (checked on NeoForge 21.11.42); both
        // rebuilt classes name Identifier, which arrived here, so earlier hosts are left alone.
        com.retromod.shim.common.LegacyTextureAtlasHolderBridge.register(transformer);
        com.retromod.shim.neoforge.LegacyNeoForgeRegisterAdditionalBridge.register(transformer);
        // 1.21.2 made a block or item's id mandatory, Forge's register(String, Supplier) sets none,
        // and the bridge names Identifier, which 1.21.11 introduced. NeoForge has its own bridge.
        if (!com.retromod.util.McReflect.isNeoForge()) {
            RegistryIdBridgeSynthetic.registerForgeRedirects(transformer);
            // Forge 1.21.11 removed CraftingHelper and IConditionSerializer, which 1.20.1 mods
            // still name when they register recipe condition serializers.
            ForgeNeoForgeSynthetics.registerForgeConditionStandIns(transformer);
        }
    }
    @Override public String[] getShimClasses() { return new String[0]; }
}
