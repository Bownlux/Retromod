/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.common.RebuiltApiClasses;

import java.util.List;

/** Rebuilds Forge's DistExecutor, which Forge 26.2 removed, for old mods that pick client or server code with it. */
public final class LegacyDistExecutorBridge {

    static final String DIST_EXECUTOR = "net/minecraftforge/fml/DistExecutor";
    static final String REBUILT = RebuiltApiClasses.ROOT + "forge/fml/DistExecutor";
    private static final List<String> MARKERS = List.of("SafeReferent", "SafeRunnable", "SafeCallable", "SafeSupplier");

    private LegacyDistExecutorBridge() {}

    /** Registers the rebuilt class where the Forge host lacks DistExecutor; offline, a Forge target from 26.2. */
    public static void register(RetromodTransformer t) {
        // Forge 1.21.11 still ships it, and mods on a host that has it keep sharing the real class.
        if (!ForgeHostShape.lacks(DIST_EXECUTOR)) return;
        RebuiltApiClasses.registerTree(t, REBUILT);
        t.registerClassRedirect(DIST_EXECUTOR, REBUILT);
        // The safe forms take lambdas that implement these markers, so they move with the class.
        for (String marker : MARKERS) {
            t.registerClassRedirect(DIST_EXECUTOR + "$" + marker, REBUILT + "$" + marker);
        }
    }
}
