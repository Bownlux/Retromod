/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;

/** Client-wide flags 26.2 moved onto the HUD, for mods that still ask Minecraft for them. */
public final class LegacyClientFlags {

    private LegacyClientFlags() {}

    /** {@code Minecraft.renderNames()}: names show unless the player hid the HUD with F1. */
    public static boolean renderNames() {
        Minecraft minecraft = Minecraft.getInstance();
        Gui gui = minecraft == null ? null : minecraft.gui;
        return gui == null || gui.hud == null || !gui.hud.isHidden();
    }
}
