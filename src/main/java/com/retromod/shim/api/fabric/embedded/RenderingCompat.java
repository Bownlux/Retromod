/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 RevivalSMP. Licensed under MIT.
 */
package com.retromod.shim.api.fabric.embedded;

import com.retromod.core.EnvironmentDetector;
import com.retromod.shim.common.embedded.LegacyPublicMethods;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.nio.IntBuffer;
import java.util.function.Supplier;

/**
 * Fallbacks for rendering methods that differ across graphics backends (OpenGL, Vulkan, Metal).
 * Called via bytecode redirects from RenderingBackendShim; each picks the backend method by
 * reflection at runtime.
 */
public class RenderingCompat {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-RenderCompat");

    private static Method cachedGetTessellator = null;
    private static Method cachedBindTarget = null;
    private static Object cachedTessellatorInstance = null;

    /** Stand-in for OpenGL calls removed under Vulkan (enableTexture/disableTexture/...). */
    public static void noop() {
    }

    /** Render-thread assertion that works on every backend; off-thread calls warn instead of crashing. */
    public static void assertRenderThread(Supplier<?> supplier) {
        String threadName = Thread.currentThread().getName();
        boolean isRenderThread = threadName.contains("Render") ||
                                  threadName.contains("Main") ||
                                  threadName.equals("Server thread");
        if (!isRenderThread) {
            if (EnvironmentDetector.isVulkan() || EnvironmentDetector.isMetal()) {
                LOGGER.warn("Render call from non-render thread '{}' - this may crash on Vulkan/Metal",
                    threadName);
            }
        }

        try {
            Class<?> renderSystem = Class.forName("com.mojang.blaze3d.systems.RenderSystem");
            Method assertMethod = renderSystem.getMethod("assertOnRenderThread");
            assertMethod.invoke(null);
        } catch (Exception ignored) {
        }
    }

    /** Loads a shader, preferring a pre-compiled SPIR-V variant on Vulkan and falling back to GLSL. */
    public static void loadShaderCompat(String shaderName) {
        LOGGER.debug("Loading shader (compat): {}", shaderName);

        try {
            if (EnvironmentDetector.isVulkan()) {
                String spirvName = shaderName.replace(".glsl", ".spv")
                                              .replace(".vsh", ".vert.spv")
                                              .replace(".fsh", ".frag.spv");

                try {
                    Class<?> shaderClass = Class.forName("net.minecraft.client.gl.ShaderProgram");
                    Method loadMethod = shaderClass.getMethod("loadProgram", String.class);
                    loadMethod.invoke(null, spirvName);
                    return;
                } catch (Exception e) {
                    LOGGER.debug("No SPIR-V shader found, falling back to GLSL: {}", shaderName);
                }
            }

            try {
                Class<?> shaderClass = Class.forName("net.minecraft.client.gl.ShaderProgram");
                Method loadMethod = shaderClass.getMethod("loadProgram", String.class);
                loadMethod.invoke(null, shaderName);
            } catch (Exception e) {
                LOGGER.warn("Could not load shader: {}", shaderName);
            }
        } catch (Exception e) {
            LOGGER.warn("Shader compat error: {}", e.getMessage());
        }
    }

    /** Binds a render target across OpenGL framebuffers and Vulkan render passes. */
    public static void bindRenderTarget(Object framebuffer, boolean setViewport) {
        if (framebuffer == null) return;

        try {
            Method bindMethod = LegacyPublicMethods.method(framebuffer.getClass(), "bindFramebuffer", boolean.class);
            bindMethod.invoke(framebuffer, setViewport);
        } catch (NoSuchMethodException e) {
            try {
                Method bindMethod = LegacyPublicMethods.method(framebuffer.getClass(), "bindWrite", boolean.class);
                bindMethod.invoke(framebuffer, setViewport);
            } catch (Exception e2) {
                try {
                    Method bindMethod = LegacyPublicMethods.method(framebuffer.getClass(), "beginRenderPass");
                    bindMethod.invoke(framebuffer);
                } catch (Exception e3) {
                    LOGGER.debug("Could not bind render target: {}", e3.getMessage());
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Render target bind error: {}", e.getMessage());
        }
    }

    /** Returns the Tessellator singleton, falling back to the relocated Tesselator class on newer versions. */
    public static Object getTessellator() {
        if (cachedTessellatorInstance != null) {
            return cachedTessellatorInstance;
        }

        try {
            Class<?> tessClass = Class.forName("net.minecraft.client.render.Tessellator");
            if (cachedGetTessellator == null) {
                cachedGetTessellator = tessClass.getMethod("getInstance");
            }
            cachedTessellatorInstance = cachedGetTessellator.invoke(null);
            return cachedTessellatorInstance;
        } catch (ClassNotFoundException e) {
            try {
                Class<?> tessClass = Class.forName("com.mojang.blaze3d.vertex.Tesselator");
                Method getInstance = tessClass.getMethod("getInstance");
                cachedTessellatorInstance = getInstance.invoke(null);
                return cachedTessellatorInstance;
            } catch (Exception e2) {
                LOGGER.debug("Tessellator not available");
                return null;
            }
        } catch (Exception e) {
            LOGGER.debug("Could not get Tessellator: {}", e.getMessage());
            return null;
        }
    }

    /** Uploads a texture across OpenGL, Vulkan, and Metal. */
    public static void texImage2DCompat(int target, int level, int internalFormat,
                                         int width, int height, int border,
                                         int format, int type, IntBuffer pixels) {
        try {
            if (EnvironmentDetector.isVulkan() || EnvironmentDetector.isMetal()) {
                try {
                    Class<?> texManager = Class.forName(
                        "com.mojang.blaze3d.platform.TextureManager");
                    Method uploadMethod = texManager.getMethod("upload",
                        int.class, int.class, int.class, int.class, int.class,
                        int.class, int.class, int.class, IntBuffer.class);
                    uploadMethod.invoke(null, target, level, internalFormat,
                        width, height, border, format, type, pixels);
                    return;
                } catch (Exception ignored) {}
            }

            Class<?> glStateManager = Class.forName(
                "com.mojang.blaze3d.platform.GlStateManager");
            Method texMethod = glStateManager.getMethod("texImage2D",
                int.class, int.class, int.class, int.class, int.class,
                int.class, int.class, int.class, IntBuffer.class);
            texMethod.invoke(null, target, level, internalFormat,
                width, height, border, format, type, pixels);
        } catch (Exception e) {
            LOGGER.warn("texImage2D compat failed: {}", e.getMessage());
        }
    }
}
