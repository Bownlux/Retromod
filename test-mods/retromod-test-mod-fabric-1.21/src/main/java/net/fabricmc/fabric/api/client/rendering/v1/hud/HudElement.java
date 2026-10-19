/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package net.fabricmc.fabric.api.client.rendering.v1.hud;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

/** Compile-only copy of the Fabric API 0.128.2 (1.21.6) signature; the jar task leaves it out. */
@FunctionalInterface
public interface HudElement {
    void render(DrawContext context, RenderTickCounter tickCounter);
}
