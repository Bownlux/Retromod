package net.fabricmc.fabric.api.client.rendering.v1;

import net.fabricmc.fabric.api.event.Event;
import net.minecraft.class_332;

/** Compile-only copy of the Fabric API 0.92 (1.20.1) signature. */
@FunctionalInterface
public interface HudRenderCallback {
    Event<HudRenderCallback> EVENT = null;

    void onHudRender(class_332 drawContext, float tickDelta);
}
