// Compile-only stub of the MultiBufferSource stand-in RenderBufferSynthetics generates, for src/legacyapi; never packaged.
package com.retromod.shim.common.embedded;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.rendertype.RenderType;

public interface MultiBufferSource {
    VertexConsumer getBuffer(RenderType renderType);
}
