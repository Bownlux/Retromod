/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.gui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the decision logic of the in-game restart prompt (#33). The GUI itself
 * needs a live client, but the "should we show it" state machine and config
 * gate are pure and testable.
 */
class RestartPromptTest {

    @BeforeEach
    @AfterEach
    void reset() {
        RestartPrompt.resetForTesting();
    }

    @Test
    @DisplayName("No prompt when nothing was converted")
    void noPromptWhenNothingTransformed() {
        assertFalse(RestartPrompt.shouldShow());
    }

    @Test
    @DisplayName("Prompt is armed once mods are converted")
    void promptWhenModsTransformed() {
        RestartPrompt.markPending(3);
        assertEquals(3, RestartPrompt.pendingForTesting());
        assertTrue(RestartPrompt.shouldShow());
    }

    @Test
    @DisplayName("Shows at most once per session")
    void onlyShowsOncePerSession() {
        RestartPrompt.markPending(2);
        assertTrue(RestartPrompt.shouldShow());
        RestartPrompt.markShownForTesting();
        assertFalse(RestartPrompt.shouldShow());
    }

    @Test
    @DisplayName("markPending(0) is a no-op")
    void markPendingZeroIsNoop() {
        RestartPrompt.markPending(0);
        assertFalse(RestartPrompt.shouldShow());
    }

    @Test
    @DisplayName("Config defaults on when no config file is present")
    void configEnabledDefaultsOn() {
        assertTrue(RestartPrompt.configEnabled());
    }

    @Test
    @DisplayName("In-place mode says the files in mods/ were not changed")
    void inPlaceWordingDoesNotClaimChangedFiles() {
        String cache = RestartPrompt.message(2, true);
        assertTrue(cache.contains("not changed"), cache);
        assertFalse(cache.contains("converted"), cache);
        assertTrue(RestartPrompt.message(1, false).contains("converted 1 mod "),
                "rewrite mode keeps its wording");
        assertTrue(RestartPrompt.cacheRestartPopupText(1).contains("not changed"));
        assertFalse(RestartPrompt.cacheRestartPopupText(3).contains("first time"),
                "a Retromod update clears the cache, so the restart is not one-time");
    }

    @Test
    @DisplayName("Translations into the cache arm the prompt like converted mods")
    void cacheTranslationsArmThePrompt() {
        RestartPrompt.markTranslatedIntoCache(2);
        assertEquals(2, RestartPrompt.pendingForTesting());
        assertTrue(RestartPrompt.shouldShow());
        RestartPrompt.markTranslatedIntoCache(0);
        assertEquals(2, RestartPrompt.pendingForTesting(), "zero is a no-op");
    }
}
