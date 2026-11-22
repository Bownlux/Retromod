/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.biomesearch;

/** Stands in for DataFixerUpper's {@code Pair}. */
public final class FakePair {
    private final Object first;
    private final Object second;

    private FakePair(Object first, Object second) {
        this.first = first;
        this.second = second;
    }

    public static FakePair of(Object first, Object second) {
        return new FakePair(first, second);
    }

    public Object getFirst() {
        return first;
    }

    public Object getSecond() {
        return second;
    }
}
