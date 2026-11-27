/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.modelhost;

/** A 1.21.1 model base that draws itself, as HumanoidModel does; it records the color it drew with. */
public class SelfDrawingModel extends Model {
    public int drawnColor;

    @Override
    public void renderToBuffer(PoseStack pose, VertexConsumer consumer, int light, int overlay, int color) {
        drawnColor = color;
    }
}
