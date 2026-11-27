/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.client.event;

import com.mojang.blaze3d.platform.Window;
import com.retromod.shim.common.embedded.LegacyLog;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.neoforged.bus.api.Event;

import java.util.ArrayList;

/** NeoForge 1.21.1's and Forge 1.20.1's debug screen text event, rebuilt for NeoForge 26.x, which posts its debug text otherwise. */
public final class LegacyDebugTextEvent extends Event {

    private final ArrayList<String> left = new ArrayList<>();
    private final ArrayList<String> right = new ArrayList<>();

    /** The lines old code adds to the left column of the debug screen. */
    public ArrayList<String> getLeft() {
        return left;
    }

    /** The lines old code adds to the right column of the debug screen. */
    public ArrayList<String> getRight() {
        return right;
    }

    /** NeoForge 1.21.1's {@code getPartialTick()}: the game's delta tracker. */
    public DeltaTracker getPartialTick() {
        return Minecraft.getInstance().getDeltaTracker();
    }

    /** Forge 1.20.1's {@code getPartialTick()}, a float. */
    public static float partialTickFloat(LegacyDebugTextEvent event) {
        return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
    }

    public Window getWindow() {
        return Minecraft.getInstance().getWindow();
    }

    /** The debug screen gathers text before it draws, so there is no graphics object to draw with. */
    public GuiGraphicsExtractor getGuiGraphics() {
        LegacyLog.warnOnce("debug text graphics", "An old mod asked for the graphics object while adding debug screen text,"
                + " which this NeoForge version gathers before drawing, so that drawing does not show.");
        return null;
    }
}
