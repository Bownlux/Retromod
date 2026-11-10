/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core.adapterfixture;

/** An addon's own tier, as the test mod's LibraryTier. */
public final class AddonTier implements TierFixture {

    @Override
    public int uses() {
        return 7;
    }
}
