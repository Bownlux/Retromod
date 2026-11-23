/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;

/** Points old uses of TryFindWaterGoal, which 26.3 removed, at a rebuilt goal of the same shape. */
public final class LegacyTryFindWaterGoalBridge {

    static final String OLD = "net/minecraft/world/entity/ai/goal/TryFindWaterGoal";
    static final String REBUILT = RebuiltApiClasses.ROOT + "minecraft/world/entity/ai/goal/LegacyTryFindWaterGoal";

    private LegacyTryFindWaterGoalBridge() {}

    public static void register(RetromodTransformer t) {
        if (!hostRemovedIt(ClassResourceInspector.read("net/minecraft/world/entity/ai/goal/Goal") != null,
                ClassResourceInspector.read(OLD) != null)) {
            return;
        }
        RebuiltApiClasses.registerTree(t, REBUILT);
        t.registerClassRedirect(OLD, REBUILT);
    }

    /** Whether the host has goals but not this one; offline, a target from 26.3. */
    static boolean hostRemovedIt(boolean hostReadable, boolean hostHasIt) {
        if (!hostReadable) return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.3") >= 0;
        return !hostHasIt;
    }
}
