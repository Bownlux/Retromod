/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.shieldhost;

/** NeoForge 1.21.1's LivingShieldBlockEvent: a float shield damage that starts at the hit, and a cancel nothing reads. */
public class FloatShieldEvent implements ICancellableEvent {
    public final LivingEntity entity;
    public final DamageContainer container;
    private final float blockedDamage;
    private float shieldDamage;
    private final boolean originalBlocked;
    private boolean blocked;
    private boolean canceled;

    public FloatShieldEvent(LivingEntity entity, DamageContainer container, boolean blocked) {
        this.entity = entity;
        this.container = container;
        this.originalBlocked = blocked;
        this.blocked = blocked;
        this.blockedDamage = container.getNewDamage();
        this.shieldDamage = container.getNewDamage();
    }

    // A negative amount reads as the blocked damage, as NeoForge 1.21.1 does.
    public float shieldDamage() {
        if (!blocked) return 0;
        return shieldDamage >= 0 ? shieldDamage : getBlockedDamage();
    }

    public float getBlockedDamage() {
        return blockedDamage;
    }

    public boolean getOriginalBlock() {
        return originalBlocked;
    }

    public void setShieldDamage(float amount) {
        shieldDamage = amount;
    }

    public boolean getBlocked() {
        return blocked;
    }

    public void setBlocked(boolean blocked) {
        this.blocked = blocked;
    }

    @Override
    public void setCanceled(boolean canceled) {
        this.canceled = canceled;
    }

    @Override
    public boolean isCanceled() {
        return canceled;
    }
}
