/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core.adapterfixture;

/** A stand-in shaped like the rebuilt Tier: a method, the old enum's name, and statics answered by views. */
public interface TierFixture {

    int uses();

    default String name() {
        return toString();
    }

    static TierFixture[] values() {
        return new TierFixture[]{TierFixtureView.IRON(), TierFixtureView.GOLD()};
    }

    static TierFixture valueOf(String name) {
        if (name.equals("IRON")) return TierFixtureView.IRON();
        if (name.equals("GOLD")) return TierFixtureView.GOLD();
        throw new IllegalArgumentException(name);
    }
}
