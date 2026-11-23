/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer;

import com.mojang.blaze3d.vertex.VertexSorting;
import org.joml.Matrix4f;

/** The old RenderSystem projection matrix and vertex sorting, kept as plain state since the host now takes a GPU buffer. */
public final class LegacyProjection {

    // The last values an old mod set, so its save, change, and restore code reads back what it wrote.
    private static final Matrix4f PROJECTION = new Matrix4f();
    private static VertexSorting sorting;

    private LegacyProjection() {}

    /** {@code RenderSystem.setProjectionMatrix(matrix, sorting)}: stored only, the host's own projection stays in effect. */
    public static void setProjectionMatrix(Matrix4f matrix, VertexSorting newSorting) {
        synchronized (PROJECTION) {
            if (matrix != null) PROJECTION.set(matrix);
            sorting = newSorting;
        }
    }

    /** {@code RenderSystem.getProjectionMatrix()}: a copy of the last stored matrix, identity before any. */
    public static Matrix4f getProjectionMatrix() {
        synchronized (PROJECTION) {
            return new Matrix4f(PROJECTION);
        }
    }

    /** {@code RenderSystem.getVertexSorting()}: the last stored sorting, or the orthographic one GUI code used. */
    public static VertexSorting getVertexSorting() {
        synchronized (PROJECTION) {
            return sorting != null ? sorting : VertexSorting.ORTHOGRAPHIC_Z;
        }
    }
}
