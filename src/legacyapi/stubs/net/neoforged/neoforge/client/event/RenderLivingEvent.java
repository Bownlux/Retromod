// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.neoforge.client.event;

import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;

public abstract class RenderLivingEvent<T extends LivingEntity, S extends LivingEntityRenderState, M> {
    public S getRenderState() { throw new UnsupportedOperationException(); }
}
