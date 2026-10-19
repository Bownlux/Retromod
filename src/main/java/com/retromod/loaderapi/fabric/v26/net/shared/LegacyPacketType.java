/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.net.shared;

import java.util.Objects;
import java.util.function.Function;

/** The old {@code PacketType}: a channel id and the reader that turns its bytes into a packet. */
public final class LegacyPacketType<T> {

    private final Object id;
    private final Function<Object, Object> reader;

    private LegacyPacketType(Object id, Function<Object, Object> reader) {
        this.id = Objects.requireNonNull(id, "id");
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    /** The old {@code PacketType.create(Identifier, Function<FriendlyByteBuf, T>)}. */
    @SuppressWarnings("unchecked")
    public static <T> LegacyPacketType<T> create(Object id, Function<?, ?> reader) {
        return new LegacyPacketType<>(id, (Function<Object, Object>) reader);
    }

    /** The channel id, an {@code Identifier}. */
    public Object getId() {
        return id;
    }

    /** Reads one packet from a {@code FriendlyByteBuf}. */
    public LegacyFabricPacket read(Object buf) {
        return (LegacyFabricPacket) reader.apply(buf);
    }

    @Override
    public String toString() {
        return "PacketType{" + id + "}";
    }
}
