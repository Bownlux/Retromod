/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.biomesearch;

/** Stands in for {@code RandomSource}. */
public interface FakeRandomSource {
    int nextInt(int bound);
}
