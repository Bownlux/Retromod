/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.modelhost;

/** Minecraft 1.21.1's final ModelPart, whose draw takes one ARGB int; it records the last one. */
public final class ModelPart {
    public int drawnColor;

    public void render(PoseStack pose, VertexConsumer consumer, int light, int overlay, int color) {
        drawnColor = color;
    }
}
