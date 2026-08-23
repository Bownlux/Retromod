/*
 * Retromod test suite. Copyright (c) 2026 Bownlux. MIT License.
 */
package com.retromod.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retromod.testutil.SignedJarTestSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchivePublicationTest {

    @Test
    void completedCopyIsPublishedByteForByte(@TempDir Path directory) throws Exception {
        Path source = SignedJarTestSupport.createSignedJar(directory, "source.jar",
                SignedJarTestSupport.entries("payload.bin", new byte[]{1, 2, 3, 4}));
        Path destination = Files.write(
                directory.resolve("destination.jar"), new byte[]{9, 9, 9});

        ArchivePublication.copyReplacing(source, destination);

        assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(destination));
        SignedJarTestSupport.verifyEveryEntry(destination);
    }

    @Test
    void failedStagedCopyKeepsPreviousDestination(@TempDir Path directory) throws Exception {
        Path source = SignedJarTestSupport.createSignedJar(directory, "source.jar",
                SignedJarTestSupport.entries("payload.bin", new byte[]{1, 2, 3, 4}));
        Path destination = Files.write(
                directory.resolve("destination.jar"), new byte[]{9, 8, 7, 6});
        byte[] previous = Files.readAllBytes(destination);

        assertThrows(IOException.class, () -> ArchivePublication.copyReplacing(
                source, destination, (validatedSource, staged) -> {
                    Files.write(staged, new byte[]{1, 2},
                            StandardOpenOption.TRUNCATE_EXISTING);
                    throw new IOException("injected copy failure");
                }));

        assertArrayEquals(previous, Files.readAllBytes(destination));
        try (var files = Files.list(directory)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString()
                    .startsWith(".destination.jar.")));
        }
    }

    @Test
    void movePublishesBeforeRemovingTheSource(@TempDir Path directory) throws Exception {
        Path source = SignedJarTestSupport.createSignedJar(directory, "move-source.jar",
                SignedJarTestSupport.entries("payload.bin", new byte[]{4, 3, 2, 1}));
        byte[] expected = Files.readAllBytes(source);
        Path destination = Files.write(
                directory.resolve("move-destination.jar"), new byte[]{8, 8});

        ArchivePublication.moveReplacing(source, destination);

        assertFalse(Files.exists(source));
        assertArrayEquals(expected, Files.readAllBytes(destination));
    }

    @Test
    void copyNewNeverReplacesAnExistingArchive(@TempDir Path directory) throws Exception {
        Path source = SignedJarTestSupport.createSignedJar(directory, "new-source.jar",
                SignedJarTestSupport.entries("payload.bin", new byte[]{1, 1, 1}));
        Path destination = Files.write(
                directory.resolve("existing.jar"), new byte[]{7, 7, 7});
        byte[] previous = Files.readAllBytes(destination);

        assertThrows(IOException.class,
                () -> ArchivePublication.copyNew(source, destination));

        assertArrayEquals(previous, Files.readAllBytes(destination));
    }

    @Test
    void moveRefusesSymlinkedDestinationDirectory(@TempDir Path directory) throws Exception {
        Path source = SignedJarTestSupport.createSignedJar(directory, "linked-source.jar",
                SignedJarTestSupport.entries("payload.bin", new byte[]{5, 6, 7}));
        Path outside = Files.createDirectories(directory.resolve("outside"));
        Path existing = Files.write(outside.resolve("published.jar"), new byte[]{9, 8, 7});
        byte[] previous = Files.readAllBytes(existing);
        Path linked = directory.resolve("linked-output");
        Files.createSymbolicLink(linked, outside);

        assertThrows(IOException.class,
                () -> ArchivePublication.moveReplacing(source, linked.resolve("published.jar")));

        assertTrue(Files.exists(source), "a refused move must retain its source");
        assertArrayEquals(previous, Files.readAllBytes(existing),
                "a refused move must not change the linked destination");
    }
}
