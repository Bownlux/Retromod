/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

/**
 * Stand-in for the 1.12.2 {@code ConfigManager} behind {@code @Config} classes. Syncing is a
 * no-op, so every {@code @Config} field keeps the default it was initialized with.
 */
public final class LegacyConfigManager {

    private LegacyConfigManager() {}

    public static void sync(Object modid, Object type) {
        LegacyForgeEvents.note("1.12 config files (defaults are used)", modid);
    }

    public static boolean hasConfigForMod(Object modid) {
        return false;
    }

    public static void load(Object modid, Object type) {
    }
}
