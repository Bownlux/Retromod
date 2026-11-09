/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.gui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

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
    void inPlaceWordingDoesNotClaimChangedFiles() throws Exception {
        JsonObject english;
        try (InputStream in = RestartPromptTest.class.getResourceAsStream(
                "/assets/retromod/lang/en_us.json")) {
            assertNotNull(in, "en_us.json is missing from the classpath");
            english = JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        String cache = english.get(RestartPrompt.messageKey(true)).getAsString();
        String converted = english.get(RestartPrompt.messageKey(false)).getAsString();

        assertTrue(cache.contains("mods/ were not changed"), cache);
        assertFalse(converted.contains("not changed"), "rewrite mode did change mods/: " + converted);
        // The count fills one %s slot, so the text reads right for any number of mods.
        assertEquals(1, cache.split("%s", -1).length - 1, cache);
        assertEquals(1, converted.split("%s", -1).length - 1, converted);
    }

    @Test
    @DisplayName("The console line on a client says to restart Minecraft, not a server")
    void consoleTextOnAClientNamesMinecraft() {
        for (boolean intoCache : new boolean[] {true, false}) {
            String text = RestartPrompt.consoleText(false, 3, intoCache);
            assertTrue(text.contains("Restart Minecraft to load them."), text);
            assertFalse(text.toLowerCase().contains("server"), text);
        }
    }

    @Test
    @DisplayName("The console line on a dedicated server says to restart the server")
    void consoleTextOnAServerNamesTheServer() {
        assertEquals("Updated 1 mod. Restart the server to load it.",
                RestartPrompt.consoleText(true, 1, false));
        String cache = RestartPrompt.consoleText(true, 2, true);
        assertTrue(cache.contains("Restart the server to load them."), cache);
        assertTrue(cache.contains("The files in mods/ were not changed."),
                "in-place mode never claims mods/ changed: " + cache);
        assertNull(RestartPrompt.consoleText(true, 0, true), "nothing translated prints nothing");
        assertNull(RestartPrompt.consoleText(false, 0, false), "nothing translated prints nothing");
    }

    @Test
    @DisplayName("A prompt that cannot open is not reported as shown, and is tried only once")
    void promptThatCannotOpenIsNotReportedAsShown() {
        RestartPrompt.markPending(2);

        // No Minecraft classes resolve in the unit test JVM, so the confirm screen cannot open.
        assertFalse(RestartPrompt.maybeShow(null));
        assertFalse(RestartPrompt.shouldShow(), "a title screen re-init must not retry and warn again");
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

    /** A Mojang-named client: stop() ends the main loop, close() tears the game down. */
    public static final class MojangClient {
        int stops;
        int closes;

        public void stop() {
            stops++;
        }

        public void close() {
            closes++;
        }
    }

    @Test
    @DisplayName("Yes stops a Mojang-named client through stop(), which has no scheduleStop()")
    void stopsAMojangClientThroughStop() throws Exception {
        MojangClient client = new MojangClient();

        RestartPrompt.stop(client);

        assertEquals(1, client.stops);
        assertEquals(0, client.closes, "close() is only the last resort");
    }

    /** An intermediary-named client before 26.1: stop() is method_1592, close() keeps its name. */
    public static final class IntermediaryClient {
        int stops;
        int closes;

        public void method_1592() {
            stops++;
        }

        public void close() {
            closes++;
        }
    }

    @Test
    @DisplayName("Yes stops an intermediary client through method_1592, not the teardown in close()")
    void stopsAnIntermediaryClientThroughItsStopId() throws Exception {
        assertEquals("method_1592", McReflect.findMethod(IntermediaryClient.class,
                RestartPrompt.STOP_NAMES).getName());
        assertEquals("stop", McReflect.findMethod(MojangClient.class,
                RestartPrompt.STOP_NAMES).getName());

        IntermediaryClient client = new IntermediaryClient();
        RestartPrompt.stop(client);

        assertEquals(1, client.stops);
        assertEquals(0, client.closes, "close() tears the game down mid-frame");
    }
}
