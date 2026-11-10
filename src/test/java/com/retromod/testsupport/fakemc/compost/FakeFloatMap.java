/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.compost;

/** fastutil's Object2FloatMap, as the rebuilt table uses it. */
public interface FakeFloatMap {
    float put(Object key, float value);
    float getFloat(Object key);
    boolean containsKey(Object key);
    float removeFloat(Object key);
    float getOrDefault(Object key, float defaultValue);
}
