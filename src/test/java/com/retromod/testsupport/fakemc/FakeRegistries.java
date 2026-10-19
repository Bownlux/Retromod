/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc;

/** Stands in for Minecraft's {@code Registries}: the registry keys a test needs. */
public final class FakeRegistries {

    public static final FakeResourceKey ENTITY_TYPE = FakeResourceKey.registryKey("entity_type");
    public static final FakeResourceKey ITEM = FakeResourceKey.registryKey("item");

    private FakeRegistries() {}
}
