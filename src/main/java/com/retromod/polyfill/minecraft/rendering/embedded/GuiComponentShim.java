/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.polyfill.minecraft.rendering.embedded;

import com.retromod.shim.common.embedded.LegacyPublicMethods;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Reimplementation of GuiComponent (removed in MC 1.20), whose 2D drawing methods
 * moved to the GuiGraphics context object.
 *
 * These methods accept Object parameters and reflect into the modern GuiGraphics
 * equivalents (GuiGraphics lives in the MC runtime, no compile-time reference).
 * The old PoseStack parameter is dropped since GuiGraphics owns its pose stack.
 * They are fallbacks: the primary transform path rewrites callsites to GuiGraphics
 * directly when an instance is available, this covers the cases where it isn't.
 */
public class GuiComponentShim {

    private static final MethodHandle GUI_GRAPHICS_FILL;
    private static final MethodHandle GUI_GRAPHICS_DRAW_STRING;
    private static final MethodHandle GUI_GRAPHICS_BLIT;
    private static final Class<?> GUI_GRAPHICS_CLASS;

    static {
        MethodHandle fill = null;
        MethodHandle drawString = null;
        MethodHandle blit = null;
        Class<?> guiGraphicsClass = null;

        try {
            guiGraphicsClass = Class.forName("net.minecraft.client.gui.GuiGraphics");
            Class<?> fontClass = Class.forName("net.minecraft.client.gui.Font");
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();

            // GuiGraphics.fill(int x1, int y1, int x2, int y2, int color)
            fill = lookup.findVirtual(guiGraphicsClass, "fill",
                MethodType.methodType(void.class, int.class, int.class, int.class, int.class, int.class));

            // GuiGraphics.drawString(Font font, String text, int x, int y, int color)
            drawString = lookup.findVirtual(guiGraphicsClass, "drawString",
                MethodType.methodType(int.class, fontClass, String.class, int.class, int.class, int.class));

            // GuiGraphics.blit(int x, int y, int blitOffset, int u, int v, int width, int height)
            blit = lookup.findVirtual(guiGraphicsClass, "blit",
                MethodType.methodType(void.class, int.class, int.class, int.class, int.class, int.class, int.class));
        } catch (Exception e) {
            // GuiGraphics not available (older MC or dedicated server)
        }

        GUI_GRAPHICS_FILL = fill;
        GUI_GRAPHICS_DRAW_STRING = drawString;
        GUI_GRAPHICS_BLIT = blit;
        GUI_GRAPHICS_CLASS = guiGraphicsClass;
    }

    /**
     * Reimplements GuiComponent.fill(PoseStack, int, int, int, int, int).
     * No-op when no GuiGraphics instance can be resolved.
     *
     * @param poseStack the old PoseStack (ignored, kept for signature compatibility)
     * @param x1    Left edge
     * @param y1    Top edge
     * @param x2    Right edge
     * @param y2    Bottom edge
     * @param color ARGB color
     */
    public static void fill(Object poseStack, int x1, int y1, int x2, int y2, int color) {
        if (GUI_GRAPHICS_FILL != null && GUI_GRAPHICS_CLASS != null) {
            Object guiGraphics = getCurrentGuiGraphics();
            if (guiGraphics != null) {
                try {
                    GUI_GRAPHICS_FILL.invoke(guiGraphics, x1, y1, x2, y2, color);
                } catch (Throwable t) {
                    // swallow: better than crashing the game
                }
            }
        }
    }

    /**
     * Reimplements GuiComponent.drawString(Font, String, int, int, int).
     * Font is passed as Object (no compile-time reference to Font).
     *
     * @param font  the Font instance
     * @param text  Text to draw
     * @param x     X position
     * @param y     Y position
     * @param color ARGB color
     */
    public static void drawString(Object font, String text, int x, int y, int color) {
        if (GUI_GRAPHICS_DRAW_STRING != null && GUI_GRAPHICS_CLASS != null) {
            Object guiGraphics = getCurrentGuiGraphics();
            if (guiGraphics != null) {
                try {
                    GUI_GRAPHICS_DRAW_STRING.invoke(guiGraphics, font, text, x, y, color);
                } catch (Throwable t) {
                    // swallow
                }
            }
        }
    }

    /**
     * Reimplements GuiComponent.blit(PoseStack, int, int, int, int, int, int),
     * delegating to GuiGraphics.blit() with the PoseStack dropped.
     *
     * @param poseStack the old PoseStack (ignored)
     * @param x         X position
     * @param y         Y position
     * @param u         Texture U coordinate
     * @param v         Texture V coordinate
     * @param width     Width to blit
     * @param height    Height to blit
     */
    public static void blit(Object poseStack, int x, int y, int u, int v, int width, int height) {
        if (GUI_GRAPHICS_BLIT != null && GUI_GRAPHICS_CLASS != null) {
            Object guiGraphics = getCurrentGuiGraphics();
            if (guiGraphics != null) {
                try {
                    GUI_GRAPHICS_BLIT.invoke(guiGraphics, x, y, u, v, width, height);
                } catch (Throwable t) {
                    // swallow
                }
            }
        }
    }

    /** Builds a GuiGraphics from the current Minecraft instance, or null if unavailable. */
    private static Object getCurrentGuiGraphics() {
        try {
            Class<?> minecraftClass = Class.forName("net.minecraft.client.Minecraft");
            Object minecraft = minecraftClass.getMethod("getInstance").invoke(null);

            Object bufferSource = minecraftClass.getMethod("renderBuffers").invoke(minecraft);
            Object bufSource = LegacyPublicMethods.method(bufferSource.getClass(), "bufferSource").invoke(bufferSource);

            return GUI_GRAPHICS_CLASS
                .getConstructor(minecraftClass, Class.forName("net.minecraft.client.renderer.MultiBufferSource$BufferSource"))
                .newInstance(minecraft, bufSource);
        } catch (Exception e) {
            return null;
        }
    }
}
