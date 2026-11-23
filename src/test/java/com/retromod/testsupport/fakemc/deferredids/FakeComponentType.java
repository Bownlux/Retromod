/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.deferredids;

/** Stand-in for {@code DataComponentType}. */
public final class FakeComponentType {
    private final String name;

    public FakeComponentType(String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        return name;
    }
}
