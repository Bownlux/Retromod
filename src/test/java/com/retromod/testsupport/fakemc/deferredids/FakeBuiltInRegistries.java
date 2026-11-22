/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.deferredids;

/** Stand-in for {@code BuiltInRegistries}. */
public final class FakeBuiltInRegistries {
    public static final FakeComponentInitializers DATA_COMPONENT_INITIALIZERS = new FakeComponentInitializers();

    private FakeBuiltInRegistries() {}
}
