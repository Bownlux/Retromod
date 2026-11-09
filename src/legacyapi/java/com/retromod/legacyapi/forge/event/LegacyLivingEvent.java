/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.event;

import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingEvent;

/** Forge 1.20.1's {@code LivingEvent} class, rebuilt for mod events that extend it, such as Curios' slot events. */
public class LegacyLivingEvent extends LegacyEntityEvent implements LivingEvent {

    public LegacyLivingEvent(LivingEntity entity) {
        super(entity);
    }

    @Override
    public LivingEntity getEntity() {
        return (LivingEntity) super.getEntity();
    }
}
