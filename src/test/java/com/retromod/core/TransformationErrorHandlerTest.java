/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.loaderapi.LoaderApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A failed transform reaches the log with its stack trace, and the report and dialog stay usable. */
class TransformationErrorHandlerTest {

    private static final Path MOD = Path.of("broken-mod.jar");

    @Test
    @DisplayName("a failure is logged at ERROR with its stack trace")
    void failureIsLoggedAtErrorWithItsStackTrace() throws Exception {
        String log = LoaderApiTestSupport.captureErr(() -> TransformationErrorHandler.recordFailure(
                MOD, new IllegalStateException("boom"), "broken", "fabric", "1.20.1"));

        assertTrue(log.lines().anyMatch(line -> line.contains("ERROR")
                        && line.contains("broken-mod.jar")),
                "the ERROR line must name the jar; log was:\n" + log);
        assertTrue(log.contains("java.lang.IllegalStateException: boom"), log);
        assertTrue(log.contains("\tat "), "the default log level must show a stack frame; log was:\n"
                + log);
    }

    @Test
    @DisplayName("the recorded failure's report carries its stack trace")
    void serverReportCarriesTheStackTrace() throws Exception {
        TransformationErrorHandler.FailedMod[] failed = new TransformationErrorHandler.FailedMod[1];
        LoaderApiTestSupport.captureErr(() -> failed[0] = TransformationErrorHandler.recordFailure(
                MOD, new IllegalStateException("boom"), "broken", "fabric", "1.20.1"));

        String trace = stackTraceBlock(TransformationErrorHandler.generateBugReport(failed[0]));

        assertTrue(trace.contains("\tat "), "the Stack Trace block must hold a frame:\n" + trace);
    }

    @Test
    @DisplayName("a dedicated server prints the report with its stack trace")
    void dedicatedServerPrintsTheReportWithItsStackTrace() throws Exception {
        // A dedicated server shows no dialog, so the console report is the only full report.
        String log = withDedicatedServer(() -> LoaderApiTestSupport.captureErr(
                () -> TransformationErrorHandler.handleError(MOD,
                        new IllegalStateException("boom"), "broken", "fabric", "1.20.1")));

        assertTrue(stackTraceBlock(log).contains("\tat "),
                "the console report must hold a stack frame; log was:\n" + log);
    }

    @Test
    @DisplayName("the dialog names the mod and speaks of Retromod in the third person")
    void dialogMessageUsesThirdPerson() {
        String message = TransformationErrorHandler.dialogMessage(new TransformationErrorHandler.FailedMod(
                "broken-mod.jar", "broken", "fabric", "1.20.1", "IllegalStateException", "boom", ""));

        assertTrue(message.contains("broken-mod.jar"), message);
        assertFalse(Pattern.compile("(?i)\\b(us|we|our)\\b").matcher(message).find(), message);
    }

    /** The text between the report's Stack Trace and System Info headings. */
    private static String stackTraceBlock(String text) {
        int start = text.indexOf("## Stack Trace");
        int end = text.indexOf("## System Info", Math.max(start, 0));
        assertTrue(start >= 0 && end > start, "the report must have a Stack Trace block:\n" + text);
        return text.substring(start, end);
    }

    private interface Captured {
        String run() throws Exception;
    }

    /** Runs {@code action} as on a dedicated server, then restores the detected environment. */
    private static String withDedicatedServer(Captured action) throws Exception {
        Field client = environmentField("isClient");
        Field headless = environmentField("isHeadless");
        Field server = environmentField("isDedicatedServer");
        Object savedClient = client.get(null);
        Object savedHeadless = headless.get(null);
        Object savedServer = server.get(null);
        try {
            client.set(null, Boolean.FALSE);
            headless.set(null, Boolean.TRUE);
            server.set(null, Boolean.TRUE);
            return action.run();
        } finally {
            client.set(null, savedClient);
            headless.set(null, savedHeadless);
            server.set(null, savedServer);
        }
    }

    private static Field environmentField(String name) throws NoSuchFieldException {
        Field field = EnvironmentDetector.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
