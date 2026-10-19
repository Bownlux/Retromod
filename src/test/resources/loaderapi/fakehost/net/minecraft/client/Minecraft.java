package net.minecraft.client;

import fixture.FakeResources;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.packs.resources.ResourceManager;

public class Minecraft {
    private static final Minecraft INSTANCE = new Minecraft();
    public LocalPlayer player = new LocalPlayer();
    public net.minecraft.client.multiplayer.ClientLevel level;
    private final ClientPacketListener connection = new ClientPacketListener();
    // Null while client mods initialize, as on the real client; fixture.FakeClientStartup sets it.
    BlockColors blockColors = new BlockColors();
    public static Minecraft getInstance() { return INSTANCE; }
    public ClientPacketListener getConnection() { return connection; }
    public BlockColors getBlockColors() { return blockColors; }
    public ResourceManager getResourceManager() { return FakeResources.INSTANCE; }
    public void setBlockColorsForTesting(BlockColors colors) { blockColors = colors; }
}
