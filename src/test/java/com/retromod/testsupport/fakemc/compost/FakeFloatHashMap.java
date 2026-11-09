/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.compost;

import java.util.HashMap;
import java.util.Map;

/** fastutil's Object2FloatOpenHashMap, backed by a plain map. */
public class FakeFloatHashMap implements FakeFloatMap {
    private final Map<Object, Float> values = new HashMap<>();

    public float put(Object key, float value) {
        Float old = values.put(key, value);
        return old == null ? 0f : old;
    }

    public float getFloat(Object key) {
        Float value = values.get(key);
        return value == null ? 0f : value;
    }

    public boolean containsKey(Object key) {
        return values.containsKey(key);
    }

    public float removeFloat(Object key) {
        Float old = values.remove(key);
        return old == null ? 0f : old;
    }

    public float getOrDefault(Object key, float defaultValue) {
        return values.containsKey(key) ? values.get(key) : defaultValue;
    }

    public float defaultReturnValue() {
        return 0f;
    }
}
