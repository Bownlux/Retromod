/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.biomesearch;

/** Stands in for {@code BiomeSource}: a quart is "match" when its x plus z is a multiple of three. */
public class FakeBiomeSource {
    private static final FakeHolder MATCH = new FakeNamedBiome("match");
    private static final FakeHolder OTHER = new FakeNamedBiome("other");

    public FakeBiomeResolver createResolver(FakeSampler sampler) {
        return (x, y, z) -> biomeAt(x, z);
    }

    /** The biome at a quart, also read by the reference search in the test. */
    public static FakeHolder biomeAt(int quartX, int quartZ) {
        return Math.floorMod(quartX + quartZ, 3) == 0 ? MATCH : OTHER;
    }
}
