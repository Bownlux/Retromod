/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.compost;

/** A registry key. */
public final class FakeResourceKey {
    private final FakeIdentifier id;

    private FakeResourceKey(FakeIdentifier id) {
        this.id = id;
    }

    public static FakeResourceKey create(FakeResourceKey registry, FakeIdentifier id) {
        return new FakeResourceKey(id);
    }

    public FakeIdentifier identifier() {
        return id;
    }
}
