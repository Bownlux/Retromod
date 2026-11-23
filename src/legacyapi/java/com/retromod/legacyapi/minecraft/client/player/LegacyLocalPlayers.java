/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.player;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;

/** {@code LocalPlayer.clientLevel}, the field the player kept beside its level until it was removed. */
public final class LegacyLocalPlayers {

    private LegacyLocalPlayers() {}

    /** The client level the player is in, which is the level it reports. */
    public static ClientLevel clientLevel(LocalPlayer player) {
        return (ClientLevel) player.level();
    }
}
