/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.fabric.embedded;

import com.retromod.shim.common.embedded.LegacyPublicMethods;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Bridges the old Font.draw/drawShadow methods (removed in MC 26.1) onto drawInBatch.
 * Devirtualized: the Font is the first parameter, PoseStack arrives as Object and the
 * Matrix4f is pulled out via reflection. Returns 0 for the width.
 */
public final class FontBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-FontBridge");

    // MC handles resolved via reflection (MC isn't on the compile classpath). DCL with
    // volatile: text rendering runs on the render thread after mod init.
    private static volatile boolean initialized = false;
    private static volatile boolean initFailed = false;

    // poseStack.last().pose() yields the Matrix4f drawInBatch wants.
    private static volatile Method poseStackLast;       // PoseStack.last() -> Pose
    private static volatile Method posePose;            // Pose.pose() -> Matrix4f

    // Minecraft.getInstance().renderBuffers().bufferSource()
    private static volatile Method minecraftGetInstance;
    private static volatile Method renderBuffers;
    private static volatile Method bufferSource;
    private static volatile Method bufferSourceEndBatch; // flush after drawing

    private static volatile Method drawInBatchString;
    private static volatile Method drawInBatchComponent;
    private static volatile Method drawInBatchFCSeq;

    private static volatile Object displayModeNormal;
    private static volatile Object displayModeSeeThrough;

    private static volatile Method componentGetString;

    private static boolean hasFCSeqMethod = false;

    private FontBridge() {
    }

    private static void ensureInitialized() {
        if (initialized || initFailed) return;
        synchronized (FontBridge.class) {
            if (initialized || initFailed) return;
            try {
                ClassLoader cl = Thread.currentThread().getContextClassLoader();

                Class<?> poseStackClass = cl.loadClass("com.mojang.blaze3d.vertex.PoseStack");
                poseStackLast = poseStackClass.getMethod("last");
                Class<?> poseClass = poseStackLast.getReturnType();
                posePose = poseClass.getMethod("pose");

                Class<?> minecraftClass = cl.loadClass("net.minecraft.client.Minecraft");
                minecraftGetInstance = minecraftClass.getMethod("getInstance");

                Object mc = minecraftGetInstance.invoke(null);
                renderBuffers = minecraftClass.getMethod("renderBuffers");
                Object rb = renderBuffers.invoke(mc);
                bufferSource = LegacyPublicMethods.method(rb.getClass(), "bufferSource");

                Object bs = bufferSource.invoke(rb);
                try {
                    bufferSourceEndBatch = LegacyPublicMethods.method(bs.getClass(), "endBatch");
                } catch (NoSuchMethodException e) {
                    try {
                        bufferSourceEndBatch = LegacyPublicMethods.method(bs.getClass(), "endLastBatch");
                    } catch (NoSuchMethodException e2) {
                        // no flush method; text still renders, just not flushed immediately
                    }
                }

                Class<?> fontClass = cl.loadClass("net.minecraft.client.gui.Font");
                Class<?> matrix4fClass = cl.loadClass("org.joml.Matrix4f");
                Class<?> multiBufferSourceClass = cl.loadClass(
                    "net.minecraft.client.renderer.MultiBufferSource");
                Class<?> displayModeClass = cl.loadClass(
                    "net.minecraft.client.gui.Font$DisplayMode");

                Object[] displayModes = displayModeClass.getEnumConstants();
                for (Object dm : displayModes) {
                    String name = ((Enum<?>) dm).name();
                    if ("NORMAL".equals(name)) {
                        displayModeNormal = dm;
                    } else if ("SEE_THROUGH".equals(name)) {
                        displayModeSeeThrough = dm;
                    }
                }
                if (displayModeNormal == null && displayModes.length > 0) {
                    displayModeNormal = displayModes[0];
                }
                if (displayModeSeeThrough == null && displayModes.length > 1) {
                    displayModeSeeThrough = displayModes[displayModes.length > 2 ? 2 : 1];
                }

                // drawInBatch(String, float, float, int, boolean, Matrix4f,
                //             MultiBufferSource, DisplayMode, int, int) -> int
                drawInBatchString = fontClass.getMethod("drawInBatch",
                    String.class, float.class, float.class, int.class, boolean.class,
                    matrix4fClass, multiBufferSourceClass, displayModeClass,
                    int.class, int.class);

                try {
                    Class<?> componentClass = cl.loadClass("net.minecraft.network.chat.Component");
                    drawInBatchComponent = fontClass.getMethod("drawInBatch",
                        componentClass, float.class, float.class, int.class, boolean.class,
                        matrix4fClass, multiBufferSourceClass, displayModeClass,
                        int.class, int.class);
                    componentGetString = componentClass.getMethod("getString");
                } catch (Exception e) {
                    LOGGER.debug("Font.drawInBatch(Component,...) not found, will use String fallback");
                }

                try {
                    Class<?> fcsClass = cl.loadClass(
                        "net.minecraft.util.FormattedCharSequence");
                    drawInBatchFCSeq = fontClass.getMethod("drawInBatch",
                        fcsClass, float.class, float.class, int.class, boolean.class,
                        matrix4fClass, multiBufferSourceClass, displayModeClass,
                        int.class, int.class);
                    hasFCSeqMethod = true;
                } catch (Exception e) {
                    LOGGER.debug("Font.drawInBatch(FormattedCharSequence,...) not found, will use fallback");
                }

                initialized = true;
                LOGGER.debug("FontBridge initialized successfully");

            } catch (Exception e) {
                initFailed = true;
                LOGGER.warn("FontBridge initialization failed: {}. " +
                    "Text rendering from old mods may not work.", e.getMessage());
            }
        }
    }

    /** Bridge for Font.draw(PoseStack, String, float, float, int). */
    public static int drawString(Object font, Object poseStack, String text,
                                  float x, float y, int color) {
        return drawInternal(font, poseStack, text, null, null, x, y, color, false);
    }

    /** Bridge for Font.draw(PoseStack, Component, float, float, int). */
    public static int drawComponent(Object font, Object poseStack, Object component,
                                     float x, float y, int color) {
        return drawInternal(font, poseStack, null, component, null, x, y, color, false);
    }

    /** Bridge for Font.drawShadow(PoseStack, String, float, float, int). */
    public static int drawShadowString(Object font, Object poseStack, String text,
                                        float x, float y, int color) {
        return drawInternal(font, poseStack, text, null, null, x, y, color, true);
    }

    /** Bridge for Font.drawShadow(PoseStack, Component, float, float, int). */
    public static int drawShadowComponent(Object font, Object poseStack, Object component,
                                           float x, float y, int color) {
        return drawInternal(font, poseStack, null, component, null, x, y, color, true);
    }

    /** Bridge for Font.drawShadow(PoseStack, FormattedCharSequence, float, float, int). */
    public static int drawShadowFCS(Object font, Object poseStack, Object formattedCharSeq,
                                     float x, float y, int color) {
        return drawInternal(font, poseStack, null, null, formattedCharSeq, x, y, color, true);
    }

    /** shadow=true picks SEE_THROUGH; text/component/formattedCharSeq are mutually exclusive. */
    private static int drawInternal(Object font, Object poseStack,
                                     String text, Object component, Object formattedCharSeq,
                                     float x, float y, int color, boolean shadow) {
        ensureInitialized();
        if (initFailed) return 0;

        try {
            Object pose = poseStackLast.invoke(poseStack);
            Object matrix4f = posePose.invoke(pose);

            Object mc = minecraftGetInstance.invoke(null);
            Object rb = renderBuffers.invoke(mc);
            Object bs = bufferSource.invoke(rb);

            Object displayMode = shadow ? displayModeSeeThrough : displayModeNormal;
            if (displayMode == null) displayMode = displayModeNormal;

            int packedLight = 15728880; // LightTexture.FULL_BRIGHT
            int backgroundColor = 0;

            if (text != null && drawInBatchString != null) {
                drawInBatchString.invoke(font, text, x, y, color, shadow,
                    matrix4f, bs, displayMode, backgroundColor, packedLight);
            } else if (component != null && drawInBatchComponent != null) {
                drawInBatchComponent.invoke(font, component, x, y, color, shadow,
                    matrix4f, bs, displayMode, backgroundColor, packedLight);
            } else if (component != null && componentGetString != null) {
                String str = (String) componentGetString.invoke(component);
                if (drawInBatchString != null) {
                    drawInBatchString.invoke(font, str, x, y, color, shadow,
                        matrix4f, bs, displayMode, backgroundColor, packedLight);
                }
            } else if (formattedCharSeq != null && drawInBatchFCSeq != null) {
                drawInBatchFCSeq.invoke(font, formattedCharSeq, x, y, color, shadow,
                    matrix4f, bs, displayMode, backgroundColor, packedLight);
            } else if (formattedCharSeq != null && drawInBatchString != null) {
                String str = formattedCharSeq.toString();
                drawInBatchString.invoke(font, str, x, y, color, shadow,
                    matrix4f, bs, displayMode, backgroundColor, packedLight);
            }

            if (bufferSourceEndBatch != null) {
                bufferSourceEndBatch.invoke(bs);
            }

        } catch (Exception e) {
            LOGGER.debug("FontBridge.drawInternal failed: {}", e.getMessage());
        }

        return 0;
    }

    /** No-op for RenderSystem.enableTexture(), removed in 26.1. */
    public static void noOpVoid() {
    }

    /** No-op for RenderSystem.setShaderTexture(int, ResourceLocation), removed in 26.1. */
    public static void noOpSetShaderTexture(int slot, Object resourceLocation) {
    }
}
