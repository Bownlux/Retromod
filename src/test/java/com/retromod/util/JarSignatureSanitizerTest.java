/*
 * Retromod test suite. Copyright (c) 2026 Bownlux. MIT License.
 */
package com.retromod.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retromod.testutil.SignedJarTestSupport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JarSignatureSanitizerTest {

    @Test
    void mismatchedSignedEntryCanBeSanitizedAndVerified(@TempDir Path directory)
            throws Exception {
        Path jar = SignedJarTestSupport.createSignedJar(
                directory, "signed.jar", SignedJarTestSupport.entries(
                        "payload.txt", "original".getBytes(StandardCharsets.UTF_8)));
        SignedJarTestSupport.replaceEntryWithoutResigning(
                jar, "payload.txt", "changed".getBytes(StandardCharsets.UTF_8));

        assertThrows(SecurityException.class,
                () -> SignedJarTestSupport.verifyEveryEntry(jar),
                "the fixture must contain an actually mismatched signed entry");

        JarSignatureSanitizer.sanitizeJar(jar);

        assertDoesNotThrow(() -> SignedJarTestSupport.verifyEveryEntry(jar));
        assertFalse(SignedJarTestSupport.hasSigningMetadata(jar));
        try (JarFile output = new JarFile(jar.toFile(), true)) {
            assertArrayEquals("changed".getBytes(StandardCharsets.UTF_8),
                    output.getInputStream(output.getJarEntry("payload.txt")).readAllBytes());
        }
    }

    @Test
    void finalManifestBytesAreChargedBeforePublication(@TempDir Path directory)
            throws Exception {
        byte[] compactManifest = ("Manifest-Version: 1.0\n"
                + "Custom-Attribute: retained\n\n").getBytes(StandardCharsets.UTF_8);
        byte[] serialized = JarSignatureSanitizer.sanitizeManifest(compactManifest);
        assertTrue(serialized.length > compactManifest.length,
                "manifest serialization must expand this compact fixture");

        Path jar = directory.resolve("short-budget.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry(JarFile.MANIFEST_NAME));
            output.write(compactManifest);
            output.closeEntry();
        }
        byte[] before = Files.readAllBytes(jar);

        assertThrows(IOException.class, () -> JarSignatureSanitizer.sanitizeJar(
                jar, 1024, compactManifest.length, 10));
        assertArrayEquals(before, Files.readAllBytes(jar),
                "a final-byte budget failure must preserve the generated input");
    }
}
