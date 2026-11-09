/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.compost;


/** One component initializer for an item. */
public interface FakeInitializer {
    void run(FakeComponentMapBuilder builder, FakeLookupProvider lookup, FakeResourceKey key);
}
