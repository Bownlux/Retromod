/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core.adapterfixture;

/** A second-library interface an addon implements, as Curios' ICurioRenderer is implemented. */
public interface SecondRenderer {

    TierFixture render(TierFixture tier);

    static int run(SecondRenderer renderer) {
        return renderer.render(TierFixtureView.GOLD()).uses();
    }
}
