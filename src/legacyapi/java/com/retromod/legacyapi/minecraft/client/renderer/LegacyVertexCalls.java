/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** The VertexConsumer calls Minecraft 1.21 removed, each writing the vertex data 1.20.1 wrote through the builder 1.21 brought. */
public final class LegacyVertexCalls {

    // Fixed colors set with the old defaultColor on a host consumer; old consumers keep their own.
    private static final Map<VertexConsumer, int[]> DEFAULT_COLORS = Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile boolean anyDefaultColor;
    // 1.21.11 gave the lines formats a line width every vertex there needs; 1.21.1 has neither the element nor the setter.
    private static final boolean LINE_WIDTH = hostHasLineWidth();

    private LegacyVertexCalls() {}

    /** What Retromod adds to a mod's own consumer written for 1.20.1, so the old calls that have no new form still reach it. */
    public interface OldConsumer {
        void retromod$endVertex();

        void retromod$defaultColor(int red, int green, int blue, int alpha);

        void retromod$unsetDefaultColor();
    }

    /** {@code vertex(x, y, z)}: starts a vertex, which 1.21 does with addVertex. */
    public static VertexConsumer vertex(VertexConsumer consumer, double x, double y, double z) {
        VertexConsumer next = consumer.addVertex((float) x, (float) y, (float) z);
        lineWidth(next);
        // An old builder with a fixed color filled the color element itself.
        int[] fixed = defaultColor(consumer);
        if (fixed != null) next.setColor(fixed[0], fixed[1], fixed[2], fixed[3]);
        return next;
    }

    /** {@code vertex(matrix, x, y, z)}: the position moved by the matrix, as 1.20.1 moved it. */
    public static VertexConsumer vertex(VertexConsumer consumer, Matrix4f matrix, float x, float y, float z) {
        Vector4f moved = matrix.transform(new Vector4f(x, y, z, 1.0F));
        return vertex(consumer, (double) moved.x, (double) moved.y, (double) moved.z);
    }

    /** {@code color(red, green, blue, alpha)} with channels from 0 to 255. */
    public static VertexConsumer color(VertexConsumer consumer, int red, int green, int blue, int alpha) {
        // An old builder with a fixed color kept it, so a color call left the vertex as it was.
        if (defaultColor(consumer) != null) return consumer;
        return consumer.setColor(red, green, blue, alpha);
    }

    /** {@code color(red, green, blue, alpha)} with channels from 0 to 1, cut to whole steps as 1.20.1 cut them. */
    public static VertexConsumer color(VertexConsumer consumer, float red, float green, float blue, float alpha) {
        return color(consumer, (int) (red * 255.0F), (int) (green * 255.0F), (int) (blue * 255.0F), (int) (alpha * 255.0F));
    }

    /** {@code color(argb)}. */
    public static VertexConsumer color(VertexConsumer consumer, int argb) {
        return color(consumer, argb >> 16 & 255, argb >> 8 & 255, argb & 255, argb >>> 24);
    }

    /** {@code uv(u, v)}, the texture coordinates. */
    public static VertexConsumer uv(VertexConsumer consumer, float u, float v) {
        return consumer.setUv(u, v);
    }

    /** {@code overlayCoords(u, v)}, which 1.21 calls the second texture coordinates. */
    public static VertexConsumer overlayCoords(VertexConsumer consumer, int u, int v) {
        return consumer.setUv1(u, v);
    }

    /** {@code overlayCoords(packed)}. */
    public static VertexConsumer overlayCoords(VertexConsumer consumer, int packed) {
        return consumer.setUv1(packed & 0xFFFF, packed >> 16 & 0xFFFF);
    }

    /** {@code uv2(u, v)}, the light coordinates. */
    public static VertexConsumer uv2(VertexConsumer consumer, int u, int v) {
        return consumer.setUv2(u, v);
    }

    /** {@code uv2(packed)}. */
    public static VertexConsumer uv2(VertexConsumer consumer, int packed) {
        return consumer.setUv2(packed & 0xFFFF, packed >> 16 & 0xFFFF);
    }

    /** {@code normal(x, y, z)}. */
    public static VertexConsumer normal(VertexConsumer consumer, float x, float y, float z) {
        return consumer.setNormal(x, y, z);
    }

    /** {@code normal(matrix, x, y, z)}: the normal turned by the matrix, as 1.20.1 turned it. */
    public static VertexConsumer normal(VertexConsumer consumer, Matrix3f matrix, float x, float y, float z) {
        Vector3f turned = matrix.transform(new Vector3f(x, y, z));
        return consumer.setNormal(turned.x(), turned.y(), turned.z());
    }

    /** {@code endVertex()}: 1.21 ends a vertex when the next one starts, so only an old consumer of a mod's own has work to do. */
    public static void endVertex(VertexConsumer consumer) {
        if (consumer instanceof OldConsumer old) old.retromod$endVertex();
    }

    /** The whole-vertex {@code vertex(...)} of 1.20.1, with float color channels and packed overlay and light. */
    public static void vertex(VertexConsumer consumer, float x, float y, float z, float red, float green, float blue, float alpha,
            float u, float v, int overlay, int light, float normalX, float normalY, float normalZ) {
        boolean old = consumer instanceof OldConsumer;
        if (old || defaultColor(consumer) != null) {
            // Element by element, as 1.20.1's whole-vertex call did: a fixed color wins, and a mod's own consumer gets
            // the color cut to whole steps but not masked, then its own endVertex.
            vertex(consumer, x, y, z);
            if (old) consumer.setColor((int) (red * 255.0F), (int) (green * 255.0F), (int) (blue * 255.0F), (int) (alpha * 255.0F));
            uv(consumer, u, v);
            overlayCoords(consumer, overlay);
            uv2(consumer, light);
            normal(consumer, normalX, normalY, normalZ);
            if (old) endVertex(consumer);
            return;
        }
        int argb = ((int) (alpha * 255.0F) & 255) << 24 | ((int) (red * 255.0F) & 255) << 16
                | ((int) (green * 255.0F) & 255) << 8 | (int) (blue * 255.0F) & 255;
        consumer.addVertex(x, y, z, argb, u, v, overlay, light, normalX, normalY, normalZ);
        lineWidth(consumer);
    }

    /** {@code defaultColor(...)}: a fixed color for every vertex until {@code unsetDefaultColor()}. */
    public static void defaultColor(VertexConsumer consumer, int red, int green, int blue, int alpha) {
        if (consumer instanceof OldConsumer old) {
            old.retromod$defaultColor(red, green, blue, alpha);
            return;
        }
        DEFAULT_COLORS.put(consumer, new int[] {red, green, blue, alpha});
        anyDefaultColor = true;
    }

    /** {@code unsetDefaultColor()}. */
    public static void unsetDefaultColor(VertexConsumer consumer) {
        if (consumer instanceof OldConsumer old) {
            old.retromod$unsetDefaultColor();
            return;
        }
        DEFAULT_COLORS.remove(consumer);
    }

    // 1.20.1's lines() drew at the window-scaled width max(2.5, window width / 1920 * 2.5), the value 1.21.11 and 26.x
    // compute as the window's appropriate line width. A host writes it only into a format that has the element.
    private static void lineWidth(VertexConsumer vertex) {
        if (LINE_WIDTH) vertex.setLineWidth(Minecraft.getInstance().getWindow().getAppropriateLineWidth());
    }

    // Found by shape, the one abstract setter that takes a single float, since a Fabric host names it in intermediary.
    private static boolean hostHasLineWidth() {
        for (Method method : VertexConsumer.class.getMethods()) {
            if (Modifier.isAbstract(method.getModifiers()) && method.getReturnType() == VertexConsumer.class
                    && method.getParameterCount() == 1 && method.getParameterTypes()[0] == float.class) {
                return true;
            }
        }
        return false;
    }

    private static int[] defaultColor(VertexConsumer consumer) {
        // Most games never set one, so the map is read only after a mod did.
        return anyDefaultColor ? DEFAULT_COLORS.get(consumer) : null;
    }
}
