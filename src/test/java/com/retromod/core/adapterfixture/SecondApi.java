/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core.adapterfixture;

/** The second tool library's API, the same as {@link LibraryApi}. */
public final class SecondApi {

    public static TierFixture standard = TierFixtureView.GOLD();

    private SecondApi() {}

    public static int uses(TierFixture tier) {
        return tier.uses();
    }

    /** Compares with the library's own constant, as {@code tier == Tiers.IRON} does. */
    public static boolean isIron(TierFixture tier) {
        return tier == TierFixtureView.IRON();
    }

    public static TierFixture same(TierFixture tier) {
        return tier;
    }

    /** The identity the library sees, so a test can tell whether two calls passed the same object. */
    public static int seenAs(TierFixture tier) {
        return System.identityHashCode(tier);
    }

    public static int both(TierFixture first, int extra, TierFixture second) {
        return first.uses() + extra + second.uses();
    }
}
