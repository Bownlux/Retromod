/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;

/** Exact pre-1.13 texture moves outside the block and item directories. */
final class LegacyTexturePathMappings {

    private static final Map<String, String> TEXTURE_PATH_RENAMES = Map.ofEntries(
        Map.entry("entity/bed/silver.png", "entity/bed/light_gray.png"),
        Map.entry("entity/boat/boat_acacia.png", "entity/boat/acacia.png"),
        Map.entry("entity/boat/boat_birch.png", "entity/boat/birch.png"),
        Map.entry("entity/boat/boat_darkoak.png", "entity/boat/dark_oak.png"),
        Map.entry("entity/boat/boat_jungle.png", "entity/boat/jungle.png"),
        Map.entry("entity/boat/boat_oak.png", "entity/boat/oak.png"),
        Map.entry("entity/boat/boat_spruce.png", "entity/boat/spruce.png"),
        Map.entry("entity/endercrystal/endercrystal.png",
            "entity/end_crystal/end_crystal.png"),
        Map.entry("entity/endercrystal/endercrystal_beam.png",
            "entity/end_crystal/end_crystal_beam.png"),
        Map.entry("entity/illager/fangs.png", "entity/illager/evoker_fangs.png"),
        Map.entry("entity/illager/illusionist.png", "entity/illager/illusioner.png"),
        Map.entry("entity/llama/llama_brown.png", "entity/llama/brown.png"),
        Map.entry("entity/llama/llama_creamy.png", "entity/llama/creamy.png"),
        Map.entry("entity/llama/llama_gray.png", "entity/llama/gray.png"),
        Map.entry("entity/llama/llama_white.png", "entity/llama/white.png"),
        Map.entry("entity/llama/decor/decor_black.png", "entity/llama/decor/black.png"),
        Map.entry("entity/llama/decor/decor_blue.png", "entity/llama/decor/blue.png"),
        Map.entry("entity/llama/decor/decor_brown.png", "entity/llama/decor/brown.png"),
        Map.entry("entity/llama/decor/decor_cyan.png", "entity/llama/decor/cyan.png"),
        Map.entry("entity/llama/decor/decor_gray.png", "entity/llama/decor/gray.png"),
        Map.entry("entity/llama/decor/decor_green.png", "entity/llama/decor/green.png"),
        Map.entry("entity/llama/decor/decor_light_blue.png",
            "entity/llama/decor/light_blue.png"),
        Map.entry("entity/llama/decor/decor_lime.png", "entity/llama/decor/lime.png"),
        Map.entry("entity/llama/decor/decor_magenta.png", "entity/llama/decor/magenta.png"),
        Map.entry("entity/llama/decor/decor_orange.png", "entity/llama/decor/orange.png"),
        Map.entry("entity/llama/decor/decor_pink.png", "entity/llama/decor/pink.png"),
        Map.entry("entity/llama/decor/decor_purple.png", "entity/llama/decor/purple.png"),
        Map.entry("entity/llama/decor/decor_red.png", "entity/llama/decor/red.png"),
        Map.entry("entity/llama/decor/decor_silver.png",
            "entity/llama/decor/light_gray.png"),
        Map.entry("entity/llama/decor/decor_white.png", "entity/llama/decor/white.png"),
        Map.entry("entity/llama/decor/decor_yellow.png", "entity/llama/decor/yellow.png"),
        Map.entry("entity/shulker/shulker_silver.png",
            "entity/shulker/shulker_light_gray.png"),
        Map.entry("entity/snowman.png", "entity/snow_golem.png")
    );

    private LegacyTexturePathMappings() {}

    static Map<String, String> mappings() {
        return TEXTURE_PATH_RENAMES;
    }

    static int apply(Path texturesDirectory) throws IOException {
        if (!Files.isDirectory(texturesDirectory, LinkOption.NOFOLLOW_LINKS)) return 0;

        int renamed = 0;
        for (var entry : TEXTURE_PATH_RENAMES.entrySet()) {
            if (move(texturesDirectory, entry.getKey(), entry.getValue())) renamed++;
        }
        return renamed;
    }

    static boolean move(Path texturesDirectory, String sourceName, String destinationName)
            throws IOException {
        Path root = texturesDirectory.toAbsolutePath().normalize();
        Path source = root.resolve(sourceName).normalize();
        Path destination = root.resolve(destinationName).normalize();
        requireContained(root, source);
        requireContained(root, destination);
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) return false;
        requireRegularFile(source, "Texture source");

        Path sourceMetadata = source.resolveSibling(source.getFileName() + ".mcmeta");
        Path destinationMetadata = destination.resolveSibling(
            destination.getFileName() + ".mcmeta");
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)
                || Files.exists(destinationMetadata, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Texture rename destination already exists: "
                + destination);
        }
        if (Files.exists(sourceMetadata, LinkOption.NOFOLLOW_LINKS)) {
            requireRegularFile(sourceMetadata, "Texture animation metadata");
        }

        Files.createDirectories(destination.getParent());
        Files.move(source, destination);
        if (Files.exists(sourceMetadata, LinkOption.NOFOLLOW_LINKS)) {
            Files.move(sourceMetadata, destinationMetadata);
        }
        removeEmptyParents(source.getParent(), root);
        return true;
    }

    private static void requireContained(Path root, Path path) throws IOException {
        if (!path.startsWith(root)) {
            throw new IOException("Texture mapping escapes the texture directory: " + path);
        }
    }

    private static void requireRegularFile(Path path, String description) throws IOException {
        if (Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(description + " is not a regular file: " + path);
        }
    }

    private static void removeEmptyParents(Path directory, Path root) throws IOException {
        Path current = directory;
        while (current != null && !current.equals(root)) {
            try {
                Files.delete(current);
            } catch (DirectoryNotEmptyException occupied) {
                return;
            }
            current = current.getParent();
        }
    }
}
