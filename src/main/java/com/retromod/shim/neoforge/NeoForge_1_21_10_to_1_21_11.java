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

        // The int permission level in CommandSourceStack's constructor became a PermissionSet.
        com.retromod.shim.common.LegacyCommandSourceBridge.register(transformer);

        // Mojang renamed ResourceLocation to Identifier.
        transformer.registerClassRedirect(
            "net/minecraft/resources/ResourceLocation",
            "net/minecraft/resources/Identifier"
        );

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
        
        // Vanilla render types moved from RenderType to RenderTypes.
        transformer.registerMethodRedirect(
            "net/minecraft/client/renderer/RenderType", "solid",
            "()Lnet/minecraft/client/renderer/RenderType;",
            "net/minecraft/client/renderer/RenderTypes", "solid",
            "()Lnet/minecraft/client/renderer/RenderType;"
        );
        
        transformer.registerMethodRedirect(
            "net/minecraft/client/renderer/RenderType", "cutout",
            "()Lnet/minecraft/client/renderer/RenderType;",
            "net/minecraft/client/renderer/RenderTypes", "cutout",
            "()Lnet/minecraft/client/renderer/RenderType;"
        );
        
        transformer.registerMethodRedirect(
            "net/minecraft/client/renderer/RenderType", "cutoutMipped",
            "()Lnet/minecraft/client/renderer/RenderType;",
            "net/minecraft/client/renderer/RenderTypes", "cutoutMipped",
            "()Lnet/minecraft/client/renderer/RenderType;"
        );
        
        transformer.registerMethodRedirect(
            "net/minecraft/client/renderer/RenderType", "translucent",
            "()Lnet/minecraft/client/renderer/RenderType;",
            "net/minecraft/client/renderer/RenderTypes", "translucent",
            "()Lnet/minecraft/client/renderer/RenderType;"
        );
        
        // Baked quad block_light/sky_light -> light_emission is a model-JSON change only.
    }

    @Override
    public String[] getShimClasses() {
        return new String[] {};
    }
}
