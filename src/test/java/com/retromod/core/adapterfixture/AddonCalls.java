/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core.adapterfixture;

/** An addon that hands tiers to two libraries, written against one copy of each library's API. */
public final class AddonCalls {

    private static final TierFixture OWN = new AddonTier();

    private AddonCalls() {}

    public static int ownUses(boolean second) {
        return second ? SecondApi.uses(OWN) : LibraryApi.uses(OWN);
    }

    public static boolean ironIsIron(boolean second) {
        return second ? SecondApi.isIron(TierFixtureView.IRON()) : LibraryApi.isIron(TierFixtureView.IRON());
    }

    public static boolean ownComesBack(boolean second) {
        return (second ? SecondApi.same(OWN) : LibraryApi.same(OWN)) == OWN;
    }

    public static boolean ironComesBack(boolean second) {
        TierFixture iron = TierFixtureView.IRON();
        return (second ? SecondApi.same(iron) : LibraryApi.same(iron)) == iron;
    }

    public static int standardUses(boolean second) {
        return (second ? SecondApi.standard : LibraryApi.standard).uses();
    }

    /** The second library calls the addon's override of its abstract method. */
    public static int overrideRuns() {
        return SecondBase.run(new AddonSub());
    }

    /** The second library calls the addon's implementation of its interface, which returns the addon's own tier. */
    public static int implementationRuns() {
        return SecondRenderer.run(new AddonRenderer());
    }

    /** Two equal but distinct tiers each come back as themselves. */
    public static boolean equalTiersStayApart() {
        TierFixture first = new ValueTier(5);
        TierFixture second = new ValueTier(5);
        return SecondApi.same(first) == first && SecondApi.same(second) == second;
    }

    /** Hands a fresh tier to the second library and drops it. */
    public static void sendAndForget(int uses) {
        SecondApi.uses(new ValueTier(uses));
    }

    /** The identity the second library sees for the addon's own tier. */
    public static int ownSeenBySecond() {
        return SecondApi.seenAs(OWN);
    }

    public static int both(boolean second) {
        TierFixture gold = TierFixtureView.GOLD();
        return second ? SecondApi.both(OWN, 1, gold) : LibraryApi.both(OWN, 1, gold);
    }
}
