/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.client.color;

import com.retromod.shim.common.embedded.LegacyLog;

/** Sets up the old block and item color events for each old mod that uses them, on a NeoForge client only; safe on a server. */
public final class LegacyNeoForgeColors {

    // This class names no client class, so a dedicated server can load and run it.
    private static final String CLIENT_CLASS = "net.minecraft.client.Minecraft";
    private static volatile Boolean client;

    private LegacyNeoForgeColors() {}

    /** Called first in each old {@code @Mod} constructor of a jar that names the old color events or item colors. */
    public static void install() {
        if (!isClient()) return;
        try {
            // Client classes are reached only from here, so a dedicated server never loads them.
            LegacyColorEventPost.listen();
        } catch (RuntimeException | LinkageError failure) {
            LegacyLog.warn("Retromod did not set up the old block and item colors of the mod constructing now: " + failure);
        }
    }

    // initialize=false: a probe that ran a game class's static initializer this early broke other mods.
    private static boolean isClient() {
        Boolean known = client;
        if (known == null) {
            try {
                Class.forName(CLIENT_CLASS, false, LegacyNeoForgeColors.class.getClassLoader());
                known = Boolean.TRUE;
            } catch (ClassNotFoundException | LinkageError absent) {
                known = Boolean.FALSE;
            }
            client = known;
        }
        return known;
    }
}
