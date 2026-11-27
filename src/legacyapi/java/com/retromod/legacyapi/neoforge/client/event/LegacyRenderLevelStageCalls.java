/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.client.event;

import com.retromod.shim.common.embedded.LegacyLog;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** The getters NeoForge 1.21.1's RenderLevelStageEvent had, answered on 26.x from the game and the level render state. */
public final class LegacyRenderLevelStageCalls {

    private LegacyRenderLevelStageCalls() {}

    /** {@code event.getStage()}. */
    public static LegacyRenderLevelStage getStage(RenderLevelStageEvent event) {
        return LegacyRenderLevelStage.of(event);
    }

    /** {@code event.getPartialTick()}: the game's own delta tracker. */
    public static DeltaTracker getPartialTick(RenderLevelStageEvent event) {
        return Minecraft.getInstance().getDeltaTracker();
    }

    /** Forge 1.20.1's {@code event.getPartialTick()}, a float: the game's partial tick for this frame. */
    public static float getPartialTickFloat(RenderLevelStageEvent event) {
        return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
    }

    /** {@code event.getCamera()}: the main camera, named mainCamera() from 26.2 and getMainCamera() before. */
    public static Camera getCamera(RenderLevelStageEvent event) {
        Object renderer = Minecraft.getInstance().gameRenderer;
        for (String name : new String[]{"mainCamera", "getMainCamera"}) {
            Object camera = call(renderer, name);
            if (camera instanceof Camera found) return found;
        }
        return null;
    }

    /** {@code event.getModelViewMatrix()}, which returned a mutable matrix; a copy keeps old code from changing the game's. */
    public static Matrix4f getModelViewMatrix(RenderLevelStageEvent event) {
        return new Matrix4f(event.getModelViewMatrix());
    }

    /** {@code event.getProjectionMatrix()}: the camera's projection from the level render state. */
    public static Matrix4f getProjectionMatrix(RenderLevelStageEvent event) {
        Object state = call(event, "getLevelRenderState");
        Object camera = field(state, "cameraRenderState");
        if (field(camera, "projectionMatrix") instanceof Matrix4fc projection) return new Matrix4f(projection);
        LegacyLog.warnOnce("render stage projection", "An old mod read the projection matrix in a render stage event, which this"
                + " Minecraft version keeps elsewhere, so it got an identity matrix. Report this line with the mod.");
        return new Matrix4f();
    }

    /** {@code event.getFrustum()}: the camera's culling frustum from the level render state, or null with one warning. */
    public static Frustum getFrustum(RenderLevelStageEvent event) {
        Object state = call(event, "getLevelRenderState");
        if (field(field(state, "cameraRenderState"), "cullFrustum") instanceof Frustum frustum) return frustum;
        LegacyLog.warnOnce("render stage frustum", "An old mod read the culling frustum in a render stage event, which this"
                + " Minecraft version does not keep there, so it got none and may fail when it uses it.");
        return null;
    }

    /** {@code event.getRenderTick()}: the level's game time, which counted the same ticks. */
    public static int getRenderTick(RenderLevelStageEvent event) {
        Object state = call(event, "getLevelRenderState");
        return field(state, "gameTime") instanceof Long time ? (int) (long) time : 0;
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
