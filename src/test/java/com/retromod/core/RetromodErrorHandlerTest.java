/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.loaderapi.LoaderApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A failed mod entry point is logged once, at ERROR, with its stack trace. */
class RetromodErrorHandlerTest {

    @Test
    @DisplayName("a repeated entry point failure logs one ERROR line and one stack trace")
    void repeatedFailureLogsOnceAtError() throws Exception {
        RetromodErrorHandler.reset();
        IllegalStateException failure = new IllegalStateException("no key mapping");

        String log = LoaderApiTestSupport.captureErr(() -> {
            RetromodErrorHandler.handleNonFatal("com.example.BrokenClient", failure);
            RetromodErrorHandler.handleNonFatal("com.example.BrokenClient", failure);
        });
        RetromodErrorHandler.reset();

        long errorLines = log.lines()
                .filter(line -> line.contains("ERROR Retromod - A mod entry point failed in "
                        + "com.example.BrokenClient"))
                .count();
        long traceHeaders = log.lines()
                .filter(line -> line.equals(failure.toString()))
                .count();
        assertEquals(1, errorLines, "expected one ERROR line naming the class; log was:\n" + log);
        assertEquals(1, traceHeaders, "expected one stack trace; log was:\n" + log);
    }
}
