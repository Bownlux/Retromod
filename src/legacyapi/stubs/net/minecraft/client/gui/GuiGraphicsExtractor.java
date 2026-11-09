// Compile-only stub of a 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.gui;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3x2fStack;

import java.util.List;

public class GuiGraphicsExtractor {
    public int guiWidth() { throw new UnsupportedOperationException(); }
    public int guiHeight() { throw new UnsupportedOperationException(); }
    public Matrix3x2fStack pose() { throw new UnsupportedOperationException(); }
    public void horizontalLine(int x0, int x1, int y, int col) { throw new UnsupportedOperationException(); }
    public void verticalLine(int x, int y0, int y1, int col) { throw new UnsupportedOperationException(); }
    public void fill(int x0, int y0, int x1, int y1, int col) { throw new UnsupportedOperationException(); }
    public void fill(RenderPipeline pipeline, int x0, int y0, int x1, int y1, int col) { throw new UnsupportedOperationException(); }
    public void fillGradient(int x0, int y0, int x1, int y1, int col1, int col2) { throw new UnsupportedOperationException(); }
    public void text(Font font, String text, int x, int y, int col, boolean dropShadow) { throw new UnsupportedOperationException(); }
    public void text(Font font, FormattedCharSequence text, int x, int y, int col, boolean dropShadow) { throw new UnsupportedOperationException(); }
    public void blitSprite(RenderPipeline renderPipeline, Identifier location, int x, int y, int width, int height) { throw new UnsupportedOperationException(); }
    public void blitSprite(RenderPipeline renderPipeline, Identifier location, int spriteWidth, int spriteHeight, int textureX, int textureY,
            int x, int y, int width, int height) { throw new UnsupportedOperationException(); }
    public void blitSprite(RenderPipeline renderPipeline, TextureAtlasSprite sprite, int x, int y, int width, int height) { throw new UnsupportedOperationException(); }
    public void blitSprite(RenderPipeline renderPipeline, TextureAtlasSprite sprite, int x, int y, int width, int height, int color) { throw new UnsupportedOperationException(); }
    public void item(ItemStack itemStack, int x, int y, int seed) { throw new UnsupportedOperationException(); }
    public void setTooltipForNextFrame(Font font, List<FormattedCharSequence> tooltip, ClientTooltipPositioner positioner, int xo, int yo,
            boolean replaceExisting) { throw new UnsupportedOperationException(); }
}
