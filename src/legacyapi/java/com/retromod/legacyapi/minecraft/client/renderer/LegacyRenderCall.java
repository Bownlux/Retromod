/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer;

/** The render-thread task Minecraft removed with RenderSystem.recordRenderCall; old lambdas implement this instead. */
@FunctionalInterface
public interface LegacyRenderCall {

    void execute();
}
