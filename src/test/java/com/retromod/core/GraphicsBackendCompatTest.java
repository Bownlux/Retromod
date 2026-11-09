/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OpenGL-preference logic ({@link GraphicsBackendCompat#apply}): set
 * {@code preferredGraphicsBackend:"opengl"} for old mods on 26.2+, but only when the
 * user hasn't made an explicit choice, preserving the rest of options.txt.
 *
 * <p>Format matches what MC 26.2 persists: the key is {@code preferredGraphicsBackend}
 * (not the label key {@code graphicsApi}) and the value is JSON-quoted.
 */
class GraphicsBackendCompatTest {

    private static String backendLine(Path p) throws IOException {
        return Files.readAllLines(p).stream()
                .filter(l -> l.startsWith("preferredGraphicsBackend:"))
                .findFirst().orElse(null);
    }

    @Test
    void createsOptionsFileWhenAbsent(@TempDir Path dir) throws IOException {
        Path opts = dir.resolve("options.txt");
        assertEquals(GraphicsBackendCompat.Result.SET_OPENGL, GraphicsBackendCompat.apply(opts));
        assertTrue(Files.exists(opts));
        assertEquals("preferredGraphicsBackend:\"opengl\"", backendLine(opts));
    }

    @Test
    void createdFileCarriesTheGamesDataVersion(@TempDir Path dir) throws IOException {
        // Without a version line Minecraft runs every options fix, and 26.4-snapshot-7's
        // OptionsForceDefaultGraphicsApiFix (data version 5117) then resets the setting to default.
        Path opts = dir.resolve("options.txt");
        assertEquals(GraphicsBackendCompat.Result.SET_OPENGL,
                GraphicsBackendCompat.apply(opts, java.util.OptionalInt.of(5127)));
        assertEquals(List.of("version:5127", "preferredGraphicsBackend:\"opengl\""), Files.readAllLines(opts));
    }

    @Test
    void readsTheDataVersionFromTheGamesVersionJson() {
        // The shape of 26.4-snapshot-7's version.json.
        String json = "{\n    \"id\": \"26.4-snapshot-7\",\n    \"name\": \"26.4 Snapshot 7\",\n"
                + "    \"world_version\": 5127,\n    \"series_id\": \"main\",\n    \"stable\": false\n}";
        assertEquals(java.util.OptionalInt.of(5127), GraphicsBackendCompat.worldVersion(json));
        assertEquals(java.util.OptionalInt.empty(), GraphicsBackendCompat.worldVersion("{\"id\": \"x\"}"));
    }

    @Test
    void snapshotOptionsWithDefaultSwitchToOpenGl(@TempDir Path dir) throws IOException {
        // A 26.4-snapshot-7 options.txt after the game reset the setting: its own version, "default".
        Path opts = dir.resolve("options.txt");
        Files.write(opts, List.of("version:5127", "preferredGraphicsBackend:\"default\"", "renderDistance:12"));
        assertEquals(GraphicsBackendCompat.Result.SET_OPENGL,
                GraphicsBackendCompat.apply(opts, java.util.OptionalInt.of(5127)));
        assertEquals(List.of("version:5127", "preferredGraphicsBackend:\"opengl\"", "renderDistance:12"),
                Files.readAllLines(opts), "an existing file keeps its own version line");
    }

    @Test
    void switchesDefaultToOpenGlPreservingOtherLines(@TempDir Path dir) throws IOException {
        Path opts = dir.resolve("options.txt");
        Files.write(opts, List.of("fov:0.5", "preferredGraphicsBackend:\"default\"", "renderDistance:12"));
        assertEquals(GraphicsBackendCompat.Result.SET_OPENGL, GraphicsBackendCompat.apply(opts));
        List<String> after = Files.readAllLines(opts);
        assertEquals("preferredGraphicsBackend:\"opengl\"", backendLine(opts));
        assertTrue(after.contains("fov:0.5"), "unrelated options must be preserved");
        assertTrue(after.contains("renderDistance:12"), "unrelated options must be preserved");
        assertEquals(3, after.size(), "no lines added or dropped, only the value changed");
    }

    @Test
    void appendsKeyWhenMissing(@TempDir Path dir) throws IOException {
        Path opts = dir.resolve("options.txt");
        Files.write(opts, List.of("fov:0.5", "renderDistance:12"));
        assertEquals(GraphicsBackendCompat.Result.SET_OPENGL, GraphicsBackendCompat.apply(opts));
        assertEquals("preferredGraphicsBackend:\"opengl\"", backendLine(opts));
        assertTrue(Files.readAllLines(opts).contains("fov:0.5"));
    }

    @Test
    void leavesExplicitOpenGlAlone(@TempDir Path dir) throws IOException {
        Path opts = dir.resolve("options.txt");
        Files.write(opts, List.of("preferredGraphicsBackend:\"opengl\""));
        assertEquals(GraphicsBackendCompat.Result.ALREADY_OPENGL, GraphicsBackendCompat.apply(opts));
        assertEquals("preferredGraphicsBackend:\"opengl\"", backendLine(opts));
    }

    @Test
    void respectsExplicitVulkanChoice(@TempDir Path dir) throws IOException {
        Path opts = dir.resolve("options.txt");
        Files.write(opts, List.of("fov:0.5", "preferredGraphicsBackend:\"vulkan\""));
        assertEquals(GraphicsBackendCompat.Result.RESPECTED_VULKAN, GraphicsBackendCompat.apply(opts));
        assertEquals("preferredGraphicsBackend:\"vulkan\"", backendLine(opts),
                "an explicit Vulkan choice must be left intact");
    }

    @Test
    void oversizedOptionsFileIsLeftUnchanged(@TempDir Path dir) throws IOException {
        Path opts = dir.resolve("options.txt");
        byte[] original = new byte[1024 * 1024 + 1];
        java.util.Arrays.fill(original, (byte) 'x');
        Files.write(opts, original);

        assertThrows(IOException.class, () -> GraphicsBackendCompat.apply(opts));

        assertArrayEquals(original, Files.readAllBytes(opts));
    }
}
