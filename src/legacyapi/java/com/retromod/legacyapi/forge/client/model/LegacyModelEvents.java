/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.model;

import com.retromod.legacyapi.forge.client.LegacyClientRenderBootstrap;
import com.retromod.shim.common.embedded.LegacyLog;

/** Sets up Forge 1.20.1's model events for each old mod that uses them, on a client only; safe to call on a server. */
public final class LegacyModelEvents {

    private static boolean attached;

    private LegacyModelEvents() {}

    /** Called first in each old {@code @Mod} constructor of a jar that uses the old model API. */
    public static void install() {
        LegacyClientRenderBootstrap.install();
        synchronized (LegacyModelEvents.class) {
            if (attached || !LegacyClientRenderBootstrap.isClient()) return;
            attached = true;
        }
        try {
            // The listeners name client classes, so a dedicated server never reaches them.
            LegacyModelEventHooks.attach();
        } catch (RuntimeException | LinkageError failure) {
            LegacyLog.warn("Retromod did not set up the old model events of " + LegacyClientRenderBootstrap.namespaces()
                    + ": " + failure);
        }
    }
}
