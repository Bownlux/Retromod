/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.common.util;

/** Answers an old {@code instanceof FakePlayer} check for every mod's copy of the rebuilt class. */
public final class FakePlayers {

    // Each translated mod carries its own copy under its own package, so the check goes by name.
    private static final String FAKE_PLAYER = "com.retromod.legacyapi.forge.common.util.FakePlayer";

    private FakePlayers() {}

    public static boolean isFake(Object value) {
        for (Class<?> type = value == null ? null : value.getClass(); type != null; type = type.getSuperclass()) {
            String name = type.getName();
            if (name.equals(FAKE_PLAYER) || name.endsWith("." + FAKE_PLAYER)) return true;
        }
        return false;
    }
}
