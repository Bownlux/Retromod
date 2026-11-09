/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.compost;

/** The compostable component: how many layers an item adds, here always a preset key. */
public final class FakeCompostable {
    private final FakeResolvableInt layers;

    public FakeCompostable(FakeResourceKey key) {
        this.layers = new FakeResolvableIntReference(key);
    }

    public FakeCompostable(FakeResolvableInt layers) {
        this.layers = layers;
    }

    public FakeResolvableInt layers() {
        return layers;
    }
}
