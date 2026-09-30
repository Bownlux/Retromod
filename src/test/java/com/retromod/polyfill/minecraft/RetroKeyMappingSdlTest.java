/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.polyfill.minecraft;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 26.3 moved input from GLFW to SDL and renumbered every key and mouse button. Values below are
 * the real ones from the 26.2 and 26.3 client jars' {@code InputConstants}.
 */
class RetroKeyMappingSdlTest {

    /** A slice of 26.3's SDL-numbered constants. */
    private static final Map<String, Integer> SDL_HOST = Map.of(
            "KEY_R", 21,
            "KEY_A", 4,
            "KEY_LSHIFT", 225,
            "KEY_ESCAPE", 41,
            "KEY_LGUI", 227,
            "MOUSE_BUTTON_LEFT", 1,
            "MOUSE_BUTTON_RIGHT", 3);

    @Test
    @DisplayName("a GLFW key code becomes the host's code for the same key")
    void keysTranslateByName() {
        assertEquals(21, RetroKeyMapping.translate(82, false, SDL_HOST), "R");
        assertEquals(225, RetroKeyMapping.translate(340, false, SDL_HOST), "left shift");
        assertEquals(41, RetroKeyMapping.translate(256, false, SDL_HOST), "escape");
    }

    @Test
    @DisplayName("mouse buttons use their own table")
    void mouseButtonsTranslate() {
        assertEquals(1, RetroKeyMapping.translate(0, true, SDL_HOST), "left button was 0");
        assertEquals(3, RetroKeyMapping.translate(1, true, SDL_HOST), "right button was 1");
    }

    @Test
    @DisplayName("26.3 renamed the super keys, and the translation follows the rename")
    void renamedKeysFollow() {
        assertEquals(227, RetroKeyMapping.translate(343, false, SDL_HOST), "KEY_LSUPER is KEY_LGUI");
    }

    @Test
    @DisplayName("a key 26.3 dropped is left unbound instead of landing on another key")
    void droppedKeysAreUnbound() {
        assertEquals(-1, RetroKeyMapping.translate(314, false, SDL_HOST), "KEY_F25 is gone");
        assertEquals(-1, RetroKeyMapping.translate(9999, false, SDL_HOST), "not a GLFW constant");
    }

    @Test
    @DisplayName("a GLFW host and the unbound code are left alone")
    void glfwHostIsUntouched() {
        assertEquals(82, RetroKeyMapping.translate(82, false, null));
        assertEquals(-1, RetroKeyMapping.translate(-1, false, SDL_HOST));
    }

    @Test
    @DisplayName("without Minecraft on the classpath nothing is translated")
    void noHostMeansNoTranslation() {
        assertEquals(82, RetroKeyMapping.hostKeyCode(82));
    }
}
