/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.archive;

import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.TestAbortedException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApiArchiveManagerSecurityTest {

    @Test
    @DisplayName("loader and version values resolve to one archive-directory child")
    void archivePathComponentsCannotTraverse(@TempDir Path dir) throws Exception {
        assertEquals(dir.resolve("fabric-1.21.11.jar").toAbsolutePath().normalize(),
                ApiArchiveManager.resolveArchivePath(dir, "FABRIC", "1.21.11"));

        for (String version : new String[]{"../outside", "1.21/../../outside", "/tmp/owned",
                "C:\\owned", ""}) {
            assertThrows(IllegalArgumentException.class,
                    () -> ApiArchiveManager.resolveArchivePath(dir, "fabric", version));
        }
        assertThrows(IllegalArgumentException.class,
                () -> ApiArchiveManager.resolveArchivePath(dir, "../fabric", "1.21"));
    }

    @Test
    @DisplayName("archive storage rejects a symlinked parent or target")
    void archiveStorageRejectsSymlinks(@TempDir Path dir) throws Exception {
        Path realArchiveDir = Files.createDirectory(dir.resolve("real-archive"));
        Path linkedArchiveDir = dir.resolve("linked-archive");
        try {
            Files.createSymbolicLink(linkedArchiveDir, realArchiveDir);
        } catch (UnsupportedOperationException | IOException e) {
            throw new TestAbortedException("symbolic links are unavailable on this filesystem", e);
        }

        Path linkedParentTarget = linkedArchiveDir.resolve("fabric-1.21.jar");
        assertThrows(IOException.class,
                () -> ApiArchiveManager.validateArchiveTarget(
                        linkedArchiveDir, linkedParentTarget));

        Path regularFile = Files.writeString(dir.resolve("regular.jar"), "not used");
        Path symlinkTarget = realArchiveDir.resolve("fabric-1.21.jar");
        Files.createSymbolicLink(symlinkTarget, regularFile);
        assertThrows(IOException.class,
                () -> ApiArchiveManager.validateArchiveTarget(realArchiveDir, symlinkTarget));
    }

    @Test
    @DisplayName("archive class extraction enforces per-class and aggregate budgets")
    void extractionBudgetsAreEnforced(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("api.jar");
        writeJar(jar, new String[]{"one/A.class", "two/B.class"},
                new byte[][]{new byte[6], new byte[6]});

        assertThrows(IOException.class,
                () -> ApiArchiveManager.extractClasses(jar, 5, 20),
                "one oversized class must be rejected");
        assertThrows(IOException.class,
                () -> ApiArchiveManager.extractClasses(jar, 10, 10),
                "many individually safe classes must still respect the total budget");
        assertEquals(2, ApiArchiveManager.extractClasses(jar, 10, 12).size());
    }

    @Test
    @DisplayName("archive class extraction caps the full central-directory entry count")
    void extractionEntryCountIsEnforced(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("many-entries.jar");
        writeJar(jar, new String[]{"one/A.class", "metadata.txt"},
                new byte[][]{new byte[]{1}, new byte[]{2}});

        assertThrows(IOException.class,
                () -> ApiArchiveManager.extractClasses(jar, 10, 10, 1));
    }

    @Test
    @DisplayName("unsafe archive entry names are rejected before classes enter the cache")
    void extractionRejectsUnsafeEntryNames(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("unsafe.jar");
        writeJar(jar, new String[]{"../Outside.class"}, new byte[][]{new byte[]{1}});

        assertThrows(IOException.class,
                () -> ApiArchiveManager.extractClasses(jar, 10, 10));
    }

    @Test
    @DisplayName("archive loading and download validation refuse normalized duplicate entries")
    void normalizedDuplicateEntriesAreRejected(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("duplicate.jar");
        writeJar(jar,
                new String[]{"api/example//Value.class", "api/example/Value.class"},
                new byte[][]{new byte[]{1}, new byte[]{2}});

        assertThrows(IOException.class,
                () -> ApiArchiveManager.extractClasses(jar, 10, 20));
        assertThrows(IOException.class, () -> ApiArchiveManager.validateJar(jar));
    }

    @Test
    @DisplayName("download streaming stops at the hard byte limit")
    void boundedDownloadStopsBeforeOverflow() throws Exception {
        ByteArrayOutputStream accepted = new ByteArrayOutputStream();
        assertEquals(4, ApiArchiveManager.copyDownloadBounded(
                new ByteArrayInputStream(new byte[]{1, 2, 3, 4}), accepted, 4));
        assertArrayEquals(new byte[]{1, 2, 3, 4}, accepted.toByteArray());

        assertThrows(IOException.class, () -> ApiArchiveManager.copyDownloadBounded(
                new ByteArrayInputStream(new byte[]{1, 2, 3, 4, 5}),
                new ByteArrayOutputStream(), 4));
    }

    @Test
    @DisplayName("archive downloads refuse redirects and identify the current Retromod build")
    void downloadConnectionIsPinnedToConsentedOrigin() throws Exception {
        RecordingConnection connection = new RecordingConnection();

        ApiArchiveManager.configureConnection(connection);

        assertFalse(connection.getInstanceFollowRedirects(),
                "a consented Maven URL must not redirect to an unreported origin");
        assertEquals("Retromod/" + RetromodVersion.RETROMOD_VERSION,
                connection.getRequestProperty("User-Agent"));
        assertEquals(30000, connection.getConnectTimeout());
        assertEquals(60000, connection.getReadTimeout());
    }

    private static void writeJar(Path jar, String[] names, byte[][] contents) throws IOException {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (int i = 0; i < names.length; i++) {
                output.putNextEntry(new JarEntry(names[i]));
                output.write(contents[i]);
                output.closeEntry();
            }
        }
    }

    private static final class RecordingConnection extends HttpURLConnection {
        private RecordingConnection() throws Exception {
            super(URI.create("https://example.invalid/archive.jar").toURL());
        }

        @Override
        public void disconnect() {
        }

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public void connect() {
        }
    }
}
