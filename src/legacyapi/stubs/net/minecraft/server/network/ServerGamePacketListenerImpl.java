// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.server.network;

import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public class ServerGamePacketListenerImpl extends ServerCommonPacketListenerImpl {
    public ServerGamePacketListenerImpl(MinecraftServer server, Connection connection, ServerPlayer player,
            CommonListenerCookie cookie) {
        super(server, connection, cookie);
    }
}
