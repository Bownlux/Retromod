/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.rshost;

/** Stand-in for the host LivingEntity. */
public class LivingEntity extends Entity {
    public LivingEntity(EntityType type, double x, double y, double z) {
        super(type, x, y, z);
    }
}
