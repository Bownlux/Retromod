/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;

/** Fabric 1.15.2 to 1.16.5: dimension rework plus Fabric API churn. */
public class Fabric_1_15_2_to_1_16_5 implements VersionShim {

    @Override public String getShimName() { return "Fabric 1.15.2 to 1.16.5"; }
    @Override public String getSourceVersion() { return "1.15.2"; }
    @Override public String getTargetVersion() { return "1.16.5"; }
    @Override public String getModLoaderType() { return "fabric"; }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // Yarn's world/dimension/DimensionType is world/level/dimension/DimensionType in Mojang
        // naming. This used to name the source on both sides, which registered a no-op.
        transformer.registerClassRedirect(
            "net/minecraft/world/dimension/DimensionType",
            "net/minecraft/world/level/dimension/DimensionType"
        );
        // getDimension() became RegistryKey-based
        transformer.registerMethodRedirect(
            "net/minecraft/world/World", "getDimension",
            "()Lnet/minecraft/world/dimension/DimensionType;",
            "com/retromod/shim/fabric/embedded/DimensionShim", "getDimension",
            "(Ljava/lang/Object;)Ljava/lang/Object;"
        );
        transformer.registerMethodRedirect(
            "net/minecraft/world/World", "getDimensionRegistryKey",
            "()Lnet/minecraft/util/registry/RegistryKey;",
            "net/minecraft/world/World", "getRegistryKey",
            "()Lnet/minecraft/util/registry/RegistryKey;"
        );
        transformer.registerMethodRedirect(
            "net/minecraft/world/biome/Biome", "getTemperature",
            "()F",
            "com/retromod/shim/fabric/embedded/BiomeShim", "getTemperature",
            "(Ljava/lang/Object;)F"
        );
        transformer.registerMethodRedirect(
            "net/minecraft/recipe/RecipeType", "register",
            "(Ljava/lang/String;)Lnet/minecraft/recipe/RecipeType;",
            "com/retromod/shim/fabric/embedded/RecipeShim", "register",
            "(Ljava/lang/String;)Ljava/lang/Object;"
        );
    }

    @Override
    public String[] getShimClasses() {
        return new String[0];
    }
}
