/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.neoforge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;

/** Updates NeoForge 1.21.5 mods for 1.21.6. */
public class NeoForge_1_21_5_to_1_21_6 implements VersionShim {
    @Override public String getShimName() { return "NeoForge 1.21.5 to 1.21.6"; }
    @Override public String getSourceVersion() { return "1.21.5"; }
    @Override public String getTargetVersion() { return "1.21.6"; }
    @Override public String getModLoaderType() { return "neoforge"; }
    
    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // ServerPlayer.server became private.
        com.retromod.shim.common.LegacyPlayerServerBridge.register(transformer);

        // INBTSerializable was renamed in 1.21.6
        transformer.registerClassRedirect(
            "net/neoforged/neoforge/common/util/INBTSerializable",
            "net/neoforged/neoforge/common/util/ValueIOSerializable"
        );

        // LayeredDraw was removed in 1.21.6; point refs at GuiComponent (a polyfill is needed for full compat)
        transformer.registerClassRedirect(
            "net/minecraft/client/gui/LayeredDraw",
            "net/minecraft/client/gui/GuiComponent"
        );
    }
    
    @Override public String[] getShimClasses() { return new String[0]; }
}
