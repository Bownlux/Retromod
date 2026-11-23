/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.deferredids;

/** Stand-in for {@code Component}, with the static factory the rebuilt class calls. */
public interface FakeComponent {
    static FakeMutableComponent translatable(String key) {
        return new FakeMutableComponent(key);
    }
}
