/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.event;

import com.mojang.blaze3d.platform.Window;
import com.retromod.legacyapi.forge.client.gui.overlay.ForgeGui;
import com.retromod.legacyapi.forge.client.gui.overlay.NamedGuiOverlay;
import com.retromod.legacyapi.forge.eventbus.LegacyEvent;
import com.retromod.legacyapi.forge.eventbus.LegacyEvents;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.eventbus.api.event.InheritableEvent;

/** Forge 1.20.1's game-bus event around each HUD overlay, which Forge 26.2 removed, with the old event rules. */
public abstract class RenderGuiOverlayEvent extends LegacyEvent implements InheritableEvent {

    // The rebuilt event base keeps its own cancel flag, receiveCanceled and priority order, and a listener of this
    // base class hears Pre and Post, as on Forge 1.20.1.

    private final Window window;
    private final GuiGraphicsExtractor guiGraphics;
    private final float partialTick;
    private final NamedGuiOverlay overlay;

    protected RenderGuiOverlayEvent(Window window, GuiGraphicsExtractor guiGraphics, float partialTick, NamedGuiOverlay overlay) {
        this.window = window;
        this.guiGraphics = guiGraphics;
        this.partialTick = partialTick;
        this.overlay = overlay;
    }

    public Window getWindow() {
        return window;
    }

    public GuiGraphicsExtractor getGuiGraphics() {
        return guiGraphics;
    }

    public float getPartialTick() {
        return partialTick;
    }

    public NamedGuiOverlay getOverlay() {
        return overlay;
    }

    /** Posts this mod's Pre or Post from {@code {name, Identifier, Window, GuiGraphicsExtractor, Float[, Boolean cancelled]}}; a Pre answers whether it ends cancelled. */
    public static boolean postFor(boolean pre, Object[] request) {
        NamedGuiOverlay overlay = NamedGuiOverlay.of((Identifier) request[1]);
        Window window = (Window) request[2];
        GuiGraphicsExtractor graphics = (GuiGraphicsExtractor) request[3];
        float partialTick = (Float) request[4];
        RenderGuiOverlayEvent event = pre ? new Pre(window, graphics, partialTick, overlay)
                : new Post(window, graphics, partialTick, overlay);
        // An earlier mod's cancel arrives set, so this mod's receiveCanceled listeners see it and may undo it.
        if (pre && request.length > 5 && Boolean.TRUE.equals(request[5])) event.setCanceled(true);
        ForgeGui.pullHeights();
        try {
            return LegacyEvents.post(BusGroup.DEFAULT, event);
        } finally {
            ForgeGui.pushHeights();
        }
    }

    /** Whether anything in this mod listens for the overlay events, so the coordinator can pass it by. */
    public static boolean hasListeners() {
        return LegacyEvents.busOf(BusGroup.DEFAULT, Pre.class).hasListeners()
                || LegacyEvents.busOf(BusGroup.DEFAULT, Post.class).hasListeners();
    }

    /** Before an overlay draws; cancelling it skips the overlay and its Post. */
    public static final class Pre extends RenderGuiOverlayEvent {
        public Pre(Window window, GuiGraphicsExtractor guiGraphics, float partialTick, NamedGuiOverlay overlay) {
            super(window, guiGraphics, partialTick, overlay);
        }

        @Override
        public boolean isCancelable() {
            return true;
        }
    }

    /** After an overlay drew. */
    public static final class Post extends RenderGuiOverlayEvent {
        public Post(Window window, GuiGraphicsExtractor guiGraphics, float partialTick, NamedGuiOverlay overlay) {
            super(window, guiGraphics, partialTick, overlay);
        }
    }
}
