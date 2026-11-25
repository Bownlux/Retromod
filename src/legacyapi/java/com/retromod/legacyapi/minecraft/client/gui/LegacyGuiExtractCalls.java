/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;

/** Tooltip and slot highlight calls from before 1.21.2, under the extract names 26.1 gave them. */
public final class LegacyGuiExtractCalls {

    private LegacyGuiExtractCalls() {}

    /** {@code extractTooltipBackground(graphics, x, y, width, height, z)}: the default background, with no depth to pass. */
    public static void extractTooltipBackground(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int z) {
        // A null sprite draws the vanilla background; the GUI is flat since 1.21.6, so the depth has no use.
        TooltipRenderUtil.extractTooltipBackground(graphics, x, y, width, height, null);
    }

    /** {@code component.getHeight()}: measured with the game's font, which the old height never needed. */
    public static int getHeight(ClientTooltipComponent component) {
        return component.getHeight(Minecraft.getInstance().font);
    }

    /** {@code component.extractImage(font, x, y, graphics)}: the component's own size fills the new width and height. */
    public static void extractImage(ClientTooltipComponent component, Font font, int x, int y, GuiGraphicsExtractor graphics) {
        component.extractImage(font, x, y, component.getWidth(font), component.getHeight(font), graphics);
    }

    /** {@code AbstractContainerScreen.extractSlotHighlight(graphics, x, y, z)}: the translucent white square over a 16 pixel slot. */
    public static void extractSlotHighlight(GuiGraphicsExtractor graphics, int x, int y, int z) {
        // 1.21.2 split it into a back and front sprite drawn by the screen itself; the old call only filled the slot.
        graphics.fill(x, y, x + 16, y + 16, SLOT_HIGHLIGHT);
    }

    // White at half opacity, the color the old highlight used.
    private static final int SLOT_HIGHLIGHT = 0x80FFFFFF;
}
