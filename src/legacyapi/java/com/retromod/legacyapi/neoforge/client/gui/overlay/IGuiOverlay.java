/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.client.gui.overlay;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Forge 1.20.1's HUD overlay, which runs on NeoForge as a GUI layer. */
@FunctionalInterface
public interface IGuiOverlay {
    void render(ForgeGui gui, GuiGraphicsExtractor graphics, float partialTick, int screenWidth, int screenHeight);
}
