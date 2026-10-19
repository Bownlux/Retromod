/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.net.shared;

/** The old {@code ClientPlayNetworking.PlayChannelHandler}, erased so every mod shares one type. */
@FunctionalInterface
public interface ClientPlayChannelHandler {
    // Named for this type alone, so one class can implement several shared handler types.
    void receiveClientPlayChannel(Object client, Object listener, Object buf, Object responseSender);
}
