/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.dimension;

/** Stands in for {@code EnvironmentAttributes}: only the cloud height. */
public final class FakeAttributes {
    public static final FakeAttribute<Float> CLOUD_HEIGHT = new FakeAttribute<>();

    private FakeAttributes() {}
}
