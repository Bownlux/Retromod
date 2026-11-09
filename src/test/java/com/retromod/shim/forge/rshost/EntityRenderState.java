/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.rshost;

/** Stand-in for the host EntityRenderState fields an extraction fills. */
public class EntityRenderState {
    public EntityType entityType;
    public double x;
    public double y;
    public double z;
    public float ageInTicks;

    /** What the host extraction writes for the entity at one partial tick. */
    public static EntityRenderState of(Entity entity, float partialTick) {
        EntityRenderState state = new EntityRenderState();
        state.entityType = entity.getType();
        state.x = entity.xOld + partialTick * (entity.getX() - entity.xOld);
        state.y = entity.yOld + partialTick * (entity.getY() - entity.yOld);
        state.z = entity.zOld + partialTick * (entity.getZ() - entity.zOld);
        state.ageInTicks = entity.tickCount + partialTick;
        return state;
    }
}
