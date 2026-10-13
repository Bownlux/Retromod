/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

/**
 * Stands in for 1.12.2's {@code DimensionManager}. 1.16 made dimensions data: a level stem in a
 * data pack, not a numbered dimension with a {@code WorldProvider} class. Registering a 1.12
 * dimension is accepted and does nothing, so the mod loads without its dimension.
 *
 * <p>Calls on this class are erased to {@code Object}.
 */
public final class LegacyDimensionManager {
    private LegacyDimensionManager() {}

    /** {@code registerDimension(id, type)}. */
    public static void registerDimension(int id, Object type) {
    }

    /** {@code unregisterDimension(id)}. */
    public static void unregisterDimension(int id) {
    }

    /** {@code isDimensionRegistered(id)}: only the vanilla three exist. */
    public static boolean isDimensionRegistered(int id) {
        return id >= -1 && id <= 1;
    }

    /** {@code getNextFreeDimId()}. */
    public static int getNextFreeDimId() {
        return 2;
    }

    /** {@code getWorld(id)}: numbered dimensions have no level to look up. */
    public static Object getWorld(int id) {
        return null;
    }

    /** {@code getProviderType(id)}. */
    public static Object getProviderType(int id) {
        return null;
    }

    /** {@code getStaticDimensionIDs()}. */
    public static Object getStaticDimensionIDs() {
        return new Integer[] {0, -1, 1};
    }
}
