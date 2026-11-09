/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Retromod's screens open through Minecraft.setScreen up to 26.1 and through Gui.setScreen after. */
class McScreensTest {

    /** Stands in for Minecraft's Screen. */
    public static class Screen {
    }

    /** The 26.2 and newer Gui, which owns the screen setter. */
    public static class Gui {
        int calls;
        Screen shown;

        public void setScreen(Screen screen) {
            calls++;
            shown = screen;
        }
    }

    /** Minecraft up to 26.1: a setter of its own, and a gui field too. */
    public static class OldClient {
        public final Gui gui = new Gui();
        int calls;
        Screen shown;

        public void setScreen(Screen screen) {
            calls++;
            shown = screen;
        }
    }

    /** Minecraft 26.2 and 26.3: only setScreenAndShow, so screens go through gui. */
    public static class NewClient {
        public final Gui gui = new Gui();

        public void setScreenAndShow(Screen screen) {
            throw new AssertionError("setScreenAndShow forces a frame and is not a fallback");
        }
    }

    /** A client with no screen setter at all. */
    public static class BareClient {
    }

    /** A client whose setter fails. */
    public static class ThrowingClient {
        public void setScreen(Screen screen) {
            throw new IllegalStateException("screen setter failed");
        }
    }

    @Test
    void usesClientSetterWhereItExists() {
        OldClient client = new OldClient();
        Screen screen = new Screen();

        assertTrue(McScreens.show(client, Screen.class, screen));

        assertSame(screen, client.shown);
        assertEquals(0, client.gui.calls, "older hosts keep Minecraft.setScreen");
    }

    @Test
    void usesGuiSetterWhenClientSetterIsGone() {
        NewClient client = new NewClient();
        Screen screen = new Screen();

        assertTrue(McScreens.show(client, Screen.class, screen), "the 26.2 and 26.3 shape");

        assertSame(screen, client.gui.shown);
    }

    @Test
    void passesNullToCloseTheScreen() {
        NewClient client = new NewClient();

        assertTrue(McScreens.show(client, Screen.class, null));

        assertEquals(1, client.gui.calls);
        assertNull(client.gui.shown);
    }

    @Test
    void reportsFailureWithoutAnySetter() {
        assertFalse(McScreens.show(new BareClient(), Screen.class, new Screen()));
    }

    @Test
    void reportsFailureWhenSetterThrows() {
        assertFalse(McScreens.show(new ThrowingClient(), Screen.class, new Screen()));
    }

    @Test
    void reportsFailureWithoutMinecraft() {
        // The unit test JVM has no Minecraft classes, like a client that never started.
        assertFalse(McScreens.show(new Screen()));
    }
}
