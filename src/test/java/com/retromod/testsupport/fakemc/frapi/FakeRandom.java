/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.frapi;

/** Stands in for RandomSource. */
public interface FakeRandom {
    static FakeRandom create(long seed) {
        return new FakeRandomImpl(seed);
    }

    void setSeed(long seed);

    long nextLong();

    int nextInt(int bound);
}
