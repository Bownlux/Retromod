/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.gui.screens;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** The LAN screen 26.3 removed, kept as a type old mods can name; opening it goes straight back to the screen before. */
public class ShareToLanScreen extends Screen {

    private final Screen lastScreen;

    public ShareToLanScreen(Screen lastScreen) {
        super(Component.translatable("lanServer.title"));
        this.lastScreen = lastScreen;
    }

    @Override
    protected void init() {
        // 26.2 opens a world to other players from the pause menu, so there is no LAN form to show here.
        // The switch goes through the GUI, since showing a screen from inside init() would draw a frame within one.
        minecraft.gui.setScreen(lastScreen);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(lastScreen);
    }
}
