/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The extracted tree and the in-memory tree are two views of the same thing, so a converter must
 * see the same names, refusals and results from both.
 */
class ResourceTreeTest {

    private static final List<String> ESCAPING_NAMES = List.of(
            "../outside.json", "data/../../outside.json", "/absolute.json", "data//double.json",
            "data/./dot.json", "data\\backslash.json", "C:/drive.json", "trailing/", "");

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static DirectoryResourceTree directoryTree(Path root) throws IOException {
        Files.createDirectories(root.resolve("assets/mod/lang"));
        Files.writeString(root.resolve("assets/mod/lang/en_us.json"), "{}");
        Files.createDirectories(root.resolve("data/mod/recipes"));
        Files.writeString(root.resolve("data/mod/recipes/a.json"), "{\"a\":1}");
        return DirectoryResourceTree.open(root);
    }

    private static MapResourceTree mapTree() throws IOException {
        return new MapResourceTree(Map.of(
                "assets/mod/lang/en_us.json", bytes("{}"),
                "data/mod/recipes/a.json", bytes("{\"a\":1}")));
    }

    private static void assertBehavesAsTree(ResourceTree tree) throws IOException {
        assertEquals(List.of("assets/mod/lang/en_us.json", "data/mod/recipes/a.json"),
                List.copyOf(tree.names()), "names are sorted, forward-slash, files only");
        assertTrue(tree.exists("data/mod/recipes/a.json"));
        assertFalse(tree.exists("data/mod/recipes"), "a directory is not a file");
        assertEquals("{\"a\":1}", new String(tree.read("data/mod/recipes/a.json"), StandardCharsets.UTF_8));
        assertThrows(NoSuchFileException.class, () -> tree.read("data/mod/recipes/missing.json"));
        assertThrows(IOException.class, () -> tree.read("data/mod/recipes/a.json", 3),
                "a read is bounded");

        tree.write("data/mod/recipe/a.json", bytes("{\"b\":2}"));
        assertTrue(tree.exists("data/mod/recipe/a.json"), "a write creates its folders");
        assertTrue(tree.names().contains("data/mod/recipe/a.json"));

        assertTrue(tree.delete("data/mod/recipes/a.json"));
        assertFalse(tree.delete("data/mod/recipes/a.json"), "a second delete finds nothing");
        assertFalse(tree.exists("data/mod/recipes/a.json"));

        for (String name : ESCAPING_NAMES) {
            assertThrows(IOException.class, () -> tree.write(name, bytes("x")),
                    "refuses to write " + name);
            assertThrows(IOException.class, () -> tree.read(name), "refuses to read " + name);
            assertFalse(tree.exists(name), "never lists " + name);
            assertThrows(IOException.class, () -> tree.delete(name), "refuses to delete " + name);
        }
        assertThrows(IOException.class, () -> tree.write("assets/mod/lang/en_us.json/inner", bytes("x")),
                "a file cannot also be a folder");
        assertThrows(IOException.class, () -> tree.write("assets/mod/lang", bytes("x")),
                "a folder cannot also be a file");
    }

    @Test
    @DisplayName("An extracted directory behaves as a resource tree")
    void directoryTreeContract(@TempDir Path root) throws IOException {
        DirectoryResourceTree tree = directoryTree(root);
        assertBehavesAsTree(tree);
        assertEquals("{\"b\":2}", Files.readString(root.resolve("data/mod/recipe/a.json")),
                "writes land in the directory");
        assertFalse(Files.exists(root.resolve("data/mod/recipes/a.json")));
        assertFalse(Files.exists(root.getParent().resolve("outside.json")),
                "nothing escaped the root");
    }

    @Test
    @DisplayName("Entries held in memory behave as a resource tree")
    void mapTreeContract() throws IOException {
        MapResourceTree tree = mapTree();
        assertBehavesAsTree(tree);
        assertEquals(List.of("assets/mod/lang/en_us.json", "data/mod/recipe/a.json"),
                List.copyOf(tree.entries().keySet()), "entries keep insertion order for writing");
    }

    @Test
    @DisplayName("An in-memory tree refuses a non-canonical name from its initial entries")
    void mapTreeRefusesEscapingInitialEntries() {
        assertThrows(IOException.class,
                () -> new MapResourceTree(Map.of("../outside.json", bytes("{}"))));
    }

    @Test
    @DisplayName("A symbolic link anywhere in an extracted tree is refused")
    void directoryTreeRefusesSymbolicLinks(@TempDir Path base) throws IOException {
        Path root = Files.createDirectories(base.resolve("mod"));
        Path outside = Files.createDirectories(base.resolve("outside"));
        Files.createDirectories(root.resolve("data"));
        try {
            Files.createSymbolicLink(root.resolve("data/link"), outside);
        } catch (UnsupportedOperationException | IOException noLinks) {
            Assumptions.abort("symbolic links are not available here");
        }
        assertThrows(IOException.class, () -> DirectoryResourceTree.open(root));
    }

    @Test
    @DisplayName("A link planted after the tree was opened cannot redirect a write")
    void directoryTreeRefusesLinkPlantedLater(@TempDir Path base) throws IOException {
        Path root = Files.createDirectories(base.resolve("mod"));
        Path outside = Files.createDirectories(base.resolve("outside"));
        DirectoryResourceTree tree = DirectoryResourceTree.open(root);
        try {
            Files.createSymbolicLink(root.resolve("data"), outside);
        } catch (UnsupportedOperationException | IOException noLinks) {
            Assumptions.abort("symbolic links are not available here");
        }
        assertThrows(IOException.class, () -> tree.write("data/mod/x.json", bytes("{}")));
        try (var files = Files.list(outside)) {
            assertEquals(0, files.count(), "nothing was written through the link");
        }
    }

    @Test
    @DisplayName("Opening a file instead of a directory fails")
    void directoryTreeNeedsADirectory(@TempDir Path base) throws IOException {
        Path file = Files.writeString(base.resolve("mod.jar"), "x");
        assertThrows(IOException.class, () -> DirectoryResourceTree.open(file));
    }
}
