/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric.mcstub;

/** Stands in for {@code net.minecraft.world.item.Item$Properties} in bridge behavior tests. */
public class StubItemProperties {
    public StubResourceKey id;

    public StubItemProperties setId(StubResourceKey id) {
        this.id = id;
        return this;
    }
}
