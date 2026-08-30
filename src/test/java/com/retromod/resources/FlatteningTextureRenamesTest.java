/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Flattening rename table is derived from vanilla jars rather than written by hand, so these
 * tests check it against those jars: an entry has to start from a texture 1.12.2 shipped and land on
 * one the current version still ships. A row that points at a name the game dropped would move a
 * pack's file somewhere Minecraft never looks.
 */
class FlatteningTextureRenamesTest {

    @Test
    @DisplayName("The table covers the Flattening rather than a handful of examples")
    void tableIsComprehensive() {
        assertTrue(FlatteningTextureRenames.blocks().size() > 250,
                "saw " + FlatteningTextureRenames.blocks().size() + " block renames");
        assertTrue(FlatteningTextureRenames.items().size() > 80,
                "saw " + FlatteningTextureRenames.items().size() + " item renames");
    }

    @Test
    @DisplayName("Block and item names stay apart where they disagree")
    void blockAndItemNamesStayApart() {
        // A 1.12.2 pack ships blocks/brick.png and items/brick.png. Only the block became bricks,
        // so merging the two tables would rename the item as well and lose its texture.
        assertEquals("bricks", FlatteningTextureRenames.blocks().get("brick"));
        assertNotEquals("bricks", FlatteningTextureRenames.items().get("brick"));
    }

    @Test
    @DisplayName("The curated table wins where the two disagree")
    void curatedEntriesOverrideDerivedOnes() {
        Map<String, String> merged = ResourcePackTransformer.blockRenames();
        for (Map.Entry<String, String> curated : ResourcePackTransformer.TEXTURE_RENAMES.entrySet()) {
            assertEquals(curated.getValue(), merged.get(curated.getKey()),
                    "a hand-checked entry must survive the merge: " + curated.getKey());
        }
        assertTrue(merged.size() >= FlatteningTextureRenames.blocks().size(),
                "the merge adds to the derived table, it does not shrink it");
    }

    @Test
    @DisplayName("Every rename starts from a texture 1.12.2 actually shipped")
    void everyRenameStartsFromARealOldTexture() throws Exception {
        Set<String> old = textureNames("1.12.2");
        Assumptions.assumeTrue(old != null, "1.12.2 jar present");
        assertEach((kind, from, to) -> assertTrue(
                old.contains(directory(kind, true) + "/" + from + ".png"),
                "1.12.2 has no " + kind + " texture named " + from));
    }

    @Test
    @DisplayName("Every rename lands on a texture the current version still ships")
    void everyRenameLandsOnALiveTexture() throws Exception {
        Set<String> current = textureNames("26.2");
        Assumptions.assumeTrue(current != null, "26.2 jar present");
        assertEach((kind, from, to) -> {
            assertTrue(current.contains(directory(kind, false) + "/" + to + ".png"),
                    "26.2 has no " + kind + " texture named " + to);
            assertFalse(current.contains(directory(kind, false) + "/" + from + ".png"),
                    "26.2 still ships " + from + ", so renaming it would lose the original");
        });
    }

    private interface Check {
        void run(String kind, String from, String to);
    }

    private static void assertEach(Check check) {
        FlatteningTextureRenames.blocks().forEach((f, t) -> check.run("block", f, t));
        FlatteningTextureRenames.items().forEach((f, t) -> check.run("item", f, t));
    }

    private static String directory(String kind, boolean legacy) {
        return legacy ? kind + "s" : kind;
    }

    private static Set<String> textureNames(String version) throws Exception {
        Path jar = null;
        String home = System.getProperty("user.home", "");
        for (Path candidate : List.of(
                Path.of("test-jars-mixin/minecraft-" + version + "-client.jar"),
                Path.of(home, "Library/Application Support/PrismLauncher/libraries",
                        "com/mojang/minecraft", version, "minecraft-" + version + "-client.jar"))) {
            if (Files.isRegularFile(candidate)) { jar = candidate; break; }
        }
        if (jar == null) return null;

        String prefix = "assets/minecraft/textures/";
        Set<String> names = new HashSet<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            zip.stream().map(java.util.zip.ZipEntry::getName)
                    .filter(n -> n.startsWith(prefix) && n.endsWith(".png"))
                    .forEach(n -> names.add(n.substring(prefix.length())));
        }
        return names;
    }
}
