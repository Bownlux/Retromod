/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.shieldhost;

/** NeoForge 1.21.11 to 26.3's LivingShieldBlockEvent: an int shield damage where -1 means the game's own amount. */
public class IntShieldEvent {
    public final LivingEntity entity;
    public final DamageContainer container;
    public final float blockedAmount;
    private int shieldDamage = -1;
    private final boolean originalBlocked;
    private boolean blocked;

    public IntShieldEvent(LivingEntity entity, DamageContainer container, float blockedAmount, boolean blocked) {
        this.entity = entity;
        this.container = container;
        this.blockedAmount = blockedAmount;
        this.originalBlocked = blocked;
        this.blocked = blocked;
    }

    public boolean getOriginalBlock() {
        return originalBlocked;
    }

    public int shieldDamage() {
        return shieldDamage;
    }

    public void setShieldDamage(int amount) {
        shieldDamage = amount;
    }

    public boolean getBlocked() {
        return blocked;
    }

    public void setBlocked(boolean blocked) {
        this.blocked = blocked;
    }
}
