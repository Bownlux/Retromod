/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.net.shared;

/** The old {@code ServerPlayNetworking.PlayChannelHandler}, erased so every mod shares one type. */
@FunctionalInterface
public interface ServerPlayChannelHandler {
    // Named for this type alone, so one class can implement several shared handler types.
    void receiveServerPlayChannel(Object server, Object player, Object listener, Object buf,
            Object responseSender);
}
