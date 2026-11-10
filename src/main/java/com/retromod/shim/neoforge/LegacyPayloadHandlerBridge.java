/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.shim.common.RebuiltApiClasses;

/** Keeps a mod's two-way payload handler working after NeoForge 1.21.7 removed DirectionalPayloadHandler. */
public final class LegacyPayloadHandlerBridge {

    static final String HANDLING = "net/neoforged/neoforge/network/handling/";
    static final String OLD = HANDLING + "DirectionalPayloadHandler";
    static final String REBUILT = RebuiltApiClasses.ROOT + "neoforge/network/DirectionalPayloadHandler";

    private LegacyPayloadHandlerBridge() {}

    public static void register(RetromodTransformer t) {
        if (!hostRemovedIt()) return;
        // Tom's Storage hands one to playBidirectional, which still takes any payload handler.
        RebuiltApiClasses.registerTree(t, REBUILT);
        t.registerClassRedirect(OLD, REBUILT);
    }

    /** Read from the host's payload API; offline, NeoForge targets from 1.21.7 lack it. */
    static boolean hostRemovedIt() {
        if (ClassResourceInspector.read(HANDLING + "IPayloadHandler") == null) {
            return OfflineHostVersion.neoForgeFrom("1.21.7");
        }
        return !ClassResourceInspector.exists(OLD);
    }
}
