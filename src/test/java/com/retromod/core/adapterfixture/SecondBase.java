/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core.adapterfixture;

/** A second-library base class whose abstract method takes a tier, which the library calls itself. */
public abstract class SecondBase {

    public abstract int score(TierFixture tier);

    public static int run(SecondBase base) {
        return base.score(TierFixtureView.IRON());
    }
}
