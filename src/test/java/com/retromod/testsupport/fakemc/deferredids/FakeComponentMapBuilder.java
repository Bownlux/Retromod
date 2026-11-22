/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.deferredids;

/** Stand-in for {@code DataComponentMap.Builder}. */
public final class FakeComponentMapBuilder {
    private final java.util.Map<FakeComponentType, Object> values = new java.util.HashMap<>();

    public FakeComponentMapBuilder set(FakeComponentType type, Object value) {
        values.put(type, value);
        return this;
    }

    public FakeComponentMapBuilder addAll(FakeComponentMap map) {
        values.putAll(((FakeSimpleComponentMap) map).values());
        return this;
    }

    public FakeComponentMap build() {
        return new FakeSimpleComponentMap(new java.util.HashMap<>(values));
    }
}
