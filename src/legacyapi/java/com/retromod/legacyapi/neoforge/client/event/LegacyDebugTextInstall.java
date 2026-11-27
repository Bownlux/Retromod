/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.client.event;

import com.retromod.shim.common.embedded.LegacyLog;

/** Sets up the old debug text event for a mod that uses it, on a NeoForge 26.x client only; safe on a server. */
public final class LegacyDebugTextInstall {

    // This class names no client class, so a dedicated server can load and run it.
    private static final String CLIENT_CLASS = "net.minecraft.client.Minecraft";

    private LegacyDebugTextInstall() {}

    /** Called first in each old {@code @Mod} constructor of a jar that names the old debug text event. */
    public static void install() {
        try {
            // initialize=false: a probe that ran a game class's static initializer this early broke other mods.
            Class.forName(CLIENT_CLASS, false, LegacyDebugTextInstall.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError server) {
            return;
        }
        try {
            LegacyDebugTextEntry.listen();
        } catch (RuntimeException | LinkageError failure) {
            LegacyLog.warn("Retromod did not set up the old debug screen text of the mod constructing now: " + failure);
        }
    }
}
