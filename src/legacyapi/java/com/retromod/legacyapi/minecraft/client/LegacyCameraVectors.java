/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client;

import net.minecraft.client.Camera;
import org.joml.Vector3f;

/** {@code Camera.getLookVector()}, {@code getUpVector()}, and {@code getLeftVector()}, which became read-only vectors. */
public final class LegacyCameraVectors {

    private LegacyCameraVectors() {}

    /** A copy of the direction the camera faces, so old code that changed the vector it got changes only its copy. */
    public static Vector3f getLookVector(Camera camera) {
        return new Vector3f(camera.forwardVector());
    }

    /** A copy of the camera's up direction. */
    public static Vector3f getUpVector(Camera camera) {
        return new Vector3f(camera.upVector());
    }

    /** A copy of the camera's left direction. */
    public static Vector3f getLeftVector(Camera camera) {
        return new Vector3f(camera.leftVector());
    }
}
