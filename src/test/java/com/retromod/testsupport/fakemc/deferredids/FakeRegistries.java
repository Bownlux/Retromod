/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.deferredids;

/** Stand-in for {@code Registries}. */
public final class FakeRegistries {
    public static final FakeResourceKey ITEM = FakeResourceKey.create(null, FakeIdentifier.fromNamespaceAndPath("minecraft", "item"));
    public static final FakeResourceKey BLOCK = FakeResourceKey.create(null, FakeIdentifier.fromNamespaceAndPath("minecraft", "block"));
    public static final FakeResourceKey LOOT_TABLE = FakeResourceKey.create(null, FakeIdentifier.fromNamespaceAndPath("minecraft", "loot_table"));

    private FakeRegistries() {}
}
