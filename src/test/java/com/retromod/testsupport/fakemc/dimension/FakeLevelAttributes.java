/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.dimension;

/** The level's attributes: only the cloud height is answered. */
public final class FakeLevelAttributes implements FakeAttributeReader {
    private final Float cloudHeight;

    public FakeLevelAttributes(Float cloudHeight) {
        this.cloudHeight = cloudHeight;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <Value> Value getDimensionValue(FakeAttribute<Value> attribute) {
        return attribute == FakeAttributes.CLOUD_HEIGHT ? (Value) cloudHeight : null;
    }
}
