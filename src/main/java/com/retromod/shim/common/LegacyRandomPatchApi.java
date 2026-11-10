/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;

/** Rebuilds {@code RandomPatchConfiguration}, which 26.1 removed, inside each translated jar that names it. */
public final class LegacyRandomPatchApi {

    static final String OLD = "net/minecraft/world/level/levelgen/feature/configurations/RandomPatchConfiguration";
    static final String REBUILT = RebuiltApiClasses.ROOT
            + "minecraft/world/level/levelgen/feature/configurations/RandomPatchConfiguration";
    private static final String FEATURE = "net/minecraft/world/level/levelgen/feature/Feature";
    private static final String FEATURE_CONFIGURATION =
            "net/minecraft/world/level/levelgen/feature/configurations/FeatureConfiguration";

    private LegacyRandomPatchApi() {}

    /**
     * Whether the host lost the class but kept the configured feature system it belongs to: read
     * from the host when it can be, else 26.1 and 26.2. 26.3 folded every configuration into its
     * feature, so the rebuilt record would have no interface to implement there.
     */
    static boolean hostRemovedRandomPatch() {
        if (ClassResourceInspector.exists(FEATURE)) {
            return ClassResourceInspector.exists(FEATURE_CONFIGURATION) && !ClassResourceInspector.exists(OLD);
        }
        String target = RetromodVersion.TARGET_MC_VERSION;
        return RetromodVersion.compareMcVersions(target, "26.1") >= 0
                && RetromodVersion.compareMcVersions(target, "26.3") < 0;
    }

    public static void register(RetromodTransformer t) {
        if (!hostRemovedRandomPatch()) return;
        // A mod's own patch feature (Farmer's Delight's wild rice) is built with this codec while features register.
        RebuiltApiClasses.registerTree(t, REBUILT);
        t.registerClassRedirect(OLD, REBUILT);
    }
}
