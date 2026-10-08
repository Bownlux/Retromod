/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.polyfill.minecraft;

import java.lang.reflect.Method;

/**
 * GUI and input calls of 1.21.4-era client code whose shape changed by 26.1.
 *
 * <ul>
 *   <li>{@code blitSprite(Function, Identifier, x, y, w, h)} took a render-type factory; 1.21.6
 *       replaced it with a {@code RenderPipeline}. Every 1.21.4 caller passed the textured GUI
 *       type, so the sprite is drawn with {@code RenderPipelines.GUI_TEXTURED}.</li>
 *   <li>{@code InputConstants.isKeyDown(long, int)} took the raw window handle; 26.1 takes the
 *       {@code Window} and 26.3 takes only the key.</li>
 * </ul>
 *
 * <p>No compile-time Minecraft dependency: public members are reached reflectively and cached.
 */
public final class LegacyGuiCalls {

    private static volatile Method blitSprite;
    private static volatile Object texturedPipeline;
    private static volatile Method keyDownByCode;
    private static volatile Method keyDownByWindow;

    private LegacyGuiCalls() {}

    /** {@code GuiGraphics.blitSprite(Function, Identifier, int, int, int, int)}. */
    public static void blitSprite(Object graphics, Object renderTypeFactory, Object sprite,
            int x, int y, int width, int height) {
        try {
            Method method = blitSprite;
            Object pipeline = texturedPipeline;
            if (method == null || pipeline == null) {
                ClassLoader loader = LegacyGuiCalls.class.getClassLoader();
                pipeline = Class.forName("net.minecraft.client.renderer.RenderPipelines", false, loader)
                        .getField("GUI_TEXTURED").get(null);
                for (Method candidate : graphics.getClass().getMethods()) {
                    Class<?>[] params = candidate.getParameterTypes();
                    if (candidate.getName().equals("blitSprite") && params.length == 6
                            && params[0].isInstance(pipeline) && params[1].isInstance(sprite)
                            && params[2] == int.class) {
                        method = candidate;
                        break;
                    }
                }
                if (method == null) throw new NoSuchMethodException("blitSprite(RenderPipeline, ...)");
                texturedPipeline = pipeline;
                blitSprite = method;
            }
            method.invoke(graphics, pipeline, sprite, x, y, width, height);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not draw a legacy GUI sprite", e);
        }
    }

    /** {@code InputConstants.isKeyDown(long, int)}. */
    public static boolean isKeyDown(long windowHandle, int key) {
        try {
            ClassLoader loader = LegacyGuiCalls.class.getClassLoader();
            Class<?> input = Class.forName("com.mojang.blaze3d.platform.InputConstants", false, loader);
            if (keyDownByCode == null && keyDownByWindow == null) {
                for (Method method : input.getMethods()) {
                    if (!method.getName().equals("isKeyDown")) continue;
                    if (method.getParameterCount() == 1) keyDownByCode = method;
                    if (method.getParameterCount() == 2 && !method.getParameterTypes()[0].isPrimitive()) {
                        keyDownByWindow = method;
                    }
                }
            }
            if (keyDownByCode != null) return (Boolean) keyDownByCode.invoke(null, key);
            if (keyDownByWindow == null) return false;
            Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft", false, loader);
            Object client = minecraft.getMethod("getInstance").invoke(null);
            Object window = minecraft.getMethod("getWindow").invoke(client);
            return (Boolean) keyDownByWindow.invoke(null, window, key);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }
}
