// Compile-only stub of a 1.20.2 to 1.21.1 and 1.21.11 host type for src/legacyapi; never packaged.
package net.minecraft.client.gui;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceLocation;

public class GuiGraphics {
    // 1.20.2 to 1.21.1 shape.
    public void blit(ResourceLocation texture, int x, int y, int u, int v, int width, int height) { throw new UnsupportedOperationException(); }
    // 1.21.11 shape.
    public void blit(RenderPipeline pipeline, Identifier texture, int x, int y, float u, float v, int width, int height, int uWidth, int vHeight,
            int textureWidth, int textureHeight) { throw new UnsupportedOperationException(); }
}
