package net.fabricmc.fabric.api.client.rendering.v1;

import net.fabricmc.fabric.api.event.Event;
import net.minecraft.class_332;
import net.minecraft.class_9779;

/** Compile-only copy of the Fabric API 0.116 (1.21.1) signature. */
@FunctionalInterface
public interface HudRenderCallback {
    Event<HudRenderCallback> EVENT = null;

    void onHudRender(class_332 drawContext, class_9779 tickCounter);
}
