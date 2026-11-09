/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric.mcstub;

/** Stands in for {@code net.minecraft.world.level.material.MapColor} in bridge tests. */
public class StubMapColor {
    public static final StubMapColor NONE = new StubMapColor();
    public static final StubMapColor STONE = new StubMapColor();
}
