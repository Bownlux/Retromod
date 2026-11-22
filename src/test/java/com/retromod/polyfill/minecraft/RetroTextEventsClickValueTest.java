/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.polyfill.minecraft;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Old ClickEvent.getValue reads the one value a 26.x click event record carries. */
class RetroTextEventsClickValueTest {

    record OpenUrl(java.net.URI uri) {}

    record ChangePage(int page) {}

    record RunCommand(String command) {}

    record ShowDialog(Object dialog) {}

    record Custom(String id, String payload) {}

    @Test
    @DisplayName("The record's single component comes back as the old string value")
    void readsTheSingleComponent() {
        assertEquals("https://example.org", RetroTextEvents.clickValue(new OpenUrl(java.net.URI.create("https://example.org"))));
        assertEquals("3", RetroTextEvents.clickValue(new ChangePage(3)));
        assertEquals("/say hi", RetroTextEvents.clickValue(new RunCommand("/say hi")));
    }

    @Test
    @DisplayName("An event with no single value, or none at all, gives null")
    void nothingToRead() {
        assertNull(RetroTextEvents.clickValue(new Custom("a", "b")));
        assertNull(RetroTextEvents.clickValue(new ShowDialog(new Object())), "a dialog holder has no old string form");
        assertNull(RetroTextEvents.clickValue(null));
        assertNull(RetroTextEvents.clickValue("not a record"));
    }
}
