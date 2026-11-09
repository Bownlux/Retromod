// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraftforge.client.event;

import java.util.Set;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.PlayerModelType;

public interface EntityRenderersEvent {
    final class AddLayers implements EntityRenderersEvent {
        public Set<PlayerModelType> getModelTypes() { throw new UnsupportedOperationException(); }
        public <R extends EntityRenderer<?, ?>> R getPlayerRenderer(PlayerModelType type) { throw new UnsupportedOperationException(); }
        public <T extends LivingEntity, S extends EntityRenderState, R extends EntityRenderer<T, S>> R getEntityRenderer(EntityType<? extends T> type) {
            throw new UnsupportedOperationException();
        }
    }
}
