/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import java.lang.reflect.Method;

/** Answers for RenderSystem and RenderTarget getters that moved onto GPU objects, so old render code keeps linking. */
public final class LegacyRenderCalls {

    // Fog moved into a uniform buffer; these are the values that leave fog off, as the old defaults did.
    private static final float FOG_START_OFF = Float.MAX_VALUE;
    private static final float FOG_END_OFF = Float.MAX_VALUE;

    private LegacyRenderCalls() {}

    /** {@code RenderSystem.getShaderFogStart()}: there is no single fog start any more. */
    public static float shaderFogStart() {
        return FOG_START_OFF;
    }

    /** {@code RenderSystem.getShaderFogEnd()}. */
    public static float shaderFogEnd() {
        return FOG_END_OFF;
    }

    /** {@code RenderSystem.getShaderColor()}: the tint setter is gone, so the color is always white. */
    public static float[] shaderColor() {
        return new float[] {1.0F, 1.0F, 1.0F, 1.0F};
    }

    /** {@code RenderSystem.getShaderTexture(int)}: textures are bound per draw now, so no id is bound. */
    public static int shaderTexture(int unit) {
        return 0;
    }

    /** {@code RenderSystem.runAsFancy(task)}: the graphics mode no longer switches per call, so the task runs as is. */
    public static void runAsFancy(Runnable task) {
        task.run();
    }

    /** {@code target.getColorTextureId()}: the OpenGL id behind the color texture, or -1 off OpenGL. */
    public static int colorTextureId(Object target) {
        return glId(call(target, "getColorTexture"));
    }

    /** {@code target.getDepthTextureId()}: the OpenGL id behind the depth texture, or -1 off OpenGL. */
    public static int depthTextureId(Object target) {
        return glId(call(target, "getDepthTexture"));
    }

    /** {@code target.resize(width, height, clearError)}: the error flag is gone. */
    public static void resize(Object target, int width, int height, boolean clearError) {
        try {
            Method resize = target.getClass().getMethod("resize", int.class, int.class);
            resize.invoke(target, width, height);
        } catch (ReflectiveOperationException failure) {
            LegacyLog.warnOnce("render-target-resize", "An old mod resized a render target, and the host has no resize(int, int): "
                    + failure);
        }
    }

    private static int glId(Object texture) {
        // Only the OpenGL backend has an id; Vulkan textures answer -1, which old code treats as unbound.
        Object id = call(texture, "glId");
        return id instanceof Integer value ? value : -1;
    }

    private static Object call(Object target, String name) {
        if (target == null) return null;
        try {
            Method method = target.getClass().getMethod(name);
            return method.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }
}
