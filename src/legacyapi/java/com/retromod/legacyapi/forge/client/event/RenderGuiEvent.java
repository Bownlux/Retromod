/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.event;

import com.mojang.blaze3d.platform.Window;
import com.retromod.legacyapi.forge.client.gui.overlay.ForgeGui;
import com.retromod.legacyapi.forge.eventbus.LegacyEvent;
import com.retromod.legacyapi.forge.eventbus.LegacyEvents;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.eventbus.api.event.InheritableEvent;

/** Forge 1.20.1's game-bus event before and after the whole HUD, which Forge 26.2 removed, with the old event rules. */
public abstract class RenderGuiEvent extends LegacyEvent implements InheritableEvent {

    private final Window window;
    private final GuiGraphicsExtractor guiGraphics;
    private final float partialTick;

    protected RenderGuiEvent(Window window, GuiGraphicsExtractor guiGraphics, float partialTick) {
        this.window = window;
        this.guiGraphics = guiGraphics;
        this.partialTick = partialTick;
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

    /** Posts this mod's Pre or Post from {@code {name, Window, GuiGraphicsExtractor, Float[, Boolean cancelled]}}; a Pre answers whether it ends cancelled. */
    public static boolean postFor(boolean pre, Object[] request) {
        Window window = (Window) request[1];
        GuiGraphicsExtractor graphics = (GuiGraphicsExtractor) request[2];
        float partialTick = (Float) request[3];
        RenderGuiEvent event = pre ? new Pre(window, graphics, partialTick) : new Post(window, graphics, partialTick);
        // An earlier mod's cancel arrives set, so this mod's receiveCanceled listeners see it and may undo it.
        if (pre && request.length > 4 && Boolean.TRUE.equals(request[4])) event.setCanceled(true);
        ForgeGui.pullHeights();
        try {
            return LegacyEvents.post(BusGroup.DEFAULT, event);
        } finally {
            ForgeGui.pushHeights();
        }
    }

    /** Whether anything in this mod listens for the HUD events, so the coordinator can pass it by. */
    public static boolean hasListeners() {
        return LegacyEvents.busOf(BusGroup.DEFAULT, Pre.class).hasListeners()
                || LegacyEvents.busOf(BusGroup.DEFAULT, Post.class).hasListeners();
    }

    /** Before the HUD draws; cancelling it skips every old overlay and the Post. */
    public static final class Pre extends RenderGuiEvent {
        public Pre(Window window, GuiGraphicsExtractor guiGraphics, float partialTick) {
            super(window, guiGraphics, partialTick);
        }

        @Override
        public boolean isCancelable() {
            return true;
        }
    }

    /** After the HUD drew. */
    public static final class Post extends RenderGuiEvent {
        public Post(Window window, GuiGraphicsExtractor guiGraphics, float partialTick) {
            super(window, guiGraphics, partialTick);
        }
    }
}
