/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.modelhost;

/** A 1.20.1 model on a base that draws itself, whose own draw needs something the host lacks. */
public class BrokenOldModel extends SelfDrawingModel {
    public int attempts;

    public void renderToBuffer(PoseStack pose, VertexConsumer consumer, int light, int overlay,
            float red, float green, float blue, float alpha) {
        attempts++;
        throw new NoSuchMethodError("a member the host removed");
    }
}
