/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc;

/** Stands in for Minecraft's {@code BuiltInRegistries}. The stand-in registry keeps nothing. */
public final class FakeBuiltInRegistries {

    public static final FakeRegistry ENTITY_TYPE = null;

    private FakeBuiltInRegistries() {}
}
