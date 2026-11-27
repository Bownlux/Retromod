// Compile-only stub of a 26.1 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.renderer.entity;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;

public abstract class EntityRenderer<T extends Entity, S extends EntityRenderState> {
    public final S createRenderState(T entity, float partialTick) { throw new UnsupportedOperationException(); }
    public boolean affectedByCulling(T entity) { throw new UnsupportedOperationException(); }
    public net.minecraft.world.phys.Vec3 getRenderOffset(S state) { throw new UnsupportedOperationException(); }
}
