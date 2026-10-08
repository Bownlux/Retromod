/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

/** Stand-in for the 1.12.2 {@code NetworkRegistry.TargetPoint}, the area of a dropped send. */
public final class LegacyTargetPoint {

    public final int dimension;
    public final double x;
    public final double y;
    public final double z;
    public final double range;

    public LegacyTargetPoint(int dimension, double x, double y, double z, double range) {
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
        this.range = range;
    }
}
