package net.fabricmc.fabric.api.networking.v1;

import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.Identifier;

public interface LoginPacketSender extends PacketSender {
    Packet<?> createPacket(Identifier channel, FriendlyByteBuf buf);

    default void sendPacket(Identifier channel, FriendlyByteBuf buf) { sendPacket(createPacket(channel, buf)); }

    default void sendPacket(Identifier channel, FriendlyByteBuf buf, ChannelFutureListener listener) {
        sendPacket(createPacket(channel, buf), listener);
    }
}
