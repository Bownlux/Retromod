/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.common.embedded;

/**
 * Framework 0.8's {@code MessageDirection}. Framework 0.13 takes Minecraft's {@code PacketFlow}
 * instead, so the direction is read by name when a message is registered.
 */
public enum LegacyMessageDirection {
    PLAY_SERVER_BOUND,
    PLAY_CLIENT_BOUND,
    HANDSHAKE_SERVER_BOUND,
    HANDSHAKE_CLIENT_BOUND;

    public boolean isClient() {
        return this == PLAY_CLIENT_BOUND || this == HANDSHAKE_CLIENT_BOUND;
    }

    public boolean isServer() {
        return !isClient();
    }
}
