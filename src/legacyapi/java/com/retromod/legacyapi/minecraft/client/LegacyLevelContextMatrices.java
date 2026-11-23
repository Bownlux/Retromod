/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client;

import com.retromod.shim.common.embedded.LegacyLog;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** Fabric API's old {@code positionMatrix()} on the level render context, read from the camera render state. */
public final class LegacyLevelContextMatrices {

    private LegacyLevelContextMatrices() {}

    /** The camera's view rotation, copied so old code cannot change the game's own matrix. */
    public static Matrix4f positionMatrix(Object context) {
        Object camera = field(call(context, "levelState"), "cameraRenderState");
        if (field(camera, "viewRotationMatrix") instanceof Matrix4fc view) return new Matrix4f(view);
        LegacyLog.warnOnce("level context position matrix", "An old mod read the position matrix of a level render context,"
                + " which this Minecraft version does not offer there, so it got an identity matrix. Report this line with the mod.");
        return new Matrix4f();
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

    private static Object field(Object target, String name) {
        if (target == null) return null;
        try {
            Field field = target.getClass().getField(name);
            return field.get(target);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }
}
