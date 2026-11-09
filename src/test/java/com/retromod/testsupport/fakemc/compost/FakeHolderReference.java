/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.compost;


/** An item's registry holder: its key, and its default components once they are bound. */
public class FakeHolderReference {
    private final FakeResourceKey key;
    private FakeComponentMap components;

    public FakeHolderReference(FakeResourceKey key) {
        this.key = key;
    }

    public FakeResourceKey key() {
        return key;
    }

    public boolean areComponentsBound() {
        return components != null;
    }

    public FakeComponentMap components() {
        return components;
    }

    public void bindComponents(FakeComponentMap components) {
        this.components = components;
    }
}
