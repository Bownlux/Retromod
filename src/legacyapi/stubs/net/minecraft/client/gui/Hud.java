// Compile-only stub of a 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.gui;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.gui.components.spectator.SpectatorGui;
import net.minecraft.network.chat.Component;

public class Hud {
    public boolean isHidden() { throw new UnsupportedOperationException(); }
    public BossHealthOverlay getBossOverlay() { throw new UnsupportedOperationException(); }
    public ChatComponent getChat() { throw new UnsupportedOperationException(); }
    public DebugScreenOverlay getDebugOverlay() { throw new UnsupportedOperationException(); }
    public PlayerTabOverlay getTabList() { throw new UnsupportedOperationException(); }
    public SpectatorGui getSpectatorGui() { throw new UnsupportedOperationException(); }
    public Font getFont() { throw new UnsupportedOperationException(); }
    public int getGuiTicks() { throw new UnsupportedOperationException(); }
    public void clearCache() { throw new UnsupportedOperationException(); }
    public void clearTitles() { throw new UnsupportedOperationException(); }
    public void onDisconnected() { throw new UnsupportedOperationException(); }
    public void resetTitleTimes() { throw new UnsupportedOperationException(); }
    public void setNowPlaying(Component text) { throw new UnsupportedOperationException(); }
    public void setOverlayMessage(Component text, boolean animate) { throw new UnsupportedOperationException(); }
    public void setSubtitle(Component text) { throw new UnsupportedOperationException(); }
    public void setTimes(int fadeIn, int stay, int fadeOut) { throw new UnsupportedOperationException(); }
    public void setTitle(Component text) { throw new UnsupportedOperationException(); }
    public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) { throw new UnsupportedOperationException(); }
}
