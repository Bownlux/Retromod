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
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A texture migration may take several steps: {@code entity/sign.png} became
 * {@code entity/signs/oak.png} in 1.14 and only became {@code block/oak_sign.png} in 26.2. A pack
 * picks up whichever hops its own format has not passed yet, so a destination is allowed to be
 * absent from the current version as long as another row carries it onward.
 *
 * <p>Both halves matter. A destination that is neither shipped nor carried onward moves a pack's
 * file somewhere Minecraft never looks, and collapsing a chain into one row breaks packs that sit
 * at an intermediate format.
 */
class TextureMigrationChainTest {

    @Test
    @DisplayName("Every migration ends somewhere the game ships, or is carried onward")
    void everyDestinationIsReachable() throws Exception {
        Set<String> current = textureNames("26.2");
        Assumptions.assumeTrue(current != null, "26.2 jar present");

        List<VersionedTexturePathMappings.Migration> migrations =
                VersionedTexturePathMappings.migrations();
        Set<String> sources = migrations.stream()
                .map(VersionedTexturePathMappings.Migration::source)
                .collect(Collectors.toSet());

        for (var migration : migrations) {
            String destination = migration.destination();
            assertTrue(current.contains(destination) || sources.contains(destination),
                    destination + " is neither shipped by 26.2 nor moved on by another row, so "
                            + migration.source() + " would land where nothing looks for it");
        }
    }

    @Test
    @DisplayName("A chained move keeps its intermediate step")
    void chainedMovesKeepTheirIntermediateStep() {
        // Collapsing entity/sign.png straight onto block/oak_sign.png would strand a pack that
        // already sits at the intermediate name, because nothing would move it the rest of the way.
        assertMigration("entity/sign.png", "entity/signs/oak.png");
        assertMigration("entity/signs/oak.png", "block/oak_sign.png");
        assertMigration("entity/chicken.png", "entity/chicken/temperate_chicken.png");
        assertMigration("entity/chicken/temperate_chicken.png", "entity/chicken/chicken_temperate.png");
    }

    @Test
    @DisplayName("No migration starts where another one ends at the same boundary")
    void noMigrationUndoesAnother() {
        for (var one : VersionedTexturePathMappings.migrations()) {
            for (var other : VersionedTexturePathMappings.migrations()) {
                if (one == other) continue;
                if (one.source().equals(other.destination())
                        && one.destination().equals(other.source())) {
                    fail("a pair of rows swaps two paths, which would move a file back and forth: "
                            + one.source() + " and " + other.source());
                }
            }
        }
    }

    private static void assertMigration(String source, String destination) {
        assertTrue(VersionedTexturePathMappings.migrations().stream()
                        .anyMatch(m -> m.source().equals(source)
                                && m.destination().equals(destination)),
                "missing migration " + source + " -> " + destination);
    }

    private static Set<String> textureNames(String version) throws Exception {
        String home = System.getProperty("user.home", "");
        Path jar = null;
        for (Path candidate : List.of(
                Path.of("test-jars-mixin/minecraft-" + version + "-client.jar"),
                Path.of(home, "Library/Application Support/PrismLauncher/libraries",
                        "com/mojang/minecraft", version, "minecraft-" + version + "-client.jar"))) {
            if (Files.isRegularFile(candidate)) { jar = candidate; break; }
        }
        if (jar == null) return null;

        String prefix = "assets/minecraft/textures/";
        Set<String> names = new HashSet<>();
        try (var zip = new java.util.zip.ZipFile(jar.toFile())) {
            zip.stream().map(java.util.zip.ZipEntry::getName)
                    .filter(n -> n.startsWith(prefix) && n.endsWith(".png"))
                    .forEach(n -> names.add(n.substring(prefix.length())));
        }
        return names;
    }
}
