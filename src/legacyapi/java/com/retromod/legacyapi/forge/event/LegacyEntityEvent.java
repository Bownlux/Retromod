/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.event;

import com.retromod.legacyapi.forge.eventbus.LegacyEvent;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.entity.EntityEvent;

/** Forge 1.20.1's {@code EntityEvent} class, which Forge 26.2 made an interface, rebuilt for mod events that extend it. */
public class LegacyEntityEvent extends LegacyEvent implements EntityEvent {

    private final Entity entity;

    public LegacyEntityEvent(Entity entity) {
        this.entity = entity;
    }

    @Override
    public Entity getEntity() {
        return entity;
    }
}
