/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.compost;

import java.util.Map;

/** A component map over a plain map. */
public class FakeSimpleComponentMap implements FakeComponentMap {
    private final Map<FakeComponentType, Object> values;

    public FakeSimpleComponentMap(Map<FakeComponentType, Object> values) {
        this.values = values;
    }

    public Object get(FakeComponentType type) {
        return values.get(type);
    }

    public Map<FakeComponentType, Object> values() {
        return values;
    }
}
