/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.event;

import com.retromod.legacyapi.minecraft.client.renderer.entity.RenderStateEntities;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.client.event.RenderLivingEvent;

/** Forge 1.20.1's {@code RenderLivingEvent.getEntity()}, on Forge hosts whose event carries a render state instead. */
public final class RenderLivingMembers {

    private RenderLivingMembers() {}

    /** The entity the event's render state was made from, or null when no entity in the client level matches it. */
    public static LivingEntity getEntity(RenderLivingEvent<?, ?, ?> event) {
        return RenderStateEntities.livingEntity(event.getState());
    }
}
