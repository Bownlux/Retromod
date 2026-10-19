/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.net.shared;

/** The old {@code FabricPacket}: a packet that writes itself to a buffer and names its type. */
public interface LegacyFabricPacket {

    /** Writes this packet to a {@code FriendlyByteBuf}; named for this type alone, like the handler methods. */
    void writeLegacyPacket(Object buf);

    /** The type this packet travels as. */
    LegacyPacketType<?> getType();
}
