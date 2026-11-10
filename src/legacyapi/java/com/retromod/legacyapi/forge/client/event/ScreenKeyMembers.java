/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.event;

import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.ScreenEvent;

/** Forge's {@code getScanCode()} on screen and in-game key events, which Forge 26.3 removed. */
public final class ScreenKeyMembers {

    private ScreenKeyMembers() {}

    /** The key event's layout keycode, its second value, kept so a key event an old mod rebuilds matches the host's. */
    public static int scanCode(ScreenEvent.KeyInput event) {
        // 26.3 builds a key event from SDL's scan code, keycode and modifiers, so key() is the physical key and
        // keycode() the layout one. Old code passes this back with getKeyCode() to build a key event.
        return event.getInfo().keycode();
    }

    /** The same value for {@code InputEvent.Key}, the key event of the game outside screens. */
    public static int inputScanCode(InputEvent.Key event) {
        return event.getInfo().keycode();
    }
}
