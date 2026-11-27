/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.gui;

import com.retromod.legacyapi.forge.LegacySharedSlots;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** The screen tooltip calls 1.21.6 replaced with the GUI frame's own, set on the frame an old render method saw last. */
public final class LegacyFrameTooltips {

    private static final String FRAME_SLOT = "minecraft.guiFrame";
    private static volatile ThreadLocal<Object> frameSlot;

    private LegacyFrameTooltips() {}

    /** Records the GUI frame an old render method was handed, which every copy of this class shares. */
    public static void frame(Object graphics) {
        // Called at the start of every old render method, so it must never be what stops one.
        try {
            frames().set(graphics);
        } catch (RuntimeException | LinkageError unavailable) {
            // Without a recorded frame, an old tooltip call draws nothing, as before.
        }
    }

    // Looked up once per copy: every render method of an old mod calls frame().
    @SuppressWarnings("unchecked")
    private static ThreadLocal<Object> frames() {
        ThreadLocal<Object> shared = frameSlot;
        if (shared == null) frameSlot = shared = LegacySharedSlots.get(FRAME_SLOT, 1, ThreadLocal.class, ThreadLocal::new);
        return shared;
    }

    /** {@code screen.setTooltipForNextRenderPass(lines)}: set on the frame being drawn, at the mouse. */
    public static void setTooltipForNextRenderPass(Object screen, List<FormattedCharSequence> lines) {
        GuiGraphicsExtractor graphics = currentFrame();
        if (graphics == null || lines == null) return;
        graphics.setTooltipForNextFrame(lines, mouseX(), mouseY());
    }

    /** The same with a positioner and focus flag; the font is the client's. */
    public static void setTooltipForNextRenderPass(Object screen, List<FormattedCharSequence> lines, ClientTooltipPositioner positioner,
            boolean focused) {
        GuiGraphicsExtractor graphics = currentFrame();
        if (graphics == null || lines == null) return;
        graphics.setTooltipForNextFrame(Minecraft.getInstance().font, lines, positioner, mouseX(), mouseY(), focused);
    }

    /** {@code screen.setTooltipForNextRenderPass(tooltip, positioner, focused)}: the tooltip's lines. */
    public static void setTooltipForNextRenderPass(Object screen, Tooltip tooltip, ClientTooltipPositioner positioner, boolean focused) {
        if (tooltip == null) return;
        setTooltipForNextRenderPass(screen, tooltip.toCharSequence(Minecraft.getInstance()), positioner, focused);
    }

    /** {@code screen.setTooltipForNextRenderPass(text)}. */
    public static void setTooltipForNextRenderPass(Object screen, Component text) {
        GuiGraphicsExtractor graphics = currentFrame();
        if (graphics == null || text == null) return;
        graphics.setTooltipForNextFrame(text, mouseX(), mouseY());
    }

    // The frame an old render method saw last on this thread; only the render thread draws, so it is this frame's.
    private static GuiGraphicsExtractor currentFrame() {
        Object frame = frames().get();
        return frame instanceof GuiGraphicsExtractor graphics ? graphics : null;
    }

    private static int mouseX() {
        Minecraft minecraft = Minecraft.getInstance();
        return (int) minecraft.mouseHandler.getScaledXPos(minecraft.getWindow());
    }

    private static int mouseY() {
        Minecraft minecraft = Minecraft.getInstance();
        return (int) minecraft.mouseHandler.getScaledYPos(minecraft.getWindow());
    }
}
