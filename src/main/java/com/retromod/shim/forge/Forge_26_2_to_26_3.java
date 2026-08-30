/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;
import com.retromod.shim.common.Mc26_2To26_3CoreMoves;

/**
 * Forge 26.2 to 26.3 shim. Both versions are Mojang named, so this is class moves only
 * ({@link Mc26_2To26_3CoreMoves}), dominated by {@code com/mojang/blaze3d} becoming
 * {@code com/mojang/renderpearl}. Forge API renames land here once a Forge build targets 26.3.
 */
public class Forge_26_2_to_26_3 implements VersionShim {

    @Override
    public String getShimName() {
        return "Forge 26.2 to 26.3";
    }

    @Override
    public String getSourceVersion() {
        return "26.2";
    }

    @Override
    public String getTargetVersion() {
        return "26.3";
    }

    @Override
    public String getModLoaderType() {
        return "forge";
    }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        Mc26_2To26_3CoreMoves.register(transformer);
    }
}
