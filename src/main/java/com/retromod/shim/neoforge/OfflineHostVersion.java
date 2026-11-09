/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.RetromodVersion;
import com.retromod.util.McReflect;

/** Answers a host check offline, where the loader cannot be read: a NeoForge target from the release that made the change. */
final class OfflineHostVersion {

    private OfflineHostVersion() {}

    static boolean neoForgeFrom(String firstVersion) {
        String target = RetromodVersion.TARGET_MC_VERSION;
        return McReflect.isNeoForge() && target != null
                && RetromodVersion.compareMcVersions(target, firstVersion) >= 0;
    }
}
