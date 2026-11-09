/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.compost;

import java.util.HashMap;
import java.util.Map;

/** Builds a component map; a null value removes the component, as Minecraft's does. */
public class FakeComponentMapBuilder {
    private final Map<FakeComponentType, Object> values = new HashMap<>();

    public FakeComponentMapBuilder set(FakeComponentType type, Object value) {
        if (value == null) values.remove(type);
        else values.put(type, value);
        return this;
    }

    public FakeComponentMapBuilder addAll(FakeComponentMap map) {
        if (map instanceof FakeSimpleComponentMap simple) values.putAll(simple.values());
        return this;
    }

    public FakeComponentMap build() {
        return new FakeSimpleComponentMap(new HashMap<>(values));
    }
}
