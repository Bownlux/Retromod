/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.modelhost;

/** A 1.20.1 MCreator model: its only draw takes four floats, which it records. */
public class OldModel extends Model {
    public float[] drawn;

    public void renderToBuffer(PoseStack pose, VertexConsumer consumer, int light, int overlay,
            float red, float green, float blue, float alpha) {
        drawn = new float[]{red, green, blue, alpha};
    }

    // Compiled against 1.21.1 only so this stand-in links; the bridge adds the real one to an old model.
    @Override
    public void renderToBuffer(PoseStack pose, VertexConsumer consumer, int light, int overlay, int color) {
        throw new AssertionError("replaced in the test");
    }
}
