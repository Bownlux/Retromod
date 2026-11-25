/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.FloatBuffer;

/**
 * Minecraft 1.21.1's shader uniform, which 1.21.5 replaced with uniform buffers that each render pipeline fills. Old
 * shader programs never run on 26.x, so every value set here is dropped.
 */
public class LegacyUniform {

    private static final LegacyUniform INERT = new LegacyUniform();

    protected LegacyUniform() {}

    /** {@code program.safeGetUniform(name)} and {@code getUniform(name)} where the old uniform type is expected. */
    public static LegacyUniform inert(Object program, String name) {
        return INERT;
    }

    /** {@code getUniform(name)} where a Fabric mod expects the host's uniform type: none, as the old program has none. */
    public static Object none(Object program, String name) {
        return null;
    }

    /** A named uniform field such as {@code MODEL_VIEW_MATRIX}, read as the old uniform type. */
    public static LegacyUniform inertField(Object program) {
        return INERT;
    }

    /** The same field read as the host's uniform type. */
    public static Object noField(Object program) {
        return null;
    }

    /** {@code program.getVertexProgram()} and {@code getFragmentProgram()}: none, as the old program parts are never compiled. */
    public static Object noProgram(Object program) {
        return null;
    }

    /** {@code program.uniforms}: an empty list, as no old uniform is ever parsed. */
    public static java.util.List<Object> noUniforms(Object program) {
        return new java.util.ArrayList<>();
    }

    /** {@code program.getId()}: no GL program, as old shader programs are never linked on 26.x. */
    public static int programId(Object program) {
        return 0;
    }

    public void set(float x) {}
    public void set(float x, float y) {}
    public void set(float x, float y, float z) {}
    public void set(float x, float y, float z, float w) {}
    public void setSafe(float x, float y, float z, float w) {}
    public void set(int x) {}
    public void set(int x, int y) {}
    public void set(int x, int y, int z) {}
    public void set(int x, int y, int z, int w) {}
    public void setSafe(int x, int y, int z, int w) {}
    public void set(float[] values) {}
    public void set(Matrix4f matrix) {}
    public void set(Matrix3f matrix) {}
    public void set(Vector3f vector) {}
    public void set(Vector4f vector) {}
    public void upload() {}
    public void close() {}

    /** No location, as the uniform is in no linked program. */
    public int getLocation() {
        return -1;
    }

    /** A buffer of its own the size of a 4x4 matrix, so code that writes into it keeps running. */
    public FloatBuffer getFloatBuffer() {
        return FloatBuffer.allocate(16);
    }

    // The same calls on the host's uniform interface, which a Fabric mod's old uniform type now names; each is dropped.
    public static void set(Object uniform, float x) {}
    public static void set(Object uniform, float x, float y) {}
    public static void set(Object uniform, float x, float y, float z) {}
    public static void set(Object uniform, float x, float y, float z, float w) {}
    public static void set(Object uniform, int x) {}
    public static void set(Object uniform, float[] values) {}
    public static void set(Object uniform, Matrix4f matrix) {}
    public static void set(Object uniform, Matrix3f matrix) {}
    public static void upload(Object uniform) {}

    public static int getLocation(Object uniform) {
        return -1;
    }
}
