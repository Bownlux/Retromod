package net.minecraft.server.level;

import net.minecraft.server.network.ServerGamePacketListenerImpl;

public class ServerPlayer {
    public ServerGamePacketListenerImpl connection;
    private final ServerLevel level;
    public ServerPlayer(ServerLevel level) { this.level = level; }
    public ServerLevel level() { return level; }
}
