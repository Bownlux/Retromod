/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionedTexturePathMappingsTest {

    @TempDir
    Path root;

    @Test
    void catalogCoversEveryVerifiedPostFlatteningBoundary() {
        // Grown by the derived pass over the vanilla jars; a drop means the table lost rows.
        assertTrue(VersionedTexturePathMappings.migrations().size() >= 219,
            "saw " + VersionedTexturePathMappings.migrations().size() + " migrations");
        Set<Integer> boundaries = VersionedTexturePathMappings.migrations().stream()
            .map(migration -> migration.requiredFormat().major())
            .collect(Collectors.toSet());
        assertEquals(Set.of(4, 5, 7, 13, 15, 22, 32, 34, 42, 46, 55, 69, 75, 84, 88),
            boundaries);
    }

    @Test
    void movesEveryCatalogEntryAtItsFormatBoundary() throws Exception {
        int index = 0;
        for (var migration : VersionedTexturePathMappings.migrations()) {
            Path textures = root.resolve("migration-" + index++).resolve("textures");
            Path source = textures.resolve(migration.source());
            Files.createDirectories(source.getParent());
            Files.writeString(source, migration.source());
            int moved = VersionedTexturePathMappings.apply(textures,
                migration.sourceMaximum(), migration.requiredFormat());

            assertEquals(1, moved, migration.source());
            assertFalse(Files.exists(source), migration.source());
            assertTrue(Files.isRegularFile(textures.resolve(migration.destination())),
                migration.destination());
        }
    }

    @Test
    void appliesOrderedMigrationsAcrossSeveralVersions() throws Exception {
        Path textures = root.resolve("textures");
        Path chicken = textures.resolve("entity/chicken.png");
        Path glint = textures.resolve("misc/enchanted_item_glint.png");
        Path pillar = textures.resolve("block/quartz_pillar.png");
        Files.createDirectories(chicken.getParent());
        Files.createDirectories(glint.getParent());
        Files.createDirectories(pillar.getParent());
        Files.writeString(chicken, "chicken");
        Files.writeString(chicken.resolveSibling("chicken.png.mcmeta"), "animation");
        Files.writeString(glint, "glint");
        Files.writeString(pillar, "pillar");

        int moved = VersionedTexturePathMappings.apply(textures,
            new PackFormat(3, 0), new PackFormat(88, 0));

        assertEquals(4, moved);
        assertTrue(Files.isRegularFile(
            textures.resolve("entity/chicken/chicken_temperate.png")));
        assertTrue(Files.isRegularFile(
            textures.resolve("entity/chicken/chicken_temperate.png.mcmeta")));
        assertTrue(Files.isRegularFile(textures.resolve("misc/enchanted_glint_item.png")));
        assertTrue(Files.isRegularFile(textures.resolve("block/quartz_pillar_side.png")));
        assertFalse(Files.exists(chicken));
    }

    @Test
    void stopsAtTheSelectedTargetFormat() throws Exception {
        Path textures = root.resolve("textures");
        Path armor = textures.resolve("models/armor/diamond_layer_1.png");
        Path pathTop = textures.resolve("block/grass_path_top.png");
        Files.createDirectories(armor.getParent());
        Files.createDirectories(pathTop.getParent());
        Files.writeString(armor, "armor");
        Files.writeString(pathTop, "path");

        int moved = VersionedTexturePathMappings.apply(textures,
            new PackFormat(6, 0), new PackFormat(34, 0));

        assertEquals(1, moved);
        assertTrue(Files.isRegularFile(textures.resolve("block/dirt_path_top.png")));
        assertTrue(Files.isRegularFile(armor));
    }

    @Test
    void doesNotReplayAMigrationAlreadyCoveredByTheSourceFormat() throws Exception {
        Path textures = root.resolve("textures");
        Path oldArmor = textures.resolve("models/armor/diamond_layer_1.png");
        Files.createDirectories(oldArmor.getParent());
        Files.writeString(oldArmor, "outdated");

        int moved = VersionedTexturePathMappings.apply(textures,
            new PackFormat(42, 0), new PackFormat(88, 0));

        assertEquals(0, moved);
        assertTrue(Files.isRegularFile(oldArmor));
    }
}
