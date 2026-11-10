/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.multiplayer;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;

/** Two connection reads that Minecraft 1.20.2 removed: the server brand on the player, and a connection's own id. */
public final class LegacyClientConnection {

    // One id per connection, kept only while the connection is alive, like the field 1.20.1 gave each one.
    private static final Map<ClientPacketListener, UUID> IDS = new WeakHashMap<>();

    private LegacyClientConnection() {}

    /** LocalPlayer.getServerBrand(): the brand moved to the player's connection. */
    public static String serverBrand(LocalPlayer player) {
        return player.connection.serverBrand();
    }

    /** ClientPacketListener.getId(): a random id made the first time a connection is asked, then kept. */
    public static UUID id(ClientPacketListener connection) {
        synchronized (IDS) {
            return IDS.computeIfAbsent(connection, ignored -> UUID.randomUUID());
        }
    }
}
