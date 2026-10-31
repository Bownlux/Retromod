/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.neoforge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;

/** NeoForge 1.20.2 to 1.20.3: the block constructors that 1.20.3 reshaped for block codecs. */
public class NeoForge_1_20_2_to_1_20_3 implements VersionShim {

    @Override public String getShimName() { return "NeoForge 1.20.2 to 1.20.3"; }
    @Override public String getSourceVersion() { return "1.20.2"; }
    @Override public String getTargetVersion() { return "1.20.3"; }
    @Override public String getModLoaderType() { return "neoforge"; }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        com.retromod.shim.common.LegacyBlockConstructorBridge.register(transformer);
        // The same release put doors, trapdoors, fence gates and pressure plates' type ahead of their properties.
        com.retromod.shim.common.LegacyBlockSetTypeBridge.register(transformer);
    }

    @Override
    public String[] getShimClasses() {
        return new String[0];
    }
}
