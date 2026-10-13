/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;

/** Forge 1.16.5 to 1.17: the tag rework dropped getAllTags(). */
public class Forge_1_16_5_to_1_17 implements VersionShim {

    @Override public String getShimName() { return "Forge 1.16.5 to 1.17"; }
    @Override public String getSourceVersion() { return "1.16.5"; }
    @Override public String getTargetVersion() { return "1.17"; }
    @Override public String getModLoaderType() { return "forge"; }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // 1.16.5 still uses the legacy Forge/MCP class namespace. Reuse the host-validated
        // direct move table so the entire package migration runs, not only member shims.
        new Forge_1_12_2_to_1_13_2().loadDirectClassMoves(transformer);
        registerConfigMoves(transformer);
        transformer.registerClassRedirect(
            "net/minecraft/block/FlowingFluidBlock",
            "net/minecraft/world/level/block/LiquidBlock"
        );
        String properties = "net/minecraft/world/level/block/state/BlockBehaviour$Properties";
        String result = "L" + properties + ";";
        transformer.registerArgDropMethodRedirect(
            properties, "of",
            "(Lnet/minecraft/block/material/Material;)" + result,
            properties, "of", "()" + result
        );
        transformer.registerArgDropMethodRedirect(
            properties, "of",
            "(Lnet/minecraft/block/material/Material;"
                    + "Lnet/minecraft/world/level/material/MapColor;)" + result,
            properties, "of", "()" + result
        );
        transformer.registerMethodRedirect(
            "net/minecraft/tags/BlockTags", "getAllTags",
            "()Lnet/minecraft/tags/TagCollection;",
            "com/retromod/shim/forge/embedded/TagShim", "getBlockTags",
            "()Ljava/lang/Object;"
        );
        transformer.registerMethodRedirect(
            "net/minecraft/tags/ItemTags", "getAllTags",
            "()Lnet/minecraft/tags/TagCollection;",
            "com/retromod/shim/forge/embedded/TagShim", "getItemTags",
            "()Ljava/lang/Object;"
        );
        transformer.registerMethodRedirect(
            "net/minecraftforge/event/RegistryEvent$Register", "getRegistry",
            "()Lnet/minecraftforge/registries/IForgeRegistry;",
            "com/retromod/shim/forge/embedded/RegistryShim", "getRegistry",
            "(Ljava/lang/Object;)Ljava/lang/Object;"
        );
    }

    /**
     * 1.17 moved the config events out of {@code ModConfig} and typed {@code registerConfig}'s spec
     * as {@code IConfigSpec}, so a 1.16 mod's config registration failed with
     * {@code NoSuchMethodError}. On NeoForge the later config bridge takes the call from here.
     */
    private static void registerConfigMoves(RetromodTransformer transformer) {
        String oldEvent = "net/minecraftforge/fml/config/ModConfig$";
        String newEvent = "net/minecraftforge/fml/event/config/ModConfigEvent";
        transformer.registerClassRedirect(oldEvent + "ModConfigEvent", newEvent);
        transformer.registerClassRedirect(oldEvent + "Loading", newEvent + "$Loading");
        transformer.registerClassRedirect(oldEvent + "Reloading", newEvent + "$Reloading");

        String context = "net/minecraftforge/fml/ModLoadingContext";
        String type = "Lnet/minecraftforge/fml/config/ModConfig$Type;";
        String oldSpec = "Lnet/minecraftforge/common/ForgeConfigSpec;";
        String spec = "Lnet/minecraftforge/fml/config/IConfigSpec;";
        for (String fileName : new String[] {"", "Ljava/lang/String;"}) {
            transformer.registerMethodRedirect(context, "registerConfig", "(" + type + oldSpec + fileName + ")V",
                    context, "registerConfig", "(" + type + spec + fileName + ")V");
        }
    }

    @Override
    public String[] getShimClasses() {
        return new String[0];
    }
}
