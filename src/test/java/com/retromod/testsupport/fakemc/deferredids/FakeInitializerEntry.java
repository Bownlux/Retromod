/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.deferredids;

/** Stand-in for {@code DataComponentInitializers.InitializerEntry}, a record as on the host. */
record FakeInitializerEntry(FakeResourceKey key, FakeInitializer initializer) {
}
