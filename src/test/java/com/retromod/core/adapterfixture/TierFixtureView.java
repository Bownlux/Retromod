/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core.adapterfixture;

/** The old enum constants of {@link TierFixture}, one cached instance each, as the rebuilt views are. */
public final class TierFixtureView implements TierFixture {

    private static final TierFixtureView IRON = new TierFixtureView("IRON", 250);
    private static final TierFixtureView GOLD = new TierFixtureView("GOLD", 32);

    private final String name;
    private final int uses;

    private TierFixtureView(String name, int uses) {
        this.name = name;
        this.uses = uses;
    }

    public static TierFixture IRON() {
        return IRON;
    }

    public static TierFixture GOLD() {
        return GOLD;
    }

    @Override
    public int uses() {
        return uses;
    }

    @Override
    public String toString() {
        return name;
    }
}
