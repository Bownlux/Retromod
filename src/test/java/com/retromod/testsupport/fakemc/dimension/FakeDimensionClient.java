/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.dimension;

/** Stands in for {@code Minecraft}, with no level loaded. */
public class FakeDimensionClient {
    public FakeClientLevel level;

    public static FakeDimensionClient getInstance() {
        return null;
    }
}
