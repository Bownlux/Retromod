/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.multiplayer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/** {@code ClientLevel.ClientLevelData.isRaining()}, which 26.1 moved off the level data onto the level's weather. */
public final class LegacyClientWeather {

    private LegacyClientWeather() {}

    /** Whether it rains in the client level that owns this data; data of no current level reads as dry. */
    public static boolean isRaining(ClientLevel.ClientLevelData data) {
        ClientLevel level = Minecraft.getInstance().level;
        return level != null && level.getLevelData() == data && level.isRaining();
    }
}
