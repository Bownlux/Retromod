/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.polyfill;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Loading polyfills writes one INFO summary, once per JVM, and lists each provider only as detail. */
class PolyfillRegistryLoggingTest {

    private static final String INFO_LINE = " INFO Retromod-Polyfills - ";
    private static final String SUMMARY = INFO_LINE + "Loaded ";

    @AfterEach
    void resetTransformer() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void secondPassInTheSameJvmLogsNoInfoLines() {
        PolyfillRegistry.resetListingForTesting();

        String firstPass = captureErr(PolyfillRegistryLoggingTest::registerOnce);
        assertEquals(1, occurrences(firstPass, INFO_LINE),
                "the first pass should print only its summary at INFO, got:\n" + firstPass);
        assertTrue(firstPass.contains(SUMMARY) && firstPass.contains("polyfills and skipped"),
                "the INFO line should be the summary, got:\n" + firstPass);

        // The batch loop resets the transformer and registers the polyfills again for each mod.
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        String secondPass = captureErr(PolyfillRegistryLoggingTest::registerOnce);
        assertFalse(secondPass.contains(INFO_LINE),
                "a repeated pass should keep its polyfill lines at DEBUG, got:\n" + secondPass);
    }

    private static void registerOnce() {
        new PolyfillRegistry().loadAndRegister(RetromodTransformer.getInstance());
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }

    /** slf4j-simple looks up System.err on every write, so swapping it captures the log. */
    private static String captureErr(Runnable action) {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setErr(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }
}
