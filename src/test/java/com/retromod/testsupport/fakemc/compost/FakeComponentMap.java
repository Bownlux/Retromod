/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.compost;

/** A component map. */
public interface FakeComponentMap {
    Object get(FakeComponentType type);

    static FakeComponentMapBuilder builder() {
        return new FakeComponentMapBuilder();
    }
}
