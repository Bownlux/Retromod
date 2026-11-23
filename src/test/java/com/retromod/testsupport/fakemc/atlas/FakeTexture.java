/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.atlas;

/** Stands in for {@code AbstractTexture}. */
public class FakeTexture {
    private final String name;

    public FakeTexture(String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        return name;
    }
}
