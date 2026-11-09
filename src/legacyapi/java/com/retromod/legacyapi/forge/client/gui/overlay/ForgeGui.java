/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.gui.overlay;

import com.retromod.legacyapi.forge.client.LegacyOverlayHub;
import com.retromod.shim.common.embedded.LegacyLog;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.gui.components.spectator.SpectatorGui;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;

/** Forge 1.20.1's {@code Minecraft.gui}, one per mod: the old left and right heights, and the rest asked of the 26.x Hud. */
public final class ForgeGui {

    // Each mod casts Minecraft.gui to its own copy, and every copy's heights meet in the shared HUD state.
    private static final ForgeGui INSTANCE = new ForgeGui();
    // Matched by the end of its name, since another mod's copy is relocated under its own package.
    private static final String COPY = "com.retromod.legacyapi.forge.client.gui.overlay.ForgeGui";
    // Overlays turned off for a missing class or member, which would fail the same way every frame.
    private static final Set<Identifier> DISABLED = ConcurrentHashMap.newKeySet();

    /** Rows the left and right status bars take up from the bottom; Forge 1.20.1 reset both to 39 each frame. */
    public int leftHeight = 39;
    public int rightHeight = 39;

    // Some mods read the old private field by reflection, so each cast points it at the Hud's debug screen.
    @SuppressWarnings("unused")
    private DebugScreenOverlay debugOverlay;

    private ForgeGui() {}

    /** {@code (ForgeGui) value}: this mod's object for the host's Gui or Hud or another mod's copy, as the old cast passed them. */
    public static ForgeGui cast(Object value) {
        if (value == null) return null;
        if (!isInstance(value)) {
            throw new ClassCastException(value.getClass().getName() + " cannot be cast to " + ForgeGui.class.getName());
        }
        Hud hud = value instanceof Gui gui ? gui.hud : value instanceof Hud own ? own : null;
        if (hud != null) INSTANCE.debugOverlay = hud.getDebugOverlay();
        return INSTANCE;
    }

    /** {@code value instanceof ForgeGui}: true for the host's Gui and Hud, which stand where the old ForgeGui was. */
    public static boolean isInstance(Object value) {
        if (value == null) return false;
        return value instanceof Gui || value instanceof Hud || isCopy(value.getClass());
    }

    private static boolean isCopy(Class<?> type) {
        String name = type.getName();
        return name.equals(COPY) || name.endsWith("." + COPY);
    }

    public Minecraft getMinecraft() {
        return Minecraft.getInstance();
    }

    /** Whether the hearts, armor, food and air draw: the player can be hurt and the camera is a player, as before. */
    public boolean shouldDrawSurvivalElements() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.gameMode != null && minecraft.gameMode.canHurtPlayer() && minecraft.getCameraEntity() instanceof Player;
    }

    /** Minecraft 26.x extracts render state instead of drawing, so there is no blend or depth state to set. */
    public void setupOverlayRenderState(boolean blend, boolean depthTest) {}

    // The old Gui members a mod reaches through its ForgeGui, now on the Hud.
    public Font getFont() {
        return hud().getFont();
    }

    public int getGuiTicks() {
        return hud().getGuiTicks();
    }

    public ChatComponent getChat() {
        return hud().getChat();
    }

    public BossHealthOverlay getBossOverlay() {
        return hud().getBossOverlay();
    }

    public DebugScreenOverlay getDebugOverlay() {
        return hud().getDebugOverlay();
    }

    public PlayerTabOverlay getTabList() {
        return hud().getTabList();
    }

    public SpectatorGui getSpectatorGui() {
        return hud().getSpectatorGui();
    }

    public void clearCache() {
        hud().clearCache();
    }

    public void clearTitles() {
        hud().clearTitles();
    }

    public void onDisconnected() {
        hud().onDisconnected();
    }

    public void resetTitleTimes() {
        hud().resetTitleTimes();
    }

    public void setNowPlaying(Component text) {
        hud().setNowPlaying(text);
    }

    public void setOverlayMessage(Component text, boolean animate) {
        hud().setOverlayMessage(text, animate);
    }

    public void setSubtitle(Component text) {
        hud().setSubtitle(text);
    }

    public void setTimes(int fadeIn, int stay, int fadeOut) {
        hud().setTimes(fadeIn, stay, fadeOut);
    }

    public void setTitle(Component text) {
        hud().setTitle(text);
    }

    public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        hud().extractRenderState(graphics, delta);
    }

    private static Hud hud() {
        return Minecraft.getInstance().gui.hud;
    }

    /** Copies the shared heights in before this mod's code runs during a frame. */
    public static void pullHeights() {
        int[] heights = LegacyOverlayHub.heights();
        INSTANCE.leftHeight = heights[0];
        INSTANCE.rightHeight = heights[1];
    }

    /** Copies this mod's heights back out after its code ran, for the next mod and overlay to read. */
    public static void pushHeights() {
        int[] heights = LegacyOverlayHub.heights();
        heights[0] = INSTANCE.leftHeight;
        heights[1] = INSTANCE.rightHeight;
    }

    /** Draws one of this mod's overlays as Forge 1.20.1 did, with the frame's partial tick and the screen size. */
    public static void draw(Identifier id, String owner, IGuiOverlay overlay, GuiGraphicsExtractor graphics, DeltaTracker delta) {
        if (DISABLED.contains(id)) return;
        pullHeights();
        try {
            overlay.render(INSTANCE, graphics, delta.getGameTimeDeltaPartialTick(false), graphics.guiWidth(), graphics.guiHeight());
        } catch (LinkageError missing) {
            // Every frame would fail the same way, so the overlay is left out from now on.
            DISABLED.add(id);
            LegacyLog.warn("Retromod stopped drawing the HUD overlay " + id + " of " + owner + ", which uses " + missing
                    + ". The rest of the HUD and the mod keep running. Report this line with the mod and its version.");
        } catch (RuntimeException failure) {
            // Forge 1.20.1 logged a failing overlay and went on with the rest of the HUD.
            LegacyLog.warnOnce("hud overlay " + id, "The HUD overlay " + id + " of " + owner + " threw while drawing: "
                    + failure + ". Retromod went on with the rest of the HUD");
        } finally {
            pushHeights();
        }
    }
}
