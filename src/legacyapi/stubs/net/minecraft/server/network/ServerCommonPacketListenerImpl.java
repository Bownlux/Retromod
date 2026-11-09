// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.server.network;

import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;

public abstract class ServerCommonPacketListenerImpl {
    public ServerCommonPacketListenerImpl(MinecraftServer server, Connection connection, CommonListenerCookie cookie) {}

    public void send(Packet<?> packet) { throw new UnsupportedOperationException(); }
    public void send(Packet<?> packet, ChannelFutureListener listener) { throw new UnsupportedOperationException(); }
    public void disconnect(Component reason) { throw new UnsupportedOperationException(); }
    public void disconnect(DisconnectionDetails details) { throw new UnsupportedOperationException(); }
}
