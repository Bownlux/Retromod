/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.client.event;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;
import net.neoforged.neoforge.client.event.RenderArmEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/** Client event reads old Forge and NeoForge mods make, written only with members every NeoForge host from 1.21 has. */
public final class LegacyClientEventReads {

    private LegacyClientEventReads() {}

    /** {@code RenderGuiEvent.getPartialTick()} as a float, the frame's partial tick Forge 1.20.1 passed. */
    public static float guiPartialTick(RenderGuiEvent event) {
        return event.getPartialTick().getGameTimeDeltaPartialTick(false);
    }

    /** {@code RenderGuiEvent.getWindow()}: the event carried the game's window, which is the only one there is. */
    public static Window guiWindow(RenderGuiEvent event) {
        return Minecraft.getInstance().getWindow();
    }

    /** {@code CustomizeGuiOverlayEvent.getPartialTick()} as a float. */
    public static float overlayPartialTick(CustomizeGuiOverlayEvent event) {
        return event.getPartialTick().getGameTimeDeltaPartialTick(false);
    }

    /** {@code RenderArmEvent.getPlayer()}: the first person arm is always the local player's. */
    public static AbstractClientPlayer armPlayer(RenderArmEvent event) {
        // Read as an Object, so this class never needs the local player's type to verify.
        Object player = Minecraft.getInstance().player;
        return player instanceof AbstractClientPlayer clientPlayer ? clientPlayer : null;
    }
}
