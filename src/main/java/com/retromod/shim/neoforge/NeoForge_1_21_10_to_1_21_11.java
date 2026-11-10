/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 *
 * NeoForge 1.21.11 changes: https://neoforged.net/news/21.11release/
 */
package com.retromod.shim.neoforge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;

/** Updates NeoForge 1.21.10 mods for 1.21.11, the last obfuscated Minecraft version. */
public class NeoForge_1_21_10_to_1_21_11 implements VersionShim {
    
    @Override
    public String getShimName() {
        return "NeoForge 1.21.10 to 1.21.11";
    }
    
    @Override
    public String getSourceVersion() {
        return "1.21.10";
    }
    
    @Override
    public String getTargetVersion() {
        return "1.21.11";
    }
    
    @Override
    public String getModLoaderType() {
        return "neoforge";
    }
    
    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // The RenderSystem setters this version removed.
        com.retromod.shim.common.RemovedRenderStateNeutralize.registerRemovedIn(transformer, "1.21.11");
        // WorldVersion.getPackVersion(PackType) became packVersion(PackType) with a major and minor number.
        com.retromod.shim.common.LegacyPackVersionBridge.register(transformer);
        // VertexConsumer.addVertex, Transformation, and Vec3 took JOML's read-only interfaces.
        com.retromod.shim.common.JomlParameterWidening.register1_21_11(transformer);
        // Screen.resize lost its Minecraft argument; old overrides and calls are bridged to the new one.
        com.retromod.shim.common.LegacyGuiOverrideAdapter.registerResizeCalls(transformer);
        // ItemProperties, gone since 1.21.4; from 1.21.11 NeoForge runs the names the rebuilt classes are written for.
        com.retromod.shim.common.LegacyItemPropertiesBridge.register(transformer);

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
        // The reload listener events already carry their newer names here (checked on 1.21.11).
        if (com.retromod.core.ClassResourceInspector.exists("net/neoforged/neoforge/event/AddServerReloadListenersEvent")
                && !com.retromod.core.ClassResourceInspector.exists("net/neoforged/neoforge/event/AddReloadListenerEvent")) {
            NeoForge_1_21_11_to_26_1.registerReloadListenerEvents(transformer);
        }

        // Mojang renamed ResourceLocation to Identifier.
        transformer.registerClassRedirect(
            "net/minecraft/resources/ResourceLocation",
            "net/minecraft/resources/Identifier"
        );
        // So did the buffer members named after it (checked on NeoForge 21.11.42).
        com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves.registerIdentifierMemberRenames(transformer);
        // 1.21.11 has no TextureAtlasHolder and no RegisterAdditional (checked on NeoForge 21.11.42); both
        // rebuilt classes name Identifier, which arrived here, so earlier hosts are left alone.
        com.retromod.shim.common.LegacyTextureAtlasHolderBridge.register(transformer);
        com.retromod.shim.neoforge.LegacyNeoForgeRegisterAdditionalBridge.register(transformer);

        // Singular LootContextParamSet moved to util.context.ContextKeySet; plural stays put. (#51)
        transformer.registerClassRedirect(
            "net/minecraft/world/level/storage/loot/parameters/LootContextParamSet",
            "net/minecraft/util/context/ContextKeySet"
        );
        transformer.registerClassRedirect(
            "net/minecraft/world/level/storage/loot/parameters/LootContextParamSet$Builder",
            "net/minecraft/util/context/ContextKeySet$Builder"
        );

        transformer.registerMethodRedirect(
            "net/minecraft/resources/ResourceLocation", "fromNamespaceAndPath",
            "(Ljava/lang/String;Ljava/lang/String;)Lnet/minecraft/resources/ResourceLocation;",
            "net/minecraft/resources/Identifier", "fromNamespaceAndPath",
            "(Ljava/lang/String;Ljava/lang/String;)Lnet/minecraft/resources/Identifier;"
        );
        
        transformer.registerMethodRedirect(
            "net/minecraft/resources/ResourceLocation", "parse",
            "(Ljava/lang/String;)Lnet/minecraft/resources/ResourceLocation;",
            "net/minecraft/resources/Identifier", "parse",
            "(Ljava/lang/String;)Lnet/minecraft/resources/Identifier;"
        );
        
        transformer.registerMethodRedirect(
            "net/minecraft/resources/ResourceLocation", "tryParse",
            "(Ljava/lang/String;)Lnet/minecraft/resources/ResourceLocation;",
            "net/minecraft/resources/Identifier", "tryParse",
            "(Ljava/lang/String;)Lnet/minecraft/resources/Identifier;"
        );
        
        // javax.annotation -> jspecify
        transformer.registerClassRedirect(
            "javax/annotation/Nullable",
            "org/jspecify/annotations/Nullable"
        );
        
        transformer.registerClassRedirect(
            "javax/annotation/Nonnull",
            "org/jspecify/annotations/NonNull"
        );
        
        // RenderType moved into the rendertype package and its static factories into RenderTypes.
        com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves.registerBlockLayerGetters(transformer);
        com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves.registerRenderTypeFactoryMoves(transformer);

        // Baked quad block_light/sky_light -> light_emission is a model-JSON change only.
    }

    @Override
    public String[] getShimClasses() {
        return new String[] {"com.retromod.shim.neoforge.embedded.ReloadListenerEventShim"};
    }
}
