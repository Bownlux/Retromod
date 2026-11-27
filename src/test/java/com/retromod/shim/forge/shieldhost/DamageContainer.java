/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.shieldhost;

/** Stands in for NeoForge's DamageContainer: the source and the damage still to apply. */
public class DamageContainer {
    public final DamageSource source;
    private final float newDamage;

    public DamageContainer(DamageSource source, float amount) {
        this.source = source;
        this.newDamage = amount;
    }

    public float getNewDamage() {
        return newDamage;
    }
}
