/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Runtime half of {@link com.retromod.shim.common.LegacyPoseGuiBridge}: pre-1.20 GUI drawing on
 * 26.1 and newer. Reflective, because this class is embedded into each mod and compiled without
 * Minecraft or JOML.
 *
 * <p>An old HUD or screen handler asked its event for a {@code PoseStack} and drew with static
 * {@code GuiComponent} helpers and {@code Font.draw}, after binding a texture through
 * {@code RenderSystem.setShaderTexture}. Here the event hands out a fresh pose stack tied to the
 * frame's {@code GuiGraphicsExtractor}; each old draw goes to that extractor with the pose stack's
 * 2D transform applied, and the bound texture and shader color are remembered for the next blit.
 * A draw on a pose stack that came from somewhere else, such as one the mod made itself, has no
 * extractor to reach and draws nothing.
 */
public final class LegacyPoseGui {
    private LegacyPoseGui() {}

    private static final Map<Object, Object> GRAPHICS = Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile Object texture;
    private static volatile int color = 0xFFFFFFFF;

    // ---- where pose stacks come from -------------------------------------------------------

    /** An old {@code event.getPoseStack()}: a pose stack that draws through the event's extractor. */
    public static Object poseFor(Object event) {
        try {
            Object graphics = event.getClass().getMethod("getGuiGraphics").invoke(event);
            Object pose = Class.forName("com.mojang.blaze3d.vertex.PoseStack", true, event.getClass().getClassLoader())
                    .getConstructor().newInstance();
            GRAPHICS.put(pose, graphics);
            return pose;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not give an old GUI handler its pose stack", e);
        }
    }

    // ---- render state ----------------------------------------------------------------------

    /** {@code RenderSystem.setShaderTexture(unit, texture)}: the next blit draws this texture. */
    public static void setShaderTexture(int unit, Object textureId) {
        if (unit == 0) texture = textureId;
    }

    /** {@code RenderSystem.setShaderColor(r, g, b, a)}: the next blit is tinted with it. */
    public static void setShaderColor(float red, float green, float blue, float alpha) {
        color = (channel(alpha) << 24) | (channel(red) << 16) | (channel(green) << 8) | channel(blue);
    }

    // ---- draws -----------------------------------------------------------------------------

    /** {@code blit(pose, x, y, u, v, width, height)} on a 256 by 256 texture. */
    public static void blit(Object pose, int x, int y, int u, int v, int width, int height) {
        blit(pose, x, y, u, v, width, height, 256, 256);
    }

    /** The receiver of the old instance {@code blit} is ignored. */
    public static void blitInstance(Object receiver, Object pose, int x, int y, int u, int v, int width, int height) {
        blit(pose, x, y, u, v, width, height, 256, 256);
    }

    /** {@code blit(pose, x, y, z, u, v, width, height, textureHeight, textureWidth)}, depth ignored. */
    public static void blitZ(Object pose, int x, int y, int z, float u, float v, int width, int height,
            int textureHeight, int textureWidth) {
        blit(pose, x, y, u, v, width, height, textureWidth, textureHeight);
    }

    /** {@code blit(pose, x, y, u, v, width, height, textureWidth, textureHeight)}. */
    public static void blitUv(Object pose, int x, int y, float u, float v, int width, int height,
            int textureWidth, int textureHeight) {
        blit(pose, x, y, u, v, width, height, textureWidth, textureHeight);
    }

    /** {@code blit(pose, x, y, width, height, u, v, regionWidth, regionHeight, textureWidth, textureHeight)}. */
    public static void blitSized(Object pose, int x, int y, int width, int height, float u, float v,
            int regionWidth, int regionHeight, int textureWidth, int textureHeight) {
        if (texture == null) return;
        Object graphics = begin(pose);
        if (graphics == null) return;
        try {
            Object pipeline = guiTextured(graphics);
            for (Method method : graphics.getClass().getMethods()) {
                if (method.getName().equals("blit") && method.getParameterCount() == 12) {
                    method.invoke(graphics, pipeline, texture, x, y, u, v, width, height, regionWidth, regionHeight,
                            textureWidth, textureHeight);
                    return;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // skipped, as a missing texture would have been
        } finally {
            end(graphics);
        }
    }

    private static void blit(Object pose, int x, int y, float u, float v, int width, int height,
            int textureWidth, int textureHeight) {
        if (texture == null) return;
        Object graphics = begin(pose);
        if (graphics == null) return;
        try {
            Object pipeline = guiTextured(graphics);
            for (Method method : graphics.getClass().getMethods()) {
                Class<?>[] types = method.getParameterTypes();
                // blit(pipeline, texture, x, y, u, v, width, height, textureWidth, textureHeight, color)
                if (method.getName().equals("blit") && types.length == 11 && types[4] == float.class) {
                    method.invoke(graphics, pipeline, texture, x, y, u, v, width, height, textureWidth, textureHeight, color);
                    return;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // skipped, as a missing texture would have been
        } finally {
            end(graphics);
        }
    }

    /** {@code GuiComponent.fill(pose, x1, y1, x2, y2, color)}. */
    public static void fill(Object pose, int x1, int y1, int x2, int y2, int argb) {
        Object graphics = begin(pose);
        if (graphics == null) return;
        try {
            graphics.getClass().getMethod("fill", int.class, int.class, int.class, int.class, int.class)
                    .invoke(graphics, x1, y1, x2, y2, argb);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // skipped
        } finally {
            end(graphics);
        }
    }

    /** {@code GuiComponent.drawString(pose, font, text, x, y, color)}, which drew a shadow. */
    public static void drawString(Object pose, Object font, Object text, int x, int y, int argb) {
        text(pose, font, text, x, y, argb, true, false);
    }

    /** {@code GuiComponent.drawCenteredString(pose, font, text, x, y, color)}. */
    public static void drawCenteredString(Object pose, Object font, Object text, int x, int y, int argb) {
        text(pose, font, text, x, y, argb, true, true);
    }

    /** {@code Font.draw(pose, text, x, y, color)}, without a shadow; returned the end x. */
    public static int fontDraw(Object font, Object pose, Object text, float x, float y, int argb) {
        text(pose, font, text, (int) x, (int) y, argb, false, false);
        return (int) x + width(font, text);
    }

    /** {@code Font.drawShadow(pose, text, x, y, color)}. */
    public static int fontDrawShadow(Object font, Object pose, Object text, float x, float y, int argb) {
        text(pose, font, text, (int) x, (int) y, argb, true, false);
        return (int) x + width(font, text);
    }

    // ---- helpers ---------------------------------------------------------------------------

    /**
     * The pose stack's extractor with the pose stack's 2D transform pushed onto it, or null when
     * the pose stack came from somewhere else. Every non-null result is passed to {@link #end}.
     */
    private static Object begin(Object pose) {
        Object graphics = pose == null ? null : GRAPHICS.get(pose);
        if (graphics == null) return null;
        try {
            Object stack = graphics.getClass().getMethod("pose").invoke(graphics);
            stack.getClass().getMethod("pushMatrix").invoke(stack);
            Object last = pose.getClass().getMethod("last").invoke(pose);
            Object m = last.getClass().getMethod("pose").invoke(last);
            Object affine = Class.forName("org.joml.Matrix3x2f", true, stack.getClass().getClassLoader())
                    .getConstructor(float.class, float.class, float.class, float.class, float.class, float.class)
                    .newInstance(get(m, "m00"), get(m, "m01"), get(m, "m10"), get(m, "m11"), get(m, "m30"), get(m, "m31"));
            for (Method mul : stack.getClass().getMethods()) {
                if (mul.getName().equals("mul") && mul.getParameterCount() == 1
                        && mul.getParameterTypes()[0].isInstance(affine)) {
                    mul.invoke(stack, affine);
                    break;
                }
            }
            return graphics;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static void end(Object graphics) {
        try {
            Object stack = graphics.getClass().getMethod("pose").invoke(graphics);
            stack.getClass().getMethod("popMatrix").invoke(stack);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // the extractor is gone with its frame
        }
    }

    private static void text(Object pose, Object font, Object text, int x, int y, int argb, boolean shadow, boolean centered) {
        Object graphics = begin(pose);
        if (graphics == null) return;
        try {
            for (Method method : graphics.getClass().getMethods()) {
                Class<?>[] types = method.getParameterTypes();
                boolean wanted = centered ? method.getName().equals("centeredText") && types.length == 5
                        : method.getName().equals("text") && types.length == 6;
                if (wanted && types[1].isInstance(text)) {
                    if (centered) method.invoke(graphics, font, text, x, y, argb);
                    else method.invoke(graphics, font, text, x, y, argb, shadow);
                    return;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // skipped
        } finally {
            end(graphics);
        }
    }

    private static int width(Object font, Object text) {
        for (Method method : font.getClass().getMethods()) {
            if (method.getName().equals("width") && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isInstance(text)) {
                try {
                    return (Integer) method.invoke(font, text);
                } catch (ReflectiveOperationException e) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private static Object guiTextured(Object graphics) throws ReflectiveOperationException {
        return Class.forName("net.minecraft.client.renderer.RenderPipelines", true, graphics.getClass().getClassLoader())
                .getField("GUI_TEXTURED").get(null);
    }

    private static float get(Object matrix, String element) throws ReflectiveOperationException {
        return (Float) matrix.getClass().getMethod(element).invoke(matrix);
    }

    private static int channel(float value) {
        return Math.max(0, Math.min(255, Math.round(value * 255)));
    }
}
