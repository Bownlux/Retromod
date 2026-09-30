/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;
import com.retromod.shim.common.Mc26_2To26_3CoreMoves;

/**
 * NeoForge 26.2 to 26.3 shim. Both versions are Mojang named, so this is class moves only
 * ({@link Mc26_2To26_3CoreMoves}), dominated by {@code com/mojang/blaze3d} becoming
 * {@code com/mojang/renderpearl}. NeoForge API renames land here once a NeoForge build targets 26.3.
 */
public class NeoForge_26_2_to_26_3 implements VersionShim {

    @Override
    public String getShimName() {
        return "NeoForge 26.2 to 26.3";
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
        return "neoforge";
    }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        Mc26_2To26_3CoreMoves.register(transformer);
        if (hasNewDatapackRegistryEvent()) {
            registerDatapackRegistryEventRename(transformer);
        }
    }

    private static final String REGISTRIES = "net/neoforged/neoforge/registries/";

    /**
     * Whether to use the new event. NeoForge replaced {@code DataPackRegistryEvent} partway through
     * the 26.3 betas (present on 26.3.0.3, gone by 26.3.0.23), so the Minecraft version cannot
     * decide it. A running host that still has the old class keeps it; every other case, including
     * an offline transform with no host to look at, takes the current name. Probed without
     * initializing anything. A jar transformed in place keeps the choice its host made, so updating
     * NeoForge across that change needs the mod transformed again.
     */
    private static boolean hasNewDatapackRegistryEvent() {
        return !com.retromod.util.McReflect.classExists(
                REGISTRIES.replace('/', '.') + "DataPackRegistryEvent");
    }

    /**
     * {@code DataPackRegistryEvent.NewRegistry} became {@code NewDatapackRegistryEvent}, and its
     * {@code dataPackRegistry} overloads became {@code worldRegistry} with identical descriptors.
     * A mod that adds a custom datapack registry otherwise dies with {@code NoClassDefFoundError}
     * when its listener registers.
     */
    static void registerDatapackRegistryEventRename(RetromodTransformer transformer) {
        String replacement = REGISTRIES + "NewDatapackRegistryEvent";
        transformer.registerClassRedirect(REGISTRIES + "DataPackRegistryEvent$NewRegistry", replacement);
        transformer.registerClassRedirect(REGISTRIES + "DataPackRegistryEvent", replacement);
        String key = "Lnet/minecraft/resources/ResourceKey;";
        String codec = "Lcom/mojang/serialization/Codec;";
        for (String desc : new String[]{
                "(" + key + codec + ")V",
                "(" + key + codec + codec + ")V",
                "(" + key + codec + codec + "Ljava/util/function/Consumer;)V"}) {
            transformer.registerMethodRedirect(replacement, "dataPackRegistry", desc,
                    replacement, "worldRegistry", desc);
        }
    }
}
