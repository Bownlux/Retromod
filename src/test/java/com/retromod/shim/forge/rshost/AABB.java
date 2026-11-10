/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.rshost;

/** Stand-in for the host AABB. */
public class AABB {
    final double minX;
    final double minY;
    final double minZ;
    final double maxX;
    final double maxY;
    final double maxZ;

    public AABB(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
    }

    public boolean contains(Entity entity) {
        return entity.getX() >= minX && entity.getX() <= maxX && entity.getY() >= minY && entity.getY() <= maxY
                && entity.getZ() >= minZ && entity.getZ() <= maxZ;
    }
}
