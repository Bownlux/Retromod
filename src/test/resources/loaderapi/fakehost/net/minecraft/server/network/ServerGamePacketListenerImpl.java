package net.minecraft.server.network;

import net.minecraft.server.level.ServerPlayer;

public class ServerGamePacketListenerImpl {
    public ServerPlayer player;
    public ServerGamePacketListenerImpl(ServerPlayer player) { this.player = player; player.connection = this; }
    public ServerPlayer getPlayer() { return player; }
}
