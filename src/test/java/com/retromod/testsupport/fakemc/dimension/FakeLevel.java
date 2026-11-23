/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.dimension;

/** Stands in for {@code Level}: a dimension type and a cloud height attribute. */
public class FakeLevel {
    private final FakeDimensionType type;
    private final Float cloudHeight;

    public FakeLevel(FakeDimensionType type, Float cloudHeight) {
        this.type = type;
        this.cloudHeight = cloudHeight;
    }

    public FakeDimensionType dimensionType() {
        return type;
    }

    public FakeAttributeReader environmentAttributes() {
        return new FakeLevelAttributes(cloudHeight);
    }
}
