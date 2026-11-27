/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.polyfill.minecraft;

import com.retromod.shim.common.embedded.LegacyPublicMethods;
import java.lang.reflect.Method;

/**
 * Accessors of the 1.21.x Fabric {@code WorldRenderContext} that 26.1's
 * {@code LevelRenderContext} dropped, plus the no-argument {@code Camera.getNearPlane()}. Each
 * takes the receiver as its first argument (the call is devirtualized) and reads the same value
 * from the client, which is what the old context returned during level rendering.
 *
 * <p>No compile-time Minecraft dependency: public members are reached reflectively and cached.
 */
public final class LegacyWorldRenderContext {

    private static volatile Method cameraAccessor;

    private LegacyWorldRenderContext() {}

    /** {@code WorldRenderContext.camera()}: the game renderer's main camera. */
    public static Object camera(Object context) {
        try {
            Object renderer = call(context, "gameRenderer");
            Method accessor = cameraAccessor;
            if (accessor == null) {
                accessor = zeroArg(renderer.getClass(), "mainCamera");
                if (accessor == null) accessor = zeroArg(renderer.getClass(), "getMainCamera");
                cameraAccessor = accessor;
            }
            return accessor.invoke(renderer);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("Retromod could not reach the main camera for a legacy "
                    + "world render callback", e);
        }
    }

    /** {@code WorldRenderContext.frustum()}: the camera's culling frustum. */
    public static Object frustum(Object context) {
        try {
            return call(camera(context), "getCullFrustum");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not reach the culling frustum for a legacy "
                    + "world render callback", e);
        }
    }

    /** {@code WorldRenderContext.world()}: the client level. */
    public static Object world(Object context) {
        try {
            Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft", false,
                    LegacyWorldRenderContext.class.getClassLoader());
            Object client = minecraft.getMethod("getInstance").invoke(null);
            return minecraft.getField("level").get(client);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not reach the client level for a legacy "
                    + "world render callback", e);
        }
    }

    /** {@code WorldRenderContext.tickCounter()}: the client's delta tracker. */
    public static Object tickCounter(Object context) {
        try {
            Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft", false,
                    LegacyWorldRenderContext.class.getClassLoader());
            Object client = minecraft.getMethod("getInstance").invoke(null);
            return minecraft.getMethod("getDeltaTracker").invoke(client);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not reach the delta tracker for a legacy "
                    + "world render callback", e);
        }
    }

    /** {@code WorldRenderContext.projectionMatrix()}: a copy of the camera's projection from the level render state. */
    public static Object projectionMatrix(Object context) {
        try {
            Object state = call(context, "levelState");
            Object camera = state.getClass().getField("cameraRenderState").get(state);
            Object projection = camera.getClass().getField("projectionMatrix").get(camera);
            // A copy, so old code that changes the matrix leaves the game's own alone.
            Class<?> matrix = Class.forName("org.joml.Matrix4f", false, LegacyWorldRenderContext.class.getClassLoader());
            Class<?> readOnly = Class.forName("org.joml.Matrix4fc", false, LegacyWorldRenderContext.class.getClassLoader());
            return matrix.getConstructor(readOnly).newInstance(projection);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("Retromod could not reach the projection matrix for a legacy "
                    + "world render callback", e);
        }
    }

    /** {@code WorldRenderContext.tickDelta()}: the frame's partial tick from the client's delta tracker. */
    public static float tickDelta(Object context) {
        try {
            Object tracker = tickCounter(context);
            Method partialTick = LegacyPublicMethods.method(tracker.getClass(), "getGameTimeDeltaPartialTick", boolean.class);
            return (Float) partialTick.invoke(tracker, false);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("Retromod could not reach the partial tick for a legacy "
                    + "world render callback", e);
        }
    }

    /** {@code WorldRenderContext.blockOutlines()}: whether the block outline draws this frame, which Fabric API left on by default. */
    public static boolean blockOutlines(Object context) {
        return true;
    }

    /**
     * {@code Camera.getNearPlane()}: 26.1 takes the field of view as an argument; the old method
     * used the camera's own.
     */
    public static Object nearPlane(Object camera) {
        try {
            float fov = (Float) call(camera, "getFov");
            return LegacyPublicMethods.method(camera.getClass(), "getNearPlane", float.class).invoke(camera, fov);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not compute the camera near plane", e);
        }
    }

    private static Object call(Object target, String name) throws ReflectiveOperationException {
        Method method = zeroArg(target.getClass(), name);
        if (method == null) throw new NoSuchMethodException(target.getClass().getName() + "." + name);
        return method.invoke(target);
    }

    private static Method zeroArg(Class<?> type, String name) {
        for (Method method : LegacyPublicMethods.methods(type)) {
            if (method.getName().equals(name) && method.getParameterCount() == 0) return method;
        }
        return null;
    }
}
