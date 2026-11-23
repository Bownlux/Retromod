/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.biomesearch;

/** Stands in for {@code BiomeResolver}. */
public interface FakeBiomeResolver {
    FakeHolder getNoiseBiome(int x, int y, int z);
}
