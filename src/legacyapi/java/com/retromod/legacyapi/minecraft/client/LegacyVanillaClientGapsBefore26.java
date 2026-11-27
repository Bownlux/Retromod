/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceLocation;

/** The nine-slice draws 1.20.1 mods call, rebuilt on the texture blits of 1.20.2 to 1.21.1 and of 1.21.11; each is kept only where it links. */
public final class LegacyVanillaClientGapsBefore26 {

    private LegacyVanillaClientGapsBefore26() {}

    /** {@code graphics.blitNineSliced(texture, x, y, width, height, sliceWidth, sliceHeight, sourceWidth, sourceHeight, u, v)}. */
    public static void blitNineSliced(GuiGraphics graphics, ResourceLocation texture, int x, int y, int width, int height,
            int sliceWidth, int sliceHeight, int sourceWidth, int sourceHeight, int u, int v) {
        blitNineSliced(graphics, texture, x, y, width, height, sliceWidth, sliceHeight, sliceWidth, sliceHeight, sourceWidth,
                sourceHeight, u, v);
    }

    /** The same draw with one slice size for every edge. */
    public static void blitNineSliced(GuiGraphics graphics, ResourceLocation texture, int x, int y, int width, int height,
            int slice, int sourceWidth, int sourceHeight, int u, int v) {
        blitNineSliced(graphics, texture, x, y, width, height, slice, slice, slice, slice, sourceWidth, sourceHeight, u, v);
    }

    /** 1.20.1's nine-slice draw from a 256 by 256 texture: fixed corners, repeated edges and middle (left, top, right, bottom). */
    public static void blitNineSliced(GuiGraphics graphics, ResourceLocation texture, int x, int y, int width, int height,
            int left, int top, int right, int bottom, int sourceWidth, int sourceHeight, int u, int v) {
        // A slice may not be wider than half the drawn or source area, as the old draw clamped it.
        left = Math.min(left, Math.min(width, sourceWidth) / 2);
        right = Math.min(right, Math.min(width, sourceWidth) / 2);
        top = Math.min(top, Math.min(height, sourceHeight) / 2);
        bottom = Math.min(bottom, Math.min(height, sourceHeight) / 2);
        int middleWidth = width - left - right;
        int middleHeight = height - top - bottom;
        int sourceMiddleWidth = sourceWidth - left - right;
        int sourceMiddleHeight = sourceHeight - top - bottom;
        // The four corners.
        part(graphics, texture, x, y, left, top, u, v);
        part(graphics, texture, x + width - right, y, right, top, u + sourceWidth - right, v);
        part(graphics, texture, x, y + height - bottom, left, bottom, u, v + sourceHeight - bottom);
        part(graphics, texture, x + width - right, y + height - bottom, right, bottom, u + sourceWidth - right, v + sourceHeight - bottom);
        // The edges and the middle repeat their source region.
        tile(graphics, texture, x + left, y, middleWidth, top, u + left, v, sourceMiddleWidth, top);
        tile(graphics, texture, x + left, y + height - bottom, middleWidth, bottom, u + left, v + sourceHeight - bottom,
                sourceMiddleWidth, bottom);
        tile(graphics, texture, x, y + top, left, middleHeight, u, v + top, left, sourceMiddleHeight);
        tile(graphics, texture, x + width - right, y + top, right, middleHeight, u + sourceWidth - right, v + top, right,
                sourceMiddleHeight);
        tile(graphics, texture, x + left, y + top, middleWidth, middleHeight, u + left, v + top, sourceMiddleWidth,
                sourceMiddleHeight);
    }

    // Repeats a source region over the area, cutting the last copy in each direction.
    private static void tile(GuiGraphics graphics, ResourceLocation texture, int x, int y, int width, int height, int u, int v,
            int sourceWidth, int sourceHeight) {
        if (width <= 0 || height <= 0 || sourceWidth <= 0 || sourceHeight <= 0) return;
        for (int dx = 0; dx < width; dx += sourceWidth) {
            for (int dy = 0; dy < height; dy += sourceHeight) {
                part(graphics, texture, x + dx, y + dy, Math.min(sourceWidth, width - dx), Math.min(sourceHeight, height - dy), u, v);
            }
        }
    }

    private static void part(GuiGraphics graphics, ResourceLocation texture, int x, int y, int width, int height, int u, int v) {
        if (width <= 0 || height <= 0) return;
        graphics.blit(texture, x, y, u, v, width, height);
    }

    /** The 10-int draw on 1.21.11, whose GUI blits through a render pipeline and names textures by Identifier. */
    public static void blitNineSliced(GuiGraphics graphics, Identifier texture, int x, int y, int width, int height,
            int sliceWidth, int sliceHeight, int sourceWidth, int sourceHeight, int u, int v) {
        blitNineSliced(graphics, texture, x, y, width, height, sliceWidth, sliceHeight, sliceWidth, sliceHeight, sourceWidth,
                sourceHeight, u, v);
    }

    /** The same draw with one slice size for every edge. */
    public static void blitNineSliced(GuiGraphics graphics, Identifier texture, int x, int y, int width, int height,
            int slice, int sourceWidth, int sourceHeight, int u, int v) {
        blitNineSliced(graphics, texture, x, y, width, height, slice, slice, slice, slice, sourceWidth, sourceHeight, u, v);
    }

    /** The 12-int draw: the same nine parts, each blitted through the GUI's textured pipeline. */
    public static void blitNineSliced(GuiGraphics graphics, Identifier texture, int x, int y, int width, int height,
            int left, int top, int right, int bottom, int sourceWidth, int sourceHeight, int u, int v) {
        left = Math.min(left, Math.min(width, sourceWidth) / 2);
        right = Math.min(right, Math.min(width, sourceWidth) / 2);
        top = Math.min(top, Math.min(height, sourceHeight) / 2);
        bottom = Math.min(bottom, Math.min(height, sourceHeight) / 2);
        int middleWidth = width - left - right;
        int middleHeight = height - top - bottom;
        int sourceMiddleWidth = sourceWidth - left - right;
        int sourceMiddleHeight = sourceHeight - top - bottom;
        RenderPipeline pipeline = RenderPipelines.GUI_TEXTURED;
        part(graphics, pipeline, texture, x, y, left, top, u, v);
        part(graphics, pipeline, texture, x + width - right, y, right, top, u + sourceWidth - right, v);
        part(graphics, pipeline, texture, x, y + height - bottom, left, bottom, u, v + sourceHeight - bottom);
        part(graphics, pipeline, texture, x + width - right, y + height - bottom, right, bottom, u + sourceWidth - right,
                v + sourceHeight - bottom);
        tile(graphics, pipeline, texture, x + left, y, middleWidth, top, u + left, v, sourceMiddleWidth, top);
        tile(graphics, pipeline, texture, x + left, y + height - bottom, middleWidth, bottom, u + left, v + sourceHeight - bottom,
                sourceMiddleWidth, bottom);
        tile(graphics, pipeline, texture, x, y + top, left, middleHeight, u, v + top, left, sourceMiddleHeight);
        tile(graphics, pipeline, texture, x + width - right, y + top, right, middleHeight, u + sourceWidth - right, v + top, right,
                sourceMiddleHeight);
        tile(graphics, pipeline, texture, x + left, y + top, middleWidth, middleHeight, u + left, v + top, sourceMiddleWidth,
                sourceMiddleHeight);
    }

    private static void tile(GuiGraphics graphics, RenderPipeline pipeline, Identifier texture, int x, int y, int width, int height,
            int u, int v, int sourceWidth, int sourceHeight) {
        if (width <= 0 || height <= 0 || sourceWidth <= 0 || sourceHeight <= 0) return;
        for (int dx = 0; dx < width; dx += sourceWidth) {
            for (int dy = 0; dy < height; dy += sourceHeight) {
                part(graphics, pipeline, texture, x + dx, y + dy, Math.min(sourceWidth, width - dx), Math.min(sourceHeight, height - dy), u, v);
            }
        }
    }

    private static void part(GuiGraphics graphics, RenderPipeline pipeline, Identifier texture, int x, int y, int width, int height,
            int u, int v) {
        if (width <= 0 || height <= 0) return;
        graphics.blit(pipeline, texture, x, y, u, v, width, height, width, height, 256, 256);
    }
}
