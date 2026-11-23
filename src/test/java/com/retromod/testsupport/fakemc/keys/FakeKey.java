/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.keys;

/** Stands in for {@code InputConstants$Key}. */
public final class FakeKey {
    private final int value;

    public FakeKey(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}
