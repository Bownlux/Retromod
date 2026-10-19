/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.net.shared;

/** The old {@code ClientConfigurationNetworking.ConfigurationPacketHandler}, erased so every mod shares one type. */
@FunctionalInterface
public interface ClientConfigurationPacketHandler<T> {
    // Named for this type alone, so one class can implement several shared handler types.
    void receiveClientConfigurationPacket(Object packet, Object responseSender);
}
