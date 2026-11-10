/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.nbthost;

/** Stands in for Forge's server hooks; a test sets the running server. */
public class ServerLifecycleHooks {
    public static MinecraftServer current;

    public static MinecraftServer getCurrentServer() {
        return current;
    }
}
