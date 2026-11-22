/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.biomesearch;

import java.util.Random;

/** A seeded random behind the {@code RandomSource} stand-in. */
public final class FakeSeededRandom implements FakeRandomSource {
    private final Random random;

    public FakeSeededRandom(long seed) {
        this.random = new Random(seed);
    }

    @Override
    public int nextInt(int bound) {
        return random.nextInt(bound);
    }
}
