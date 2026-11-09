/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.event;

import com.mojang.blaze3d.platform.Window;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraftforge.client.event.CustomizeGuiOverlayEvent;
import net.minecraftforge.eventbus.api.bus.EventBus;
import net.minecraftforge.eventbus.api.event.MutableEvent;

/** Forge 1.20.1's debug screen text event, with both columns as lists, which Forge 26.2 replaced with one record per side. */
public final class LegacyDebugText extends MutableEvent {

    public static final EventBus<LegacyDebugText> BUS = EventBus.create(LegacyDebugText.class);

    private final Window window;
    private final GuiGraphicsExtractor guiGraphics;
    private final float partialTick;
    private final ArrayList<String> left;
    private final ArrayList<String> right;

    public LegacyDebugText(Window window, GuiGraphicsExtractor guiGraphics, float partialTick, ArrayList<String> left,
            ArrayList<String> right) {
        this.window = window;
        this.guiGraphics = guiGraphics;
        this.partialTick = partialTick;
        this.left = left;
        this.right = right;
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

    public ArrayList<String> getLeft() {
        return left;
    }

    public ArrayList<String> getRight() {
        return right;
    }

    /** The newer record's text, for a mod that reads it here: the left column. */
    public List<String> getText() {
        return left;
    }

    /** The newer record's side, for a mod that reads it here: the left one, which {@link #getText()} returns. */
    public CustomizeGuiOverlayEvent.DebugText.Side getSide() {
        return CustomizeGuiOverlayEvent.DebugText.Side.Left;
    }
}
