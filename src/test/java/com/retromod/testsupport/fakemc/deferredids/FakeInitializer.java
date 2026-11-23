/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.deferredids;

/** Stand-in for {@code DataComponentInitializers.Initializer}. */
public interface FakeInitializer {
    void run(FakeComponentMapBuilder builder, FakeLookupProvider lookup, FakeResourceKey key);
}
