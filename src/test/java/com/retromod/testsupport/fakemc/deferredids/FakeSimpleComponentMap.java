/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.deferredids;

/** The component map the builder makes. */
public record FakeSimpleComponentMap(java.util.Map<FakeComponentType, Object> values) implements FakeComponentMap {
    @Override
    public Object get(FakeComponentType type) {
        return values.get(type);
    }
}
