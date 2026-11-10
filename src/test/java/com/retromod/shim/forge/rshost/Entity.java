/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.rshost;

/** Stand-in for the host Entity: its interpolation fields, position and type. */
public class Entity {
    private static int nextId;
    public final int id = nextId++;
    public double xOld;
    public double yOld;
    public double zOld;
    public int tickCount;
    private final EntityType type;
    private double x;
    private double y;
    private double z;

    public Entity(EntityType type, double x, double y, double z) {
        this.type = type;
        this.x = x;
        this.y = y;
        this.z = z;
        this.xOld = x;
        this.yOld = y;
        this.zOld = z;
    }

    /** One tick of movement: the old position becomes where the entity was. */
    public void tickTo(double nextX, double nextY, double nextZ) {
        xOld = x;
        yOld = y;
        zOld = z;
        x = nextX;
        y = nextY;
        z = nextZ;
        tickCount++;
    }

    public EntityType getType() {
        return type;
    }

    public final double getX() {
        return x;
    }

    public final double getY() {
        return y;
    }

    public final double getZ() {
        return z;
    }
}
