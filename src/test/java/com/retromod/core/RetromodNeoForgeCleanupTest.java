/*
 * Retromod test suite. Copyright (c) 2026 Bownlux. MIT License.
 */
package com.retromod.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RetromodNeoForgeCleanupTest {

    @Test
    void failedTransformKeepsItsExceptionAndRemovesTheTemporaryTree() {
        IOException transformFailure = new IOException("injected transform failure");
        AtomicReference<Path> temporaryDirectory = new AtomicReference<>();

        IOException thrown = assertThrows(IOException.class,
                () -> RetromodNeoForge.withInPlaceTransformDirectory(directory -> {
                    temporaryDirectory.set(directory);
                    Files.write(directory.resolve("partial.jar"), new byte[]{1, 2, 3});
                    throw transformFailure;
                }));

        assertSame(transformFailure, thrown);
        assertFalse(Files.exists(temporaryDirectory.get()));
    }
}
