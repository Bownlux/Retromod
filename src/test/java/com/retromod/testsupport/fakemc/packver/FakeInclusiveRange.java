/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.packver;

/** An inclusive range. */
public final class FakeInclusiveRange<T extends Comparable<T>> {
    private final T min;
    private final T max;

    public FakeInclusiveRange(T min, T max) {
        this.min = min;
        this.max = max;
    }

    public T minInclusive() {
        return min;
    }

    public T maxInclusive() {
        return max;
    }
}
