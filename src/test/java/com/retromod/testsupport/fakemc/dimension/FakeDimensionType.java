/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.dimension;

/** Stands in for {@code DimensionType}: a skybox and a cardinal lighting type. */
public final class FakeDimensionType {
    private final FakeSkybox skybox;
    private final FakeCardinalType lighting;

    public FakeDimensionType(FakeSkybox skybox, FakeCardinalType lighting) {
        this.skybox = skybox;
        this.lighting = lighting;
    }

    public FakeSkybox skybox() {
        return skybox;
    }

    public FakeCardinalType cardinalLightType() {
        return lighting;
    }
}
