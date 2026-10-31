/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.McReflect;

/** Runs Forge 1.20.1 SimpleChannel networking on Forge 26.x, through the channels Forge 26.x builds itself. */
public final class LegacyForgeNetworkBridge {

    static final String OLD_CHANNEL = "net/minecraftforge/network/simple/SimpleChannel";

    private LegacyForgeNetworkBridge() {}

    public static void register(RetromodTransformer t) {
        // The old API surface goes to NetworkShim, which opens a Forge channel for each old one.
        if (hostDroppedOldChannels()) Forge_1_20_to_NeoForge_1_21.registerNetworkBridge(t);
    }

    /** Read from the host when it can be; offline, a Forge target from 26.1, the first checked without it. */
    static boolean hostDroppedOldChannels() {
        if (McReflect.isNeoForge()) return false;
        if (ClassResourceInspector.exists("net/minecraftforge/network/ChannelBuilder")) {
            return !ClassResourceInspector.exists(OLD_CHANNEL);
        }
        return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.1") >= 0;
    }
}
