/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

/** An old typed packet carrying one int, in the 1.19.4 to 1.20.4 shape. */
record N1Packet(int value) implements FabricPacket {

    static final PacketType<N1Packet> TYPE =
            PacketType.create(new Identifier("retromod_test", "n1_typed"), N1Packet::new);

    N1Packet(PacketByteBuf buf) {
        this(buf.readInt());
    }

    @Override
    public void write(PacketByteBuf buf) {
        buf.writeInt(value);
    }

    @Override
    public PacketType<?> getType() {
        return TYPE;
    }
}
