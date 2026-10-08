/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric.mcstub;

/** Stands in for {@code net.minecraft.resources.ResourceKey} in bridge behavior tests. */
public final class StubResourceKey {
    public final Object registry;
    public final StubIdentifier location;

    private StubResourceKey(Object registry, StubIdentifier location) {
        this.registry = registry;
        this.location = location;
    }

    public static StubResourceKey create(StubResourceKey registry, StubIdentifier location) {
        return new StubResourceKey(registry, location);
    }

    public static StubResourceKey root(String name) {
        return new StubResourceKey(name, null);
    }
}
