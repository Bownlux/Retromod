/*
 * Retromod Test Library
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testlib;

import net.minecraft.item.ToolMaterial;

/**
 * A library mod for the test mod's #362 case. Its API takes a type that Retromod rebuilds inside
 * each translated jar on newer hosts, so the test mod's call only links when Retromod has it reuse
 * this jar's copy.
 */
public final class RetromodTestLib {

    private RetromodTestLib() {}

    /** The tool material interface Minecraft 1.21.2 removed, rebuilt per mod on newer hosts. */
    public static float speed(ToolMaterial material) {
        return material.getMiningSpeedMultiplier();
    }
}
