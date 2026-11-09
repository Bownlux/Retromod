/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core.adapterfixture;

/** An addon tier that is equal to any other with the same uses, to tell equal objects apart from the same one. */
public final class ValueTier implements TierFixture {

    private final int uses;

    public ValueTier(int uses) {
        this.uses = uses;
    }

    @Override
    public int uses() {
        return uses;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ValueTier tier && tier.uses == uses;
    }

    @Override
    public int hashCode() {
        return uses;
    }
}
