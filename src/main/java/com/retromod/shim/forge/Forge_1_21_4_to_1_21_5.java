/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;

public class Forge_1_21_4_to_1_21_5 implements VersionShim {
    @Override public String getShimName() { return "Forge 1.21.4 to 1.21.5"; }
    @Override public String getSourceVersion() { return "1.21.4"; }
    @Override public String getTargetVersion() { return "1.21.5"; }
    @Override public String getModLoaderType() { return "forge"; }
    @Override public void registerRedirects(RetromodTransformer transformer) {
        // Vanilla item bases removed in 1.21.5, rebased only on hosts that lost them.
        com.retromod.shim.common.RemovedItemBaseBridge.registerRemovedIn(transformer, "1.21.5");
        // appendHoverText moved to TooltipDisplay + Consumer; keep old tooltip overrides called.
        com.retromod.shim.common.LegacyTooltipSynthetic.register(transformer);
        // LeavesBlock became abstract with a (float, Properties) constructor.
        com.retromod.shim.common.LegacyLeavesBlockBridge.register(transformer);
        com.retromod.shim.common.LegacyMobEffectNames.register(transformer);
    }
    @Override public String[] getShimClasses() { return new String[0]; }
}
