/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.polyfill.minecraft;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.Function;

/** Draws 1.21.1 {@code GuiGraphics} texture calls, tint, and buffer source on hosts from 1.21.2 on. */
public final class LegacyGuiGraphics {

    private LegacyGuiGraphics() {}

    // The running host's draw methods, found on the first draw. This class is embedded into mods
    // on its own, so it keeps them in fields rather than a nested type. Writing blit last
    // publishes the rest.
    private static Object textured;
    private static Method sprite;
    private static Method flush;
    private static Method shaderColor;
    private static volatile Method blit;

    // The tint of the last setColor, for hosts that take a color with each draw.
    private static volatile int color = -1;

    /** {@code setColor(red, green, blue, alpha)}: tints what is drawn next. */
    public static void setColor(Object graphics, float red, float green, float blue, float alpha) {
        findHost(graphics);
        if (shaderColor == null) {
            color = (channel(alpha) << 24) | (channel(red) << 16) | (channel(green) << 8) | channel(blue);
            return;
        }
        // Up to 1.21.5 the tint is shader state, so the queued draws go out first, as 1.21.1 did it.
        invoke(flush, graphics);
        invoke(shaderColor, null, red, green, blue, alpha);
    }

    /** {@code blit(texture, x, y, u, v, width, height, textureWidth, textureHeight)}. */
    public static void blit(Object graphics, Object texture, int x, int y, float u, float v,
            int width, int height, int textureWidth, int textureHeight) {
        blitRegion(graphics, texture, x, y, u, v, width, height, width, height, textureWidth, textureHeight);
    }

    /** {@code blit(texture, x, y, u, v, width, height)}, which read a 256 by 256 texture. */
    public static void blitTile(Object graphics, Object texture, int x, int y, int u, int v,
            int width, int height) {
        blitRegion(graphics, texture, x, y, u, v, width, height, width, height, 256, 256);
    }

    /** {@code blit(texture, x, y, depth, u, v, width, height, textureWidth, textureHeight)}. */
    public static void blitAtDepth(Object graphics, Object texture, int x, int y, int depth, float u, float v,
            int width, int height, int textureWidth, int textureHeight) {
        // 1.21.2 dropped the depth: GUI layers are ordered by draw order.
        blitRegion(graphics, texture, x, y, u, v, width, height, width, height, textureWidth, textureHeight);
    }

    /**
     * {@code blit(texture, x, y, width, height, u, v, regionWidth, regionHeight, textureWidth,
     * textureHeight)}, which scaled a texture region to the drawn size.
     */
    public static void blitScaled(Object graphics, Object texture, int x, int y, int width, int height,
            float u, float v, int regionWidth, int regionHeight, int textureWidth, int textureHeight) {
        blitRegion(graphics, texture, x, y, u, v, width, height, regionWidth, regionHeight,
                textureWidth, textureHeight);
    }

    /** {@code blitSprite(sprite, x, y, width, height)}. */
    public static void blitSprite(Object graphics, Object spriteId, int x, int y, int width, int height) {
        findHost(graphics);
        invoke(sprite, graphics, textured, spriteId, x, y, width, height, drawColor());
    }

    /** {@code bufferSource()}, on 1.21.2 to 1.21.5. */
    public static Object bufferSource(Object graphics) {
        // Those versions build the frame's GuiGraphics on the game's main buffer source.
        try {
            Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft", false,
                    graphics.getClass().getClassLoader());
            Object game = minecraft.getMethod("getInstance").invoke(null);
            Object buffers = minecraft.getMethod("renderBuffers").invoke(game);
            return buffers.getClass().getMethod("bufferSource").invoke(buffers);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not find the GUI buffer source", e);
        }
    }

    private static void blitRegion(Object graphics, Object texture, int x, int y, float u, float v,
            int width, int height, int regionWidth, int regionHeight, int textureWidth, int textureHeight) {
        findHost(graphics);
        // Every host from 1.21.2 on has this overload: x, y, u, v, drawn size, region size,
        // texture size, color.
        invoke(blit, graphics, textured, texture, x, y, u, v, width, height,
                regionWidth, regionHeight, textureWidth, textureHeight, drawColor());
    }

    private static int drawColor() {
        // A shader tint already colors the draw, so the vertices stay white.
        return shaderColor == null ? color : -1;
    }

    private static void findHost(Object graphics) {
        if (blit != null) return;
        Class<?> owner = graphics.getClass();
        try {
            Method found = method(owner, "blit", 13, null);
            Class<?> id = found.getParameterTypes()[1];
            sprite = method(owner, "blitSprite", 7, id);
            ClassLoader loader = owner.getClassLoader();
            if (found.getParameterTypes()[0] == Function.class) {
                // 1.21.2 to 1.21.5 take a render type factory and still tint through the shader.
                Method guiTextured = Class.forName("net.minecraft.client.renderer.RenderType", false, loader)
                        .getMethod("guiTextured", id);
                textured = (Function<Object, Object>) texture -> invoke(guiTextured, null, texture);
                flush = owner.getMethod("flush");
                shaderColor = Class.forName("com.mojang.blaze3d.systems.RenderSystem", false, loader)
                        .getMethod("setShaderColor", float.class, float.class, float.class, float.class);
            } else {
                // 1.21.6 and newer take a render pipeline and a color with each draw.
                textured = Class.forName("net.minecraft.client.renderer.RenderPipelines", true, loader)
                        .getField("GUI_TEXTURED").get(null);
            }
            blit = found;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not find the GUI draw methods of "
                    + owner.getName() + " for an old mod's blit", e);
        }
    }

    /** The public method with this name and arity whose last parameter is the int color. */
    private static Method method(Class<?> owner, String name, int arity, Class<?> id)
            throws NoSuchMethodException {
        for (Method candidate : owner.getMethods()) {
            Class<?>[] params = candidate.getParameterTypes();
            // A sprite draw also has an atlas-sprite twin; the texture id tells them apart.
            if (candidate.getName().equals(name) && params.length == arity
                    && params[arity - 1] == int.class && params[2] == int.class
                    && (id == null || params[1] == id)) {
                return candidate;
            }
        }
        throw new NoSuchMethodException(owner.getName() + "." + name + " with " + arity + " parameters");
    }

    private static Object invoke(Method method, Object receiver, Object... args) {
        try {
            return method.invoke(receiver, args);
        } catch (InvocationTargetException e) {
            // The host method itself failed; its own exception says why.
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(cause);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Retromod could not call " + method, e);
        }
    }

    private static int channel(float value) {
        return Math.max(0, Math.min(255, Math.round(value * 255)));
    }
}
