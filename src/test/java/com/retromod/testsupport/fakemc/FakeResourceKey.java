/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc;

/** Stands in for Minecraft's {@code ResourceKey}: a registry's id and an entry's id. */
public final class FakeResourceKey {

    private final FakeIdentifier registry;
    private final FakeIdentifier location;

    private FakeResourceKey(FakeIdentifier registry, FakeIdentifier location) {
        this.registry = registry;
        this.location = location;
    }

    public static FakeResourceKey create(FakeResourceKey registry, FakeIdentifier location) {
        return new FakeResourceKey(registry.location, location);
    }

    public static FakeResourceKey registryKey(String name) {
        return new FakeResourceKey(FakeIdentifier.parse("root"), FakeIdentifier.parse(name));
    }

    public boolean isFor(FakeResourceKey registryKey) {
        return registry.equals(registryKey.location);
    }

    @Override
    public String toString() {
        return "ResourceKey[" + registry + " / " + location + "]";
    }
}
