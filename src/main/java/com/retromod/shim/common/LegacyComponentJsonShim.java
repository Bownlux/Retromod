/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.MinecraftVersionedApiShim;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;

/** Keeps old JSON text calls working on Mojang-named hosts from 1.20.5, where {@code Component.Serializer} took registries. */
public class LegacyComponentJsonShim implements MinecraftVersionedApiShim {

    @Override
    public String getShimName() {
        return "Registry-Aware JSON Text";
    }

    @Override
    public String getSourceVersion() {
        return "1.20.4";
    }

    /** The serializer's statics take no registries below 1.20.5, so nothing is rewritten on those hosts. */
    @Override
    public String getTargetVersion() {
        return "1.20.5";
    }

    @Override
    public String getModLoaderType() {
        return "common";
    }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // Fabric before 26.1 runs intermediary names, which the bridge's intermediary copy and
        // Pre1_20_5ComponentJsonBridge answer.
        if (!LegacyEnchantmentRegistryShim.mojangNamedHost(RetromodVersion.TARGET_MC_VERSION)) return;
        LegacyComponentJsonBridge.register(transformer);
    }

    @Override
    public String[] getShimClasses() {
        return new String[0];
    }
}
