/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.gui;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;

/** {@code WorldSelectionList.getScreen()}, which returns a plain screen since the list also serves other screens. */
public final class LegacyWorldLists {

    private LegacyWorldLists() {}

    /** The world selection screen the list belongs to, or null when another screen holds it, which the old list never did. */
    public static SelectWorldScreen getScreen(WorldSelectionList list) {
        Screen screen = list.getScreen();
        return screen instanceof SelectWorldScreen select ? select : null;
    }
}
