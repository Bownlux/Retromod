/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.brewing;

/** Vanilla's three start potions. */
public final class FakePotions {
    public static final FakeHolder WATER = new FakeDirectHolder(new FakePotion("water"));
    public static final FakeHolder MUNDANE = new FakeDirectHolder(new FakePotion("mundane"));
    public static final FakeHolder AWKWARD = new FakeDirectHolder(new FakePotion("awkward"));

    private FakePotions() {}
}
