/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.biomesearch;

/** A biome holder named after the biome, so a predicate can test it by {@code toString}. */
public final class FakeNamedBiome implements FakeHolder {
    private final String name;

    public FakeNamedBiome(String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        return name;
    }
}
