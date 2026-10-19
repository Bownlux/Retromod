/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;

/** Updates Forge 1.21.5 mods for 1.21.6. */
public class Forge_1_21_5_to_1_21_6 implements VersionShim {
    @Override public String getShimName() { return "Forge 1.21.5 to 1.21.6"; }
    @Override public String getSourceVersion() { return "1.21.5"; }
    @Override public String getTargetVersion() { return "1.21.6"; }
    @Override public String getModLoaderType() { return "forge"; }
    @Override public void registerRedirects(RetromodTransformer transformer) {
        // ServerPlayer.server became private.
        com.retromod.shim.common.LegacyPlayerServerBridge.register(transformer);
    }
    @Override public String[] getShimClasses() { return new String[0]; }
}
