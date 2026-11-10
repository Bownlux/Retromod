/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.server;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.NameAndId;

/** MinecraftServer members that took a name and id in place of a game profile. */
public final class LegacyServerMembers {

    private LegacyServerMembers() {}

    /** {@code server.isSingleplayerOwner(profile)}. */
    public static boolean isSingleplayerOwner(Object server, GameProfile profile) {
        return ((MinecraftServer) server).isSingleplayerOwner(new NameAndId(profile));
    }
}
