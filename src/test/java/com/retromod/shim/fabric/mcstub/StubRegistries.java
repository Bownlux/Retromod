/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric.mcstub;

/** Stands in for {@code net.minecraft.core.registries.Registries} in bridge behavior tests. */
public final class StubRegistries {
    public static final StubResourceKey ITEM = StubResourceKey.root("item");
    public static final StubResourceKey BLOCK = StubResourceKey.root("block");

    private StubRegistries() {}
}
