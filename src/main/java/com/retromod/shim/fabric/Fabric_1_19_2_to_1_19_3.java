/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;

/** Fabric 1.19.2 to 1.19.3: registry package move, creative tab rework, DrawContext, feature flags. */
public class Fabric_1_19_2_to_1_19_3 implements VersionShim {

    @Override public String getShimName() { return "Fabric 1.19.2 to 1.19.3"; }
    @Override public String getSourceVersion() { return "1.19.2"; }
    @Override public String getTargetVersion() { return "1.19.3"; }
    @Override public String getModLoaderType() { return "fabric"; }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // Registry package moved
        transformer.registerClassRedirect(
            "net/minecraft/util/registry/Registry",
            "net/minecraft/registry/Registries"
        );
        transformer.registerClassRedirect(
            "net/minecraft/util/registry/BuiltinRegistries",
            "net/minecraft/registry/RegistryKeys"
        );
        transformer.registerClassRedirect(
            "net/minecraft/util/registry/RegistryEntry",
            "net/minecraft/registry/entry/RegistryEntry"
        );
        transformer.registerClassRedirect(
            "net/minecraft/util/registry/RegistryKey",
            "net/minecraft/registry/RegistryKey"
        );
        // Creative tab API rework
        transformer.registerMethodRedirect(
            "net/minecraft/item/ItemGroup", "builder",
            "(Lnet/minecraft/item/ItemGroup$Row;I)Lnet/minecraft/item/ItemGroup$Builder;",
            "com/retromod/shim/fabric/embedded/ItemGroupShim", "builder",
            "(Ljava/lang/Object;I)Ljava/lang/Object;"
        );
        // DrawContext replaced the MatrixStack arg
        transformer.registerMethodRedirect(
            "net/minecraft/client/gui/screen/Screen", "renderBackground",
            "(Lnet/minecraft/client/util/math/MatrixStack;)V",
            "com/retromod/shim/fabric/embedded/ScreenShim", "renderBackground",
            "(Ljava/lang/Object;Ljava/lang/Object;)V"
        );
        // 1.19.3 gave the screen handler type's constructor a feature flag set.
        com.retromod.shim.common.LegacyMenuTypeBridge.register(transformer,
                com.retromod.core.RetromodVersion.isUnobfuscatedTarget(com.retromod.core.RetromodVersion.TARGET_MC_VERSION)
                        ? com.retromod.shim.common.LegacyMenuTypeBridge.MOJANG
                        : com.retromod.shim.common.LegacyMenuTypeBridge.INTERMEDIARY, null, null);
        // Feature flags
        transformer.registerClassRedirect(
            "net/minecraft/resource/featuretoggle/FeatureFlags",
            "net/minecraft/world/flag/FeatureFlags"
        );

        // Widget renamed to Renderable
        transformer.registerClassRedirect(
            "net/minecraft/client/gui/components/Widget",
            "net/minecraft/client/gui/components/Renderable"
        );
        // Loot table classes split into sub-providers
        transformer.registerClassRedirect(
            "net/minecraft/data/loot/BlockLoot",
            "net/minecraft/data/loot/BlockLootSubProvider"
        );
        transformer.registerClassRedirect(
            "net/minecraft/data/loot/EntityLoot",
            "net/minecraft/data/loot/EntityLootSubProvider"
        );
        // The registries and registry keys that left Registry, by the Mojang names a Fabric mod carries
        // only after the 26.1 remap, as the Forge 1.19.3 shim moves them.
        if (com.retromod.core.RetromodVersion.isUnobfuscatedTarget(com.retromod.core.RetromodVersion.TARGET_MC_VERSION)) {
            com.retromod.shim.forge.LegacyRegistryKeyMoves.register(transformer);
            // 1.19.3 replaced com.mojang.math's vectors, quaternions and matrices with JOML. After the 26.1 remap a
            // Fabric mod names them as a Forge mod does, so the same bridge applies.
            com.retromod.shim.common.LegacyMojangMathBridge.register(transformer);
        }
    }

    @Override
    public String[] getShimClasses() {
        return new String[0];
    }
}
