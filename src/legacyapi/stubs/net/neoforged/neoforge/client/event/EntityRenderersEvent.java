// Compile-only stub of a NeoForge 1.21.11 to 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.neoforge.client.event;

import java.util.Set;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.PlayerModelType;

public abstract class EntityRenderersEvent extends net.neoforged.bus.api.Event {
    public static class AddLayers extends EntityRenderersEvent {
        public Set<PlayerModelType> getSkins() { throw new UnsupportedOperationException(); }
        public <R extends AvatarRenderer<?>> R getPlayerRenderer(PlayerModelType type) { throw new UnsupportedOperationException(); }
        public <T extends Entity, R extends EntityRenderer<T, ?>> R getRenderer(EntityType<? extends T> type) { throw new UnsupportedOperationException(); }
    }
}
