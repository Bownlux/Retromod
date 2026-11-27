/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.frapi;

/** A seeded random behind {@link FakeRandom}. */
public final class FakeRandomImpl implements FakeRandom {
    private final java.util.Random random;

    public FakeRandomImpl(long seed) {
        random = new java.util.Random(seed);
    }

    @Override
    public void setSeed(long seed) {
        random.setSeed(seed);
    }

    @Override
    public long nextLong() {
        return random.nextLong();
    }

    @Override
    public int nextInt(int bound) {
        return random.nextInt(bound);
    }
}
