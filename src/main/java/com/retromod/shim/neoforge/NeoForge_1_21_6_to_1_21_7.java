/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;

/** NeoForge 1.21.6 to 1.21.7: the two-way payload handler NeoForge removed. */
public class NeoForge_1_21_6_to_1_21_7 implements VersionShim {
    @Override public String getShimName() { return "NeoForge 1.21.6 to 1.21.7"; }
    @Override public String getSourceVersion() { return "1.21.6"; }
    @Override public String getTargetVersion() { return "1.21.7"; }
    @Override public String getModLoaderType() { return "neoforge"; }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // NeoForge 1.21.7 removed DirectionalPayloadHandler; a rebuilt one takes its place.
        LegacyPayloadHandlerBridge.register(transformer);
    }

    @Override public String[] getShimClasses() { return new String[0]; }
}
