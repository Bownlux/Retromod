/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc;

/** Stands in for Minecraft's {@code Registry}: registering hands the value back, as the real one does. */
public interface FakeRegistry {

    static Object register(FakeRegistry registry, FakeIdentifier id, Object value) {
        return value;
    }
}
