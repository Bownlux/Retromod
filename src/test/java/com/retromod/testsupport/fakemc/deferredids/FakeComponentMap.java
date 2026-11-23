/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.deferredids;

/** Stand-in for {@code DataComponentMap}. */
public interface FakeComponentMap {
    Object get(FakeComponentType type);

    static FakeComponentMapBuilder builder() {
        return new FakeComponentMapBuilder();
    }
}
