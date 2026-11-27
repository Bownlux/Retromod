/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.modelhost;

/** Minecraft 1.21.1's Model: the draw takes the color as one ARGB int and is abstract. */
public abstract class Model {
    public abstract void renderToBuffer(PoseStack pose, VertexConsumer consumer, int light, int overlay, int color);
}
