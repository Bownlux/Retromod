package net.fabricmc.fabric.api.client.rendering.v1.hud;

import net.minecraft.class_332;
import net.minecraft.class_9779;

/** Compile-only copy of the Fabric API 0.128.2 (1.21.6) signature. */
@FunctionalInterface
public interface HudElement {
    void render(class_332 context, class_9779 tickCounter);
}
