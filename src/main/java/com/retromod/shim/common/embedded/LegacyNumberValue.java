/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

/**
 * An old {@code ConstantValue} or {@code UniformGenerator}. Before 26.3 one float-valued provider
 * served both int and float consumers, so the value is kept as written and only turned into an int
 * or a float provider once a 26.3 API says which one it needs.
 */
public final class LegacyNumberValue implements LegacyNumberProvider {

    private final float constant;
    /** The bounds of a uniform range, or null for a constant. Each is any loot number provider. */
    private final Object min;
    private final Object max;

    private LegacyNumberValue(float constant, Object min, Object max) {
        this.constant = constant;
        this.min = min;
        this.max = max;
    }

    static LegacyNumberValue constant(float value) {
        return new LegacyNumberValue(value, null, null);
    }

    static LegacyNumberValue uniform(Object min, Object max) {
        if (min == null || max == null) {
            throw new NullPointerException("a uniform loot number needs both bounds");
        }
        return new LegacyNumberValue(0F, min, max);
    }

    boolean isConstant() {
        return min == null;
    }

    float constant() {
        return constant;
    }

    Object min() {
        return min;
    }

    Object max() {
        return max;
    }

    @Override
    public String toString() {
        return isConstant() ? "ConstantValue[" + constant + "]" : "UniformGenerator[" + min + ", " + max + "]";
    }
}
