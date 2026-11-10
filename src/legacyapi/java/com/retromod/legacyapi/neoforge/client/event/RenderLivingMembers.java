/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.client.event;

import com.retromod.legacyapi.minecraft.client.renderer.entity.RenderStateEntities;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.client.event.RenderLivingEvent;

/** NeoForge 1.21.1's {@code RenderLivingEvent.getEntity()}, on NeoForge hosts whose event carries a render state instead. */
public final class RenderLivingMembers {

    private RenderLivingMembers() {}

    /** The entity the event's render state was made from, or null when no entity in the client level matches it. */
    public static LivingEntity getEntity(RenderLivingEvent<?, ?, ?> event) {
        return RenderStateEntities.livingEntity(event.getRenderState());
    }
}
