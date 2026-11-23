/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;

/** Points old villager schedules, which 26.1 replaced with environment timelines, at rebuilt classes of the same shape. */
public final class LegacyScheduleBridge {

    static final String PACKAGE = "net/minecraft/world/entity/schedule/";
    static final String OLD = PACKAGE + "Schedule";
    static final String OLD_BUILDER = PACKAGE + "ScheduleBuilder";
    static final String REBUILT = RebuiltApiClasses.ROOT + "minecraft/world/entity/schedule/LegacySchedule";
    static final String REBUILT_BUILDER = RebuiltApiClasses.ROOT + "minecraft/world/entity/schedule/LegacyScheduleBuilder";
    private static final String BRAIN = "net/minecraft/world/entity/ai/Brain";

    private LegacyScheduleBridge() {}

    public static void register(RetromodTransformer t) {
        if (!hostRemovedIt(ClassResourceInspector.read(PACKAGE + "Activity") != null, ClassResourceInspector.read(OLD) != null)) {
            return;
        }
        RebuiltApiClasses.registerTree(t, REBUILT);
        RebuiltApiClasses.registerTree(t, REBUILT_BUILDER);
        t.registerClassRedirect(OLD, REBUILT);
        t.registerClassRedirect(OLD_BUILDER, REBUILT_BUILDER);
        t.registerClassRedirect(OLD_BUILDER + "$ActivityTransition", REBUILT_BUILDER + "$ActivityTransition");
        // A brain now follows the world's timelines; an old mod's own plan is accepted and not applied.
        for (String schedule : new String[]{OLD, REBUILT}) {
            t.registerMethodRedirect(BRAIN, "setSchedule", "(L" + schedule + ";)V",
                    REBUILT, "applyTo", "(Ljava/lang/Object;L" + REBUILT + ";)V", true);
        }
    }

    /** Whether the host has activities but no schedule class; offline, a target from 26.1. */
    static boolean hostRemovedIt(boolean hostReadable, boolean hostHasIt) {
        if (!hostReadable) return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.1") >= 0;
        return !hostHasIt;
    }
}
