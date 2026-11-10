/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.util.random;

/** The {@code Weight} 1.21.5 removed: the non-negative whole number a weighted entry carries. */
public final class Weight {

    public static final Weight ONE = new Weight(1);

    private final int value;

    private Weight(int value) {
        this.value = value;
    }

    /** A weight of {@code value}, refusing a negative one as the old factory did. */
    public static Weight of(int value) {
        if (value == 1) return ONE;
        if (value < 0) {
            throw new IllegalArgumentException("A weighted entry's weight cannot be negative, but it was " + value);
        }
        return new Weight(value);
    }

    public int asInt() {
        return value;
    }

    @Override
    public String toString() {
        return Integer.toString(value);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof Weight weight && weight.value == value;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(value);
    }
}
