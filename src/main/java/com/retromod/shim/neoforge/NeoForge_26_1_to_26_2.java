/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;
import com.retromod.shim.common.Mc26_1To26_2CoreMoves;

/** NeoForge 26.1 to 26.2 shim: shared Mojang-name class moves only (no NeoForge-API renames yet). */
public class NeoForge_26_1_to_26_2 implements VersionShim {

    @Override
    public String getShimName() {
        return "NeoForge 26.1 to 26.2";
    }

    @Override
    public String getSourceVersion() {
        return "26.1";
    }

    @Override
    public String getTargetVersion() {
        return "26.2";
    }

    @Override
    public String getModLoaderType() {
        return "neoforge";
    }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        Mc26_1To26_2CoreMoves.register(transformer);
        // 26.2 made structure processor types map codecs, so a 1.21 mod's register event converts them too.
        // A chain that starts at 1.21 never passes the 1.20.5 step that registers the conversion otherwise.
        com.retromod.shim.common.LegacyRegistryMapCodecBridge.register(transformer);
    }
}
