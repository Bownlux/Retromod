/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;

/** Points old entities that implement VariantHolder, which newer Minecraft removed, at a rebuilt interface. */
public final class LegacyVariantHolderBridge {

    static final String OLD = "net/minecraft/world/entity/VariantHolder";
    static final String REBUILT = RebuiltApiClasses.ROOT + "minecraft/world/entity/LegacyVariantHolder";

    private LegacyVariantHolderBridge() {}

    /** Registers only where the host can be read and has entities but no VariantHolder. */
    public static void register(RetromodTransformer t) {
        if (!hostRemovedIt(ClassResourceInspector.read("net/minecraft/world/entity/Entity") != null,
                ClassResourceInspector.read(OLD) != null)) {
            return;
        }
        RebuiltApiClasses.registerTree(t, REBUILT);
        t.registerClassRedirect(OLD, REBUILT);
    }

    /** Offline, a target from 1.21.5, the first release whose mappings lack VariantHolder. */
    static boolean hostRemovedIt(boolean hostReadable, boolean hostHasIt) {
        if (!hostReadable) {
            return com.retromod.core.RetromodVersion.compareMcVersions(com.retromod.core.RetromodVersion.TARGET_MC_VERSION, "1.21.5") >= 0;
        }
        return !hostHasIt;
    }
}
