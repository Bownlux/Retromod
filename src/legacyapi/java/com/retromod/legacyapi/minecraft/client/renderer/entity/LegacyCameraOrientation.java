/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer.entity;

/** The camera override old screens set before drawing an entity, which 1.21.11 and newer no longer take. */
public final class LegacyCameraOrientation {

    private LegacyCameraOrientation() {}

    /**
     * {@code dispatcher.overrideCameraOrientation(rotation)}: screens drew entities facing the player with it. An entity
     * drawn into a screen now gets a camera facing the screen from the GUI itself, so the call has nothing left to do.
     */
    public static void overrideCameraOrientation(Object dispatcher, Object rotation) {
    }
}
