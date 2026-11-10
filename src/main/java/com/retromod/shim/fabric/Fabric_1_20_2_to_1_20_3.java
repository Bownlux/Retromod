/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;

/** Shim for Fabric mods built for 1.20.2 running on 1.20.3. */
public class Fabric_1_20_2_to_1_20_3 implements VersionShim {

    @Override public String getShimName() { return "Fabric 1.20.2 to 1.20.3"; }
    @Override public String getSourceVersion() { return "1.20.2"; }
    @Override public String getTargetVersion() { return "1.20.3"; }
    @Override public String getModLoaderType() { return "fabric"; }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // GravelBlock merged into ColoredFallingBlock
        transformer.registerClassRedirect(
            "net/minecraft/world/level/block/GravelBlock",
            "net/minecraft/world/level/block/ColoredFallingBlock"
        );
        // SandBlock merged into ColoredFallingBlock
        transformer.registerClassRedirect(
            "net/minecraft/world/level/block/SandBlock",
            "net/minecraft/world/level/block/ColoredFallingBlock"
        );
        // 1.21.5 brought SandBlock back, so a mod built for it keeps the host class.
        transformer.limitClassRedirectToModsBefore("net/minecraft/world/level/block/SandBlock",
            com.retromod.shim.common.LegacyFallingBlockBridge.SAND_RETURNED);
        // FernBlock renamed to ShortPlantBlock
        transformer.registerClassRedirect(
            "net/minecraft/world/level/block/FernBlock",
            "net/minecraft/world/level/block/ShortPlantBlock"
        );
        // Entity.getTeam() returns PlayerTeam from 1.20.3. A 26.x host takes the Mojang-named bridge from the 26.1 shim.
        if (!com.retromod.core.RetromodVersion.isUnobfuscatedTarget(com.retromod.core.RetromodVersion.TARGET_MC_VERSION)) {
            com.retromod.shim.common.LegacyEntityTeamBridge.registerIntermediary(transformer);
        }
    }

    @Override
    public String[] getShimClasses() {
        return new String[0];
    }
}
