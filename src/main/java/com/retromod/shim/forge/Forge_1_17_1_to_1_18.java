/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;

/** Forge 1.17.1 to 1.18: extended world height (-64..320) plus the StructureFeature/Structure rename. */
public class Forge_1_17_1_to_1_18 implements VersionShim {

    @Override public String getShimName() { return "Forge 1.17.1 to 1.18"; }
    @Override public String getSourceVersion() { return "1.17.1"; }
    @Override public String getTargetVersion() { return "1.18"; }
    @Override public String getModLoaderType() { return "forge"; }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        transformer.registerClassRedirect(
            "net/minecraft/world/level/levelgen/feature/StructureFeature",
            "net/minecraft/world/level/levelgen/structure/Structure"
        );
        transformer.registerClassRedirect(
            "net/minecraft/world/level/biome/BiomeSource",
            "net/minecraft/world/level/biome/BiomeSource"
        );

        // World->Level rename
        transformer.registerClassRedirect(
            "net/minecraftforge/client/event/RenderWorldLastEvent",
            "net/minecraftforge/client/event/RenderLevelLastEvent"
        );
        // fmllegacy dropped in 1.18; RegistryObject moved back to registries
        transformer.registerClassRedirect(
            "net/minecraftforge/fmllegacy/RegistryObject",
            "net/minecraftforge/registries/RegistryObject"
        );
    }

    @Override
    public String[] getShimClasses() {
        return new String[0];
    }
}
