// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraftforge.client.event;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraftforge.eventbus.api.bus.CancellableEventBus;
import net.minecraftforge.eventbus.api.bus.EventBus;
import net.minecraftforge.eventbus.api.event.RecordEvent;
import net.minecraftforge.eventbus.api.event.characteristic.Cancellable;

public interface RenderAvatarEvent {
    AvatarRenderState getState();
    PoseStack getPoseStack();

    final class Pre implements RenderAvatarEvent, RecordEvent, Cancellable {
        public static final CancellableEventBus<Pre> BUS = null;
        private Pre() {}
        @Override public AvatarRenderState getState() { throw new UnsupportedOperationException(); }
        @Override public PoseStack getPoseStack() { throw new UnsupportedOperationException(); }
    }

    final class Post implements RenderAvatarEvent, RecordEvent {
        public static final EventBus<Post> BUS = null;
        private Post() {}
        @Override public AvatarRenderState getState() { throw new UnsupportedOperationException(); }
        @Override public PoseStack getPoseStack() { throw new UnsupportedOperationException(); }
    }
}
