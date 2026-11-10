/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core.adapterfixture;

/** An addon class implementing the second library's interface, returning a tier of its own. */
public final class AddonRenderer implements SecondRenderer {

    @Override
    public TierFixture render(TierFixture tier) {
        return tier.uses() > 100 ? tier : new AddonTier();
    }
}
