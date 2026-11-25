// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.entity.Entity;

public class Minecraft {
    public ClientLevel level;
    public Gui gui;
    public MultiPlayerGameMode gameMode;
    public LocalPlayer player;
    // Final on the host.
    public net.minecraft.client.gui.components.debug.DebugScreenEntryList debugEntries;
    // Final on the host.
    public LevelRenderer levelRenderer;
    public GameRenderer gameRenderer;
    // Final on the host.
    public Options options;
    // Final on the host.
    public net.minecraft.client.gui.Font font;
    // Final on the host, from 26.2.
    public net.minecraft.client.renderer.extract.LevelExtractor levelExtractor;

    public static Minecraft getInstance() { throw new UnsupportedOperationException(); }
    public ClientPacketListener getConnection() { throw new UnsupportedOperationException(); }
    public Entity getCameraEntity() { throw new UnsupportedOperationException(); }
    public Window getWindow() { throw new UnsupportedOperationException(); }
    public boolean hasShiftDown() { throw new UnsupportedOperationException(); }
    public boolean hasControlDown() { throw new UnsupportedOperationException(); }
    public boolean hasAltDown() { throw new UnsupportedOperationException(); }
    public DeltaTracker getDeltaTracker() { throw new UnsupportedOperationException(); }
    public net.minecraft.server.packs.resources.ResourceManager getResourceManager() { throw new UnsupportedOperationException(); }
    public net.minecraft.client.renderer.texture.TextureManager getTextureManager() { throw new UnsupportedOperationException(); }
    public net.minecraft.client.resources.model.sprite.AtlasManager getAtlasManager() { throw new UnsupportedOperationException(); }
    public net.minecraft.client.resources.model.ModelManager getModelManager() { throw new UnsupportedOperationException(); }
    public net.minecraft.client.renderer.item.ItemModelResolver getItemModelResolver() { throw new UnsupportedOperationException(); }
    public net.minecraft.client.color.block.BlockColors getBlockColors() { throw new UnsupportedOperationException(); }
    public net.minecraft.client.renderer.entity.EntityRenderDispatcher getEntityRenderDispatcher() { throw new UnsupportedOperationException(); }
    public com.mojang.blaze3d.platform.FramerateLimitTracker getFramerateLimitTracker() { throw new UnsupportedOperationException(); }
    public long getFrameTimeNs() { throw new UnsupportedOperationException(); }
    // Inherited from BlockableEventLoop on the host.
    public void execute(Runnable task) { throw new UnsupportedOperationException(); }
}
