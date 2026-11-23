/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.deferredids;

/** Stand-in for {@code MutableComponent}: a translatable key. */
public final class FakeMutableComponent implements FakeComponent {
    private final String key;

    FakeMutableComponent(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FakeMutableComponent component && component.key.equals(key);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    @Override
    public String toString() {
        return "translatable{" + key + "}";
    }
}
