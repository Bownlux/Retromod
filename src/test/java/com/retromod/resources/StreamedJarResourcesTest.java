/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.retromod.util.ArchiveLimits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Assets and data left in the source jar look to the migration steps like files in any other tree. */
class StreamedJarResourcesTest {

    @TempDir
    Path tmp;

    private Path source() throws IOException {
        Path jar = tmp.resolve("mod.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (String name : List.of("mod/Main.class", "assets/m/a.json", "assets/m/b.png", "data/m/c.json")) {
                out.putNextEntry(new JarEntry(name));
                out.write(name.getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return jar;
    }

    @Test
    @DisplayName("the layered tree reads, replaces, and deletes files that stay in the jar")
    void layeredTreeBehavesLikeOneTree() throws IOException {
        try (StreamedJarResources streamed = StreamedJarResources.open(source())) {
            for (String name : List.of("assets/m/a.json", "assets/m/b.png", "data/m/c.json")) streamed.add(name);
            MapResourceTree written = new MapResourceTree();
            written.write("pack.mcmeta", new byte[]{1});
            ResourceTree tree = streamed.over(written);

            assertEquals(List.of("assets/m/a.json", "assets/m/b.png", "data/m/c.json", "pack.mcmeta"),
                    List.copyOf(tree.names()));
            assertArrayEquals("assets/m/a.json".getBytes(StandardCharsets.UTF_8), tree.read("assets/m/a.json"));

            tree.write("assets/m/a.json", new byte[]{2});
            assertTrue(tree.delete("data/m/c.json"));
            assertFalse(tree.exists("data/m/c.json"));
            assertArrayEquals(new byte[]{2}, tree.read("assets/m/a.json"), "the written version wins");

            assertEquals(List.of("assets/m/b.png"), streamed.remaining(written::exists),
                    "only the untouched file is still copied from the jar");
            ByteArrayOutputStream copy = new ByteArrayOutputStream();
            streamed.copy("assets/m/b.png", copy);
            assertEquals("assets/m/b.png", copy.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("a lowercase copy on a disk that ignores case does not hide the mixed-case original")
    void writtenFilesAreComparedByExactName() throws IOException {
        Path jar = tmp.resolve("case.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("assets/m/textures/Foo.png"));
            out.write(new byte[]{1});
            out.closeEntry();
        }
        Path written = Files.createDirectories(tmp.resolve("written/assets/m/textures"));
        Files.write(written.resolve("foo.png"), new byte[]{2});
        try (StreamedJarResources streamed = StreamedJarResources.open(jar)) {
            streamed.add("assets/m/textures/Foo.png");

            assertEquals(List.of("assets/m/textures/Foo.png"), streamed.remaining(tmp.resolve("written")),
                    "the jar keeps both names, as the in-memory paths do");
        }
    }

    @Test
    @DisplayName("a write that would make a file and a folder share a path is refused, as on disk")
    void fileFolderClashIsRefused() throws IOException {
        try (StreamedJarResources streamed = StreamedJarResources.open(source())) {
            streamed.add("assets/m/b.png");
            ResourceTree tree = streamed.over(new MapResourceTree());

            assertThrows(IOException.class, () -> tree.write("assets/m/b.png/inner.json", new byte[1]));
            assertThrows(IOException.class, () -> tree.write("assets/m", new byte[1]));
        }
    }

    @Test
    @DisplayName("a file over 128 MiB that no step reads is copied from the jar; one a step reads stays unpacked")
    void largeUnreadFilesAreCopied() {
        long large = 200L << 20;

        assertTrue(StreamedJarResources.copiedUnchanged("natives/libmod.so", large));
        assertTrue(StreamedJarResources.leftInSourceJar("assets/m/music.ogg", large));
        assertFalse(StreamedJarResources.copiedUnchanged("natives/libmod.so", 100L << 20),
                "a file within the limit is unpacked as before");
        for (String read : List.of("mod.accesswidener", "mod.classtweaker", "fabric.mod.json", "mod.mixins.json",
                "META-INF/mods.toml", "META-INF/accesstransformer.cfg", "META-INF/MANIFEST.MF",
                "META-INF/jars/lib.jar", "META-INF/jarjar/lib.jar", "mod/Main.class", "mcmod.info")) {
            assertFalse(StreamedJarResources.copiedUnchanged(read, large), read + " is read by a step");
        }
    }

    @Test
    @DisplayName("only assets and data of a known size stream, and only when the jar streams or the file is huge")
    void streamingIsLimitedToAssetsAndData() {
        assertFalse(StreamedJarResources.streams("assets/m/a.json", 10), "a normal jar unpacks as before");
        assertTrue(StreamedJarResources.streams("assets/m/music.ogg", 200L << 20),
                "a file too large to hold in memory streams in any jar");

        try (ArchiveLimits.Scope ignored = ArchiveLimits.enter(new ArchiveLimits.Allowance(1L << 40, true))) {
            assertTrue(StreamedJarResources.streams("assets/m/a.json", 10));
            assertTrue(StreamedJarResources.streams("data/m/recipe/r.json", 10));
            assertFalse(StreamedJarResources.streams("mcmod.info", 10), "root files stay unpacked");
            assertFalse(StreamedJarResources.streams("META-INF/mods.toml", 10));
            assertFalse(StreamedJarResources.streams("mod/Main.class", 10));
            assertFalse(StreamedJarResources.streams("assets/m/a.json", -1), "an unknown size unpacks");
        }
    }
}
