/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.dimension;

/** Stands in for {@code EnvironmentAttributeReader}. */
public interface FakeAttributeReader {
    <Value> Value getDimensionValue(FakeAttribute<Value> attribute);
}
