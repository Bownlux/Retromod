/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3x2fStack;

import java.util.List;

/** The 1.21.1 {@code GuiGraphics} calls whose shape changed, done on the 26.x {@code GuiGraphicsExtractor}. */
public final class LegacyGuiDraws {

    // The pose stack handed out for the last frame stack asked about. Matrix3x2fStack compares by
    // value, so it is matched by identity here rather than kept in a map.
    private static Matrix3x2fStack lastStack;
    private static LegacyGuiPoseStack lastPose;

    private LegacyGuiDraws() {}

    /** {@code pose()}, which returned a {@code PoseStack} before 1.21.6. */
    public static synchronized PoseStack pose(GuiGraphicsExtractor graphics) {
        Matrix3x2fStack stack = graphics.pose();
        // The same pose stack for the whole frame, so a push and its pop pair up across pose() calls.
        if (stack != lastStack) {
            lastStack = stack;
            lastPose = new LegacyGuiPoseStack(stack);
        }
        return lastPose;
    }

    /** {@code RenderType.gui()} and its overlay forms. 26.x has no GUI render types; the fills below ignore it. */
    public static RenderType guiRenderType() {
        return null;
    }

    /** {@code RenderType.guiTextHighlight()}: a fill it reaches directly becomes {@link #fillTextHighlight}. */
    public static RenderType guiTextHighlightRenderType() {
        return null;
    }

    /** {@code fill(renderType, x0, y0, x1, y1, color)}, drawn with the GUI pipeline. */
    public static void fill(GuiGraphicsExtractor graphics, RenderType renderType, int x0, int y0, int x1, int y1, int color) {
        graphics.fill(x0, y0, x1, y1, color);
    }

    /** {@code fill(renderType, x0, y0, x1, y1, depth, color)}. */
    public static void fill(GuiGraphicsExtractor graphics, RenderType renderType, int x0, int y0, int x1, int y1, int depth,
            int color) {
        graphics.fill(x0, y0, x1, y1, color);
    }

    /** {@code fill(x0, y0, x1, y1, depth, color)}. 26.x stacks GUI layers in draw order, so the depth is dropped. */
    public static void fill(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int depth, int color) {
        graphics.fill(x0, y0, x1, y1, color);
    }

    /** A {@code fill} whose render type was {@code guiTextHighlight()}: the text selection, in the mod's color. */
    public static void fillTextHighlight(GuiGraphicsExtractor graphics, RenderType renderType, int x0, int y0, int x1, int y1,
            int color) {
        graphics.fill(RenderPipelines.GUI_TEXT_HIGHLIGHT, x0, y0, x1, y1, color);
    }

    /** {@code fillGradient(x0, y0, x1, y1, depth, colorFrom, colorTo)}. */
    public static void fillGradient(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int depth, int colorFrom,
            int colorTo) {
        graphics.fillGradient(x0, y0, x1, y1, colorFrom, colorTo);
    }

    /** {@code fillGradient(renderType, x0, y0, x1, y1, colorFrom, colorTo, depth)}. */
    public static void fillGradient(GuiGraphicsExtractor graphics, RenderType renderType, int x0, int y0, int x1, int y1,
            int colorFrom, int colorTo, int depth) {
        graphics.fillGradient(x0, y0, x1, y1, colorFrom, colorTo);
    }

    /** {@code hLine(x0, x1, y, color)}. */
    public static void hLine(GuiGraphicsExtractor graphics, int x0, int x1, int y, int color) {
        graphics.horizontalLine(x0, x1, y, color);
    }

    /** {@code hLine(renderType, x0, x1, y, color)}. */
    public static void hLine(GuiGraphicsExtractor graphics, RenderType renderType, int x0, int x1, int y, int color) {
        graphics.horizontalLine(x0, x1, y, color);
    }

    /** {@code vLine(x, y0, y1, color)}. */
    public static void vLine(GuiGraphicsExtractor graphics, int x, int y0, int y1, int color) {
        graphics.verticalLine(x, y0, y1, color);
    }

    /** {@code vLine(renderType, x, y0, y1, color)}. */
    public static void vLine(GuiGraphicsExtractor graphics, RenderType renderType, int x, int y0, int y1, int color) {
        graphics.verticalLine(x, y0, y1, color);
    }

    /** {@code drawString(font, text, x, y, color, shadow)} at a fractional position. It returned the x after the text. */
    public static int drawString(GuiGraphicsExtractor graphics, Font font, String text, float x, float y, int color,
            boolean shadow) {
        // 1.21.1 drew nothing for null text and returned 0.
        if (text == null) return 0;
        Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(x, y);
        graphics.text(font, text, 0, 0, color, shadow);
        pose.popMatrix();
        return endOfText(x, font.width(text), shadow);
    }

    /** {@code drawString(font, text, x, y, color, shadow)} for a formatted sequence at a fractional position. */
    public static int drawString(GuiGraphicsExtractor graphics, Font font, FormattedCharSequence text, float x, float y,
            int color, boolean shadow) {
        if (text == null) return 0;
        Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(x, y);
        graphics.text(font, text, 0, 0, color, shadow);
        pose.popMatrix();
        return endOfText(x, font.width(text), shadow);
    }

    /** {@code drawWordWrap(font, text, x, y, width, color)}: each wrapped line, one line height apart, without shadow. */
    public static void drawWordWrap(GuiGraphicsExtractor graphics, Font font, FormattedText text, int x, int y, int width,
            int color) {
        // 26.3 returns the height from its own wrap call and 26.2 returns nothing, so the lines are drawn here.
        for (FormattedCharSequence line : font.split(text, width)) {
            graphics.text(font, line, x, y, color, false);
            y += font.lineHeight;
        }
    }

    /** {@code drawManaged(draws)}. 26.x queues every draw for the end of the frame, so the draws simply run. */
    public static void drawManaged(GuiGraphicsExtractor graphics, Runnable draws) {
        draws.run();
    }

    /** {@code renderItem(stack, x, y, seed, depth)}. */
    public static void renderItem(GuiGraphicsExtractor graphics, ItemStack stack, int x, int y, int seed, int depth) {
        graphics.item(stack, x, y, seed);
    }

    /** {@code blitSprite(sprite, x, y, depth, width, height)}. */
    public static void blitSprite(GuiGraphicsExtractor graphics, Identifier sprite, int x, int y, int depth, int width,
            int height) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, width, height);
    }

    /** {@code blitSprite(sprite, spriteWidth, spriteHeight, u, v, x, y, width, height)}. */
    public static void blitSprite(GuiGraphicsExtractor graphics, Identifier sprite, int spriteWidth, int spriteHeight, int u,
            int v, int x, int y, int width, int height) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, spriteWidth, spriteHeight, u, v, x, y, width, height);
    }

    /** {@code blitSprite(sprite, spriteWidth, spriteHeight, u, v, x, y, depth, width, height)}. */
    public static void blitSprite(GuiGraphicsExtractor graphics, Identifier sprite, int spriteWidth, int spriteHeight, int u,
            int v, int x, int y, int depth, int width, int height) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, spriteWidth, spriteHeight, u, v, x, y, width, height);
    }

    /** {@code blit(x, y, depth, width, height, sprite)}. */
    public static void blit(GuiGraphicsExtractor graphics, int x, int y, int depth, int width, int height,
            TextureAtlasSprite sprite) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, width, height);
    }

    /** {@code blit(x, y, depth, width, height, sprite, red, green, blue, alpha)}. */
    public static void blit(GuiGraphicsExtractor graphics, int x, int y, int depth, int width, int height,
            TextureAtlasSprite sprite, float red, float green, float blue, float alpha) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, width, height, argb(red, green, blue, alpha));
    }

    /** {@code renderTooltip(font, lines, positioner, x, y)}, which drew the tooltip at once; 26.x shows it after the frame. */
    public static void renderTooltip(GuiGraphicsExtractor graphics, Font font, List<FormattedCharSequence> lines,
            ClientTooltipPositioner positioner, int x, int y) {
        // false keeps a tooltip already set this frame, as 26.x's own short forms do.
        graphics.setTooltipForNextFrame(font, lines, positioner, x, y, false);
    }

    // Font.drawInBatch returned the x after the last glyph, one further with a shadow.
    private static int endOfText(float x, int width, boolean shadow) {
        return (int) (x + width) + (shadow ? 1 : 0);
    }

    private static int argb(float red, float green, float blue, float alpha) {
        return channel(alpha) << 24 | channel(red) << 16 | channel(green) << 8 | channel(blue);
    }

    private static int channel(float value) {
        return Math.max(0, Math.min(255, Math.round(value * 255)));
    }
}
