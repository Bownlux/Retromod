/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AutoFixEngineInputBoundsTest {

    @Test
    void logByteLimitIsEnforced(@TempDir Path directory) throws Exception {
        Path log = Files.writeString(directory.resolve("latest.log"), "123456789");

        assertThrows(IOException.class,
                () -> AutoFixEngine.readLogLines(log, 8, 32, 10));
    }

    @Test
    void logLineLengthIsEnforced(@TempDir Path directory) throws Exception {
        Path log = Files.writeString(directory.resolve("latest.log"), "123456789\nnext");

        assertThrows(IOException.class,
                () -> AutoFixEngine.readLogLines(log, 64, 8, 10));
    }

    @Test
    void boundedLogReaderPreservesCommonLineEndings(@TempDir Path directory)
            throws Exception {
        Path log = Files.writeString(directory.resolve("latest.log"), "first\r\nsecond\nthird");

        assertEquals(List.of("first", "second", "third"),
                AutoFixEngine.readLogLines(log, 64, 16, 10));
    }
}
