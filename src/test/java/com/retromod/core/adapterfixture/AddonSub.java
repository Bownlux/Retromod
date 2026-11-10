/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core.adapterfixture;

/** An addon class overriding the second library's abstract method. */
public final class AddonSub extends SecondBase {

    @Override
    public int score(TierFixture tier) {
        return tier.uses() + 1;
    }
}
