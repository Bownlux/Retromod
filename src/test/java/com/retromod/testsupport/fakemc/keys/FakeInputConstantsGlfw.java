/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.keys;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Stands in for 1.21.11 to 26.2's {@code InputConstants}: GLFW numbers, and a key check that takes the window. */
public final class FakeInputConstantsGlfw {
    public static final Set<Integer> DOWN = new HashSet<>();
    private static final Map<String, Integer> KEYS = Map.of("key.keyboard.c", 67, "key.keyboard.x", 88, "key.keyboard.v", 86,
            "key.keyboard.a", 65, "key.keyboard.left.win", 343, "key.keyboard.right.win", 347);

    public static FakeKey getKey(String name) {
        return new FakeKey(KEYS.get(name));
    }

    public static boolean isKeyDown(FakeWindow window, int key) {
        return window != null && DOWN.contains(key);
    }
}
