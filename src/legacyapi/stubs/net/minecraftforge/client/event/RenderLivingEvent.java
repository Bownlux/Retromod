// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraftforge.client.event;

import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;

public interface RenderLivingEvent<T extends LivingEntity, S extends LivingEntityRenderState, M> {
    S getState();
}
