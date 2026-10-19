package net.fabricmc.fabric.api.networking.v1;

import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public interface PacketSender {
    Packet<?> createPacket(CustomPacketPayload payload);

    void sendPacket(Packet<?> packet, ChannelFutureListener listener);

    default void sendPacket(Packet<?> packet) { sendPacket(packet, null); }

    default void sendPacket(CustomPacketPayload payload) { sendPacket(createPacket(payload)); }

    default void sendPacket(CustomPacketPayload payload, ChannelFutureListener listener) {
        sendPacket(createPacket(payload), listener);
    }
}
