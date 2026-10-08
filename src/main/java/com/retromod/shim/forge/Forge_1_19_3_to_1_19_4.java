/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;

/** Forge 1.19.3 to 1.19.4: DamageSource's string ctor became registry-driven DamageType, and GENERIC/FALL were removed. */
public class Forge_1_19_3_to_1_19_4 implements VersionShim {

    @Override public String getShimName() { return "Forge 1.19.3 to 1.19.4"; }
    @Override public String getSourceVersion() { return "1.19.3"; }
    @Override public String getTargetVersion() { return "1.19.4"; }
    @Override public String getModLoaderType() { return "forge"; }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // Vanilla removals, so they apply on Forge and NeoForge alike.
        com.retromod.shim.common.LegacyButtonBlockBridge.register(transformer);
        com.retromod.shim.common.LegacyBlockSetTypeBridge.register(transformer);
        // Limb swing, the text display mode and the float inverse square root, read by renderers.
        com.retromod.shim.common.LegacyClientRenderBridge.register(transformer);
        // 1.20.5 made ArmorMaterial a registered record, which no bridge can make a mod implement.
        if (com.retromod.core.RetromodVersion.compareMcVersions(
                com.retromod.core.RetromodVersion.TARGET_MC_VERSION, "1.20.5") < 0) {
            com.retromod.shim.common.LegacyArmorBridge.register(transformer);
        }
        com.retromod.shim.common.LegacyDamageSourceBridge.register(transformer);
        // The DamageSource(String) constructor, the static sources, factories and flag predicates.
        com.retromod.shim.common.LegacyDamageConstantBridge.register(transformer);
        // 1.19.4 removed the rounding BlockPos constructors in favor of BlockPos.containing.
        String blockPos = "net/minecraft/core/BlockPos";
        transformer.registerConstructorRedirect(blockPos, "(DDD)V",
                blockPos, "containing", "(DDD)L" + blockPos + ";");
        for (String position : new String[]{"net/minecraft/world/phys/Vec3", "net/minecraft/core/Position"}) {
            transformer.registerConstructorRedirect(blockPos, "(L" + position + ";)V",
                    blockPos, "containing", "(Lnet/minecraft/core/Position;)L" + blockPos + ";");
        }
        LegacyWorldgenBridge.registerPrecipitationBridge(transformer);
    }

    @Override
    public String[] getShimClasses() {
        return new String[0];
    }
}
