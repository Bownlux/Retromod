/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;

public class Forge_1_21_1_to_1_21_2 implements VersionShim {
    @Override public String getShimName() { return "Forge 1.21.1 to 1.21.2"; }
    @Override public String getSourceVersion() { return "1.21.1"; }
    @Override public String getTargetVersion() { return "1.21.2"; }
    @Override public String getModLoaderType() { return "forge"; }
    @Override public void registerRedirects(RetromodTransformer transformer) {
        // Same vanilla 1.21.2 changes as the NeoForge shim; Forge mods use the same Mojang names.
        com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves
            .registerMojangItemInteractionResultBridge(transformer);
        com.retromod.shim.common.LegacyBlockApiSynthetic.register(transformer);
    }
    @Override public String[] getShimClasses() { return new String[0]; }
}
