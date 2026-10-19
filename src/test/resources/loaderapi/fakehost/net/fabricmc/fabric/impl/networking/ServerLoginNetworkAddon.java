package net.fabricmc.fabric.impl.networking;

import io.netty.channel.ChannelFutureListener;
import net.fabricmc.fabric.api.networking.v1.LoginPacketSender;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Login still sends raw channels; a payload there is refused, as on Fabric API 26.3. */
final class ServerLoginNetworkAddon implements LoginPacketSender {
    public Packet<?> createPacket(CustomPacketPayload payload) {
        throw new UnsupportedOperationException("Cannot send CustomPayload during login");
    }
    public Packet<?> createPacket(Identifier channel, FriendlyByteBuf buf) {
        return new FakeWire.LoginPacket(channel, buf.readableBytes());
    }
    public void sendPacket(Packet<?> packet, ChannelFutureListener listener) {
        if (packet instanceof FakeWire.LoginPacket login) FakeWire.LOG.add("login " + login.channel() + " " + login.size());
    }
}
