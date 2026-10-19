/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc;

/** Stands in for a built {@code EntityType}: it remembers the key it was built with. */
public final class FakeEntityType {

    private final FakeResourceKey key;

    FakeEntityType(FakeResourceKey key) {
        this.key = key;
    }

    @Override
    public String toString() {
        return String.valueOf(key);
    }
}
