/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;

/** Forge 1.18.1 to 1.18.2: the PlacedFeature.place() signature change has no bridge. */
public class Forge_1_18_1_to_1_18_2 implements VersionShim {

    @Override public String getShimName() { return "Forge 1.18.1 to 1.18.2"; }
    @Override public String getSourceVersion() { return "1.18.1"; }
    @Override public String getTargetVersion() { return "1.18.2"; }
    @Override public String getModLoaderType() { return "forge"; }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
    }

    @Override
    public String[] getShimClasses() {
        return new String[0];
    }
}
