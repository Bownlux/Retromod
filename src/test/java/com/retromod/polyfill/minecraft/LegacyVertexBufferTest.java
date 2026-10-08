/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.polyfill.minecraft;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/** The stand-in recovers the camera-relative offset from a legacy model-view matrix (#296). */
class LegacyVertexBufferTest {

    @Test
    void cameraRotationUndoesTheModViewRotationAndKeepsTheOffset() {
        // A camera turned 90 degrees around Y: its rotation quaternion is (0, sin45, 0, cos45).
        float s = (float) Math.sqrt(0.5);
        float[] cameraRotation = LegacyVertexBuffer.rotationMatrix(0, s, 0, s);
        // The mod's model-view is the inverse rotation followed by a translation of (1, 2, 3).
        float[] inverse = LegacyVertexBuffer.rotationMatrix(0, -s, 0, s);
        float[] translate = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 1, 2, 3, 1};
        float[] modelView = LegacyVertexBuffer.multiply(inverse, translate);

        float[] cameraSpace = LegacyVertexBuffer.multiply(cameraRotation, modelView);

        assertArrayEquals(translate, cameraSpace, 1e-5F,
                "undoing the view rotation must leave only the camera-relative translation");
    }
}
