package net.minecraft.server.level;

import net.minecraft.server.MinecraftServer;

public class ServerLevel {
    private final MinecraftServer server;
    public ServerLevel(MinecraftServer server) { this.server = server; }
    public MinecraftServer getServer() { return server; }
}
