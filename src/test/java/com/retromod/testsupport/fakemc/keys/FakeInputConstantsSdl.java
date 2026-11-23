/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.keys;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Stands in for 26.3's {@code InputConstants}: SDL numbers, and a key check without the window. */
public final class FakeInputConstantsSdl {
    public static final Set<Integer> DOWN = new HashSet<>();
    private static final Map<String, Integer> KEYS = Map.of("key.keyboard.c", 6, "key.keyboard.x", 27, "key.keyboard.v", 25,
            "key.keyboard.a", 4, "key.keyboard.left.win", 227, "key.keyboard.right.win", 231,
            "key.keyboard.enter", 40, "key.keyboard.space", 44, "key.keyboard.keypad.enter", 88);

    public static FakeKey getKey(String name) {
        return new FakeKey(KEYS.get(name));
    }

    public static boolean isKeyDown(int key) {
        return DOWN.contains(key);
    }
}
