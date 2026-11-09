/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.compost;

/** An int that names a registered provider. */
public final class FakeResolvableIntReference implements FakeResolvableInt {
    private final FakeResourceKey key;

    public FakeResolvableIntReference(FakeResourceKey key) {
        this.key = key;
    }

    public FakeResourceKey key() {
        return key;
    }
}
