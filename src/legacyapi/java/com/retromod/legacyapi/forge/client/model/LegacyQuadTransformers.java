/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.model;

import com.mojang.math.Transformation;
import org.joml.Matrix3f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Forge 1.20.1's QuadTransformers, each editing a quad's int[] vertex data. */
public final class LegacyQuadTransformers {

    private static final int STRIDE = LegacyQuadVertices.STRIDE;
    private static final LegacyQuadTransformer EMPTY = quad -> { };

    private LegacyQuadTransformers() {}

    public static LegacyQuadTransformer empty() {
        return EMPTY;
    }

    /** Moves each position through the transformation and turns each normal with it; the face direction stays. */
    public static LegacyQuadTransformer applying(Transformation transform) {
        return quad -> {
            Matrix4fc matrix = transform.getMatrix();
            int[] vertices = LegacyQuadVertices.getVertices(quad);
            for (int vertex = 0; vertex < LegacyQuadVertices.VERTICES; vertex++) {
                int base = vertex * STRIDE + LegacyQuadVertices.POSITION;
                Vector4f position = new Vector4f(Float.intBitsToFloat(vertices[base]),
                        Float.intBitsToFloat(vertices[base + 1]), Float.intBitsToFloat(vertices[base + 2]), 1);
                matrix.transform(position);
                vertices[base] = Float.floatToRawIntBits(position.x / position.w);
                vertices[base + 1] = Float.floatToRawIntBits(position.y / position.w);
                vertices[base + 2] = Float.floatToRawIntBits(position.z / position.w);
            }
            Matrix3f normalMatrix = matrix.normal(new Matrix3f());
            for (int vertex = 0; vertex < LegacyQuadVertices.VERTICES; vertex++) {
                int index = vertex * STRIDE + LegacyQuadVertices.NORMAL;
                int packed = vertices[index];
                // A vertex with no normal keeps none.
                if ((packed & 0xFFFFFF) == 0) continue;
                Vector3f normal = new Vector3f((byte) packed / 127f, (byte) (packed >> 8) / 127f, (byte) (packed >> 16) / 127f);
                normal.mul(normalMatrix).normalize();
                vertices[index] = (packed & 0xFF000000) | ((byte) (normal.x * 127) & 0xFF)
                        | (((byte) (normal.y * 127) & 0xFF) << 8) | (((byte) (normal.z * 127) & 0xFF) << 16);
            }
        };
    }

    /** Writes one packed lightmap value to every vertex. 26.2 keeps its block half, as the quad's light emission. */
    public static LegacyQuadTransformer applyingLightmap(int lightmap) {
        return quad -> {
            int[] vertices = LegacyQuadVertices.getVertices(quad);
            for (int vertex = 0; vertex < LegacyQuadVertices.VERTICES; vertex++) {
                vertices[vertex * STRIDE + LegacyQuadVertices.UV2] = lightmap;
            }
        };
    }

    public static LegacyQuadTransformer applyingLightmap(int blockLight, int skyLight) {
        return applyingLightmap(blockLight << 4 | skyLight << 20);
    }

    /** Makes the quad glow at {@code emissivity}, 0 to 15, as block and sky light alike. */
    public static LegacyQuadTransformer settingEmissivity(int emissivity) {
        if (emissivity < 0 || emissivity >= 16) throw new IllegalArgumentException("Emissivity must be between 0 and 15.");
        return applyingLightmap(emissivity, emissivity);
    }

    public static LegacyQuadTransformer settingMaxEmissivity() {
        return settingEmissivity(15);
    }

    /** Writes one ARGB color to every vertex. 26.2 quads keep no vertex color, so the color is noted and dropped. */
    public static LegacyQuadTransformer applyingColor(int color) {
        int abgr = toABGR(color);
        return quad -> {
            int[] vertices = LegacyQuadVertices.getVertices(quad);
            for (int vertex = 0; vertex < LegacyQuadVertices.VERTICES; vertex++) {
                vertices[vertex * STRIDE + LegacyQuadVertices.COLOR] = abgr;
            }
        };
    }

    public static LegacyQuadTransformer applyingColor(int red, int green, int blue) {
        return applyingColor(255, red, green, blue);
    }

    public static LegacyQuadTransformer applyingColor(int alpha, int red, int green, int blue) {
        return applyingColor(alpha << 24 | red << 16 | green << 8 | blue);
    }

    /** Swaps red and blue, as vertex data stores colors. */
    public static int toABGR(int argb) {
        return (argb & 0xFF00FF00) | ((argb >> 16) & 0xFF) | ((argb << 16) & 0xFF0000);
    }
}
